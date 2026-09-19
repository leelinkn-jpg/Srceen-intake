#!/usr/bin/env python3
"""Validate a draft pair and atomically publish it into the Syncthing tree."""
import argparse, hashlib, json, os, pathlib, tempfile, time
import fcntl

DOMAINS = {"overall": "综合报告"}
PERIODS = {"daily": "日报", "weekly": "周报", "monthly": "月报"}
REQUIRED = {"reportId", "domain", "periodType", "periodStart", "periodEnd", "generatedAt", "dataCutoff", "title", "summary", "severity", "sources", "missingData", "domainSections", "suggestedActions"}

def digest_sources(items):
    stable = json.dumps(items, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(stable.encode()).hexdigest()

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--root", required=True); p.add_argument("--metadata", required=True); p.add_argument("--markdown", required=True)
    a = p.parse_args(); root = pathlib.Path(a.root).resolve()
    meta = json.loads(pathlib.Path(a.metadata).read_text(encoding="utf-8")); body = pathlib.Path(a.markdown).read_text(encoding="utf-8").strip()
    missing = REQUIRED - meta.keys()
    if missing: raise SystemExit("missing fields: " + ", ".join(sorted(missing)))
    if meta["domain"] not in DOMAINS or meta["periodType"] not in PERIODS: raise SystemExit("invalid domain or periodType")
    expected = f'{meta["domain"]}-{meta["periodType"]}-{meta["periodEnd"][:10]}'
    if meta["reportId"] != expected: raise SystemExit(f"reportId must be {expected}")
    if meta["severity"] not in {"normal", "attention", "urgent"}: raise SystemExit("invalid severity")
    if not isinstance(meta["generatedAt"], int) or meta["generatedAt"] < 1_000_000_000_000:
        raise SystemExit("generatedAt must be epoch milliseconds")
    if not body: raise SystemExit("empty markdown")
    target = root.joinpath(*DOMAINS[meta["domain"]].split("/")); target.mkdir(parents=True, exist_ok=True)
    # 文件名给人看；身份只由确定性的 reportId 决定，改标题不会产生第二份报告。
    filename = f'{meta["periodEnd"][:10]}_{PERIODS[meta["periodType"]]}'
    json_path, md_path = target / f"{filename}.json", target / f"{filename}.md"
    source_digest = digest_sources(meta["sources"])
    lock = target / ".publish.lock"
    with lock.open("w") as lock_file:
        fcntl.flock(lock_file, fcntl.LOCK_EX)
        revision = 1
        if json_path.exists():
            old = json.loads(json_path.read_text(encoding="utf-8"))
            if old.get("reportId") not in (None, meta["reportId"]):
                raise SystemExit("target report identity mismatch")
            if old.get("sourceDigest") == source_digest and old.get("markdownSha256") == hashlib.sha256((body + "\n").encode()).hexdigest() and md_path.exists(): print(md_path); return
            revision = int(old.get("revision", 1)) + 1
        markdown = body + "\n"
        meta.update({"sourceDigest": source_digest, "revision": revision, "status": "complete", "schemaVersion": 1,
                     "markdownSha256": hashlib.sha256(markdown.encode()).hexdigest(), "publishedAt": int(time.time() * 1000)})
        # 手机端会按 markdownSha256 + revision 配对；两份文件乱序到达时继续显示旧的完整版本。
        for path, content in ((md_path, markdown), (json_path, json.dumps(meta, ensure_ascii=False, indent=2) + "\n")):
            fd, temp = tempfile.mkstemp(prefix=".publishing-", dir=target)
            with os.fdopen(fd, "w", encoding="utf-8") as out: out.write(content); out.flush(); os.fsync(out.fileno())
            os.replace(temp, path)
    print(md_path)

if __name__ == "__main__": main()
