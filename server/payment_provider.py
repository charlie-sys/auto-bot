"""Official Alipay RSA2 / WeChat Pay APIv3 Native adapters, disabled until configured."""
from __future__ import annotations
import base64
import json
import re
import secrets
import time
from datetime import datetime, timezone, timedelta
from decimal import Decimal
from urllib.parse import parse_qsl, urlencode, urlsplit

import httpx
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from fastapi import HTTPException


def private_key(value):
    key=serialization.load_pem_private_key(value.encode(),password=None)
    if not isinstance(key,rsa.RSAPrivateKey) or key.key_size<2048:raise ValueError('RSA2048 required')
    return key


def public_key(value):
    key=serialization.load_pem_public_key(value.encode())
    if not isinstance(key,rsa.RSAPublicKey) or key.key_size<2048:raise ValueError('RSA2048 required')
    return key


def sign(value,key):
    return base64.b64encode(private_key(key).sign(value.encode(),padding.PKCS1v15(),hashes.SHA256())).decode()


def verify(value,signature,key):
    public_key(key).verify(base64.b64decode(signature,validate=True),value.encode(),padding.PKCS1v15(),hashes.SHA256())


def validate_settings(settings):
    try:
        for provider in ('alipay','wechat'):
            s=settings[provider]
            if type(s.get('enabled',False)) is not bool:raise ValueError()
            if not s.get('enabled'):continue
            url=urlsplit(settings['public_base_url'])
            if url.scheme!='https' or not url.hostname or url.username or url.query or url.fragment or url.path:raise ValueError()
            if not re.fullmatch('[A-Za-z0-9]{5,40}',s.get('merchant_id','')) or not re.fullmatch('[A-Za-z0-9]{5,40}',s.get('app_id','')):raise ValueError()
            private_key(s['private_key']);public_key(s['public_key'])
            if provider=='wechat':
                if len(s.get('api_v3_key','').encode())!=32 or not re.fullmatch('[A-Za-z0-9_]{5,80}',s.get('public_key_id','')) or not re.fullmatch('[A-Fa-f0-9]{10,80}',s.get('serial_no','')):raise ValueError()
    except Exception:
        raise HTTPException(400,'启用支付前请填写公共 HTTPS 根地址、商户号、APPID、PEM 格式 RSA 密钥及对应渠道的完整参数')


def canonical(params):
    return '&'.join(k+'='+str(params[k]) for k in sorted(params) if k not in ('sign','sign_type') and params[k]!='')


