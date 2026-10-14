-- 只启用基础扩展，业务表在后续 V2、V3 等迁移中创建。
-- 数据库服务器需已安装 pgvector；Compose 提供的镜像包含扩展文件。
-- 本地 Compose 用户有创建扩展的权限；部署环境也可先由管理员创建。
CREATE EXTENSION IF NOT EXISTS vector;
