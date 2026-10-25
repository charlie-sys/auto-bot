"""Persistent accounts, per-user secrets and content-free usage records."""
from __future__ import annotations

import hashlib
import json
import os
import secrets
import sqlite3
import threading
import time
from contextlib import contextmanager
from functools import lru_cache
from pathlib import Path

from argon2 import PasswordHasher
from cryptography.fernet import Fernet

passwords = PasswordHasher()
_init_lock = threading.Lock()


class Store:
    def __init__(self, folder: str):
        self.folder = Path(folder)
        self.folder.mkdir(parents=True, exist_ok=True)
        self.folder.chmod(0o700)
        self.path = self.folder / "reader.sqlite3"
        with self.connect() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS users (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT UNIQUE NOT NULL,
                    password_hash TEXT NOT NULL, role TEXT NOT NULL DEFAULT 'user',
                    status TEXT NOT NULL DEFAULT 'pending', must_change_password INTEGER NOT NULL DEFAULT 0,
                    daily_quota INTEGER NOT NULL DEFAULT 200, created_at INTEGER NOT NULL,
                    last_login INTEGER);
                CREATE TABLE IF NOT EXISTS sessions (
                    token_hash TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id),
                    expires_at INTEGER NOT NULL, created_at INTEGER NOT NULL);
                CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id);
                CREATE TABLE IF NOT EXISTS model_settings (
                    user_id INTEGER PRIMARY KEY REFERENCES users(id), base_url TEXT NOT NULL,
                    model TEXT NOT NULL, encrypted_key TEXT NOT NULL, updated_at INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS usage (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL REFERENCES users(id),
                    created_at INTEGER NOT NULL, status TEXT NOT NULL, model TEXT NOT NULL,
                    input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0,
                    question_count INTEGER NOT NULL DEFAULT 0, duration_ms INTEGER NOT NULL DEFAULT 0,
                    error_code INTEGER NOT NULL DEFAULT 0);
                CREATE INDEX IF NOT EXISTS usage_user_time ON usage(user_id, created_at);
                CREATE INDEX IF NOT EXISTS usage_time ON usage(created_at);
                CREATE TABLE IF NOT EXISTS audit (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, actor_id INTEGER, target_id INTEGER,
                    action TEXT NOT NULL, created_at INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS network_cameras (
                    user_id INTEGER PRIMARY KEY REFERENCES users(id), key_hash TEXT UNIQUE NOT NULL,
                    encrypted_key TEXT NOT NULL, updated_at INTEGER NOT NULL);
            """)
            db.execute("INSERT OR IGNORE INTO metadata(key,value) VALUES ('instance_id',?)", (secrets.token_hex(16),))
            if 'email' not in {row[1] for row in db.execute('PRAGMA table_info(users)')}:
                db.execute('ALTER TABLE users ADD COLUMN email TEXT')
            db.executescript('''
                CREATE UNIQUE INDEX IF NOT EXISTS users_email ON users(email) WHERE email IS NOT NULL;
                CREATE TABLE IF NOT EXISTS service_settings (name TEXT PRIMARY KEY, encrypted_value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS email_codes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, email TEXT NOT NULL, purpose TEXT NOT NULL,
                    code_hash TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0, consumed INTEGER NOT NULL DEFAULT 0);
                CREATE INDEX IF NOT EXISTS email_code_lookup ON email_codes(email,purpose,id);
                CREATE TABLE IF NOT EXISTS plans (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, price_fen INTEGER NOT NULL,
                    credits INTEGER NOT NULL, duration_days INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS orders (
                    id TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id), plan_id INTEGER NOT NULL,
                    plan_name TEXT NOT NULL, price_fen INTEGER NOT NULL, credits INTEGER NOT NULL,
                    duration_days INTEGER NOT NULL, provider TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending',
                    created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, paid_at INTEGER,
                    transaction_id TEXT, payment_url TEXT, merchant_id TEXT NOT NULL, app_id TEXT NOT NULL,
                    UNIQUE(provider,transaction_id));
                CREATE INDEX IF NOT EXISTS orders_user ON orders(user_id,created_at);
                CREATE TABLE IF NOT EXISTS credit_grants (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL REFERENCES users(id),
                    order_id TEXT UNIQUE NOT NULL REFERENCES orders(id), total INTEGER NOT NULL,
                    remaining INTEGER NOT NULL, expires_at INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS credit_spend (
                    usage_id INTEGER PRIMARY KEY REFERENCES usage(id), grant_id INTEGER NOT NULL REFERENCES credit_grants(id));
            ''')
            for table, column, definition in [('plans','unit',"TEXT NOT NULL DEFAULT 'requests'"),('orders','unit',"TEXT NOT NULL DEFAULT 'requests'"),('credit_grants','unit',"TEXT NOT NULL DEFAULT 'requests'")]:
                if column not in {row[1] for row in db.execute('PRAGMA table_info('+table+')')}:
                    db.execute('ALTER TABLE '+table+' ADD COLUMN '+column+' '+definition)
            db.executescript('''
                CREATE TABLE IF NOT EXISTS billing_usage (
                    usage_id INTEGER PRIMARY KEY REFERENCES usage(id), grant_id INTEGER NOT NULL REFERENCES credit_grants(id),
                    unit TEXT NOT NULL, actual INTEGER NOT NULL DEFAULT 0, charged INTEGER NOT NULL DEFAULT 1,
                    settled INTEGER NOT NULL DEFAULT 0);
            ''')
            self.instance_id = db.execute("SELECT value FROM metadata WHERE key='instance_id'").fetchone()[0]
        keyfile = self.folder / "encryption.key"
        if not keyfile.exists():
            with self.connect() as db:
                if db.execute("SELECT 1 FROM model_settings LIMIT 1").fetchone() or db.execute('SELECT 1 FROM service_settings LIMIT 1').fetchone() or db.execute('SELECT 1 FROM network_cameras LIMIT 1').fetchone():
                    raise RuntimeError("缺少 encryption.key，请恢复数据备份中的原密钥，不能重新生成")
            with keyfile.open("xb") as stream:
                stream.write(Fernet.generate_key())
            keyfile.chmod(0o600)
        self.cipher = Fernet(keyfile.read_bytes())
        self.path.chmod(0o600)
        if os.getenv("BOOTSTRAP_ADMIN", "true").lower() == "true":
            self.bootstrap()

    @contextmanager
    def connect(self):
        connection = sqlite3.connect(self.path, timeout=15)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys=ON")
        connection.execute("PRAGMA busy_timeout=15000")
        try:
            yield connection
            connection.commit()
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def bootstrap(self):
        with self.connect() as db:
            if db.execute("SELECT 1 FROM metadata WHERE key='bootstrapped'").fetchone():
                return
            password = "A7!" + secrets.token_urlsafe(18)
            now = int(time.time())
            db.execute("INSERT INTO users(username,password_hash,role,status,must_change_password,created_at) VALUES (?,?, 'superadmin','active',1,?)",
                       ("admin", passwords.hash(password), now))
            owner = db.execute("SELECT id FROM users WHERE username='admin'").fetchone()[0]
            # One-time import into the initial administrator's private configuration.
            key = os.getenv("MODEL_API_KEY", "").strip()
            base = os.getenv("MODEL_BASE_URL", "").strip()
            model = os.getenv("MODEL_NAME", "").strip()
            if key and base and model:
                db.execute("INSERT INTO model_settings VALUES (?,?,?,?,?)", (owner, base.rstrip('/'), model, self.encrypt(key), now))
            credential = self.folder / "bootstrap-admin.json"
            with credential.open("w", encoding="utf-8") as stream:
                json.dump({"username": "admin", "password": password, "must_change_password": True}, stream)
            credential.chmod(0o600)
            db.execute("INSERT INTO metadata VALUES ('bootstrapped','1')")

    def encrypt(self, value: str) -> str:
        return self.cipher.encrypt(value.encode()).decode()

    def model(self, user_id: int, secret: bool = False):
        with self.connect() as db:
            row = db.execute("SELECT * FROM model_settings WHERE user_id=?", (user_id,)).fetchone()
        if row is None:
            return None
        result = dict(row)
        encrypted = result.pop("encrypted_key")
        result["has_key"] = bool(encrypted)
        if secret:
            result["api_key"] = self.cipher.decrypt(encrypted.encode()).decode()
        return result

    def audit(self, actor: int | None, target: int | None, action: str):
        with self.connect() as db:
            db.execute("INSERT INTO audit(actor_id,target_id,action,created_at) VALUES (?,?,?,?)", (actor, target, action, int(time.time())))

    def issue_session(self, user_id: int, role: str):
        token = secrets.token_urlsafe(36)
        lifetime = 12 * 3600 if role == "superadmin" else 7 * 86400
        now = int(time.time())
        with self.connect() as db:
            db.execute("DELETE FROM sessions WHERE expires_at < ?", (now,))
            # Bound concurrently retained sessions per account.
            db.execute("DELETE FROM sessions WHERE token_hash IN (SELECT token_hash FROM sessions WHERE user_id=? ORDER BY created_at DESC LIMIT -1 OFFSET 4)", (user_id,))
            db.execute("INSERT INTO sessions VALUES (?,?,?,?)", (digest(token), user_id, now + lifetime, now))
        return token, lifetime


def digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


@lru_cache(maxsize=8)
def _store(folder: str):
    with _init_lock:
        return Store(folder)


def store() -> Store:
    return _store(os.getenv("DATA_DIR", str(Path(__file__).parent / "data")))


def public_user(row) -> dict:
    return {key: row[key] for key in ("id", "username", "email", "role", "status", "must_change_password", "daily_quota", "created_at", "last_login")}
