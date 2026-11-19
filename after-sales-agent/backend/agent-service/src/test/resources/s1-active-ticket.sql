BEGIN;
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
COMMIT;