"""Single-use, bounded-attempt email authentication; SMTP always uses TLS."""
from __future__ import annotations
import hashlib
import hmac
import re
import secrets
import smtplib
import ssl
import time
from email.message import EmailMessage
from typing import Literal, Optional

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import BaseModel, SecretStr
from starlette.concurrency import run_in_threadpool
from auth import admin_user, current_user, csrf, limit, username, validate_password
from database import passwords, public_user, store
from service_config import config, save_config

router = APIRouter()
PURPOSES = ('register', 'login', 'reset', 'bind')


def email_address(value):
    value = value.strip().lower()
    if len(value) > 254 or not re.fullmatch(r'[a-z0-9.!#$%&\x27*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.[a-z]{2,63}', value):
        raise HTTPException(400, '请填写有效的邮箱地址')
    return value


def code_hash(email, purpose, code):
    key = (store().folder / 'encryption.key').read_bytes()
    return hmac.new(key, (email + ':' + purpose + ':' + code).encode(), hashlib.sha256).hexdigest()


def send_mail(email, content):
    settings = config('smtp')
    if not settings.get('enabled'):
        raise HTTPException(503, '邮箱服务尚未配置，请联系管理员')
    message = EmailMessage()
    message['Subject'] = '识题助手 · 邮箱验证'
    message['From'] = settings['sender']
    message['To'] = email
    message.set_content(content)
    try:
        context = ssl.create_default_context()
        if settings['security'] == 'ssl':
            client = smtplib.SMTP_SSL(settings['host'], settings['port'], timeout=15, context=context)
        else:
            client = smtplib.SMTP(settings['host'], settings['port'], timeout=15)
            client.ehlo(); client.starttls(context=context); client.ehlo()
        with client:
            client.login(settings['username'], settings['password'])
            client.send_message(message)
    except (OSError, smtplib.SMTPException):
        raise HTTPException(503, '邮件发送失败，请稍后重试或联系管理员检查邮箱配置')


def consume_code(email, purpose, code):
    valid = False
    with store().connect() as db:
        db.execute('BEGIN IMMEDIATE')
        row = db.execute('SELECT * FROM email_codes WHERE email=? AND purpose=? ORDER BY id DESC LIMIT 1', (email, purpose)).fetchone()
        if row and not row['consumed'] and row['attempts'] < 5 and row['expires_at'] > int(time.time()):
            valid = bool(re.fullmatch(r'\d{6}', code)) and hmac.compare_digest(row['code_hash'], code_hash(email, purpose, code))
            db.execute('UPDATE email_codes SET attempts=attempts+1,consumed=? WHERE id=?', (int(valid), row['id']))
    # Raise after commit so unsuccessful attempts cannot be rolled back.
    if not valid:
        raise HTTPException(400, '验证码无效或已过期，请重新获取')


class CodeRequest(BaseModel):
    email: str
    purpose: Literal['register', 'login', 'reset', 'bind']


class EmailCredentials(BaseModel):
    email: str
    code: str
    username: Optional[str] = None
    password: Optional[SecretStr] = None


@router.get('/v1/auth/options')
def options():
    return {'email_enabled': bool(config('smtp').get('enabled')), 'registration_review': True}


@router.post('/v1/auth/email-code')
async def issue_code(body: CodeRequest, request: Request):
    email = email_address(body.email)
    limit('mail-ip:' + (request.client.host if request.client else 'unknown'), 30, 3600)
    if body.purpose == 'bind':
        current_user(request)
    if not config('smtp').get('enabled'):
        raise HTTPException(503, '邮箱服务尚未配置，请联系管理员')
    now = int(time.time())
    code = f'{secrets.randbelow(1000000):06d}'
    with store().connect() as db:
        db.execute('BEGIN IMMEDIATE')
        recent = db.execute('SELECT count(*),max(created_at) FROM email_codes WHERE email=? AND created_at>?', (email, now-3600)).fetchone()
        if recent[0] >= 5 or (recent[1] is not None and now-recent[1] < 60):
            raise HTTPException(429, '验证码发送过于频繁，请稍后重试')
        db.execute('UPDATE email_codes SET consumed=1 WHERE email=? AND purpose=?', (email, body.purpose))
        identifier = db.execute('INSERT INTO email_codes(email,purpose,code_hash,created_at,expires_at) VALUES (?,?,?,?,?)', (email, body.purpose, code_hash(email, body.purpose, code), now, now+600)).lastrowid
        existing = db.execute('SELECT id FROM users WHERE email=?', (email,)).fetchone()
        db.execute('DELETE FROM email_codes WHERE created_at<?', (now-86400,))
    # Same response for unknown/known accounts. No account enumeration via send API.
    deliver = body.purpose == 'register' and not existing or body.purpose in ('login','reset') and existing or body.purpose == 'bind' and not existing
    if deliver:
        try:
            label = {'register':'注册账号', 'login':'登录账号', 'reset':'找回密码', 'bind':'绑定邮箱'}[body.purpose]
            await run_in_threadpool(send_mail, email, f'你正在{label}。验证码：{code}\n10 分钟内有效，最多尝试 5 次。请勿向任何人透露。若非本人操作，请忽略此邮件。')
        except HTTPException:
            with store().connect() as db:
                db.execute('UPDATE email_codes SET consumed=1 WHERE id=?', (identifier,))
            raise
    return {'message':'若邮箱符合当前操作条件，验证码已发送，请检查收件箱及垃圾邮件。', 'retry_after':60}


