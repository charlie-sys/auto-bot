"""Prepaid time-limited credit subscriptions. No automatic debit or test payments."""
from __future__ import annotations
import io
import time
import secrets
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.responses import Response
from pydantic import BaseModel, Field
from auth import admin_user, current_user, limit
from database import store
from service_config import config, save_config

router = APIRouter()
UNITS = {'requests':'调用次数', 'questions':'题目数量', 'tokens':'Token'}


class Plan(BaseModel):
    name: str = Field(min_length=1,max_length=60)
    price_fen: int = Field(strict=True,ge=1,le=10000000)
    credits: int = Field(strict=True,ge=1,le=1000000000)
    duration_days: int = Field(strict=True,ge=1,le=3660)
    unit: Literal['requests','questions','tokens'] = 'requests'
    enabled: bool = False


def account_balance(user_id):
    with store().connect() as db:
        rows = [dict(row) for row in db.execute('SELECT g.*,o.plan_name FROM credit_grants g JOIN orders o ON o.id=g.order_id WHERE g.user_id=? ORDER BY g.expires_at,g.id',(user_id,))]
    now = int(time.time())
    return {'balances':{unit:sum(row['remaining'] for row in rows if row['unit']==unit and row['expires_at']>now) for unit in UNITS}, 'grants':rows}


def reserve_credit(db, user_id):
    now = int(time.time())
    row = db.execute('SELECT * FROM credit_grants WHERE user_id=? AND expires_at>? AND remaining>0 ORDER BY expires_at,id LIMIT 1',(user_id,now)).fetchone()
    if not row:
        raise HTTPException(429,'今日基础额度及有效订阅额度已用完，请续订或联系管理员')
    if db.execute('SELECT 1 FROM billing_usage b JOIN usage v ON v.id=b.usage_id WHERE v.user_id=? AND b.settled=0',(user_id,)).fetchone():
        raise HTTPException(409,'当前订阅调用正在结算，请等待本次结果后再试')
    db.execute('UPDATE credit_grants SET remaining=remaining-1 WHERE id=?',(row['id'],))
    return row


def settle_credit(db, usage_id, success, questions, tokens):
    entry = db.execute('SELECT b.*,v.user_id FROM billing_usage b JOIN usage v ON v.id=b.usage_id WHERE b.usage_id=?',(usage_id,)).fetchone()
    if not entry or entry['settled']:
        return
    actual = 1 if entry['unit']=='requests' else questions if entry['unit']=='questions' else tokens if entry['unit']=='tokens' else 0
    # One unit was held before calling the model; return it before actual settlement.
    db.execute('UPDATE credit_grants SET remaining=remaining+1 WHERE id=?',(entry['grant_id'],))
    remaining = actual
    original = db.execute('SELECT * FROM credit_grants WHERE id=?',(entry['grant_id'],)).fetchone()
    rows = [original] + db.execute('SELECT * FROM credit_grants WHERE user_id=? AND unit=? AND expires_at>? AND id!=? AND remaining>0 ORDER BY expires_at,id',(entry['user_id'],entry['unit'],int(time.time()),entry['grant_id'])).fetchall()
    for row in rows:
        amount = min(row['remaining'],remaining)
        db.execute('UPDATE credit_grants SET remaining=remaining-? WHERE id=?',(amount,row['id']))
        remaining -= amount
        if remaining<=0: break
    db.execute('UPDATE billing_usage SET actual=?,charged=?,settled=1 WHERE usage_id=?',(actual,actual-remaining,usage_id))
    # Never create a debt or debit the user's payment account. Overage is recorded, waived.


@router.get('/v1/me/subscription')
def subscription(user=Depends(current_user)):
    return {**account_balance(user['id']), 'daily_quota':user['daily_quota'], 'rules':'先使用每日基础调用额度，再使用最早到期的套餐。调用套餐包含测试和失败请求；题目套餐按成功返回题数；Token 套餐按接口报告的输入与输出之和。每次付费调用先预留 1 单位，完成后结算。单次超出可用额度的部分免扣，不产生欠费；未报告 Token 的请求扣 0 Token。各套餐有效期独立，重复购买不顺延旧额度，不自动续费。套餐费用为本平台服务费，不包含个人模型服务商费用。'}


@router.get('/v1/plans')
def plans(user=Depends(current_user)):
    with store().connect() as db:
        items = [dict(row) for row in db.execute('SELECT * FROM plans WHERE enabled=1 ORDER BY price_fen,id')]
    settings = config('payment')
    return {'items':items,'providers':[p for p in ('alipay','wechat') if settings.get(p,{}).get('enabled')]}


