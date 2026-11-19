BEGIN;
SET LOCAL TIME ZONE 'Asia/Shanghai';
INSERT INTO product (id, sku, name, category) VALUES
('10000000-0000-4000-8000-000000000001', 'P-EAR', '模拟无线耳机', 'EARPHONE'),
('10000000-0000-4000-8000-000000000002', 'P-KEY', '模拟机械键盘', 'KEYBOARD')
ON CONFLICT (id) DO NOTHING;
INSERT INTO policy_rule (id, code, version, product_category, warranty_days, allowed_reason_codes, description) VALUES
('30000000-0000-4000-8000-000000000001', 'R-EAR-1', 1, 'EARPHONE', 180,
 '["LEFT_NO_SOUND","NOT_CHARGING","OTHER"]', '模拟耳机维修申请规则 v1'),
('30000000-0000-4000-8000-000000000002', 'R-KEY-1', 1, 'KEYBOARD', 365,
 '["KEY_FAILURE","OTHER"]', '模拟键盘维修申请规则 v1')
ON CONFLICT (id) DO NOTHING;
INSERT INTO customer_order (id, order_no, user_id, product_id, policy_rule_id, product_category, status, price, delivered_at)
SELECT ('20000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, order_no,
       ('00000000-0000-4000-8000-' || lpad(owner_no::text, 12, '0'))::uuid,
       ('10000000-0000-4000-8000-' || lpad(product_no::text, 12, '0'))::uuid,
       ('30000000-0000-4000-8000-' || lpad(product_no::text, 12, '0'))::uuid,
       category, status, price,
       CASE WHEN days_ago IS NULL THEN NULL
            ELSE ((current_date - days_ago) + time '10:00') AT TIME ZONE 'Asia/Shanghai' END
FROM (VALUES
 (1, 'A1001', 1, 1, 'EARPHONE', 'DELIVERED', 299.00, 30),
 (2, 'A1002', 1, 1, 'EARPHONE', 'DELIVERED', 299.00, 200),
 (3, 'A1003', 1, 1, 'EARPHONE', 'CANCELED', 299.00, NULL),
 (4, 'B2001', 2, 1, 'EARPHONE', 'DELIVERED', 299.00, 30),
 (5, 'A1004', 1, 2, 'KEYBOARD', 'DELIVERED', 499.00, 90),
 (6, 'A1005', 1, 1, 'EARPHONE', 'DELIVERED', 299.00, 40),
 (7, 'A1006', 1, 1, 'EARPHONE', 'DELIVERED', 299.00, 180),
 (8, 'A1007', 1, 1, 'EARPHONE', 'DELIVERED', 299.00, 181)
) AS v(n, order_no, owner_no, product_no, category, status, price, days_ago)
ON CONFLICT (id) DO NOTHING;
COMMIT;