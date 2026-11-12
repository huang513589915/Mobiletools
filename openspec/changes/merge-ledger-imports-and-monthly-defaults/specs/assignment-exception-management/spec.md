# Spec Delta

## MODIFIED Requirements

### Requirement: Assignment scope isolation
系统 MUST 将人工分配限定在被选中的记录、其数据来源和所属统计期内。新统计期 MUST 根据该期 Excel 客户经理姓名重新匹配且不得继承其他统计期的手工归属。同一统计期的合并更新中，Excel 客户经理姓名未变时 MUST 保留该记录当前有效客户经理；姓名发生变化时 MUST 按本次 Excel 姓名重新匹配。

#### Scenario: Import a later snapshot
- **WHEN** 管理员导入同一数据来源的后续统计期
- **THEN** 系统按新文件重新匹配客户经理，不复制其他统计期的人工分配

#### Scenario: Update a row without changing its Excel manager
- **WHEN** 同一统计期的同一业务键字段发生变化，但 Excel 客户经理姓名与当前记录相同
- **THEN** 系统更新该行业务字段并保留其当前有效客户经理和归属处理状态

#### Scenario: Update a row with a changed Excel manager
- **WHEN** 同一统计期的同一业务键字段发生变化且 Excel 客户经理姓名与当前记录不同
- **THEN** 系统保留新的 Excel 原始姓名，并按新姓名精确匹配当前有效客户经理或生成归属匹配异常

#### Scenario: Assign one source type
- **WHEN** 管理员分配欠费或专线记录
- **THEN** 系统不改变另一来源中的记录归属
