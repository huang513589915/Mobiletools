# Spec Delta

## Purpose

为本地 MVP 提供登录、会话保护和最小可用的管理员/客户经理权限边界，确保客户及业务数据只能在授权范围内被查看和操作。

## ADDED Requirements

### Requirement: Credential login
系统 MUST 提供独立登录页面，使用账号和密码建立受保护会话，数据库只保存密码哈希。

#### Scenario: Login succeeds
- **WHEN** 启用账号提交正确账号和密码
- **THEN** 系统建立会话并进入工作台

#### Scenario: Login fails
- **WHEN** 账号不存在、停用或密码错误
- **THEN** 系统拒绝登录且不泄露具体账号状态

### Requirement: Protected session
系统 MUST 将未登录访问受保护页面和接口的请求拒绝或重定向到登录页，并支持退出和会话失效。

#### Scenario: Anonymous access
- **WHEN** 未登录用户访问业务页面或接口
- **THEN** 页面跳转登录或接口返回未认证错误

#### Scenario: Logout
- **WHEN** 用户退出登录
- **THEN** 服务端使会话失效，原会话不能继续访问业务数据

### Requirement: Role permissions
系统 MUST 区分管理员和客户经理：管理员可维护账号、导入、规则和全量数据；客户经理只能查看范围内客户，可创建和处理任务，不能修改规则或全量导入导出。

#### Scenario: Manager is restricted
- **WHEN** 客户经理调用规则修改、全量导入或越权客户接口
- **THEN** 系统拒绝操作并记录必要的安全日志

#### Scenario: Administrator manages
- **WHEN** 管理员访问账号、规则或导入管理功能
- **THEN** 系统允许其在业务校验通过后执行操作

### Requirement: Customer scope
系统 MUST 在查询和写操作服务端校验客户经理的客户范围，不能只依赖前端隐藏菜单。

#### Scenario: Out-of-scope customer
- **WHEN** 客户经理访问不属于其范围的客户、账单、任务或跟进
- **THEN** 系统返回无权访问，不泄露详细数据

#### Scenario: Scope is updated
- **WHEN** 管理员调整客户经理客户范围
- **THEN** 后续查询按新范围生效，并保留变更审计

### Requirement: User administration
系统 MUST 支持管理员创建、停用和查询账号，不提供 V1 前端自助注册。

#### Scenario: Create user
- **WHEN** 管理员提交合法账号、姓名、角色和初始密码
- **THEN** 系统保存密码哈希并创建账号

#### Scenario: Disabled user logs in
- **WHEN** 已停用账号尝试登录
- **THEN** 系统拒绝登录

#### Scenario: Bootstrap accounts
- **WHEN** 在全新本地环境执行经批准的初始化流程
- **THEN** 系统创建 `admin`（系统管理员，管理员）、`huangling`（黄铃，客户经理）、`zhoumin`（周敏，客户经理）和 `chenchen`（陈晨，客户经理）四个测试账号，初始密码均为 `123456` 且数据库只保存密码哈希

### Requirement: Import and export authorization
系统 MUST 只允许管理员执行批量导入和全量数据导出；客户经理不得执行全量导入或导出。

#### Scenario: Manager requests bulk import or full export
- **WHEN** 客户经理发起批量导入或全量导出
- **THEN** 系统拒绝请求并记录审计结果

#### Scenario: Administrator exports data
- **WHEN** 管理员导出授权范围允许的全量数据
- **THEN** 系统生成结果并记录导出操作者、时间和数据范围
