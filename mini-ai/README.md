# 秒记 Mini AI 闭环

> 首次接手或部署本系统时，请先完整阅读 [系统全景说明.md](系统全景说明.md)。该文件说明手机 App、同步目录、Mini 分析任务、报告回传与用户反馈之间的完整关系。

本目录是安装到 Mac mini 的五项目任务包。原始数据仍保存在 Syncthing 的“秒记”目录；项目目录只保存规则、提示词、运行状态和不含完整敏感正文的日志。

## 项目

- `projects/finance`：财务周报、月报。
- `projects/work`：工作日日报、周日下午（周材料齐全后）的周报、月报。
- `projects/health`：健康日报、周报、月报。
- `projects/growth`：成长日报、周报、月报。
- `projects/steward`：只汇总同期领域报告，生成综合报告。

Claude Cowork 或 Codex 运行这些任务时会把任务所需内容提交给对应云端模型。不得把原始数据复制到项目目录或日志。

## 发布流程

1. 读取项目 `RULES.md` 和本周期允许的数据。
2. 在系统临时目录生成 Markdown 与 JSON 草稿。
3. 四个领域项目把中间分析保存在各自项目的 `state/`，不回传手机；总管调用 `scripts/publish_report.py` 校验并原子发布唯一总报告。
4. 同一 `reportId` 且来源摘要不变时不重复发布；来源变化时覆盖同名报告并增加 `revision`。
5. 每天运行 `scripts/cleanup_reports.py`，只清理超过一个月的 AI 报告和反馈；绝不清理原始数据或已确认待办。

手机只读取 `综合报告/` 中 JSON 为 `status=complete`、同名 Markdown 已存在且包含 `domainSections` 的总报告。

## 安装顺序

1. 在 Mini 上确认“秒记”实际路径、Syncthing 为双向同步且已稳定。
2. 为五个目录分别建立 Codex/Cowork 项目。
3. 将同步目录路径写入各项目环境变量 `MIAOJI_ROOT`，不要硬编码用户目录。
4. 按 `SCHEDULE.md` 在 Mini 上创建定时任务。
5. 先用测试周期生成报告，经过手机端展示与反馈验收后再启用正式任务。
