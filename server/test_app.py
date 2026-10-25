import asyncio
import os
import unittest
from unittest.mock import patch

import httpx

from app import app, parse_batch_text, parse_model_text


class ApiTests(unittest.TestCase):
    def test_markdown_and_source_image_survive_json_validation(self):
        import json
        item = {"question_brief": "三角形面积", "question_full": "**已知**底边为 4，高为 3。\n\n![题目原图](question-source)", "answer_brief": "**6**", "solution": "1. 面积 = 底 × 高 ÷ 2\n2. 得 **6**", "uncertainty": ""}
        result = parse_batch_text(json.dumps({"questions": [item], "uncertainty": ""}, ensure_ascii=False))
        self.assertEqual(result["questions"][0], item)

    def test_model_result_validation(self):
        valid = '{"readable":false,"question_brief":"x","question_full":"x","answer_brief":"x","solution":"x","uncertainty":"模糊"}'
        result = parse_model_text(valid)
        self.assertFalse(result["readable"])
        self.assertEqual(result["answer_brief"], "")
        with self.assertRaises(ValueError):
            parse_model_text('{"readable":true,"question_brief":"x"}')

    def test_auth_and_unconfigured_model(self):
        async def run():
            transport = httpx.ASGITransport(app=app)
            async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
                jpeg = b"\xff\xd8\xff" + b"0" * 200
                files = {"image": ("question.jpg", jpeg, "image/jpeg")}
                with patch.dict(os.environ, {"APP_TOKEN": "secret", "MODEL_NAME": "", "MODEL_API_KEY": ""}):
                    unauthorized = await client.post("/v1/solve", files=files)
                    self.assertEqual(unauthorized.status_code, 401)
                    unconfigured = await client.post("/v1/solve", headers={"Authorization": "Bearer secret"}, files=files)
                    self.assertEqual(unconfigured.status_code, 401)
                    batch_unconfigured = await client.post("/v1/solve-batch", headers={"Authorization": "Bearer secret"}, files=files)
                    self.assertEqual(batch_unconfigured.status_code, 401)
        asyncio.run(run())

    def test_batch_validation_preserves_all_complete_questions(self):
        item = {"question_brief": "求和", "question_full": "1. 2+3=?", "answer_brief": "5", "solution": "2 加 3 得 5", "uncertainty": ""}
        import json
        result = parse_batch_text(json.dumps({"questions": [item, {**item, "question_full": "2. 7-4=?", "answer_brief": "3"}], "uncertainty": "第三题被裁切"}, ensure_ascii=False))
        self.assertEqual(len(result["questions"]), 2)
        self.assertEqual(result["questions"][1]["answer_brief"], "3")
        with self.assertRaises(ValueError):
            parse_batch_text('{"questions":[{"question_full":"1. 2+3=?"}],"uncertainty":""}')
        empty = parse_batch_text('{"questions":[],"uncertainty":""}')
        self.assertTrue(empty["uncertainty"])
        copied = {**item, "question_full": "6. 哪项正确？\nA. ①②\nB. ②③\nC. ②③\nD. ④⑤", "answer_brief": "B"}
        suspicious = parse_batch_text(json.dumps({"questions": [item, copied], "uncertainty": ""}, ensure_ascii=False))
        self.assertEqual(len(suspicious["questions"]), 1)
        self.assertIn("选项转写重复", suspicious["uncertainty"])


if __name__ == "__main__":
    unittest.main()