@router.get('/v1/admin/plans')
def admin_plans(admin=Depends(admin_user)):
    with store().connect() as db:
        return {'items':[dict(row) for row in db.execute('SELECT * FROM plans ORDER BY id DESC')]}


@router.post('/v1/admin/plans',status_code=201)
def create_plan(body: Plan, admin=Depends(admin_user)):
    with store().connect() as db:
        identifier=db.execute('INSERT INTO plans(name,price_fen,credits,duration_days,unit,enabled) VALUES (?,?,?,?,?,?)',(body.name,body.price_fen,body.credits,body.duration_days,body.unit,int(body.enabled))).lastrowid
    store().audit(admin['id'],None,'create_plan:'+str(identifier))
    return {'id':identifier,'message':'套餐已保存'}


@router.put('/v1/admin/plans/{identifier}')
def update_plan(identifier:int, body:Plan, admin=Depends(admin_user)):
    with store().connect() as db:
        if not db.execute('UPDATE plans SET name=?,price_fen=?,credits=?,duration_days=?,unit=?,enabled=? WHERE id=?',(body.name,body.price_fen,body.credits,body.duration_days,body.unit,int(body.enabled),identifier)).rowcount:
            raise HTTPException(404,'套餐不存在')
    store().audit(admin['id'],None,'update_plan:'+str(identifier))
    return {'message':'套餐已更新；已有订单的价格和额度保持下单时的快照'}


class OrderRequest(BaseModel):
    plan_id:int
    provider:Literal['alipay','wechat']
    accept_rules:bool = False


@router.post('/v1/me/orders',status_code=201)
async def create_order(body:OrderRequest,user=Depends(current_user)):
    from payment_provider import create_payment
    if not body.accept_rules:
        raise HTTPException(400,'请阅读并确认套餐额度规则')
    limit('order:'+str(user['id']),5,60)
    settings=config('payment')
    merchant=settings.get(body.provider,{})
    if not merchant.get('enabled'):
        raise HTTPException(503,'此支付渠道尚未配置，当前无法收款')
    now=int(time.time()); identifier='R'+secrets.token_hex(15)
    with store().connect() as db:
        db.execute('BEGIN IMMEDIATE')
        plan=db.execute('SELECT * FROM plans WHERE id=? AND enabled=1',(body.plan_id,)).fetchone()
        if not plan: raise HTTPException(404,'套餐已下架')
        db.execute('INSERT INTO orders(id,user_id,plan_id,plan_name,price_fen,credits,duration_days,provider,created_at,expires_at,merchant_id,app_id,unit) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)',(identifier,user['id'],plan['id'],plan['name'],plan['price_fen'],plan['credits'],plan['duration_days'],body.provider,now,now+1800,merchant['merchant_id'],merchant['app_id'],plan['unit']))
        order=dict(db.execute('SELECT * FROM orders WHERE id=?',(identifier,)).fetchone())
    try:
        url=await create_payment(order,settings)
    except Exception:
        # Timeout may still mean the provider created the order: retain pending for reconciliation.
        raise HTTPException(502,'支付下单未完成，请在订单列表检查状态；请勿重复付款')
    with store().connect() as db:
        db.execute('UPDATE orders SET payment_url=? WHERE id=?',(url,identifier))
    return {'id':identifier,'payment_url':url,'message':'请使用对应支付 App 扫码完成付款'}


def fulfill(order_id,provider,transaction_id,price_fen,merchant_id,app_id):
    if not transaction_id: raise HTTPException(400,'无效支付凭据')
    now=int(time.time())
    with store().connect() as db:
        db.execute('BEGIN IMMEDIATE')
        order=db.execute('SELECT * FROM orders WHERE id=?',(order_id,)).fetchone()
        if not order or order['provider']!=provider or order['price_fen']!=price_fen or order['merchant_id']!=merchant_id or order['app_id']!=app_id:
            raise HTTPException(400,'订单支付信息不匹配')
        if order['status']=='paid':
            if order['transaction_id']!=transaction_id: raise HTTPException(400,'交易信息不匹配')
            return
        if order['status']!='pending': raise HTTPException(409,'订单状态不允许入账')
        if db.execute('SELECT 1 FROM orders WHERE provider=? AND transaction_id=?',(provider,transaction_id)).fetchone():
            raise HTTPException(409,'交易已用于其他订单')
        db.execute("UPDATE orders SET status='paid',paid_at=?,transaction_id=? WHERE id=?",(now,transaction_id,order_id))
        db.execute('INSERT INTO credit_grants(user_id,order_id,total,remaining,expires_at,unit) VALUES (?,?,?,?,?,?)',(order['user_id'],order_id,order['credits'],order['credits'],now+order['duration_days']*86400,order['unit']))
        db.execute('INSERT INTO audit(actor_id,target_id,action,created_at) VALUES (NULL,?,?,?)',(order['user_id'],'payment_confirmed:'+order_id,now))


