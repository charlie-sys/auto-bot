"""Encrypted operator settings. Secrets are never returned by settings APIs."""
import json
from database import store


def config(name):
    with store().connect() as db:
        row = db.execute('SELECT encrypted_value FROM service_settings WHERE name=?', (name,)).fetchone()
    return json.loads(store().cipher.decrypt(row[0].encode())) if row else {}


def save_config(name, value):
    encrypted = store().encrypt(json.dumps(value, ensure_ascii=False))
    with store().connect() as db:
        db.execute('INSERT INTO service_settings VALUES (?,?) ON CONFLICT(name) DO UPDATE SET encrypted_value=excluded.encrypted_value', (name, encrypted))
