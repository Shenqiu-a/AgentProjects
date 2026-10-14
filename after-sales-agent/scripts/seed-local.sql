-- 仅由 Initialize-Database.ps1 显式调用，不属于 Flyway 迁移，不在应用启动时自动执行。
-- Java 在同一事务中提供 seed_account / seed_document 临时表以及 app.seed_date。
INSERT INTO app_user (id, username, password_hash, role)
SELECT ('00000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, a.username, a.password_hash,
       CASE WHEN a.username = 'admin' THEN 'ADMIN' ELSE 'CUSTOMER' END
FROM (VALUES (1, 'alice'), (2, 'bob'), (3, 'admin')) AS u(n, username)
JOIN seed_account a USING (username)
ON CONFLICT (id) DO NOTHING;

INSERT INTO product (id, sku, name, category) VALUES
('10000000-0000-4000-8000-000000000001', 'P-EAR', '学习样例无线耳机', 'EARPHONE'),
('10000000-0000-4000-8000-000000000002', 'P-KEY', '学习样例机械键盘', 'KEYBOARD')
ON CONFLICT (id) DO NOTHING;

INSERT INTO policy_rule (id, code, version, product_category, warranty_days, allowed_reason_codes, description) VALUES
('30000000-0000-4000-8000-000000000001', 'R-EAR-1', 1, 'EARPHONE', 180,
 '["LEFT_NO_SOUND","NOT_CHARGING","OTHER"]', '模拟政策：签收日起 180 个自然日内可申请维修，最后一天包含在内。'),
('30000000-0000-4000-8000-000000000002', 'R-KEY-1', 1, 'KEYBOARD', 365,
 '["KEY_FAILURE","OTHER"]', '模拟政策：签收日起 365 个自然日内可申请维修，最后一天包含在内。')
ON CONFLICT (id) DO NOTHING;

INSERT INTO customer_order (id, order_no, user_id, product_id, policy_rule_id, product_category, status, price, delivered_at)
SELECT ('20000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, order_no,
       ('00000000-0000-4000-8000-' || lpad(owner_no::text, 12, '0'))::uuid,
       ('10000000-0000-4000-8000-' || lpad(product_no::text, 12, '0'))::uuid,
       ('30000000-0000-4000-8000-' || lpad(product_no::text, 12, '0'))::uuid,
       CASE WHEN product_no = 1 THEN 'EARPHONE' ELSE 'KEYBOARD' END,
       status, price,
       CASE WHEN days_ago IS NULL THEN NULL
            ELSE ((current_setting('app.seed_date')::date - days_ago) + time '10:00') AT TIME ZONE 'Asia/Shanghai' END
FROM (VALUES
    (1, 'A1001', 1, 1, 'DELIVERED', 299.00, 30),
    (2, 'A1002', 1, 1, 'DELIVERED', 299.00, 200),
    (3, 'A1003', 1, 1, 'CANCELED', 299.00, NULL),
    (4, 'B2001', 2, 1, 'DELIVERED', 299.00, 20),
    (5, 'A1004', 1, 2, 'DELIVERED', 499.00, 90),
    (6, 'A1005', 1, 1, 'DELIVERED', 299.00, 40),
    (7, 'A1006', 1, 1, 'DELIVERED', 299.00, 180),
    (8, 'A1007', 1, 1, 'DELIVERED', 299.00, 181)
) AS sample(n, order_no, owner_no, product_no, status, price, days_ago)
ON CONFLICT (id) DO NOTHING;

-- 七份真实本地样例文本；固定 16 维 one-hot 向量仅验证数据库向量链路，不提供语义能力。
INSERT INTO knowledge_document (id, title, kind, product_id, policy_rule_id, sha256, storage_key, content_type, status)
SELECT ('40000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, title, kind,
       CASE WHEN product_no IS NULL THEN NULL ELSE ('10000000-0000-4000-8000-' || lpad(product_no::text, 12, '0'))::uuid END,
       CASE WHEN kind <> 'POLICY' THEN NULL ELSE ('30000000-0000-4000-8000-' || lpad(product_no::text, 12, '0'))::uuid END,
       encode(sha256(convert_to(content, 'UTF8')), 'hex'), storage_key, 'text/markdown', 'PROCESSING'
FROM seed_document ON CONFLICT (id) DO NOTHING;

INSERT INTO document_ingestion (id, document_id, generation, status, stage, processed_chunks, total_chunks,
                                embedding_model, embedding_dimensions, index_namespace)
SELECT ('41000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       ('40000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       1, 'SUCCEEDED', 'DONE', 1, 1, 'fixture-one-hot-16-v1', 16, 'mock-v1'
FROM seed_document ON CONFLICT (id) DO NOTHING;

INSERT INTO knowledge_chunk (id, document_id, generation, ordinal, title_path, char_start, char_end, content,
                             content_hash, embedding, embedding_model, embedding_dimensions, index_namespace, metadata)
SELECT ('42000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       ('40000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       1, 0, title, 0, char_length(content), content, encode(sha256(convert_to(content, 'UTF8')), 'hex'),
       ('[' || (SELECT string_agg(CASE WHEN i = n THEN '1' ELSE '0' END, ',' ORDER BY i) FROM generate_series(1,16) AS i) || ']')::vector,
       'fixture-one-hot-16-v1', 16, 'mock-v1', jsonb_build_object('fixture', true, 'syntheticEmbedding', true, 'slug', slug)
FROM seed_document ON CONFLICT (id) DO NOTHING;

UPDATE knowledge_document d SET status = 'READY', active_generation = 1
FROM seed_document s
WHERE d.id = ('40000000-0000-4000-8000-' || lpad(s.n::text, 12, '0'))::uuid
  AND d.status = 'PROCESSING' AND d.active_generation IS NULL;

-- 显式标注的静态历史样例：没有调用 Chat/Embedding API、没有实际执行工具或 SSE worker。
INSERT INTO conversation (id, user_id, bound_order_id, title) VALUES
('50000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000001',
 '20000000-0000-4000-8000-000000000006', '静态样例：A1005 已有维修工单')
ON CONFLICT (id) DO NOTHING;
INSERT INTO chat_message (id, conversation_id, role, content, sequence) VALUES
('51000000-0000-4000-8000-000000000001', '50000000-0000-4000-8000-000000000001', 'USER',
 '【静态测试记录】耳机左耳持续没有声音，充电和重新配对后仍然无效。', 1)
ON CONFLICT (id) DO NOTHING;
INSERT INTO agent_run (id, conversation_id, user_id, trigger_message_id, status, outcome, mode, checkpoint, executor_version) VALUES
('52000000-0000-4000-8000-000000000001', '50000000-0000-4000-8000-000000000001',
 '00000000-0000-4000-8000-000000000001', '51000000-0000-4000-8000-000000000001', 'SUCCEEDED', 'TICKET_CREATED',
 'mock', '{"fixture":true,"modelCalled":false,"note":"静态数据库样例，未执行 Agent"}', 'seed-fixture-v1')
ON CONFLICT (id) DO NOTHING;
INSERT INTO chat_message (id, conversation_id, run_id, role, content, sequence) VALUES
('51000000-0000-4000-8000-000000000002', '50000000-0000-4000-8000-000000000001',
 '52000000-0000-4000-8000-000000000001', 'ASSISTANT', '【静态测试记录】样例维修工单 T-DEMO-0001，状态 OPEN。此消息由种子脚本写入，没有调用模型。', 2)
ON CONFLICT (id) DO NOTHING;

INSERT INTO confirmation (id, run_id, user_id, status, draft, draft_hash, expires_at)
SELECT '53000000-0000-4000-8000-000000000001', '52000000-0000-4000-8000-000000000001',
       '00000000-0000-4000-8000-000000000001', 'PENDING', draft,
       encode(sha256(convert_to(draft::text, 'UTF8')), 'hex'), now() + interval '10 minutes'
FROM (SELECT '{"fixture":true,"orderId":"20000000-0000-4000-8000-000000000006","type":"REPAIR","reasonCode":"LEFT_NO_SOUND","description":"耳机左耳持续没有声音，充电和重新配对后仍然无效。"}'::jsonb AS draft) x
ON CONFLICT (id) DO NOTHING;
INSERT INTO ticket (id, ticket_no, user_id, order_id, source_confirmation_id, reason_code, description) VALUES
('54000000-0000-4000-8000-000000000001', 'T-DEMO-0001', '00000000-0000-4000-8000-000000000001',
 '20000000-0000-4000-8000-000000000006', '53000000-0000-4000-8000-000000000001', 'LEFT_NO_SOUND',
 '耳机左耳持续没有声音，充电和重新配对后仍然无效。')
ON CONFLICT (id) DO NOTHING;
UPDATE confirmation SET status = 'APPROVED', ticket_id = '54000000-0000-4000-8000-000000000001', decision_at = now(), version = version + 1
WHERE id = '53000000-0000-4000-8000-000000000001' AND status = 'PENDING';
INSERT INTO ticket_status_history (id, ticket_id, to_status, actor_id, note) VALUES
('55000000-0000-4000-8000-000000000001', '54000000-0000-4000-8000-000000000001', 'OPEN',
 '00000000-0000-4000-8000-000000000001', '静态 fixture：确认和工单关系用于开发查询，并非真实客户操作。')
ON CONFLICT (id) DO NOTHING;
