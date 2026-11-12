# Spec Delta

## MODIFIED Requirements

### Requirement: Line-expiry record details
系统 MUST 在不离开列表页的详情抽屉中按 Excel 原始列顺序展示该行所有有表头字段，并额外展示导入批次、工作表、原始行号、Excel 原始客户经理、当前有效客户经理、归属匹配状态、续签处理状态、最后处理人、最后处理时间，以及按时间排序的状态变化与跟进说明统一处理时间线；成员状态和合同到期日只在详情中展示。

#### Scenario: View line details
- **WHEN** 用户打开一条专线记录详情
- **THEN** 系统显示所有有表头的原始 Excel 字段，包括成员状态和合同到期日，并显示工单当前状态、最后处理信息和统一处理时间线

#### Scenario: Authorized user updates status from details
- **WHEN** 管理员或当前有效归属客户经理在详情抽屉提交新的续签处理状态
- **THEN** 系统保存状态变化并刷新当前状态、最后处理人、最后处理时间和处理时间线

#### Scenario: Append optional follow-up detail
- **WHEN** 有权限的用户将专线工单设置为“跟进中”并填写可选情况说明
- **THEN** 系统追加一条包含说明、填写人和填写时间的记录，不覆盖旧说明

#### Scenario: Add follow-up without changing status
- **WHEN** 专线工单当前状态为“跟进中”且有权限的用户只填写新的跟进说明
- **THEN** 系统追加跟进记录并更新最后处理人和时间，但不新增状态变化记录

#### Scenario: Unauthorized user sees details
- **WHEN** 客户经理打开不属于自己的专线记录详情
- **THEN** 系统拒绝访问，不显示该记录的 Excel 字段或工单处理信息

#### Scenario: Display empty and zero values
- **WHEN** 原始字段值为空或为数值零
- **THEN** 系统分别显示“—”和“0”
