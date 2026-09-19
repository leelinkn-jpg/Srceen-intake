#!/usr/bin/env python3
"""Replay today's persisted WHOOP/deadline trigger without choosing a model.

Run every five minutes and at login. The pipeline owns locking/idempotency.
Never replay historical reports automatically or access business source files here.
"""
import subprocess
from datetime import datetime
from pathlib import Path


def main():
    user_root = Path.home()
    root = user_root / "Desktop" / "秒记中枢"
    day = datetime.now().astimezone().date().isoformat()
    events = [root / "系统" / "WHOOP就绪" / (day + ".json"),
              root / "系统" / "日报触发" / (day + ".json")]
    ready = user_root / "whoop-sync" / "state" / (day + ".ready")
    if not ready.exists() and not any(p.exists() for p in events):
        return
    subprocess.run(["/usr/bin/python3", str(user_root / "mini-ai" / "bin" / "run_daily_pipeline"),
                    "--date", day, "--trigger", "reconcile", "--root", str(root)],
                   check=True, timeout=1900)


if __name__ == "__main__":
    main()
