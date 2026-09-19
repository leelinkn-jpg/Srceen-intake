# 工作数据桥接协议

部门过程管理工作簿只保存在 Mini，手机只同步 `工作/业务数据/` 下的只读 JSON 镜像。不要将整个部门资料目录同步到手机。

## 手机展示镜像

- `工作概览.json`：当月厂租投放、厂租剩余储备、厂配容投放、厂配容剩余投放、厂配容储备；每一项必须有数值、单位、统计口径、数据截止时间和来源文件。
- `储备项目.json`：来自 `储备项目/储备项目表.xlsx` 的当月项目。每项至少有客户经理、项目名称、金额、预计投放时间、是否容易租、当前进度和稳定行键。
- `厂配容名单.json`：来自 `厂配容业务/厂配容名单.xlsx`。每项至少有归属人、企业名称、类型、预核额度、最近走访日期、最近投放金额、营销反馈、营销优先级和稳定行键。
- `逾期客户.json`：只保留新出现、近期恶化或短期需要跟进的客户。已长期无处理价值的历史坏账不进入手机列表；每项必须写明筛选依据、数据日期和来源。
- `走访记录.json`：由工作软件截屏识别或正式访客数据导入。每项至少有日期、客户经理、对象、对象类型、内容、匹配名单、匹配方式、来源和原始事件 ID。
- `投放记录.json`：由正式投放明细导入。每项至少有日期、客户经理、承租人、金额、业务类型、关联渠道/厂配容名单、来源和原始事件 ID。

所有镜像 JSON 均须包含 `schemaVersion`、`generatedAt`、`dataCutoff`、`sourceFiles`。原始数据缺失时必须保留空数组并写 `missingData`，不得伪造零值。

## 待确认变更

模型或截屏识别只能创建 `工作/待确认变更/<changeId>.json`。支持的 `kind` 为：

- `todo_complete`
- `reserve_create`、`reserve_update`
- `capacity_customer_update`
- `channel_update`
- `visit_import`
- `placement_import`

每份变更单必须有 `changeId`、`kind`、`status=pending`、`sourceEventId`、`evidence`、`target`、`before`、`after`、`confidence`、`createdAt`。`target` 至少包含相对工作簿路径、工作表、稳定行键与字段名。

App 将确认结果写入 `工作/变更反馈/<changeId>.json`，状态只能是 `accepted`、`rejected`、`conflict`。当前运行模式下，Mini 只消费反馈以更新报告和镜像中的处理状态；**无论状态为何，均不得改动部门过程管理目录内的正式工作簿或规则文件**。若未来需要写回，必须由用户在独立任务中明确授权，并在写入前重新核对数据。

## 周会时间确认

当节假日、调休或材料周期使下一次周会时间无法确定时，Mini 创建 `工作/待确认计划/weekly-meeting-<week>.json`，其中包含 `planId`、`status=pending`、`reason`、`evidence`、`suggestedAt` 和 `options`（至少含 `confirm_time`、`skip`）。App 对此文件只通知一次，并允许用户填写会议时间或选择本周不召开；反馈写入 `工作/计划反馈/<planId>.json`。未收到确认前，Mini 不得以默认周一时间安排周会相关报告。
