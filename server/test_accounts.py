import json
import os
import tempfile
import time
import unittest
from unittest.mock import AsyncMock, patch

import httpx
from fastapi import HTTPException

import auth
from app import app
from database import Store, passwords, store
from model_provider import PublicModelTransport, normalize_base


class AccountTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.environment = patch.dict(os.environ, {"DATA_DIR": self.temp.name, "BOOTSTRAP_ADMIN": "false"})
        self.environment.start()
        auth._buckets.clear()
        self.db = store()
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")
        self.admin = self.make_user("owner", "superadmin")
        self.first = self.make_user("alice")
        self.second = self.make_user("bob")

    def make_user(self, name, role="user"):
        with self.db.connect() as db:
            identifier = db.execute("INSERT INTO users(username,password_hash,role,status,created_at) VALUES (?,?,?,'active',?)", (name, passwords.hash("Password12345"), role, int(time.time()))).lastrowid
        token, _ = self.db.issue_session(identifier, role)
        return {"id": identifier, "token": token, "headers": {"Authorization":"Bearer " + token}}

    async def asyncTearDown(self):
        await self.client.aclose()
        self.environment.stop()
        self.temp.cleanup()

    async def configure(self, user, key="private-provider-key"):
        return await self.client.put("/v1/me/model", headers=user["headers"], json={"base_url":"https://model.example/v1", "model":"vision-model", "api_key":key})

    async def test_registration_approval_and_role_boundaries(self):
        from email_auth import code_hash
        now=int(time.time())
        with self.db.connect() as db:
            db.execute('INSERT INTO email_codes(email,purpose,code_hash,created_at,expires_at) VALUES (?,?,?,?,?)',('new@example.com','register',code_hash('new@example.com','register','123456'),now,now+600))
        value = {"username":"newuser", "password":"Password12345", "email":"new@example.com", "code":"123456"}
        registered = await self.client.post("/v1/auth/register", json=value)
        self.assertEqual(registered.status_code, 201)
        self.assertEqual((await self.client.post("/v1/auth/login", json=value)).status_code, 403)
        users = (await self.client.get("/v1/admin/users?q=newuser", headers=self.admin["headers"])).json()
        identifier = users["items"][0]["id"]
        approved = await self.client.patch(f"/v1/admin/users/{identifier}", headers=self.admin["headers"], json={"status":"active"})
        self.assertEqual(approved.status_code, 200)
        login = await self.client.post("/v1/auth/login", json=value)
        self.assertEqual(login.status_code, 200)
        self.assertEqual(login.json()["user"]["role"], "user")
        self.assertEqual((await self.client.get("/v1/admin/overview", headers=self.first["headers"])).status_code, 403)
        self.assertEqual((await self.client.patch(f"/v1/admin/users/{self.admin['id']}", headers=self.first["headers"], json={"role":"superadmin"})).status_code, 403)

    async def test_model_secrets_are_encrypted_and_owned(self):
        self.assertEqual((await self.configure(self.first)).status_code, 200)
        response = await self.client.get("/v1/me/model", headers=self.first["headers"])
        self.assertTrue(response.json()["has_key"])
        self.assertNotIn("private-provider-key", response.text)
        other = (await self.client.get("/v1/me/model", headers=self.second["headers"])).json()
        self.assertFalse(other["has_key"])
        with self.db.connect() as db:
            encrypted = db.execute("SELECT encrypted_key FROM model_settings WHERE user_id=?", (self.first["id"],)).fetchone()[0]
        self.assertNotIn("private-provider-key", encrypted)
        self.assertEqual(self.db.model(self.first["id"], secret=True)["api_key"], "private-provider-key")
        self.assertEqual((await self.configure(self.first, "")).status_code, 200)
        self.assertEqual(self.db.model(self.first["id"], secret=True)["api_key"], "private-provider-key")
        self.assertNotIn("encrypted_key", response.json())

    async def test_disable_revokes_sessions_and_prevents_self_lockout(self):
        disabled = await self.client.patch(f"/v1/admin/users/{self.first['id']}", headers=self.admin["headers"], json={"status":"disabled"})
        self.assertEqual(disabled.status_code, 200)
        self.assertEqual((await self.client.get("/v1/me", headers=self.first["headers"])).status_code, 401)
        for change in ({"status":"disabled"}, {"role":"user"}):
            response = await self.client.patch(f"/v1/admin/users/{self.admin['id']}", headers=self.admin["headers"], json=change)
            self.assertEqual(response.status_code, 400)

    async def test_reset_password_requires_change_and_revokes_old_tokens(self):
        reset = await self.client.post(f"/v1/admin/users/{self.first['id']}/reset-password", headers=self.admin["headers"])
        temporary = reset.json()["temporary_password"]
        self.assertEqual((await self.client.get("/v1/me", headers=self.first["headers"])).status_code, 401)
        login = await self.client.post("/v1/auth/login", json={"username":"alice", "password":temporary})
        self.assertTrue(login.json()["user"]["must_change_password"])
        headers = {"Authorization":"Bearer " + login.json()["access_token"]}
        self.assertEqual((await self.client.get("/v1/me/model", headers=headers)).status_code, 428)
        changed = await self.client.post("/v1/auth/password", headers=headers, json={"old_password":temporary, "new_password":"ChangedPassword123"})
        self.assertEqual(changed.status_code, 200)
        self.assertFalse(changed.json()["user"]["must_change_password"])
        self.assertEqual((await self.client.get("/v1/me", headers=headers)).status_code, 401)

    async def test_cookie_csrf_and_validation_do_not_echo_secrets(self):
        login = await self.client.post("/v1/auth/login", json={"username":"alice", "password":"Password12345"})
        csrf = login.json()["csrf_token"]
        body = {"base_url":"https://model.example/v1", "model":"vision", "api_key":"secret-not-to-echo"}
        self.assertEqual((await self.client.put("/v1/me/model", json=body)).status_code, 403)
        self.assertEqual((await self.client.put("/v1/me/model", json=body, headers={"X-CSRF-Token":csrf})).status_code, 200)
        invalid = await self.client.put("/v1/me/model", headers=self.first["headers"], json={**body,"api_key":{"secret-not-to-echo":1}})
        self.assertEqual(invalid.status_code, 422)
        self.assertNotIn("secret-not-to-echo", invalid.text)

    async def test_solving_uses_callers_model_and_logs_quota_and_tokens(self):
        await self.configure(self.first, "alice-key")
        item = {"question_brief":"求和", "question_full":"2+3=?", "answer_brief":"5", "solution":"2+3=5", "uncertainty":""}
        output = {"choices":[{"message":{"content":json.dumps({"questions":[item],"uncertainty":""})}}],"usage":{"prompt_tokens":11,"completion_tokens":22}}
        with self.db.connect() as db:
            db.execute("UPDATE users SET daily_quota=2 WHERE id=?", (self.first["id"],))
        files = {"image":("image.jpg", b"\xff\xd8\xff" + b"0"*200,"image/jpeg")}
        with patch("app.completion", new=AsyncMock(return_value=output)) as provider:
            for _ in range(2):
                result = await self.client.post("/v1/solve-batch", headers=self.first["headers"], files=files)
                self.assertEqual(result.status_code,200,result.text)
            self.assertEqual(provider.call_args.args[0]["api_key"],"alice-key")
            self.assertEqual((await self.client.post("/v1/solve-batch",headers=self.first["headers"],files=files)).status_code,429)
        usage = (await self.client.get("/v1/me/usage",headers=self.first["headers"])).json()
        self.assertEqual(usage["summary"]["questions"],2)
        self.assertEqual(usage["summary"]["input_tokens"],22)
        self.assertEqual(usage["summary"]["output_tokens"],44)
        self.assertEqual((await self.client.get("/v1/me/usage",headers=self.second["headers"])).json()["summary"]["requests"],0)
        records = (await self.client.get("/v1/admin/usage",headers=self.admin["headers"])).json()
        self.assertEqual(records["total"],2)
        self.assertNotIn("2+3", json.dumps(records))

    async def test_failed_provider_call_is_recorded_without_response_or_key(self):
        await self.configure(self.first)
        with patch("app.completion", new=AsyncMock(side_effect=HTTPException(502,"模型接口无效"))):
            response = await self.client.post("/v1/solve-batch",headers=self.first["headers"],files={"image":("image.jpg",b"\xff\xd8\xff"+b"0"*200,"image/jpeg")})
        self.assertEqual(response.status_code,502)
        usage = (await self.client.get("/v1/admin/usage",headers=self.admin["headers"])).json()
        self.assertEqual(usage["items"][0]["status"],"failed")
        self.assertEqual(usage["items"][0]["error_code"],502)
        self.assertNotIn("private-provider-key",json.dumps(usage))

    async def test_private_destinations_are_rejected_and_public_dns_is_pinned(self):
        for url in ("http://model.example/v1","https://127.0.0.1/v1","https://169.254.169.254/v1","https://[::1]/v1","https://224.0.0.1/v1","https://user:pass@model.example/v1"):
            with self.assertRaises(HTTPException): normalize_base(url)
        self.assertEqual(normalize_base("https://model.example/v1/chat/completions"),"https://model.example/v1")
        transport = PublicModelTransport()
        loop = type("Loop",(),{"getaddrinfo":AsyncMock(return_value=[(2,1,6,"",("127.0.0.1",443))])})()
        with patch("model_provider.asyncio.get_running_loop",return_value=loop):
            with self.assertRaises(HTTPException):
                await transport.handle_async_request(httpx.Request("POST","https://model.example/v1/chat/completions"))
        loop.getaddrinfo.return_value=[(2,1,6,"",("8.8.8.8",443))]
        with patch("model_provider.asyncio.get_running_loop",return_value=loop), patch.object(httpx.AsyncHTTPTransport,"handle_async_request",new=AsyncMock(return_value=httpx.Response(200))) as send:
            await transport.handle_async_request(httpx.Request("POST","https://model.example/v1/chat/completions"))
            pinned = send.call_args.args[0]
            self.assertEqual(pinned.url.host,"8.8.8.8")
            self.assertEqual(pinned.headers["host"],"model.example")
            self.assertEqual(pinned.extensions["sni_hostname"],"model.example")
        await transport.aclose()

    async def test_bootstrap_import_is_one_time_and_admin_only(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ,{"BOOTSTRAP_ADMIN":"true","MODEL_API_KEY":"legacy-key","MODEL_NAME":"qwen-vision","MODEL_BASE_URL":"https://model.example/v1"}):
            database = Store(folder)
            initial = json.loads((database.folder/"bootstrap-admin.json").read_text())
            self.assertEqual(initial["username"],"admin")
            self.assertGreaterEqual(len(initial["password"]),20)
            self.assertEqual(database.model(1,secret=True)["api_key"],"legacy-key")
            with database.connect() as db:
                self.assertEqual(db.execute("SELECT count(*) FROM users").fetchone()[0],1)
                self.assertNotIn("legacy-key",db.execute("SELECT encrypted_key FROM model_settings").fetchone()[0])
            database = Store(folder)
            with database.connect() as db:
                self.assertEqual(db.execute("SELECT count(*) FROM users").fetchone()[0],1)
