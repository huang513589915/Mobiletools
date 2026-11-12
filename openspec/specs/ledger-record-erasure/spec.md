# ledger-record-erasure Specification

## Purpose

让管理员能够在确认影响后永久清除所选欠费账户或专线实例在指定统计期中的数据库历史及处理记录，并明确控制是否销毁包含它们的整份原始工作簿。

## Requirements

### Requirement: Administrator previews permanent ledger erasure
系统 MUST 只允许管理员预览和确认永久清除欠费或专线记录；预览 MUST 限定来源、统计期和所选业务唯一键，并在确认前列出将删除的数据和文件影响。

#### Scenario: Preview selected records
- **WHEN** 管理员选择一条或多条当前可见记录并发起永久删除预览
- **THEN** 系统按来源、统计期和业务唯一键汇总所有批次历史、快照、归属记录、工单记录，以及关联的原始工作簿和文件中的其他业务键数量

#### Scenario: Reject manager deletion
- **WHEN** 客户经理请求预览或确认永久删除
- **THEN** 系统拒绝请求且不修改任何数据

#### Scenario: Require a fresh preview
- **WHEN** 预览已过期、已使用，或预览后该统计期当前版本发生变化
- **THEN** 系统拒绝确认并要求管理员重新预览

### Requirement: Erase all database history for selected identities
确认后，系统 MUST 在同一来源和统计期内清除所选业务键在所有导入批次和合并版本中的原始行、快照、差异和归属历史，以及对应工单、状态变化和跟进记录；其他业务键和其他统计期 MUST 保持不变。

#### Scenario: Erase arrears account history
- **WHEN** 管理员确认删除某统计月份中的欠费账户
- **THEN** 该账户在该月份所有批次中的数据和欠费工单历史被永久删除，其他月份中的同一账户不变

#### Scenario: Erase line instance history
- **WHEN** 管理员确认删除某统计日期中的专线实例
- **THEN** 该成员服务号码在该日期所有批次中的数据及续签工单、状态历史和跟进被永久删除，其他统计日期中的同一号码不变

#### Scenario: Do not delete nonselected records
- **WHEN** 删除批次的行含有未选择的其他业务键
- **THEN** 这些业务键的数据库记录和工单保持可用

#### Scenario: Delete multiple periods safely
- **WHEN** 管理员一次选择多个统计期的记录
- **THEN** 系统分别按每个统计期和业务唯一键清理，不影响未选择统计期的数据

### Requirement: Explicitly delete whole original workbooks
系统 MUST 默认保留原始工作簿；只有管理员明确选择同时删除原件并二次确认时，系统才可销毁预览所列的整份文件，并清除所有批次对这些文件的引用。文件中其他业务键的数据库记录 MUST 保留。

#### Scenario: Keep original workbooks by default
- **WHEN** 管理员确认删除记录但未选择删除原始工作簿
- **THEN** 系统删除所选业务数据但保留原始文件及其批次引用

#### Scenario: Delete original workbooks after explicit confirmation
- **WHEN** 管理员选择删除原始工作簿并完成明确的二次确认
- **THEN** 系统删除预览列出的完整工作簿文件及数据库文件元数据，并使所有引用批次显示原件不可下载；同一文件中其他业务键的已解析数据仍保留

#### Scenario: Report pending physical file deletion
- **WHEN** 数据库记录已清除但本机文件因文件系统错误暂不能销毁
- **THEN** 系统明确显示待完成的原件清理状态并自动重试，不谎报原文件已销毁

### Requirement: Preserve minimal deletion audit
系统 MUST 为每次永久删除写入审计记录，记录操作者、时间、来源、统计期、删除数量、结果和原件清理状态；审计记录 MUST NOT 保存被删业务键、原始字段、客户或工作簿路径。

#### Scenario: Audit successful deletion
- **WHEN** 管理员成功确认永久删除
- **THEN** 管理员可在审计日志查看删除操作者、统计期、记录数量和原件处理结果，但无法从该审计记录恢复已删业务字段

#### Scenario: Audit failed or pending deletion
- **WHEN** 删除操作失败或原件清理待重试
- **THEN** 系统记录安全的操作结果和数量，不将业务字段写入错误或审计详情
