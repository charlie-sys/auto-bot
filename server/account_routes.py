from __future__ import annotations

import json
import secrets
import sqlite3
import time
from datetime import datetime, timedelta, timezone
from typing import Optional

import httpx
from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response
from pydantic import BaseModel, SecretStr
from starlette.concurrency import run_in_threadpool

from auth import COOKIE, admin_user, csrf, current_user, limit, session_user, username, validate_password, verify_password
from database import digest, passwords, public_user, store
from model_provider import completion, normalize_base

router = APIRouter()
TZ = timezone(timedelta(hours=8))


class Credentials(BaseModel):
    username: str
    password: SecretStr
    email: Optional[str] = None
    code: Optional[str] = None


class PasswordChange(BaseModel):
    old_password: SecretStr
    new_password: SecretStr


class ModelSettings(BaseModel):
    base_url: str
    model: str
    api_key: Optional[SecretStr] = None


class UserUpdate(BaseModel):
    status: Optional[str] = None
    role: Optional[str] = None
    daily_quota: Optional[int] = None


def cookie(response: Response, request: Request, token: str, lifetime: int):
    secure = request.url.scheme == "https" or request.headers.get("x-forwarded-proto") == "https"
    response.set_cookie(COOKIE, token, max_age=lifetime, httponly=True, secure=secure, samesite="strict", path="/")
    response.headers["Cache-Control"] = "no-store"


@router.post("/v1/auth/register", status_code=201)
async def register(body: Credentials, request: Request):
    limit("register:" + (request.client.host if request.client else "unknown"), 5, 3600)
    name = username(body.username)
    password = body.password.get_secret_value()
    validate_password(password)
    from email_auth import email_address, consume_code
    email = email_address(body.email or '')
    consume_code(email, 'register', body.code or '')
    encoded = await run_in_threadpool(passwords.hash, password)
    try:
        with store().connect() as db:
            cursor = db.execute("INSERT INTO users(username,email,password_hash,created_at) VALUES (?,?,?,?)", (name, email, encoded, int(time.time())))
            identifier = cursor.lastrowid
    except sqlite3.IntegrityError:
        raise HTTPException(409, "用户名或邮箱已注册，请登录或找回密码")
    store().audit(None, identifier, "register_pending")
    return {"status": "pending", "message": "注册成功，请等待管理员审核后登录"}


@router.post("/v1/auth/login")
async def login(body: Credentials, request: Request, response: Response):
    from email_auth import email_address
    name = email_address(body.username) if '@' in body.username else username(body.username)
    peer = request.client.host if request.client else "unknown"
    limit("login-ip:" + peer, 30, 300)
    limit("login-user:" + name, 10, 300)
    value = body.password.get_secret_value()
    if len(value) > 128:
        raise HTTPException(401, "用户名或密码错误")
    with store().connect() as db:
        user = db.execute("SELECT * FROM users WHERE username=? OR email=?", (name,name)).fetchone()
    if not await run_in_threadpool(verify_password, user["password_hash"] if user else None, value):
        raise HTTPException(401, "用户名或密码错误")
    if user["status"] != "active":
        raise HTTPException(403, "账号待审核，请联系管理员" if user["status"] == "pending" else "账号已停用或审核未通过，请联系管理员")
    with store().connect() as db:
        db.execute("UPDATE users SET last_login=? WHERE id=?", (int(time.time()), user["id"]))
        if passwords.check_needs_rehash(user["password_hash"]):
            encoded = await run_in_threadpool(passwords.hash, value)
            db.execute("UPDATE users SET password_hash=? WHERE id=?", (encoded, user["id"]))
    token, lifetime = store().issue_session(user["id"], user["role"])
    cookie(response, request, token, lifetime)
    store().audit(user["id"], user["id"], "login")
    return {"access_token": token, "expires_in": lifetime, "user": public_user(user), "csrf_token": csrf(token), "server_id": store().instance_id}


@router.get("/v1/me")
def me(request: Request):
    user = session_user(request, allow_password_change=True)
    return {"user": user, "csrf_token": csrf(request.state.session_token), "server_id": store().instance_id}


@router.post("/v1/auth/logout")
def logout(request: Request, response: Response):
    user = session_user(request, allow_password_change=True)
    with store().connect() as db:
        db.execute("DELETE FROM sessions WHERE token_hash=?", (digest(request.state.session_token),))
    response.delete_cookie(COOKIE, path="/")
    store().audit(user["id"], user["id"], "logout")
    return {"ok": True}


