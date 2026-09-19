# 模型执行器接入

`bin/run_daily_pipeline` 已负责 WHOOP 就绪、补跑去重、任务状态和输入规则版本；它不会替用户选择模型。

如需完全自动运行，请把你选定模型的本地执行命令包装成一个可执行文件，并在 Mini 的 LaunchAgent 环境中设置 `MIAOJI_MODEL_RUNNER` 为该文件绝对路径。执行器接收：

```text
--job <系统/系统状态/分析任务/YYYY-MM-DD.json> --root <秒记中枢>
```

执行器只应读取任务指定领域的原始数据和规则，生成草稿后通过 `scripts/publish_report.py` 发布；不得直接改动账本、会议、健康或成长原始记录。失败时更新任务 JSON 的 `status=failed` 与简短错误码。
