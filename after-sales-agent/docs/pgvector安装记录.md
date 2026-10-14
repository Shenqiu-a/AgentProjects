# pgvector 本机安装记录

日期：2026-10-14。此记录对应当前 Windows 电脑，其他电脑需要独立安装或使用包含扩展的数据库镜像。

## 安装结果

| 项目 | 已验证值 |
|---|---|
| PostgreSQL | 18.0，Windows x64 |
| PostgreSQL 根目录 | `A:\A_workSpace\Databases\Relational_databases\postgresql` |
| pgvector | 0.8.7 |
| 源码 | [官方 v0.8.7](https://github.com/pgvector/pgvector/tree/v0.8.7) |
| 源码提交 | `f37c13f68b57d2c3472b2214fbcff699d6d34876` |
| 编译 | Visual Studio 2022 x64 工具链、当前 PostgreSQL 的头文件和 postgres.lib |
| 文件安装 | 47 个文件，安装后逐个校验 SHA-256 |
| DLL SHA-256 | `30c3a886edc21aabd46007faf012ed4083d3f98b4b4fe3dd80af90ccdadd053a` |

扩展 DLL 位于 PostgreSQL 的 `lib/vector.dll`；控制文件与 SQL 位于 `share/extension`。没有修改服务器配置、现有数据库账号或业务表。

Windows 安装目录要求管理员权限，安装通过系统 UAC 授权完成。项目中的 [安装脚本](../scripts/install-pgvector-windows.ps1) 只复制已编译并核验的文件，不会覆盖内容不同的已有文件。它不包含数据库凭证。

## 数据库验证

在当前 PostgreSQL 18 实例的以下数据库执行了 `CREATE EXTENSION IF NOT EXISTS vector`：

- `after_sales`
- `after_sales_eval`

两库均读取到版本 0.8.7，并通过以下运算检查：

```sql
SELECT extname, extversion
FROM pg_extension
WHERE extname = 'vector';

SELECT vector_dims('[1,2,3]'::vector); -- 3
SELECT '[1,2,3]'::vector <-> '[1,2,4]'::vector; -- 欧氏距离 1
SELECT '[1,0,0]'::vector <=> '[1,0,0]'::vector; -- 余弦距离 0
```

这些检查没有创建永久测试表。后端的 V1 Flyway 脚本使用 IF NOT EXISTS，后续启动时可识别已经启用的扩展。完整后端启动与 Flyway 历史记录仍需在应用启动时验证。

## 当前本机连接与评测配置

本机主应用使用 localhost:5432、数据库 after_sales、登录用户 postgres，密码只保存在本地忽略文件 `.env` 中。

本次启用的 after_sales_eval 也在这一个 PostgreSQL 18 实例中。项目原始 eval 模板面向 Compose 的 15433 端口、独立评测账号；若后续选择沿用本机评测库，需要将 EVAL_DATABASE_URL、EVAL_DATABASE_USERNAME、EVAL_DATABASE_PASSWORD 改为该实例的真实连接信息。此安装没有替换那些评测连接参数。

新数据库需要单独执行 CREATE EXTENSION；服务器文件安装一次不代表每个数据库都会自动启用。
