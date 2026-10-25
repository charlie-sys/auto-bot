import base64
import json
import time
import unittest
from unittest.mock import patch, AsyncMock

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from fastapi import HTTPException
import test_accounts
from billing import fulfill, account_balance
from account_routes import reserve_usage, finish_usage
from email_auth import code_hash
from service_config import save_config
from payment_provider import sign, canonical


class EmailBillingTests(unittest.IsolatedAsyncioTestCase):
    asyncSetUp=test_accounts.AccountTests.asyncSetUp
    asyncTearDown=test_accounts.AccountTests.asyncTearDown
    make_user=test_accounts.AccountTests.make_user

    def code(self,email,purpose,code='123456',expired=False):
        now=int(time.time())
        with self.db.connect() as db:
            db.execute('INSERT INTO email_codes(email,purpose,code_hash,created_at,expires_at) VALUES (?,?,?,?,?)',(email,purpose,code_hash(email,purpose,code),now,now-1 if expired else now+600))

    def keypair(self):
        key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        private=key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()).decode()
        public=key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()
        return private,public

    def payment(self,provider='alipay'):
        private,public=self.keypair()
        settings={'public_base_url':'https://example.com',provider:{'enabled':True,'app_id':'app12345','merchant_id':'merchant12345','private_key':private,'public_key':public,'api_v3_key':'K'*32,'public_key_id':'PUB_KEY_ID_TEST','serial_no':'A'*40}}
        save_config('payment',settings)
        return settings,private

    def order(self,unit='requests',credits=10,provider='alipay',identifier=None):
        identifier=identifier or 'R'+str(time.time_ns())
        now=int(time.time())
        with self.db.connect() as db:
            db.execute('INSERT INTO orders(id,user_id,plan_id,plan_name,price_fen,credits,duration_days,provider,created_at,expires_at,merchant_id,app_id,unit) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)',(identifier,self.first['id'],1,'test',990,credits,30,provider,now,now+1800,'merchant12345','app12345',unit))
        return identifier

    def grant(self,unit='requests',credits=10):
        identifier=self.order(unit,credits)
        fulfill(identifier,'alipay','trade'+identifier,990,'merchant12345','app12345')
        with self.db.connect() as db:
            db.execute('UPDATE users SET daily_quota=0 WHERE id=?',(self.first['id'],))
        return identifier

    async def test_email_required_register_and_one_use(self):
        value={'username':'newreader','password':'Password12345'}
        self.assertEqual((await self.client.post('/v1/auth/register',json=value)).status_code,400)
        self.code('new@example.com','register')
        value.update(email='new@example.com',code='123456')
        result=await self.client.post('/v1/auth/register',json=value)
        self.assertEqual(result.status_code,201)
        self.assertEqual((await self.client.post('/v1/auth/register',json=value)).status_code,400)
        self.assertEqual((await self.client.post('/v1/auth/login',json=value)).status_code,403)

    async def test_code_send_disabled_rate_limit_and_secrets(self):
        value={'email':'alice@example.com','purpose':'bind'}
        self.assertEqual((await self.client.post('/v1/auth/email-code',json=value,headers=self.first['headers'])).status_code,503)
        save_config('smtp',{'enabled':True,'password':'smtp-secret'})
        with patch('email_auth.send_mail') as send:
            result=await self.client.post('/v1/auth/email-code',json=value,headers=self.first['headers'])
            self.assertEqual(result.status_code,200);self.assertNotIn('code',result.json());send.assert_called_once()
            self.assertEqual((await self.client.post('/v1/auth/email-code',json=value,headers=self.first['headers'])).status_code,429)
        smtp=await self.client.get('/v1/admin/smtp',headers=self.admin['headers'])
        self.assertNotIn('smtp-secret',smtp.text)
        with self.db.connect() as db:
            self.assertNotIn('smtp-secret',db.execute('SELECT encrypted_value FROM service_settings').fetchone()[0])

    async def test_code_attempts_expiry_purpose_and_bind(self):
        self.code('alice@example.com','bind')
        for _ in range(5):
            result=await self.client.post('/v1/me/email',json={'email':'alice@example.com','code':'000000'},headers=self.first['headers'])
            self.assertEqual(result.status_code,400)
        self.assertEqual((await self.client.post('/v1/me/email',json={'email':'alice@example.com','code':'123456'},headers=self.first['headers'])).status_code,400)
        self.code('alice@example.com','register')
        self.assertEqual((await self.client.post('/v1/me/email',json={'email':'alice@example.com','code':'123456'},headers=self.first['headers'])).status_code,400)
        self.code('alice@example.com','bind',expired=True)
        self.assertEqual((await self.client.post('/v1/me/email',json={'email':'alice@example.com','code':'123456'},headers=self.first['headers'])).status_code,400)
        self.code('alice@example.com','bind')
        self.assertEqual((await self.client.post('/v1/me/email',json={'email':'alice@example.com','code':'123456'},headers=self.first['headers'])).status_code,200)

    async def test_email_login_reset_revokes_sessions_preserves_status(self):
        with self.db.connect() as db:
            db.execute('UPDATE users SET email=? WHERE id=?',('alice@example.com',self.first['id']))
        self.code('alice@example.com','login')
        result=await self.client.post('/v1/auth/email-login',json={'email':'alice@example.com','code':'123456'})
        self.assertEqual(result.status_code,200)
        self.code('alice@example.com','reset')
        result=await self.client.post('/v1/auth/email-reset',json={'email':'alice@example.com','code':'123456','password':'NewPassword456'})
        self.assertEqual(result.status_code,200)
        self.assertEqual((await self.client.get('/v1/me',headers=self.first['headers'])).status_code,401)
        result=await self.client.post('/v1/auth/login',json={'username':'alice@example.com','password':'NewPassword456'})
        self.assertEqual(result.status_code,200)
        with self.db.connect() as db:db.execute("UPDATE users SET status='disabled' WHERE id=?",(self.first['id'],))
        self.code('alice@example.com','reset')
        await self.client.post('/v1/auth/email-reset',json={'email':'alice@example.com','code':'123456','password':'AgainPassword789'})
        self.assertEqual((await self.client.post('/v1/auth/login',json={'username':'alice@example.com','password':'AgainPassword789'})).status_code,403)

    async def test_plan_permissions_disabled_payment_snapshot_ownership(self):
        plan={'name':'按题测试包','price_fen':990,'credits':100,'unit':'questions','duration_days':30,'enabled':True}
        self.assertEqual((await self.client.post('/v1/admin/plans',json=plan,headers=self.first['headers'])).status_code,403)
        result=await self.client.post('/v1/admin/plans',json=plan,headers=self.admin['headers']);self.assertEqual(result.status_code,201)
        identifier=result.json()['id'];body={'plan_id':identifier,'provider':'alipay','accept_rules':True}
        self.assertEqual((await self.client.post('/v1/me/orders',json=body,headers=self.first['headers'])).status_code,503)
        self.payment()
        with patch('payment_provider.create_payment',new=AsyncMock(return_value='https://qr.alipay.com/test')):
            result=await self.client.post('/v1/me/orders',json=body,headers=self.first['headers'])
        self.assertEqual(result.status_code,201);order_id=result.json()['id']
        self.assertEqual((await self.client.post('/v1/me/orders/'+order_id+'/refresh',headers=self.second['headers'])).status_code,404)
        await self.client.put('/v1/admin/plans/'+str(identifier),json={**plan,'price_fen':1990,'credits':200},headers=self.admin['headers'])
        with self.db.connect() as db:
            row=db.execute('SELECT * FROM orders WHERE id=?',(order_id,)).fetchone()
            self.assertEqual(row['price_fen'],990);self.assertEqual(row['credits'],100)

    async def test_fulfillment_replay_wrong_amount_and_transaction_unique(self):
        identifier=self.order()
        with self.assertRaises(HTTPException):fulfill(identifier,'alipay','T1',991,'merchant12345','app12345')
        fulfill(identifier,'alipay','T1',990,'merchant12345','app12345')
        fulfill(identifier,'alipay','T1',990,'merchant12345','app12345')
        self.assertEqual(account_balance(self.first['id'])['balances']['requests'],10)
        with self.assertRaises(HTTPException):fulfill(identifier,'alipay','T2',990,'merchant12345','app12345')
        self.assertEqual((await self.client.get('/v1/me/orders',headers=self.second['headers'])).json()['items'],[])

    async def test_three_units_settlement_idempotence_free_first_and_expiry(self):
        for unit in ('requests','questions','tokens'):
            with self.db.connect() as db:db.execute('UPDATE credit_grants SET expires_at=0')
            self.grant(unit,10)
            usage=reserve_usage(self.first,'vision')
            finish_usage(usage,time.monotonic(),success=True,questions=3,tokens={'prompt_tokens':4,'completion_tokens':2})
            finish_usage(usage,time.monotonic(),success=True,questions=3,tokens={'prompt_tokens':4,'completion_tokens':2})
            self.assertEqual(account_balance(self.first['id'])['balances'][unit],10-{'requests':1,'questions':3,'tokens':6}[unit])
        with self.db.connect() as db:
            db.execute('UPDATE users SET daily_quota=100 WHERE id=?',(self.first['id'],))
        usage=reserve_usage(self.first,'vision');finish_usage(usage,time.monotonic(),success=True)
        self.assertEqual(account_balance(self.first['id'])['balances']['tokens'],4)
        with self.db.connect() as db:
            db.execute('UPDATE users SET daily_quota=0 WHERE id=?',(self.first['id'],));db.execute('UPDATE credit_grants SET expires_at=0')
        with self.assertRaises(HTTPException):reserve_usage(self.first,'vision')

    async def test_prepaid_holds_single_flight_no_debt_and_failure_refund(self):
        self.grant('tokens',2)
        usage=reserve_usage(self.first,'vision')
        with self.assertRaises(HTTPException):reserve_usage(self.first,'vision')
        finish_usage(usage,time.monotonic(),success=False)
        self.assertEqual(account_balance(self.first['id'])['balances']['tokens'],2)
        usage=reserve_usage(self.first,'vision')
        finish_usage(usage,time.monotonic(),success=True,tokens={'prompt_tokens':100,'completion_tokens':100})
        self.assertEqual(account_balance(self.first['id'])['balances']['tokens'],0)
        with self.db.connect() as db:
            row=db.execute('SELECT * FROM billing_usage WHERE usage_id=?',(usage,)).fetchone()
            self.assertEqual(row['actual'],200);self.assertEqual(row['charged'],2)

    async def test_alipay_notification_signature_amount_and_replay(self):
        settings,key=self.payment();identifier=self.order()
        data={'app_id':'app12345','seller_id':'merchant12345','out_trade_no':identifier,'trade_no':'ALITRADE','trade_status':'TRADE_SUCCESS','total_amount':'9.90','sign_type':'RSA2'}
        from urllib.parse import urlencode
        invalid=await self.client.post('/v1/payments/alipay/notify',content=urlencode({**data,'sign':'invalid'}),headers={'Content-Type':'application/x-www-form-urlencoded'})
        self.assertEqual(invalid.status_code,400)
        data['sign']=sign(canonical(data),key)
        for _ in range(2):
            result=await self.client.post('/v1/payments/alipay/notify',content=urlencode(data),headers={'Content-Type':'application/x-www-form-urlencoded'})
            self.assertEqual(result.status_code,200)
        self.assertEqual(account_balance(self.first['id'])['balances']['requests'],10)

    async def test_wechat_notification_signature_aead_and_replay(self):
        settings,key=self.payment('wechat');identifier=self.order(provider='wechat')
        data={'appid':'app12345','mchid':'merchant12345','out_trade_no':identifier,'transaction_id':'WXTRADE','trade_state':'SUCCESS','amount':{'total':990,'currency':'CNY'}}
        nonce='123456789012';associated='transaction'
        cipher=AESGCM(b'K'*32).encrypt(nonce.encode(),json.dumps(data).encode(),associated.encode())
        body=json.dumps({'event_type':'TRANSACTION.SUCCESS','resource':{'algorithm':'AEAD_AES_256_GCM','nonce':nonce,'associated_data':associated,'ciphertext':base64.b64encode(cipher).decode()}})
        stamp=str(int(time.time()))
        headers={'Wechatpay-Timestamp':stamp,'Wechatpay-Nonce':'testnonce','Wechatpay-Serial':'PUB_KEY_ID_TEST','Wechatpay-Signature':sign(stamp+'\ntestnonce\n'+body+'\n',key),'Content-Type':'application/json'}
        for _ in range(2):
            result=await self.client.post('/v1/payments/wechat/notify',content=body,headers=headers)
            self.assertEqual(result.status_code,204)
        result=await self.client.post('/v1/payments/wechat/notify',content=body.replace('SUCCESS','FAILED'),headers=headers)
        self.assertEqual(result.status_code,400)
        self.assertEqual(account_balance(self.first['id'])['balances']['requests'],10)

    async def test_private_settings_redacted_and_service_permissions(self):
        settings,_=self.payment('wechat')
        response=await self.client.get('/v1/admin/payment',headers=self.admin['headers'])
        self.assertNotIn('BEGIN PRIVATE KEY',response.text);self.assertNotIn('K'*32,response.text)
        self.assertTrue(response.json()['wechat']['has_private_key'])
        for endpoint in ('payment','smtp','orders','plans'):
            self.assertEqual((await self.client.get('/v1/admin/'+endpoint,headers=self.first['headers'])).status_code,403)


if __name__=='__main__':unittest.main()
