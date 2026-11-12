# Spec Delta

## MODIFIED Requirements

### Requirement: Preserve work-order handling across revisions
系统 MUST 在同一统计日期和成员服务号码的合并导入确认后复用对应续签工单并保留其当前状态、最后处理人、最后处理时间和状态变更历史；更新该专线的 Excel 字段 MUST NOT 重置工单。新增专线 MUST 创建“待处理”工单。部分上传未包含的当前专线及其工单 MUST 继续保留。

#### Scenario: Revised row keeps handling data
- **WHEN** 合并上传包含已有统计日期和成员服务号码且 Excel 字段发生变化
- **THEN** 系统更新该专线当前数据，但保留原工单状态、最后处理人、最后处理时间和全部状态历史

#### Scenario: Revised row is newly added
- **WHEN** 合并上传包含当前统计日期和成员服务号码均不存在的新专线
- **THEN** 系统创建一条“待处理”工单，且最后处理人和最后处理时间为空

#### Scenario: Revised row is removed
- **WHEN** 当前统计日期存在某专线但有效上传文件没有包含该成员服务号码
- **THEN** 系统继续显示该专线及其原工单，不改变工单当前值和历史

#### Scenario: Same line is uploaded with no changes
- **WHEN** 上传包含已有统计日期和成员服务号码且 Excel 字段内容完全相同的专线
- **THEN** 系统保留其现有工单且不创建重复工单或新的状态历史