def session_result(user, request, response):
    from account_routes import cookie
    token, lifetime = store().issue_session(user['id'], user['role'])
    cookie(response, request, token, lifetime)
    return {'access_token':token, 'expires_in':lifetime, 'user':public_user(user), 'csrf_token':csrf(token), 'server_id':store().instance_id}


@router.post('/v1/auth/email-login')
def email_login(body: EmailCredentials, request: Request, response: Response):
    email = email_address(body.email)
    limit('email-login:' + email, 10, 300)
    consume_code(email, 'login', body.code)
    with store().connect() as db:
        user = db.execute('SELECT * FROM users WHERE email=?', (email,)).fetchone()
        if not user or user['status'] != 'active':
            raise HTTPException(403, '账号未启用，请等待审核或联系管理员')
        db.execute('UPDATE users SET last_login=? WHERE id=?', (int(time.time()), user['id']))
    store().audit(user['id'], user['id'], 'email_login')
    return session_result(user, request, response)


@router.post('/v1/auth/email-reset')
async def email_reset(body: EmailCredentials):
    email = email_address(body.email)
    password = body.password.get_secret_value() if body.password else ''
    validate_password(password)
    consume_code(email, 'reset', body.code)
    encoded = await run_in_threadpool(passwords.hash, password)
    with store().connect() as db:
        user = db.execute('SELECT id FROM users WHERE email=?', (email,)).fetchone()
        if not user:
            raise HTTPException(400, '无法找回此账号')
        db.execute('UPDATE users SET password_hash=?,must_change_password=0 WHERE id=?', (encoded,user['id']))
        db.execute('DELETE FROM sessions WHERE user_id=?', (user['id'],))
        db.execute('UPDATE email_codes SET consumed=1 WHERE email=?',(email,))
    store().audit(user['id'], user['id'], 'email_password_reset')
    return {'message':'密码已重置，所有设备需要重新登录；账号审核状态保持原样。'}


@router.post('/v1/me/email')
def bind_email(body: EmailCredentials, user=Depends(current_user)):
    email = email_address(body.email)
    with store().connect() as db:
        if db.execute('SELECT email FROM users WHERE id=?', (user['id'],)).fetchone()[0]:
            raise HTTPException(400, '已绑定邮箱；更换邮箱请联系管理员核验身份')
    consume_code(email, 'bind', body.code)
    import sqlite3
    try:
        with store().connect() as db:
            db.execute('UPDATE users SET email=? WHERE id=? AND email IS NULL', (email,user['id']))
    except sqlite3.IntegrityError:
        raise HTTPException(409, '无法绑定此邮箱')
    store().audit(user['id'],user['id'],'bind_email')
    return {'message':'邮箱已绑定，可用于验证码登录和找回密码'}


class SmtpSettings(BaseModel):
    enabled: bool = False
    host: str = ''
    port: int = 465
    security: Literal['ssl','starttls'] = 'ssl'
    sender: str = ''
    username: str = ''
    password: Optional[SecretStr] = None


@router.get('/v1/admin/smtp')
def get_smtp(admin=Depends(admin_user)):
    value = config('smtp')
    return {**{k:v for k,v in value.items() if k != 'password'}, 'has_password':bool(value.get('password'))}


@router.put('/v1/admin/smtp')
def set_smtp(body: SmtpSettings, admin=Depends(admin_user)):
    previous = config('smtp')
    value = body.model_dump(exclude={'password'})
    value['password'] = body.password.get_secret_value() if body.password and body.password.get_secret_value() else previous.get('password','')
    if body.enabled:
        value['sender'] = email_address(body.sender)
        if not re.fullmatch(r'[A-Za-z0-9.-]{1,253}',body.host) or not 1 <= body.port <= 65535 or not body.username or not value['password']:
            raise HTTPException(400,'请填写完整的 SMTP 配置、发件地址和授权码')
    save_config('smtp',value)
    store().audit(admin['id'],None,'update_smtp')
    return {'message':'邮件配置已加密保存'}


class TestMail(BaseModel):
    email: str


@router.post('/v1/admin/smtp/test')
async def test_smtp(body: TestMail, admin=Depends(admin_user)):
    limit('smtp-test:' + str(admin['id']),3,300)
    await run_in_threadpool(send_mail,email_address(body.email),'这是一封识题助手邮件配置测试邮件。邮件发送成功。')
    return {'message':'测试邮件已发送，请检查收件箱'}