@router.get('/v1/me/orders')
def orders(offset:int=Query(0,ge=0),user=Depends(current_user)):
    return list_orders(offset,user['id'])


def list_orders(offset,user_id=None):
    where='o.user_id=?' if user_id else '1=1'; args=[user_id] if user_id else []
    with store().connect() as db:
        total=db.execute('SELECT count(*) FROM orders o WHERE '+where,args).fetchone()[0]
        rows=[dict(row) for row in db.execute('SELECT o.*,u.username FROM orders o JOIN users u ON u.id=o.user_id WHERE '+where+' ORDER BY o.created_at DESC,o.id DESC LIMIT 30 OFFSET ?',[*args,offset])]
    for row in rows:
        row.pop('merchant_id',None);row.pop('app_id',None);row.pop('transaction_id',None)
    return {'items':rows,'total':total}


@router.get('/v1/admin/orders')
def admin_orders(offset:int=Query(0,ge=0),admin=Depends(admin_user)):
    return list_orders(offset)


@router.post('/v1/me/orders/{identifier}/refresh')
async def refresh_order(identifier:str,user=Depends(current_user)):
    from payment_provider import query_payment
    limit('order-query:'+str(user['id']),12,60)
    with store().connect() as db:
        order=db.execute('SELECT * FROM orders WHERE id=? AND user_id=?',(identifier,user['id'])).fetchone()
    if not order: raise HTTPException(404,'订单不存在')
    if order['status']=='pending':
        await query_payment(dict(order),config('payment'))
    with store().connect() as db:
        status=db.execute('SELECT status FROM orders WHERE id=?',(identifier,)).fetchone()[0]
    return {'status':status,'message':'付款已确认，额度已到账' if status=='paid' else '暂未确认付款，请稍后刷新；超时订单可继续核对，避免漏记到账'}


@router.get('/v1/me/orders/{identifier}/qr')
def order_qr(identifier:str,user=Depends(current_user)):
    import qrcode
    with store().connect() as db:
        row=db.execute('SELECT payment_url,status,expires_at FROM orders WHERE id=? AND user_id=?',(identifier,user['id'])).fetchone()
    if not row or not row['payment_url']: raise HTTPException(404,'暂未生成支付二维码，请检查订单状态')
    if row['status']!='pending' or row['expires_at']<=int(time.time()): raise HTTPException(410,'二维码已失效，请核对订单状态')
    output=io.BytesIO();qrcode.make(row['payment_url']).save(output,format='PNG')
    return Response(output.getvalue(),media_type='image/png')


class PaymentSettings(BaseModel):
    public_base_url:str = ''
    alipay:dict = Field(default_factory=dict)
    wechat:dict = Field(default_factory=dict)


@router.get('/v1/admin/payment')
def get_payment(admin=Depends(admin_user)):
    value=config('payment')
    for provider in ('alipay','wechat'):
        setting=value.get(provider,{})
        for key in ('private_key','api_v3_key'):
            present=bool(setting.pop(key,None));setting['has_'+key]=present
        value[provider]=setting
    return value


@router.put('/v1/admin/payment')
def set_payment(body:PaymentSettings,admin=Depends(admin_user)):
    from payment_provider import validate_settings
    previous=config('payment');value={'public_base_url':body.public_base_url.strip().rstrip('/')}
    allowed=('enabled','app_id','merchant_id','private_key','public_key','serial_no','public_key_id','api_v3_key')
    for provider in ('alipay','wechat'):
        raw=getattr(body,provider)
        setting={key:raw[key] for key in allowed if key in raw}
        if any(not isinstance(v,(str,bool)) for v in setting.values()):raise HTTPException(400,'支付配置字段类型错误')
        for key in ('private_key','api_v3_key'):
            if not setting.get(key): setting[key]=previous.get(provider,{}).get(key,'')
        value[provider]=setting
    validate_settings(value)
    save_config('payment',value);store().audit(admin['id'],None,'update_payment')
    return {'message':'支付配置已加密保存；启用前请使用商户测试流程核对实际到账'}


@router.post('/v1/payments/{provider}/notify')
async def payment_notify(provider:Literal['alipay','wechat'],request:Request):
    from payment_provider import handle_notification
    body=await request.body()
    if len(body)>16384:raise HTTPException(413,'回调过大')
    await handle_notification(provider,body,request.headers,config('payment'))
    return Response('success',media_type='text/plain') if provider=='alipay' else Response(status_code=204)
