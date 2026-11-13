# 售后客服与工单 Agent

项目已拆成 Java 21 / Spring Boot 4 的 Maven 多模块微服务：认证、订单、知识库、Agent 四个独立进程，以及 Nginx 统一网关。前端仍是 Vue 3 + TypeScript。

| 服务 | 本地 Java 端口 | 拥有的数据 |
|---|---:|---|
| auth-service | 8081 | 账号；Redis Session、CSRF、登录限流 |
| order-service | 8082 | 商品、政策版本、订单 |
| knowledge-service | 8083 | 文档、导入任务、分块与 pgvector |
| agent-service | 8084 | 会话、Agent 运行、确认单、工单、幂等与评测 |
| gateway | 8080 | 路由；不持有业务数据 |

认证流程已保留；这次新增订单列表/详情、管理员文档目录、本人单张工单读取作为服务的数据访问入口。原需求中的完整 Agent、检索、确认建单与评测 worker 仍待开发，不能把服务启动视为这些功能已经实现。

完整目录说明、调用过程、数据库归属、旧数据复制、IDE 启动和验证限制见 [微服务拆分与启动](./docs/微服务拆分与启动.md)。

## Docker 启动

需要 Docker Desktop（Linux containers）与 Compose v2。从本目录执行：

~~~powershell
.\scripts\Initialize-MicroserviceEnvironment.ps1
.\scripts\Start-Microservices.ps1
~~~

第一条命令创建独立的 .env.microservices 并生成本地随机凭证；已有文件不会覆盖。第二条构建四个镜像、启动新数据库与 Redis，并等待四个服务健康。旧单体的 .env 和数据库卷不参与启动。

首次没有账号时初始化新库账号：

~~~powershell
.\scripts\Migrate-MicroserviceData.ps1 -Action seed-accounts
~~~

密码只写入被 Git 忽略的 data/local/microservice-accounts.properties。如果要沿用旧库数据，请先按拆分文档执行 plan/copy，复制前不要 seed-accounts。

~~~powershell
Set-Location frontend
npm ci
npm run dev
~~~

访问 http://127.0.0.1:5173。Vite 将 /api 代理到 http://127.0.0.1:8080 网关；浏览器仍使用同源 Cookie。

## 构建与检查

~~~powershell
mvn -B -ntp -f backend/pom.xml verify
~~~

四个可执行 JAR 位于 backend/<service>/target。健康检查为 /health/auth、/health/orders、/health/knowledge、/health/agent；/gateway-healthz 仅表示网关进程可用。

backend/legacy-db/migration 原样保存旧 V1—V5 迁移。旧单体部署覆盖文件已移至 deploy/legacy；旧说明中的单 backend 启动命令不适用于当前目录。
