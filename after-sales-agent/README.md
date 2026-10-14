# 售后客服与工单 Agent

Java 21、Spring Boot 4.0.8、Spring AI 2.0.1。当前已有项目目录、Maven 依赖、启动类、基础配置、18 张业务表与本地样例；业务接口、模型适配器、登录认证和 worker 按需求文档逐步开发。

2026-10-14：已在本机 `after_sales` 执行 V2—V5 建表并导入 3 个用户、2 个商品、2 份政策、8 个订单、7 份知识文档与 1 张静态测试工单。账号密码位置、表用途、查询和复现命令见 [数据库与测试数据](./docs/数据库与测试数据.md)。`after_sales_eval` 本次未改动。

下一步学习登录模块：见 [登录模块开发指南](./docs/登录模块开发指南.md)，包含六份通过编译检查的 Java 参考代码、文件关系和接口联调步骤。教程示例尚未写入业务源码。

2026-10-14：当前电脑的原生 PostgreSQL 18 已安装 pgvector 0.8.7，并在两个项目数据库中启用，详见 [安装与验证记录](./docs/pgvector安装记录.md)。下方 Compose 步骤用于创建容器环境；沿用当前本机数据库时可直接从 backend 启动应用。

## 配置文件

| 位置 | 用途 |
|---|---|
| `backend/src/main/resources/application.yml` | 公共数据源、Flyway、上传、会话、日志和自定义参数 |
| `backend/src/main/resources/application-local.yml` | 默认本地环境，从项目根目录显式读取 `.env` |
| `backend/src/main/resources/application-eval.yml` | 独立评测数据库、端口、索引和文件目录 |
| `backend/src/main/resources/application-prod.yml` | 部署模板，环境变量、Secure Cookie、JSON 控制台日志 |
| `backend/src/test/resources/application-test.yml` | 集成测试环境，强制 mock、独立测试数据源 |
| `backend/src/main/resources/db/migration/V1__enable_pgvector.sql` | Flyway 首次迁移，仅启用 vector 扩展 |
| `.env.example` | 本地参数示例，无真实密钥 |
| `compose.yaml` | PostgreSQL 17＋pgvector 0.8.7，主库与可选评测库 |
| `.gitignore` | 排除凭证、IDE 文件、构建产物与运行数据 |

详细使用方法、配置优先级、技术解释和排错说明见 [配置说明](./docs/配置说明.md)。

## 首次启动

以下命令从项目根目录执行，需要 JDK 21、Maven、Docker Desktop 和 Docker Compose v2。

```powershell
Copy-Item .env.example .env
```

编辑 `.env`，至少修改 DATABASE_PASSWORD 和 EVAL_DATABASE_PASSWORD。然后启动主数据库：

```powershell
docker compose up -d --wait postgres
```

后端始终以 backend 为工作目录启动：

```powershell
Set-Location backend
mvn spring-boot:run
```

默认 local 环境、8080 端口。应用连接数据库后，Flyway 执行扩展和建表迁移；测试数据需要显式执行初始化脚本。可以访问 `http://localhost:8080/actuator/health` 检查 Spring Boot 基础健康状态。

`/actuator/health` 是框架端点；需求中的 `/api/v1/health` 和其他业务接口仍需实现。业务安全配置尚未编写时，Spring Security 使用默认保护规则和临时开发登录用户。

## 评测数据库

从项目根目录启动独立评测库：

```powershell
docker compose --profile eval up -d --wait postgres-eval
```

从 backend 目录运行独立评测环境：

```powershell
mvn spring-boot:run '-Dspring-boot.run.profiles=eval'
```

此环境连接 15433 端口上的 after_sales_eval，应用端口为 8081。它提供隔离配置；EvaluationRunner、fixture 和评测接口需要继续开发。

## 学习时的关键区别

- `spring.*`、`server.*`、`management.*`、`logging.*` 由框架读取。
- `app.*` 是项目自定义约定，需要后续配置绑定与 Java 实现。
- `MODEL_MODE=mock` 是后续适配器的模式选择值，不表示当前已有 mock Agent 能力。
- `.env` 由 Compose 自动读取；本项目的 local/eval/test 配置通过显式 import 读取，格式限制见配置说明。
- 不提交真实密钥；不把真实模型调用、Embedding 调用和 mock 数据混为一谈。