@router.post("/v1/auth/password")
async def change_password(body: PasswordChange, request: Request, response: Response):
    user = session_user(request, allow_password_change=True)
    limit("password:" + str(user["id"]), 6, 300)
    old = body.old_password.get_secret_value()
    new = body.new_password.get_secret_value()
    validate_password(new)
    with store().connect() as db:
        encoded = db.execute("SELECT password_hash FROM users WHERE id=?", (user["id"],)).fetchone()[0]
    if len(old) > 128 or not await run_in_threadpool(verify_password, encoded, old):
        raise HTTPException(400, "当前密码错误")
    if old == new:
        raise HTTPException(400, "新密码不能与当前密码相同")
    encoded = await run_in_threadpool(passwords.hash, new)
    with store().connect() as db:
        db.execute("UPDATE users SET password_hash=?,must_change_password=0 WHERE id=?", (encoded, user["id"]))
        db.execute("DELETE FROM sessions WHERE user_id=?", (user["id"],))
    if user["username"] == "admin":
        (store().folder / "bootstrap-admin.json").unlink(missing_ok=True)
    token, lifetime = store().issue_session(user["id"], user["role"])
    cookie(response, request, token, lifetime)
    user["must_change_password"] = 0
    store().audit(user["id"], user["id"], "change_password")
    return {"access_token": token, "expires_in": lifetime, "user": user, "csrf_token": csrf(token), "server_id": store().instance_id}


@router.get("/v1/me/model")
def get_model(user=Depends(current_user)):
    return store().model(user["id"]) or {"base_url": "", "model": "", "has_key": False}


@router.put("/v1/me/model")
def save_model(body: ModelSettings, user=Depends(current_user)):
    base = normalize_base(body.base_url)
    model = body.model.strip()
    if not model or len(model) > 150 or any(ord(char) < 32 for char in model):
        raise HTTPException(400, "请填写有效的模型名")
    supplied = body.api_key.get_secret_value().strip() if body.api_key else ""
    if len(supplied) > 4096 or any(ord(char) < 33 or ord(char) > 126 for char in supplied):
        raise HTTPException(400, "API Key 格式无效")
    with store().connect() as db:
        existing = db.execute("SELECT encrypted_key FROM model_settings WHERE user_id=?", (user["id"],)).fetchone()
        if not supplied and existing is None:
            raise HTTPException(400, "首次配置请填写 API Key")
        encrypted = store().encrypt(supplied) if supplied else existing[0]
        db.execute("INSERT INTO model_settings VALUES (?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET base_url=excluded.base_url,model=excluded.model,encrypted_key=excluded.encrypted_key,updated_at=excluded.updated_at",
                   (user["id"], base, model, encrypted, int(time.time())))
    store().audit(user["id"], user["id"], "update_model")
    return {"ok": True, "message": "个人模型配置已保存"}


@router.delete("/v1/me/model")
def delete_model(user=Depends(current_user)):
    with store().connect() as db:
        db.execute("DELETE FROM model_settings WHERE user_id=?", (user["id"],))
    store().audit(user["id"], user["id"], "delete_model")
    return {"ok": True}


def reserve_usage(user: dict, model: str) -> int:
    limit("solve:" + str(user["id"]), 20)
    today = int(datetime.now(TZ).replace(hour=0, minute=0, second=0, microsecond=0).timestamp())
    with store().connect() as db:
        db.execute("BEGIN IMMEDIATE")
        current = db.execute("SELECT status,daily_quota FROM users WHERE id=?", (user["id"],)).fetchone()
        if current is None or current["status"] != "active":
            raise HTTPException(401, "账号已停用，请重新登录")
        used = db.execute("SELECT count(*) FROM usage v WHERE v.user_id=? AND v.created_at>=? AND NOT EXISTS (SELECT 1 FROM billing_usage b WHERE b.usage_id=v.id)", (user["id"], today)).fetchone()[0]
        credit = None
        if used >= current["daily_quota"]:
            from billing import reserve_credit
            credit = reserve_credit(db,user['id'])
        identifier = db.execute("INSERT INTO usage(user_id,created_at,status,model) VALUES (?,?,'processing',?)", (user["id"], int(time.time()), model)).lastrowid
        if credit:
            db.execute('INSERT INTO billing_usage(usage_id,grant_id,unit) VALUES (?,?,?)',(identifier,credit['id'],credit['unit']))
        return identifier


def finish_usage(identifier: int, started: float, *, success: bool, tokens: dict | None = None, questions: int = 0, error: int = 0):
    def number(value):
        return min(10**9, max(0, value)) if type(value) is int else 0
    tokens = tokens if isinstance(tokens, dict) else {}
    with store().connect() as db:
        db.execute('BEGIN IMMEDIATE')
        db.execute("UPDATE usage SET status=?,input_tokens=?,output_tokens=?,question_count=?,duration_ms=?,error_code=? WHERE id=?",
                   ("success" if success else "failed", number(tokens.get("prompt_tokens")), number(tokens.get("completion_tokens")), questions,
                    int((time.monotonic() - started) * 1000), error, identifier))
        from billing import settle_credit
        settle_credit(db,identifier,success,number(questions),number(tokens.get('prompt_tokens'))+number(tokens.get('completion_tokens')))


