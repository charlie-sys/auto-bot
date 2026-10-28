import asyncio
import json
import os
import tempfile
import time
import unittest
from unittest.mock import AsyncMock, patch

import httpx
from app import app
from database import passwords, store


class VoiceTextTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.environment = patch.dict(os.environ, {'DATA_DIR': self.temp.name, 'BOOTSTRAP_ADMIN': 'false'})
        self.environment.start()
        self.db = store()
        with self.db.connect() as db:
            identifier = db.execute("INSERT INTO users(username,password_hash,role,status,created_at) VALUES (?,?,'user','active',?)", ('voice_fixture', passwords.hash('Password12345'), int(time.time()))).lastrowid
        token, _ = self.db.issue_session(identifier, 'user')
        self.headers = {'Authorization': 'Bearer ' + token}
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test')
        self.item = dict(question_brief='加法', question_full='计算二加三等于多少', answer_brief='5', solution='2+3=5', uncertainty='')

    async def asyncTearDown(self):
        await self.client.aclose()
        self.environment.stop()
        self.temp.cleanup()

    async def configure(self):
        response = await self.client.put('/v1/me/model', headers=self.headers, json={'base_url': 'https://model.example/v1', 'model': 'voice-user-model', 'api_key': 'fixture-key'})
        self.assertEqual(response.status_code, 200)

    def reply(self, questions=None):
        return {'choices': [{'message': {'content': json.dumps({'questions': [self.item] if questions is None else questions, 'uncertainty': '缺少图形条件' if questions == [] else ''})}}], 'usage': {'prompt_tokens': 11, 'completion_tokens': 7}}

    async def test_authentication_configuration_and_input_bounds(self):
        self.assertEqual((await self.client.post('/v1/solve-text', json={'text': '计算二加三'})).status_code, 401)
        self.assertEqual((await self.client.post('/v1/solve-text', headers=self.headers, json={'text': '计算二加三'})).status_code, 400)
        await self.configure()
        for value in ({'text': ' '}, {'text': 'x' * 4001}, {'text': '计算二加三', 'mode': 'invalid'}):
            self.assertEqual((await self.client.post('/v1/solve-text', headers=self.headers, json=value)).status_code, 422)

    async def test_plain_text_only_owned_model_and_shared_usage_accounting(self):
        await self.configure()
        with patch('app.completion', new=AsyncMock(return_value=self.reply())) as provider:
            response = await self.client.post('/v1/solve-text', headers=self.headers, json={'text': '计算二加三等于多少'})
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json()['questions'][0]['answer_brief'], '5')
        config, messages = provider.call_args.args
        self.assertEqual(config['model'], 'voice-user-model')
        self.assertEqual(config['api_key'], 'fixture-key')
        self.assertEqual(messages[1], {'role': 'user', 'content': '计算二加三等于多少'})
        self.assertNotIn('image_url', json.dumps(messages))
        usage = (await self.client.get('/v1/me/usage', headers=self.headers)).json()['summary']
        self.assertEqual(usage['input_tokens'], 11)
        self.assertEqual(usage['output_tokens'], 7)

    async def test_incomplete_question_and_provider_image_are_not_fabricated(self):
        await self.configure()
        with patch('app.completion', new=AsyncMock(return_value=self.reply([]))):
            response = await self.client.post('/v1/solve-text', headers=self.headers, json={'text': '求图中阴影面积'})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['questions'], [])
        self.assertTrue(response.json()['uncertainty'])
        self.item['question_full'] += ' ![invented](https://example.com/image.png)'
        with patch('app.completion', new=AsyncMock(return_value=self.reply())):
            self.assertEqual((await self.client.post('/v1/solve-text', headers=self.headers, json={'text': '计算二加三'})).status_code, 502)

    async def test_invalid_provider_response_is_sanitized(self):
        await self.configure()
        with patch('app.completion', new=AsyncMock(return_value={'choices': []})):
            response = await self.client.post('/v1/solve-text', headers=self.headers, json={'text': '计算二加三'})
        self.assertEqual(response.status_code, 502)
        self.assertNotIn('fixture-key', response.text)

    async def test_text_request_can_be_cancelled_and_settled(self):
        await self.configure()
        started = asyncio.Event()
        async def slow(*args):
            started.set()
            await asyncio.sleep(30)
        headers = {**self.headers, 'X-Solve-Id': 'a' * 32, 'X-Solve-Channel': 'c' * 32}
        with patch('app.completion', slow):
            request = asyncio.create_task(self.client.post('/v1/solve-text', headers=headers, json={'text': '计算二加三'}))
            await asyncio.wait_for(started.wait(), 3)
            self.assertEqual((await self.client.post('/v1/solve-cancel', headers=self.headers, json={'request_id': 'a' * 32})).status_code, 200)
            self.assertEqual((await asyncio.wait_for(request, 2)).status_code, 499)
        with self.db.connect() as db:
            self.assertEqual(db.execute('SELECT error_code FROM usage').fetchone()[0], 499)
