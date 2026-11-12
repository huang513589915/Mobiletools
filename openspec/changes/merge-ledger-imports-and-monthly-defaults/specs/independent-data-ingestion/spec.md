# Spec Delta

## MODIFIED Requirements

### Requirement: Preview and versioning
系统 MUST 在正式写入前比较上传行与对应统计期当前数据。只有上传行在当前数据中均已存在且内容未发生变化时 MUST 判为重复；文件指纹相同但能改变当前数据的上传 MUST 可进入合并预览。确认后仅应用新增或更新的行，保留未变化和上传文件未包含的当前记录，并保留原批次追溯。

#### Scenario: Upload causes no data change
- **WHEN** 上传文件中的每个业务键在目标统计期当前数据中存在且有效字段内容相同
- **THEN** 系统将本次上传判为重复、记录该尝试且不改变当前数据

#### Scenario: Same file is uploaded again
- **WHEN** 管理员再次上传相同来源的相同文件且该文件相对目标统计期当前数据不产生新增或更新
- **THEN** 系统提示重复上传、不写入业务快照，并在批次记录保留该尝试

#### Scenario: Previously confirmed file can change current data
- **WHEN** 上传文件的指纹曾经出现，但与目标统计期当前数据相比会新增或更新至少一条记录
- **THEN** 系统允许生成合并预览并指出该文件曾被接收或确认

#### Scenario: Confirm a merged upload
- **WHEN** 管理员确认包含新增、更新或未变化记录的有效预览
- **THEN** 统计期当前数据包含新增和更新后的行，并保留原有未变化行及本次文件未包含的其他当前行

#### Scenario: Revised file is confirmed
- **WHEN** 管理员确认同一统计期的不同内容文件
- **THEN** 系统合并新增和更新行、保留未变化及文件未包含的当前行，新数据集成为该统计期当前版本且旧批次仍可追溯

#### Scenario: Show merge preview counts and differences
- **WHEN** 系统生成有效合并预览
- **THEN** 预览分别显示新增、更新、未变化和保留记录数量；每条更新记录显示变化字段的前后值，并明确标记被清空的值

#### Scenario: Current data changes after preview
- **WHEN** 某预检批次生成后、确认前，目标统计期当前数据被另一批次改变
- **THEN** 系统拒绝使用过期差异确认，并要求重新计算预览

### Requirement: Batch traceability
系统 MUST 记录每次上传的来源、文件名、文件指纹、统计日期、上传人、上传时间、状态、行级校验结果和当前数据行来源。合并后每条当前记录 MUST 可追溯到最近一次新增或更新该行的批次、工作表和原始行号；历史批次与其原始工作簿 MUST 保留。

#### Scenario: Review a completed batch
- **WHEN** 管理员打开导入批次详情
- **THEN** 系统显示汇总数量并可定位到工作表和原始行号

#### Scenario: Review merged row provenance
- **WHEN** 管理员查看由多个批次组成的统计期当前数据
- **THEN** 每条记录显示其最近一次新增或更新来源，未变化或未出现的记录继续保留既有来源

#### Scenario: Review batch contribution to current data
- **WHEN** 管理员查看导入批次列表
- **THEN** 每个批次显示仍贡献到当前数据集的记录数量，并区分全部贡献、部分贡献或无当前记录

#### Scenario: Failed confirmation
- **WHEN** 正式合并写入失败
- **THEN** 系统回滚本批次对当前数据的全部变更并保留失败状态和原因

## ADDED Requirements

### Requirement: Merge records by business identity within the statistics period
系统 MUST 在同一来源和统计期内按已定义的业务唯一键合并记录：欠费使用统计月份与账户 ID，专线使用统计日期与成员服务号码。合并不得因上传文件未包含某条当前记录而删除该记录。

#### Scenario: Add a new arrears account
- **WHEN** 欠费上传行的统计月份和账户 ID 在该月当前数据中不存在
- **THEN** 系统将该账户作为新增记录加入该月当前数据

#### Scenario: Update an existing row
- **WHEN** 上传行的来源、统计期和业务键与当前行相同且字段内容发生变化
- **THEN** 系统以本次上传行更新该记录并保留其业务键

#### Scenario: Preserve rows omitted from a partial upload
- **WHEN** 当前统计期存在某业务键但本次有效上传文件不包含该键
- **THEN** 系统继续在当前数据中保留该记录

#### Scenario: Empty cell clears an existing optional value
- **WHEN** 同键上传行包含某可选字段表头且该行该单元格为空
- **THEN** 系统将该字段原值清空

#### Scenario: Missing optional column preserves its value
- **WHEN** 上传工作表完全没有某可选字段的表头列
- **THEN** 系统保留当前记录该字段已有值

#### Scenario: Required columns remain required
- **WHEN** 上传工作表缺少该来源任一必填表头
- **THEN** 系统阻断该批次且不合并任何行