@router.post("/v1/me/model/test")
async def test_model(user=Depends(current_user)):
    config = store().model(user["id"], secret=True)
    if not config:
        raise HTTPException(400, "请先保存个人模型配置")
    record = reserve_usage(user, config["model"])
    started = time.monotonic()
    success, error, usage = False, 500, {}
    try:
        value = await completion(config, [{"role": "user", "content": "请只回复 OK，用于接口连通性测试。"}], structured=False)
        usage = value.get("usage") or {}
        content = value["choices"][0]["message"]["content"]
        if not content:
            raise ValueError()
        success, error = True, 0
        return {"ok": True, "message": "模型接口连接成功；识题还需要该模型支持图片输入"}
    except HTTPException as exc:
        error = exc.status_code
        raise
    except httpx.TimeoutException:
        error = 504
        raise HTTPException(504, "模型接口响应超时")
    except (httpx.RequestError, ValueError, KeyError, IndexError, TypeError):
        error = 502
        raise HTTPException(502, "模型接口不可用或返回格式无效")
    finally:
        finish_usage(record, started, success=success, tokens=usage, error=error)


def usage_summary(user_id: int | None, days: int):
    start = int((datetime.now(TZ).replace(hour=0, minute=0, second=0, microsecond=0) - timedelta(days=days - 1)).timestamp())
    where = "created_at>=?" + (" AND user_id=?" if user_id is not None else "")
    params = [start] + ([user_id] if user_id is not None else [])
    with store().connect() as db:
        summary = dict(db.execute(f"SELECT count(*) requests,coalesce(sum(status='success'),0) success,coalesce(sum(status='failed'),0) failed,coalesce(sum(status='processing'),0) processing,coalesce(sum(question_count),0) questions,coalesce(sum(input_tokens),0) input_tokens,coalesce(sum(output_tokens),0) output_tokens,coalesce(round(avg(CASE WHEN status!='processing' THEN duration_ms END)),0) avg_duration_ms FROM usage WHERE {where}", params).fetchone())
        daily = [dict(row) for row in db.execute(f"SELECT date(created_at,'unixepoch','+8 hours') day,count(*) requests,sum(status='success') success,sum(question_count) questions,sum(input_tokens+output_tokens) tokens FROM usage WHERE {where} GROUP BY day ORDER BY day", params)]
    return {"days": days, "timezone": "Asia/Shanghai", "summary": summary, "daily": daily}


@router.get("/v1/me/usage")
def my_usage(days: int = Query(7, ge=1, le=90), user=Depends(current_user)):
    result = usage_summary(user["id"], days)
    result["today"] = usage_summary(user["id"], 1)["summary"]["requests"]
    result["daily_quota"] = user["daily_quota"]
    from billing import account_balance
    result['subscription'] = account_balance(user['id'])
    return result


@router.get("/v1/admin/overview")
def overview(days: int = Query(7, ge=1, le=90), admin=Depends(admin_user)):
    result = usage_summary(None, days)
    with store().connect() as db:
        result["users"] = dict(db.execute("SELECT count(*) total,coalesce(sum(status='active'),0) active,coalesce(sum(status='pending'),0) pending,coalesce(sum(status IN ('disabled','rejected')),0) disabled FROM users").fetchone())
        start = int((datetime.now(TZ).replace(hour=0, minute=0, second=0, microsecond=0) - timedelta(days=days - 1)).timestamp())
        result["ranking"] = [dict(row) for row in db.execute("SELECT u.username,count(*) requests,sum(v.question_count) questions,sum(v.input_tokens+v.output_tokens) tokens FROM usage v JOIN users u ON u.id=v.user_id WHERE v.created_at>=? GROUP BY v.user_id ORDER BY requests DESC LIMIT 10", (start,))]
        result['payments'] = dict(db.execute("SELECT count(*) orders,coalesce(sum(status='paid'),0) paid,coalesce(sum(CASE WHEN status='paid' THEN price_fen ELSE 0 END),0) revenue_fen FROM orders WHERE created_at>=?",(start,)).fetchone())
    return result


@router.get("/v1/admin/users")
def users(q: str = "", status: str = "", offset: int = Query(0, ge=0), limit_count: int = Query(30, ge=1, le=100), admin=Depends(admin_user)):
    where, params = "username LIKE ?", ["%" + q[:40] + "%"]
    if status:
        where += " AND status=?"; params.append(status)
    with store().connect() as db:
        total = db.execute(f"SELECT count(*) FROM users WHERE {where}", params).fetchone()[0]
        rows = db.execute(f"SELECT * FROM users WHERE {where} ORDER BY CASE status WHEN 'pending' THEN 0 ELSE 1 END, id DESC LIMIT ? OFFSET ?", [*params, limit_count, offset]).fetchall()
        items = []
        for row in rows:
            item = public_user(row)
            counts = db.execute("SELECT count(*) requests,coalesce(sum(question_count),0) questions,coalesce(sum(input_tokens+output_tokens),0) tokens FROM usage WHERE user_id=?", (row["id"],)).fetchone()
            item.update(dict(counts)); items.append(item)
    return {"items": items, "total": total, "offset": offset}


