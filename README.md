# 政企客户欠费与到期管理工具

本项目是政企客户欠费、余额、专线到期、风险预警和任务督办管理工具。

## 项目结构

```text
Mobiletools/
├─ backend/                 # Spring Boot 模块化单体后端
├─ frontend/                # React + TypeScript + Vite 前端
├─ docs/                    # 需求、技术设计和项目文档
├─ AGENT.md                 # 项目协作规则
└─ README.md
```

## 技术栈

- 前端：React、TypeScript、Vite、Ant Design、TanStack Query、ECharts
- 后端：Java 17、Spring Boot 2.5.15、Maven
- 数据库：MySQL，默认数据库名 `arrears_management`
- 认证：Spring Security、本地数据库账号、服务端 Session

## 本地启动

### 前端

```powershell
cd frontend
npm install
npm run dev -- --host 127.0.0.1
```

前端默认地址：`http://127.0.0.1:5173`

### 后端

```powershell
cd backend
$env:APP_ADMIN_USERNAME = "admin"
$env:APP_ADMIN_PASSWORD = "<本地初始密码>"
mvn spring-boot:run
```

后端默认地址：`http://127.0.0.1:8080`

健康检查：`GET http://127.0.0.1:8080/api/health`

首次启动时，系统会在数据库不存在该账号时创建本地账号。当前本机已创建账号 `admin`；初始密码通过 PowerShell 环境变量提供，已经存在的账号不会在每次启动时自动重置密码。密码不要写入源代码、README 或 Git。

默认数据库连接为：

```text
地址：127.0.0.1:3306
数据库：arrears_management
用户名：root
密码：空（如本机 MySQL 已设置密码，请设置 DB_PASSWORD）
```

当前版本已接入客户、任务查询和本地登录，Excel 导入、正式预警计算、任务写入、消息通知和外部系统同步仍在后续开发中。
