"""Bounded public proxy. Account authorization is always checked by the API."""
import os
import re

import httpx
from httpx import AsyncClient
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import Response, StreamingResponse

app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
MAX_BODY = 9 * 1024 * 1024
ALLOWED = re.compile(r"/(?:health|admin/?|admin/assets/(?:app\.js|app\.css)|v1/(?:network-camera/(?:config|frame|rotate)|auth/(?:register|login|logout|password|options|email-code|email-login|email-reset)|me(?:/(?:model(?:/test)?|usage|email|subscription|orders(?:/[A-Za-z0-9]+/(?:qr|refresh))?))?|plans|payments/(?:alipay|wechat)/notify|admin/(?:overview|users(?:/\d+(?:/reset-password)?)?|usage|audit|plans(?:/\d+)?|orders|smtp(?:/test)?|payment)|solve(?:-batch|-stream|-detail|-cancel)?))$")


@app.api_route("/{path:path}", methods=["GET", "POST", "PUT", "PATCH", "DELETE"])
async def forward(request: Request, path: str):
    if request.url.path != "/" and not ALLOWED.fullmatch(request.url.path):
        raise HTTPException(404, "页面不存在")
    try:
        if int(request.headers.get("content-length", "0")) > MAX_BODY:
            raise HTTPException(413, "上传内容过大")
    except ValueError:
        raise HTTPException(400, "无效的请求长度")
    data = bytearray()
    async for chunk in request.stream():
        if len(data) + len(chunk) > MAX_BODY:
            raise HTTPException(413, "上传内容过大")
        data.extend(chunk)
    # No shared-token translation: a public request keeps its own account identity.
    headers = {key: request.headers[key] for key in ("authorization", "cookie", "content-type", "x-csrf-token", "host", "x-solve-id", "x-solve-channel", "wechatpay-timestamp", "wechatpay-nonce", "wechatpay-serial", "wechatpay-signature") if key in request.headers}
    headers["X-Forwarded-Proto"] = "https" if request.headers.get("x-forwarded-proto") == "https" else "http"
    origin = os.getenv("API_ORIGIN", "http://api:8080").rstrip("/")
    target = origin + request.url.path + ("?" + request.url.query if request.url.query else "")
    try:
        if request.url.path=='/v1/solve-stream':
            client=AsyncClient(timeout=httpx.Timeout(165,connect=10),follow_redirects=False,trust_env=False)
            try:
                upstream=await client.send(client.build_request(request.method,target,headers=headers,content=bytes(data)),stream=True)
            except BaseException:
                await client.aclose();raise
            async def chunks():
                try:
                    async for chunk in upstream.aiter_bytes():yield chunk
                finally:
                    await upstream.aclose();await client.aclose()
            output_headers={k:v for k,v in upstream.headers.items() if k.lower() in ('content-type','cache-control','content-security-policy','x-content-type-options','referrer-policy')}
            output_headers['X-Accel-Buffering']='no'
            return StreamingResponse(chunks(),status_code=upstream.status_code,headers=output_headers)
        async with AsyncClient(timeout=httpx.Timeout(165, connect=10), follow_redirects=False, trust_env=False) as client:
            response = await client.request(request.method, target, headers=headers, content=bytes(data))
        output_headers = {key: value for key, value in response.headers.items() if key.lower() in (
            "content-type", "set-cookie", "cache-control", "content-security-policy", "x-content-type-options", "referrer-policy", "x-camera-frame", "x-camera-age-ms")}
        return Response(response.content, status_code=response.status_code, headers=output_headers)
    except httpx.TimeoutException:
        raise HTTPException(504, "本机识题服务响应超时")
    except httpx.RequestError:
        raise HTTPException(502, "无法连接本机识题服务")