@router.patch("/v1/admin/users/{identifier}")
def update_user(identifier: int, body: UserUpdate, admin=Depends(admin_user)):
    updates = body.model_dump(exclude_none=True)
    if not updates:
        raise HTTPException(400, "没有需要修改的内容")
    if body.status is not None and body.status not in ("active", "pending", "disabled", "rejected"):
        raise HTTPException(400, "无效的账号状态")
    if body.role is not None and body.role not in ("user", "superadmin"):
        raise HTTPException(400, "无效的角色")
    if body.daily_quota is not None and not 0 <= body.daily_quota <= 100000:
        raise HTTPException(400, "每日额度须为 0—100000 次")
    with store().connect() as db:
        db.execute("BEGIN IMMEDIATE")
        target = db.execute("SELECT * FROM users WHERE id=?", (identifier,)).fetchone()
        if target is None:
            raise HTTPException(404, "用户不存在")
        if identifier == admin["id"] and (body.status not in (None, "active") or body.role not in (None, "superadmin")):
            raise HTTPException(400, "不能停用或降级当前管理员账号")
        if target["role"] == "superadmin" and target["status"] == "active" and (body.role == "user" or body.status not in (None, "active")):
            active = db.execute("SELECT count(*) FROM users WHERE role='superadmin' AND status='active'").fetchone()[0]
            if active <= 1:
                raise HTTPException(400, "必须至少保留一位启用的超级管理员")
        assignments = ",".join(key + "=?" for key in updates)
        db.execute(f"UPDATE users SET {assignments} WHERE id=?", [*updates.values(), identifier])
        if body.status is not None or body.role is not None:
            db.execute("DELETE FROM sessions WHERE user_id=?", (identifier,))
    store().audit(admin["id"], identifier, "update_user:" + json.dumps(updates, separators=(",", ":")))
    return {"ok": True}


@router.post("/v1/admin/users/{identifier}/reset-password")
async def reset_password(identifier: int, admin=Depends(admin_user)):
    if identifier == admin["id"]:
        raise HTTPException(400, "请使用个人修改密码功能")
    password = "A7!" + secrets.token_urlsafe(15)
    encoded = await run_in_threadpool(passwords.hash, password)
    with store().connect() as db:
        if not db.execute("SELECT 1 FROM users WHERE id=?", (identifier,)).fetchone():
            raise HTTPException(404, "用户不存在")
        db.execute("UPDATE users SET password_hash=?,must_change_password=1 WHERE id=?", (encoded, identifier))
        db.execute("DELETE FROM sessions WHERE user_id=?", (identifier,))
    store().audit(admin["id"], identifier, "reset_password")
    return {"temporary_password": password, "message": "临时密码仅显示本次，请通过私密方式交给用户；首次登录须修改"}


@router.get("/v1/admin/usage")
def usage_records(user_id: Optional[int] = None, offset: int = Query(0, ge=0), limit_count: int = Query(30, ge=1, le=100), admin=Depends(admin_user)):
    where, params = ("user_id=?", [user_id]) if user_id is not None else ("1=1", [])
    with store().connect() as db:
        total = db.execute(f"SELECT count(*) FROM usage WHERE {where}", params).fetchone()[0]
        usage_where = 'v.user_id=?' if user_id is not None else '1=1'
        items = [dict(row) for row in db.execute(f"SELECT v.*,u.username,b.unit billing_unit,b.actual billing_actual,b.charged billing_charged,b.settled billing_settled FROM usage v JOIN users u ON u.id=v.user_id LEFT JOIN billing_usage b ON b.usage_id=v.id WHERE {usage_where} ORDER BY v.id DESC LIMIT ? OFFSET ?", [*params, limit_count, offset])]
    return {"items": items, "total": total}


@router.get("/v1/admin/audit")
def audit_records(offset: int = Query(0, ge=0), admin=Depends(admin_user)):
    with store().connect() as db:
        total = db.execute("SELECT count(*) FROM audit").fetchone()[0]
        items = [dict(row) for row in db.execute("SELECT a.*,u.username actor,t.username target FROM audit a LEFT JOIN users u ON u.id=a.actor_id LEFT JOIN users t ON t.id=a.target_id ORDER BY a.id DESC LIMIT 30 OFFSET ?", (offset,))]
    return {"items": items, "total": total}
