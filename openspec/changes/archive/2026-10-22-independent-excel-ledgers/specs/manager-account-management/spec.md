# Spec Delta

## Purpose

让管理员维护客户经理登录账号，并提供稳定的真实姓名匹配依据，使 Excel 归属匹配、异常处理和客户经理数据权限能够使用同一账号身份。

## ADDED Requirements

### Requirement: Administrator-managed accounts
系统 MUST 允许管理员在系统内逐个创建、查询和停用客户经理账号，且 MUST NOT 提供客户经理名单批量导入或自助注册。

#### Scenario: Create a manager account
- **WHEN** 管理员提交客户经理登录名、真实姓名和账号信息
- **THEN** 系统创建客户经理账号并保留真实姓名用于展示和匹配

#### Scenario: Disable a manager account
- **WHEN** 管理员停用一个客户经理账号
- **THEN** 系统拒绝该账号后续登录

#### Scenario: Customer manager self-registration
- **WHEN** 未登录用户尝试自助注册
- **THEN** 系统不提供自助注册流程

### Requirement: Pinyin username uniqueness
系统 MUST 以客户经理姓名拼音作为默认登录名，并保证登录名唯一；拼音发生冲突时，管理员 MUST 能添加区分后缀。

#### Scenario: Pinyin username is available
- **WHEN** 管理员创建姓名拼音尚未使用的账号
- **THEN** 系统允许使用该拼音作为登录名

#### Scenario: Pinyin username conflicts
- **WHEN** 新账号姓名拼音与现有登录名冲突
- **THEN** 系统提示登录名已占用并要求管理员提供唯一的区分后缀

### Requirement: Exact real-name identity
系统 MUST 将账号真实姓名作为客户经理展示名和 Excel 归属精确匹配值；真实姓名允许重复，但精确匹配到多个账号时必须由归属异常流程处理。

#### Scenario: Duplicate real names
- **WHEN** 多个客户经理账号使用完全相同的真实姓名
- **THEN** 系统允许保存这些账号且不自动为 Excel 记录选择其中一个

#### Scenario: Display account identity
- **WHEN** 客户经理名称出现在列表或账号管理中
- **THEN** 系统显示账号真实姓名而非登录名
