import asyncio
import io
import json
import time
import unittest
from unittest.mock import patch, AsyncMock

import httpx
from starlette.datastructures import UploadFile
import test_accounts
from app import solve_stream
from streaming_solver import QuestionStream
from model_provider import completion_stream


ITEM={'question_brief':'求和','question_full':'1. 2+3=?','answer_brief':'5','solution':'直接相加得到 5','uncertainty':''}


class StreamTests(unittest.IsolatedAsyncioTestCase):
    asyncSetUp=test_accounts.AccountTests.asyncSetUp
    asyncTearDown=test_accounts.AccountTests.asyncTearDown
    make_user=test_accounts.AccountTests.make_user
    configure=test_accounts.AccountTests.configure

    async def test_incremental_objects_escaped_strings_unicode_and_size_limit(self):
        value={'questions':[{**ITEM,'question_full':'字符串有 } { [ ] 和 "引号"\n第二行'},ITEM],'uncertainty':''}
        text=json.dumps(value,ensure_ascii=False);parser=QuestionStream();items=[]
        for char in text:items.extend(parser.feed(char))
        self.assertEqual(items,value['questions'])
        with self.assertRaises(ValueError):parser.feed('x'*(1024*1024))

    async def test_first_question_precedes_upstream_finish_and_single_usage(self):
        await self.configure(self.first)
        gate=asyncio.Event()
        async def upstream(*args):
            yield {'text':'{"questions":['+json.dumps(ITEM,ensure_ascii=False)}
            await gate.wait()
            yield {'text':','+json.dumps({**ITEM,'answer_brief':'6'},ensure_ascii=False)+'],"uncertainty":""}'}
            yield {'usage':{'prompt_tokens':10,'completion_tokens':20}}
        with patch('app.completion_stream',upstream):
            response=await solve_stream(image=UploadFile(io.BytesIO(b'\xff\xd8\xff'+b'x'*200)),mode='fast',user={'id':self.first['id']})
            iterator=response.body_iterator
            self.assertEqual(json.loads(await iterator.__anext__())['type'],'start')
            question=json.loads(await asyncio.wait_for(iterator.__anext__(),1))
            self.assertEqual(question['type'],'question');self.assertFalse(gate.is_set())
            gate.set();rest=[json.loads(row) async for row in iterator]
            self.assertEqual([row['type'] for row in rest],['question','done'])
        with self.db.connect() as db:
            rows=db.execute('SELECT * FROM usage WHERE user_id=?',(self.first['id'],)).fetchall()
            self.assertEqual(len(rows),1);self.assertEqual(rows[0]['question_count'],2);self.assertEqual(rows[0]['output_tokens'],20)

    async def test_partial_failure_retains_delivered_question_and_charges_once(self):
        await self.configure(self.first)
        from test_email_billing import EmailBillingTests
        EmailBillingTests.grant(self,'questions',10)
        async def upstream(*args):
            yield {'text':'{"questions":['+json.dumps(ITEM)}
            raise httpx.ReadError('private-provider-error-never-reflected')
        with patch('app.completion_stream',upstream):
            response=await self.client.post('/v1/solve-stream?mode=fast',headers=self.first['headers'],files={'image':('q.jpg',b'\xff\xd8\xff'+b'x'*200,'image/jpeg')})
        events=[json.loads(line) for line in response.text.splitlines()]
        self.assertEqual([item['type'] for item in events],['start','question','error'])
        self.assertNotIn('private-provider-error',response.text)
        with self.db.connect() as db:
            self.assertEqual(db.execute('SELECT remaining FROM credit_grants').fetchone()[0],9)
            self.assertEqual(db.execute('SELECT question_count FROM usage').fetchone()[0],1)

    def order(self,*args,**kwargs):
        from test_email_billing import EmailBillingTests
        return EmailBillingTests.order(self,*args,**kwargs)

    async def test_cancelled_stream_settles_reservation_and_keeps_partial_count(self):
        await self.configure(self.first)
        async def upstream(*args):
            yield {'text':'{"questions":['+json.dumps(ITEM)}
            await asyncio.sleep(30)
        with patch('app.completion_stream',upstream):
            response=await solve_stream(image=UploadFile(io.BytesIO(b'\xff\xd8\xff'+b'x'*200)),mode='fast',user={'id':self.first['id']})
            await response.body_iterator.__anext__();await response.body_iterator.__anext__();await response.body_iterator.aclose()
        with self.db.connect() as db:
            row=db.execute('SELECT * FROM usage').fetchone()
            self.assertEqual(row['status'],'failed');self.assertEqual(row['question_count'],1);self.assertEqual(row['error_code'],499)

    async def test_fast_and_detailed_modes_and_target_validation(self):
        await self.configure(self.first)
        value={'choices':[{'message':{'content':json.dumps({'questions':[ITEM],'uncertainty':''})}}]}
        with patch('app.completion',new=AsyncMock(return_value=value)) as call:
            response=await self.client.post('/v1/solve-batch?mode=fast',headers=self.first['headers'],files={'image':('q.jpg',b'\xff\xd8\xff'+b'x'*200,'image/jpeg')})
            self.assertEqual(response.status_code,200)
            self.assertIn('极速答题',call.call_args.args[1][0]['content'])
        value={'choices':[{'message':{'content':json.dumps({'readable':True,**ITEM})}}]}
        with patch('app.completion',new=AsyncMock(return_value=value)) as call:
            response=await self.client.post('/v1/solve-detail',headers=self.first['headers'],data={'question':ITEM['question_full']},files={'image':('q.jpg',b'\xff\xd8\xff'+b'x'*200,'image/jpeg')})
            self.assertEqual(response.status_code,200)
            self.assertIn('<target_question>',call.call_args.args[1][1]['content'][0]['text'])
        response=await self.client.post('/v1/solve-stream',files={'image':('q.jpg',b'\xff\xd8\xff'+b'x'*200,'image/jpeg')})
        self.assertEqual(response.status_code,401)

    async def test_provider_sse_usage_parsing_and_error_redaction(self):
        content='data: '+json.dumps({'choices':[{'delta':{'content':'题目'}}]})+'\n\ndata: '+json.dumps({'choices':[],'usage':{'prompt_tokens':3,'completion_tokens':4}})+'\n\ndata: [DONE]\n\n'
        seen=[]
        def backend(request):seen.append(json.loads(request.content));return httpx.Response(200,text=content,headers={'Content-Type':'text/event-stream'})
        with patch('model_provider.PublicModelTransport',return_value=httpx.MockTransport(backend)):
            values=[value async for value in completion_stream({'base_url':'https://model.example/v1','api_key':'test','model':'qwen-test'},[])]
        self.assertEqual(values,[{'text':'题目'},{'usage':{'prompt_tokens':3,'completion_tokens':4}}])
        self.assertFalse(seen[0]['enable_thinking']);self.assertTrue(seen[0]['stream'])


if __name__=='__main__':unittest.main()
