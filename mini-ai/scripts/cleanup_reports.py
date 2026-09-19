#!/usr/bin/env python3
"""Delete only generated AI reports and feedback older than the retention window."""
import argparse, json, pathlib, time

def main():
    p = argparse.ArgumentParser(); p.add_argument("--root", required=True); p.add_argument("--days", type=int, default=30)
    p.add_argument("--apply", action="store_true", help="实际删除；默认只预览")
    a = p.parse_args()
    if a.days < 1:
        p.error("--days 必须至少为 1")
    root = pathlib.Path(a.root).resolve(); cutoff = int((time.time() - a.days * 86400) * 1000)
    dirs = [root / "综合报告"]
    for directory in dirs:
        if not directory.exists(): continue
        for meta_path in directory.glob("*.json"):
            try: meta = json.loads(meta_path.read_text(encoding="utf-8"))
            except Exception: continue
            generated = meta.get("generatedAt")
            if meta.get("status") == "complete" and isinstance(generated, int) and generated < cutoff:
                print(("删除：" if a.apply else "将删除：") + str(meta_path))
                if a.apply:
                    meta_path.with_suffix(".md").unlink(missing_ok=True); meta_path.unlink(missing_ok=True)
    feedback = root / "综合报告/反馈"
    if feedback.exists():
        for path in feedback.glob("*.json"):
            try:
                responded = json.loads(path.read_text(encoding="utf-8")).get("respondedAt")
                expired = isinstance(responded, int) and 0 < responded < cutoff
            except Exception: expired = False
            if expired:
                print(("删除：" if a.apply else "将删除：") + str(path))
                if a.apply: path.unlink(missing_ok=True)

if __name__ == "__main__": main()
