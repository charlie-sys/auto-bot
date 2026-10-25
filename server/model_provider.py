"""OpenAI-compatible BYOK transport. DNS is pinned to validated public IPs."""
from __future__ import annotations

import asyncio
import ipaddress
import socket
from urllib.parse import urlsplit, urlunsplit

import httpx
from fastapi import HTTPException


def normalize_base(value: str) -> str:
    value = value.strip().rstrip('/')
    if any(ord(char) < 33 for char in value) or len(value) > 500:
        raise HTTPException(400, "模型 URL 格式无效")
    try:
        url = urlsplit(value)
        port = url.port
        if url.scheme != "https" or not url.hostname or url.username or url.password or url.query or url.fragment or (port is not None and port == 0):
            raise ValueError()
        host = url.hostname.lower()
        if host == "localhost" or host.endswith((".localhost", ".local", ".internal")):
            raise ValueError()
        try:
            address = ipaddress.ip_address(host)
        except ValueError:
            address = None
        if address is not None and not public_address(address):
            raise ValueError()
        path = url.path.rstrip('/')
        if path.endswith("/chat/completions"):
            path = path[:-len("/chat/completions")]
        return urlunsplit(("https", url.netloc, path, "", ""))
    except ValueError:
        raise HTTPException(400, "模型地址须为公共 HTTPS 接口，不支持本机、内网地址或 URL 中的账号密码")


def public_address(address) -> bool:
    mapped = getattr(address, "ipv4_mapped", None)
    if mapped is not None:
        return public_address(mapped)
    return address.is_global and not (address.is_multicast or address.is_reserved or address.is_unspecified)


class PublicModelTransport(httpx.AsyncHTTPTransport):
    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        host = request.url.raw_host.decode("ascii")
        try:
            records = await asyncio.wait_for(asyncio.get_running_loop().getaddrinfo(host, request.url.port or 443, type=socket.SOCK_STREAM), timeout=10)
        except (OSError, asyncio.TimeoutError):
            raise httpx.ConnectError("模型域名解析失败", request=request)
        addresses = list(dict.fromkeys(item[4][0] for item in records))
        if not addresses or any(not public_address(ipaddress.ip_address(address)) for address in addresses):
            raise HTTPException(400, "模型域名解析到内网或保留地址，已拒绝访问")
        addresses.sort(key=lambda address: ":" in address)
        # Preserve the original Host and TLS SNI while connecting to the checked IP.
        headers = request.headers.copy()
        headers["Host"] = request.url.netloc.decode("ascii")
        last_error = None
        for address in addresses[:4]:
            pinned = httpx.Request(request.method, request.url.copy_with(host=address), headers=headers,
                                   stream=request.stream, extensions={**request.extensions, "sni_hostname": host})
            try:
                return await super().handle_async_request(pinned)
            except httpx.ConnectError as exc:
                last_error = exc
        raise last_error or httpx.ConnectError("无法连接模型接口", request=request)


async def completion(config: dict, messages: list, *, structured: bool = True) -> dict:
    payload = {"model": config["model"], "messages": messages}
    if structured:
        payload["response_format"] = {"type": "json_object"}
    # Some compatible services reject provider-specific fields. Enable this only for Qwen.
    if config["model"].lower().startswith("qwen"):
        payload["enable_thinking"] = False
    endpoint = normalize_base(config["base_url"]) + "/chat/completions"
    async with httpx.AsyncClient(transport=PublicModelTransport(), trust_env=False, follow_redirects=False,
                                timeout=httpx.Timeout(130, connect=10)) as client:
        response = await client.post(endpoint, headers={"Authorization": "Bearer " + config["api_key"]}, json=payload)
    if response.status_code == 429:
        raise HTTPException(429, "模型服务限流或额度不足，请检查个人模型配置")
    if response.status_code in (401, 403):
        raise HTTPException(502, "模型 API Key 无效或无权调用该模型，请检查个人配置")
    if response.status_code >= 300:
        # Never reflect provider bodies: they can contain secrets or submitted pictures.
        raise HTTPException(502, f"模型接口返回 HTTP {response.status_code}，请核对 URL 和模型名")
    return response.json()


async def completion_stream(config: dict, messages: list):
    """Yield content deltas and final usage without buffering the upstream SSE response."""
    payload = {'model':config['model'], 'messages':messages, 'stream':True,
               'stream_options':{'include_usage':True}, 'response_format':{'type':'json_object'}}
    if config['model'].lower().startswith('qwen'):
        payload['enable_thinking'] = False
    async with httpx.AsyncClient(transport=PublicModelTransport(),trust_env=False,follow_redirects=False,
                                timeout=httpx.Timeout(130,connect=10)) as client:
        async with client.stream('POST',normalize_base(config['base_url'])+'/chat/completions',
                                 headers={'Authorization':'Bearer '+config['api_key']},json=payload) as response:
            if response.status_code >= 300:
                code = 429 if response.status_code == 429 else 502
                raise HTTPException(code,'模型流式接口不可用，请关闭“逐题返回”重试，并检查模型服务是否支持流式 JSON')
            import json
            data_lines=[]
            size=0
            async for line in response.aiter_lines():
                size += len(line)
                if size > 2*1024*1024:
                    raise ValueError('stream too large')
                if line.startswith('data:'):
                    data_lines.append(line[5:].lstrip())
                elif not line and data_lines:
                    raw='\n'.join(data_lines);data_lines=[]
                    if raw=='[DONE]':return
                    value=json.loads(raw)
                    if value.get('error'):raise ValueError('provider stream error')
                    usage=value.get('usage')
                    if usage:yield {'usage':usage}
                    for choice in value.get('choices') or []:
                        delta=choice.get('delta') or {}
                        content=delta.get('content')
                        if isinstance(content,str) and content:yield {'text':content}
            if data_lines:
                raw='\n'.join(data_lines)
                if raw!='[DONE]':
                    value=json.loads(raw)
                    if value.get('usage'):yield {'usage':value['usage']}
                    for choice in value.get('choices') or []:
                        content=(choice.get('delta') or {}).get('content')
                        if isinstance(content,str) and content:yield {'text':content}
