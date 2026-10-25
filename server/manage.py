"""Local operator commands; never reachable as HTTP endpoints."""
import argparse
import json
import secrets
import sqlite3
import time
import zipfile
from pathlib import Path

from database import passwords, store


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["bootstrap-info", "status", "reset-admin", "backup"])
    args = parser.parse_args()
    database = store()
    credential = database.folder / "bootstrap-admin.json"
    if args.command == "bootstrap-info":
        print(credential.read_text() if credential.exists() else json.dumps({"initialized":True,"message":"initial password already changed"}))
    elif args.command == "status":
        with database.connect() as db:
            count = db.execute("SELECT count(*) FROM users").fetchone()[0]
            model = db.execute("SELECT 1 FROM model_settings m JOIN users u ON u.id=m.user_id WHERE u.username='admin'").fetchone()
        print(json.dumps({"accounts_ready":True,"users":count,"initial_model_configured":bool(model)}))
    elif args.command == "reset-admin":
        password = "A7!" + secrets.token_urlsafe(18)
        with database.connect() as db:
            user = db.execute("SELECT id FROM users WHERE username='admin'").fetchone()
            if user is None:
                raise RuntimeError("initial administrator not found")
            db.execute("UPDATE users SET password_hash=?,role='superadmin',status='active',must_change_password=1 WHERE id=?",(passwords.hash(password),user[0]))
            db.execute("DELETE FROM sessions WHERE user_id=?",(user[0],))
        credential.write_text(json.dumps({"username":"admin","password":password,"must_change_password":True}))
        credential.chmod(0o600)
        database.audit(None,user[0],"local_admin_recovery")
        print(json.dumps({"ok":True}))
    elif args.command == "backup":
        import tempfile
        destination = database.folder / "backups"
        destination.mkdir(exist_ok=True)
        filename = destination / ("reader-" + time.strftime("%Y%m%d-%H%M%S") + ".zip")
        with tempfile.TemporaryDirectory() as temporary:
            snapshot = Path(temporary) / "reader.sqlite3"
            with database.connect() as source:
                target = sqlite3.connect(snapshot)
                try:
                    source.backup(target)
                finally:
                    target.close()
            with zipfile.ZipFile(filename,"w",compression=zipfile.ZIP_DEFLATED) as output:
                output.write(snapshot,"reader.sqlite3")
                output.write(database.folder/"encryption.key","encryption.key")
        filename.chmod(0o600)
        print(json.dumps({"path":str(filename)}))


if __name__ == "__main__":
    main()
