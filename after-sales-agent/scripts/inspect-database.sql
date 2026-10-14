-- 在 pgAdmin 连接 after_sales 后分段执行；本文件均为查询。
SELECT current_database(), current_user;
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename;

-- 应用账号（不查询密码哈希）。
SELECT username, role, enabled FROM app_user ORDER BY username;

-- 订单与政策；这里只提供查询，正式资格结果仍由 Java 服务计算。
SELECT o.order_no, u.username, p.sku, p.name, o.status, o.price,
       (o.delivered_at AT TIME ZONE 'Asia/Shanghai')::date AS delivered_date,
       r.warranty_days,
       (o.delivered_at AT TIME ZONE 'Asia/Shanghai')::date + r.warranty_days AS last_eligible_date
FROM customer_order o JOIN app_user u ON u.id = o.user_id
JOIN product p ON p.id = o.product_id JOIN policy_rule r ON r.id = o.policy_rule_id
ORDER BY o.order_no;

SELECT t.ticket_no, u.username, o.order_no, t.status, t.reason_code, t.description,
       f.status AS confirmation_status, r.mode, r.model_calls, r.checkpoint
FROM ticket t JOIN app_user u ON u.id=t.user_id JOIN customer_order o ON o.id=t.order_id
JOIN confirmation f ON f.id=t.source_confirmation_id JOIN agent_run r ON r.id=f.run_id;

SELECT d.title, d.kind, d.status, d.active_generation, k.ordinal, k.content,
       k.index_namespace, k.embedding_model, vector_dims(k.embedding) AS dimensions, k.metadata
FROM knowledge_document d JOIN knowledge_chunk k ON k.document_id=d.id AND k.generation=d.active_generation
WHERE d.status='READY' ORDER BY d.title;

-- 仅验证向量链路：样例自身相似度为 1，其余 one-hot 向量为 0，不代表真实语义。
SELECT d.title, 1 - (k.embedding::vector(16) <=> '[1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]'::vector(16)) AS cosine_similarity
FROM knowledge_chunk k JOIN knowledge_document d ON d.id=k.document_id AND d.active_generation=k.generation
WHERE k.index_namespace='mock-v1' AND k.embedding_dimensions=16 AND d.status='READY'
ORDER BY k.embedding::vector(16) <=> '[1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]'::vector(16), d.id
LIMIT 4;