async def alipay_call(method,biz,settings,notify=None):
    s=settings['alipay']
    params={'app_id':s['app_id'],'method':method,'format':'JSON','charset':'utf-8','sign_type':'RSA2','timestamp':datetime.now(timezone(timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S'),'version':'1.0','biz_content':json.dumps(biz,ensure_ascii=False,separators=(',',':'))}
    if notify:params['notify_url']=notify
    # sign_type participates in request signatures (but not asynchronous notification signatures).
    source='&'.join(k+'='+str(params[k]) for k in sorted(params) if k!='sign' and params[k]!='')
    params['sign']=sign(source,s['private_key'])
    async with httpx.AsyncClient(timeout=20,trust_env=False,follow_redirects=False) as client:
        response=await client.post('https://openapi.alipay.com/gateway.do',data=params)
    response.raise_for_status()
    text=response.text; value=json.loads(text);name=method.replace('.','_')+'_response'
    # Response RSA2 signs the exact JSON bytes of the method response, not reserialized JSON.
    match=re.search(r'"'+re.escape(name)+r'"\s*:\s*',text)
    if not match:raise ValueError('invalid response')
    _,length=json.JSONDecoder().raw_decode(text[match.end():])
    verify(text[match.end():match.end()+length],value['sign'],s['public_key'])
    data=value[name]
    if data.get('code')!='10000':raise ValueError('provider rejected request')
    return data


def wechat_verify(body,headers,s):
    timestamp=headers.get('wechatpay-timestamp','')
    if abs(int(time.time())-int(timestamp))>300 or headers.get('wechatpay-serial')!=s['public_key_id']:
        raise ValueError('invalid signature context')
    verify(timestamp+'\n'+headers.get('wechatpay-nonce','')+'\n'+body+'\n',headers.get('wechatpay-signature',''),s['public_key'])


async def wechat_call(method,path,data,settings):
    s=settings['wechat'];nonce=secrets.token_hex(16);timestamp=str(int(time.time()))
    content=json.dumps(data,ensure_ascii=False,separators=(',',':')) if data is not None else ''
    signature=sign(method+'\n'+path+'\n'+timestamp+'\n'+nonce+'\n'+content+'\n',s['private_key'])
    authorization=f'WECHATPAY2-SHA256-RSA2048 mchid="{s["merchant_id"]}",nonce_str="{nonce}",timestamp="{timestamp}",serial_no="{s["serial_no"]}",signature="{signature}"'
    async with httpx.AsyncClient(timeout=20,trust_env=False,follow_redirects=False) as client:
        response=await client.request(method,'https://api.mch.weixin.qq.com'+path,content=content.encode(),headers={'Authorization':authorization,'Accept':'application/json','Content-Type':'application/json','Wechatpay-Serial':s['public_key_id']})
    response.raise_for_status();wechat_verify(response.text,response.headers,s)
    return response.json()


async def create_payment(order,settings):
    notify=settings['public_base_url']+'/v1/payments/'+order['provider']+'/notify'
    if order['provider']=='alipay':
        data=await alipay_call('alipay.trade.precreate',{'out_trade_no':order['id'],'total_amount':f'{order["price_fen"]/100:.2f}','subject':order['plan_name'],'timeout_express':'30m'},settings,notify)
        if data.get('out_trade_no')!=order['id']:raise ValueError()
        url=data['qr_code']
        parsed=urlsplit(url)
        if parsed.scheme!='https' or parsed.hostname!='qr.alipay.com':raise ValueError()
        return url
    s=settings['wechat']
    data=await wechat_call('POST','/v3/pay/transactions/native',{'appid':s['app_id'],'mchid':s['merchant_id'],'description':order['plan_name'],'out_trade_no':order['id'],'notify_url':notify,'time_expire':datetime.fromtimestamp(order['expires_at'],timezone(timedelta(hours=8))).isoformat(),'amount':{'total':order['price_fen'],'currency':'CNY'}},settings)
    url=data['code_url']
    if not url.startswith('weixin://wxpay/'):raise ValueError()
    return url


def alipay_paid(data,s,app_id=None):
    from billing import fulfill
    if data.get('trade_status') not in ('TRADE_SUCCESS','TRADE_FINISHED'):return
    amount=Decimal(data['total_amount'])*100
    if amount!=amount.to_integral_value():raise ValueError()
    fulfill(data['out_trade_no'],'alipay',data['trade_no'],int(amount),data['seller_id'],app_id or s['app_id'])


def wechat_paid(data):
    from billing import fulfill
    if data.get('trade_state')!='SUCCESS':return
    if data['amount'].get('currency')!='CNY' or type(data['amount']['total']) is not int:raise ValueError()
    fulfill(data['out_trade_no'],'wechat',data['transaction_id'],data['amount']['total'],data['mchid'],data['appid'])


async def query_payment(order,settings):
    s=settings.get(order['provider'],{})
    if not s.get('enabled'):raise HTTPException(503,'此支付渠道未启用，请联系管理员核对订单')
    try:
        if order['provider']=='alipay':
            data=await alipay_call('alipay.trade.query',{'out_trade_no':order['id']},settings)
            if data.get('out_trade_no')!=order['id']:raise ValueError()
            alipay_paid(data,s)
        else:
            data=await wechat_call('GET','/v3/pay/transactions/out-trade-no/'+order['id']+'?'+urlencode({'mchid':s['merchant_id']}),None,settings)
            if data.get('out_trade_no')!=order['id']:raise ValueError()
            wechat_paid(data)
    except HTTPException:raise
    except Exception:raise HTTPException(502,'暂时无法核对支付状态，请稍后重试或联系管理员')


async def handle_notification(provider,body,headers,settings):
    s=settings.get(provider,{})
    if not s.get('enabled'):raise HTTPException(503,'支付渠道未启用')
    try:
        if provider=='alipay':
            pairs=parse_qsl(body.decode('utf-8'),keep_blank_values=True,max_num_fields=100)
            data=dict(pairs)
            if len(data)!=len(pairs) or data.get('sign_type')!='RSA2':raise ValueError()
            verify(canonical(data),data['sign'],s['public_key'])
            if data.get('app_id')!=s['app_id'] or data.get('seller_id')!=s['merchant_id']:raise ValueError()
            alipay_paid(data,s,data['app_id'])
        else:
            text=body.decode();wechat_verify(text,headers,s)
            event=json.loads(text)
            if event.get('event_type')!='TRANSACTION.SUCCESS':raise ValueError()
            resource=event['resource']
            if resource.get('algorithm')!='AEAD_AES_256_GCM':raise ValueError()
            plain=AESGCM(s['api_v3_key'].encode()).decrypt(resource['nonce'].encode(),base64.b64decode(resource['ciphertext']),resource.get('associated_data','').encode())
            data=json.loads(plain)
            if data.get('trade_state')!='SUCCESS':raise ValueError()
            wechat_paid(data)
    except HTTPException:raise
    except Exception:raise HTTPException(400,'支付回调验证失败')
