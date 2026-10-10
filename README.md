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
- 后端：Java 17、Spring Boot 3、Maven
- 数据库：预留 PostgreSQL/MySQL 接入
- 任务调度：Spring Scheduler

## 本地启动

### 前端

```powershell
cd frontend
npm install
npm run dev
```

前端默认地址：`http://localhost:5173`

### 后端

```powershell
cd backend
..\.tools\apache-maven-3.9.9\bin\mvn.cmd spring-boot:run
```

后端默认地址：`http://localhost:8080`

健康检查：`GET http://localhost:8080/api/health`

当前骨架只提供工程结构、健康检查接口和前端基础工作台，真实数据源、数据库表结构和业务规则将在后续功能提交中逐步接入。
