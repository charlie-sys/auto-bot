import asyncio
import json
import time
import unittest
from unittest.mock import patch
import test_accounts
import solve_control

ITEM={'question_brief':'求和','question_full':'2+3=?','answer_brief':'5','solution':'相加得到5','uncertainty':''}

class SolveControlTests(unittest.IsolatedAsyncioTestCase):
    make_user=test_accounts.AccountTests.make_user
    configure=test_accounts.AccountTests.configure
    asyncTearDown=test_accounts.AccountTests.asyncTearDown
    async def asyncSetUp(self):
        await test_accounts.AccountTests.asyncSetUp(self)
        solve_control.active.clear();solve_control.recent.clear()
        await self.configure(self.first)

    def headers(self,identifier,channel='c'*32,user=None):
        return {**(user or self.first)['headers'],'X-Solve-Id':identifier,'X-Solve-Channel':channel}
    def image(self):return {'image':('q.jpg',b'\xff\xd8\xff'+b'x'*200,'image/jpeg')}

    async def test_explicit_cancel_stops_nonstream_provider_and_settles_499(self):
        started=asyncio.Event();stopped=asyncio.Event()
        async def slow(*args):
            started.set()
            try:await asyncio.sleep(30)
            finally:stopped.set()
        with patch('app.completion',slow):
            old=asyncio.create_task(self.client.post('/v1/solve-batch',headers=self.headers('a'*32),files=self.image()))
            await asyncio.wait_for(started.wait(),3)
            wrong=await self.client.post('/v1/solve-cancel',headers=self.second['headers'],json={'request_id':'a'*32})
            self.assertEqual(wrong.status_code,200);self.assertFalse(stopped.is_set())
            begin=time.monotonic()
            await self.client.post('/v1/solve-cancel',headers=self.first['headers'],json={'request_id':'a'*32})
            result=await asyncio.wait_for(old,2)
            self.assertLess(time.monotonic()-begin,1);self.assertEqual(result.status_code,499)
        self.assertTrue(stopped.is_set());self.assertFalse(solve_control.active)
        with self.db.connect() as db:self.assertEqual(db.execute('SELECT error_code FROM usage').fetchone()[0],499)

    async def test_new_request_same_device_preempts_old_before_model_timeout(self):
        from test_email_billing import EmailBillingTests
        self.order=lambda *a,**kw:EmailBillingTests.order(self,*a,**kw)
        EmailBillingTests.grant(self,'questions',10)
        started=asyncio.Event();calls=0
        async def model(*args):
            nonlocal calls
            calls+=1
            if calls==1:started.set();await asyncio.sleep(30)
            return {'choices':[{'message':{'content':json.dumps({'questions':[ITEM],'uncertainty':''})}}]}
        with patch('app.completion',model):
            old=asyncio.create_task(self.client.post('/v1/solve-batch',headers=self.headers('a'*32),files=self.image()))
            await asyncio.wait_for(started.wait(),3);begin=time.monotonic()
            new=await asyncio.wait_for(self.client.post('/v1/solve-batch',headers=self.headers('b'*32),files=self.image()),2)
            self.assertEqual(new.status_code,200);self.assertLess(time.monotonic()-begin,1)
            self.assertEqual((await old).status_code,499)
        with self.db.connect() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM usage WHERE status='success'").fetchone()[0],1)
            self.assertEqual(db.execute('SELECT remaining FROM credit_grants').fetchone()[0],9)

    async def test_cancel_before_upload_and_replayed_id_do_not_call_model(self):
        await self.client.post('/v1/solve-cancel',headers=self.first['headers'],json={'request_id':'a'*32})
        async def forbidden(*args):self.fail('a cancelled/replayed request must not call the model')
        with patch('app.completion',forbidden):
            response=await self.client.post('/v1/solve-batch',headers=self.headers('a'*32),files=self.image())
            self.assertEqual(response.status_code,409)
        with self.db.connect() as db:self.assertEqual(db.execute('SELECT count(*) FROM usage').fetchone()[0],0)

    async def test_other_device_is_not_preempted_and_cancel_requires_auth(self):
        started=asyncio.Event();release=asyncio.Event();calls=0
        async def model(*args):
            nonlocal calls
            calls+=1
            if calls==1:started.set();await release.wait()
            return {'choices':[{'message':{'content':json.dumps({'questions':[ITEM],'uncertainty':''})}}]}
        with patch('app.completion',model):
            old=asyncio.create_task(self.client.post('/v1/solve-batch',headers=self.headers('a'*32),files=self.image()))
            await asyncio.wait_for(started.wait(),3)
            new=await self.client.post('/v1/solve-batch',headers=self.headers('b'*32,'d'*32),files=self.image())
            self.assertEqual(new.status_code,200);self.assertFalse(old.done())
            release.set();self.assertEqual((await old).status_code,200)
        response=await self.client.post('/v1/solve-cancel',json={'request_id':'a'*32})
        self.assertEqual(response.status_code,401)

if __name__=='__main__':unittest.main()
