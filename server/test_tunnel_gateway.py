import asyncio
import os
import unittest
from unittest.mock import patch

import httpx

from tunnel_gateway import app, MAX_BODY


class TunnelGatewayTests(unittest.TestCase):
    def test_voice_text_endpoint_is_forwarded_without_audio(self):
        async def run():
            def backend(request):
                self.assertEqual(request.url.path, '/v1/solve-text')
                self.assertEqual(request.headers['authorization'], 'Bearer account-session')
                self.assertEqual(request.content, b'{"text":"2+3=?"}')
                return httpx.Response(200, json={'questions': [], 'uncertainty': 'fixture'})
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as client:
                with patch('tunnel_gateway.AsyncClient', return_value=httpx.AsyncClient(transport=httpx.MockTransport(backend))):
                    result = await client.post('/v1/solve-text', headers={'Authorization': 'Bearer account-session', 'Content-Type': 'application/json'}, content=b'{"text":"2+3=?"}')
                self.assertEqual(result.status_code, 200)
        asyncio.run(run())

    def test_account_identity_cookies_queries_and_body_limits(self):
        async def run():
            seen = []

            def backend(request):
                seen.append(request)
                self.assertEqual(request.headers["Authorization"], "Bearer account-session")
                self.assertEqual(request.headers["Cookie"], "reader_session=web-session")
                self.assertEqual(request.url.path, "/v1/solve-batch")
                self.assertEqual(request.content, b"image-body")
                return httpx.Response(200, json={"questions": [], "uncertainty": "test"}, headers={"Set-Cookie":"reader_session=next; HttpOnly", "Cache-Control":"no-store"})

            transport = httpx.ASGITransport(app=app)
            with patch.dict(os.environ, {"APP_TOKEN": "internal-secret", "TUNNEL_TOKEN": "external-secret"}):
                async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
                    with patch("tunnel_gateway.AsyncClient", return_value=httpx.AsyncClient(transport=httpx.MockTransport(backend))):
                        response = await client.post("/v1/solve-batch", headers={"Authorization": "Bearer account-session", "Cookie":"reader_session=web-session", "Content-Type": "multipart/form-data; boundary=test"}, content=b"image-body")
                    self.assertEqual(response.status_code, 200)
                    self.assertIn("HttpOnly", response.headers["set-cookie"])
                    self.assertEqual(len(seen), 1)
                    hidden = await client.get("/openapi.json")
                    self.assertEqual(hidden.status_code, 404)
                    too_big = await client.post("/v1/solve-batch", headers={"Authorization": "Bearer external-secret", "Content-Length": str(MAX_BODY + 1)})
                    self.assertEqual(too_big.status_code, 413)

        asyncio.run(run())


if __name__ == "__main__":
    unittest.main()
