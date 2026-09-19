#!/usr/bin/env python3
"""Read-only validation for the model-neutral 秒记中枢 installation."""
import json
from pathlib import Path

ROOT = Path.home() / "Desktop" / "秒记中枢"
DOMAINS = ("财务", "健康", "工作", "成长")


def main() -> None:
    errors = []
    required = [
        ROOT / "README.md",
        ROOT / "系统协同/数据映射.json",
        ROOT / "总管规则/RULES.md",
        ROOT / "总管规则/INPUTS.md",
        ROOT / "总管规则/TASKS.md",
        ROOT / "总管规则/RUNBOOK.md",
        ROOT / "总管规则/协议/report.schema.json",
        ROOT / "总管规则/协议/domain-report.schema.json",
        ROOT / "总管规则/协议/trigger-policy.json",
        ROOT / "综合报告",
    ]
    for domain in DOMAINS:
        required.extend([
            ROOT / f"{domain}/AI规则/RULES.md",
            ROOT / f"{domain}/AI规则/INPUTS.md",
            ROOT / f"{domain}/AI规则/TASKS.md",
            ROOT / f"{domain}/AI规则/协议/domain-report.schema.json",
            ROOT / f"{domain}/AI报告",
            ROOT / f"{domain}/状态",
            ROOT / f"{domain}/日志",
        ])
    for path in required:
        if not path.exists():
            errors.append(f"缺少：{path.relative_to(ROOT)}")
    protocol_roots = [ROOT / f"{domain}/AI规则/协议" for domain in DOMAINS]
    protocol_roots.append(ROOT / "总管规则/协议")
    for path in (item for protocol_root in protocol_roots for item in protocol_root.glob("*.json")):
        try:
            json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:
            errors.append(f"JSON无效：{path.name}: {exc}")
    mapped_roots = [
        ROOT / "财务",
        ROOT / "健康",
        ROOT / "工作",
        ROOT / "成长",
        ROOT / "综合报告",
    ]
    broken = []
    for mapped_root in mapped_roots:
        candidates = [mapped_root] if mapped_root.is_symlink() else list(mapped_root.iterdir())
        broken.extend(p for p in candidates if p.is_symlink() and not p.exists())
    errors.extend(f"映射失效：{p.relative_to(ROOT)}" for p in broken)
    if errors:
        print("校验失败")
        print("\n".join(errors))
        raise SystemExit(1)
    print("校验通过：四个领域项目、总管规则、协议、目录和数据映射均完整。")


if __name__ == "__main__":
    main()
