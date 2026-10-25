from __future__ import annotations

import re
import secrets
import time
import threading
from collections import defaultdict, deque

from argon2.exceptions import VerificationError, InvalidHashError
from fastapi import HTTPException, Request

from database import digest, passwords, public_user, store

COOKIE = "reader_session"
_buckets = defaultdict(deque)
_lock = threading.Lock()
# Equal work for unknown users so the login error doesn't reveal account existence.
_dummy_hash = passwords.hash(secrets.token_urlsafe(24))


def limit(key: str, maximum: int, window: int = 60):
    now = time.monotonic()
    with _lock:
        if len(_buckets) > 10000:
            for entry in list(_buckets):
                if not _buckets[entry] or _buckets[entry][-1] < now - 3600:
                    del _buckets[entry]
            if len(_buckets) > 10000 and key not in _buckets:
                raise HTTPException(429, "请求过多，请稍后重试")
        bucket = _buckets[key]
        while bucket and bucket[0] <= now - window:
            bucket.popleft()
        if len(bucket) >= maximum:
            raise HTTPException(429, "操作过于频繁，请稍后重试")
        bucket.append(now)


def username(value: str) -> str:
    value = value.strip().lower()
    if not re.fullmatch(r"[a-z0-9][a-z0-9_.-]{2,31}", value):
        raise HTTPException(400, "用户名须为 3—32 位字母、数字、下划线、点或短横线，以字母或数字开头")
    return value


def validate_password(value: str):
    if not 10 <= len(value) <= 128 or not re.search(r"[A-Za-z]", value) or not re.search(r"[0-9]", value):
        raise HTTPException(400, "密码须为 10—128 位，并包含字母和数字")


def verify_password(encoded: str | None, value: str) -> bool:
    try:
        return passwords.verify(encoded or _dummy_hash, value) and encoded is not None
    except (VerificationError, InvalidHashError):
        return False


def csrf(token: str) -> str:
    return digest("csrf:" + token)


def session_user(request: Request, allow_password_change: bool = False):
    authorization = request.headers.get("authorization", "")
    bearer = authorization.startswith("Bearer ")
    token = authorization[7:] if bearer else request.cookies.get(COOKIE, "")
    if not token or len(token) > 256:
        raise HTTPException(401, "请登录或重新登录")
    with store().connect() as db:
        user = db.execute("SELECT u.* FROM users u JOIN sessions s ON s.user_id=u.id WHERE s.token_hash=? AND s.expires_at>?", (digest(token), int(time.time()))).fetchone()
    if user is None or user["status"] != "active":
        raise HTTPException(401, "登录已失效，或账号已被停用，请重新登录")
    if not bearer and request.method not in ("GET", "HEAD", "OPTIONS"):
        if not secrets.compare_digest(request.headers.get("x-csrf-token", ""), csrf(token)):
            raise HTTPException(403, "页面验证已失效，请刷新后重试")
    if user["must_change_password"] and not allow_password_change:
        raise HTTPException(428, "请先修改初始密码")
    request.state.session_token = token
    request.state.user_id = user["id"]
    return public_user(user)


def current_user(request: Request):
    return session_user(request)


def admin_user(request: Request):
    user = current_user(request)
    if user["role"] != "superadmin":
        raise HTTPException(403, "需要超级管理员权限")
    return user
