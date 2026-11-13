CREATE TABLE conversation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES app_user(id),
    bound_order_id uuid,
    title varchar(200) NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, user_id),
    FOREIGN KEY (bound_order_id, user_id) REFERENCES customer_order(id, user_id)
);
CREATE INDEX ix_conversation_user_updated ON conversation(user_id, updated_at DESC, id);

CREATE TABLE chat_message (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id uuid NOT NULL REFERENCES conversation(id),
    run_id uuid,
    role varchar(16) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content text NOT NULL CHECK (length(content) > 0),
    citations jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(citations) = 'array'),
    sequence bigint NOT NULL CHECK (sequence > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (conversation_id, sequence),
    UNIQUE (id, conversation_id)
);

CREATE TABLE agent_run (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id uuid NOT NULL,
    user_id uuid NOT NULL,
    trigger_message_id uuid NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'WAITING_CONFIRMATION', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED')),
    outcome varchar(32) CHECK (outcome IN ('ANSWERED', 'NEED_MORE_INFO', 'TICKET_CREATED', 'REJECTED', 'NO_EVIDENCE')),
    mode varchar(8) NOT NULL CHECK (mode IN ('mock', 'live')),
    checkpoint jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(checkpoint) = 'object'),
    model_calls integer NOT NULL DEFAULT 0 CHECK (model_calls >= 0),
    tool_calls integer NOT NULL DEFAULT 0 CHECK (tool_calls >= 0),
    token_usage jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(token_usage) = 'object'),
    cancel_requested boolean NOT NULL DEFAULT false,
    recoverable boolean NOT NULL DEFAULT false,
    error_code varchar(64),
    attempt integer NOT NULL DEFAULT 1 CHECK (attempt > 0),
    executor_version varchar(64) NOT NULL,
    lease_until timestamptz,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (conversation_id, user_id) REFERENCES conversation(id, user_id),
    FOREIGN KEY (trigger_message_id, conversation_id) REFERENCES chat_message(id, conversation_id),
    UNIQUE (id, conversation_id),
    UNIQUE (id, user_id),
    CHECK (status = 'SUCCEEDED' OR outcome IS NULL)
);
ALTER TABLE chat_message ADD CONSTRAINT fk_message_run_conversation
    FOREIGN KEY (run_id, conversation_id) REFERENCES agent_run(id, conversation_id);
CREATE UNIQUE INDEX uq_active_run_conversation ON agent_run(conversation_id)
    WHERE status IN ('QUEUED', 'RUNNING', 'WAITING_CONFIRMATION', 'INTERRUPTED');
CREATE INDEX ix_run_conversation_created ON agent_run(conversation_id, created_at DESC, id);
CREATE INDEX ix_run_queue_lease ON agent_run(status, lease_until, created_at);

CREATE TABLE tool_call (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id uuid NOT NULL REFERENCES agent_run(id),
    attempt integer NOT NULL CHECK (attempt > 0),
    ordinal integer NOT NULL CHECK (ordinal > 0),
    provider_call_id varchar(200),
    name varchar(64) NOT NULL CHECK (name IN ('getOrder', 'checkEligibility', 'getProductGuide', 'searchPolicy', 'prepareTicket', 'getTicketStatus')),
    input jsonb NOT NULL CHECK (jsonb_typeof(input) = 'object'),
    output jsonb,
    status varchar(16) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    duration_ms bigint CHECK (duration_ms >= 0),
    error_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (run_id, ordinal)
);

CREATE TABLE run_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id uuid NOT NULL REFERENCES agent_run(id),
    sequence bigint NOT NULL CHECK (sequence > 0),
    type varchar(64) NOT NULL CHECK (type IN ('RUN_QUEUED', 'RUN_STARTED', 'MODEL_STARTED', 'MODEL_COMPLETED',
        'RETRIEVAL_COMPLETED', 'TOOL_STARTED', 'TOOL_COMPLETED', 'TOOL_FAILED', 'WAITING_CONFIRMATION',
        'RUN_SUCCEEDED', 'RUN_FAILED', 'RUN_CANCELLED', 'RUN_INTERRUPTED', 'RUN_RESUMED')),
    payload jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(payload) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (run_id, sequence)
);

CREATE TABLE confirmation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id uuid NOT NULL UNIQUE,
    user_id uuid NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    draft jsonb NOT NULL CHECK (jsonb_typeof(draft) = 'object'),
    draft_hash varchar(64) NOT NULL CHECK (draft_hash ~ '^[0-9a-f]{64}$'),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    ticket_id uuid UNIQUE,
    decision_at timestamptz,
    FOREIGN KEY (run_id, user_id) REFERENCES agent_run(id, user_id),
    UNIQUE (id, user_id),
    CHECK (expires_at > created_at),
    CHECK (status <> 'PENDING' OR (ticket_id IS NULL AND decision_at IS NULL)),
    CHECK (status <> 'APPROVED' OR (ticket_id IS NOT NULL AND decision_at IS NOT NULL)),
    CHECK (status NOT IN ('REJECTED', 'EXPIRED') OR ticket_id IS NULL)
);
CREATE INDEX ix_confirmation_pending_expiry ON confirmation(expires_at) WHERE status = 'PENDING';

CREATE TABLE ticket (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_no varchar(64) NOT NULL UNIQUE,
    user_id uuid NOT NULL,
    order_id uuid NOT NULL,
    source_confirmation_id uuid NOT NULL UNIQUE,
    type varchar(16) NOT NULL DEFAULT 'REPAIR' CHECK (type = 'REPAIR'),
    reason_code varchar(32) NOT NULL CHECK (reason_code IN ('LEFT_NO_SOUND', 'NOT_CHARGING', 'KEY_FAILURE', 'OTHER')),
    description text NOT NULL CHECK (char_length(description) BETWEEN 10 AND 500),
    status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'PROCESSING', 'RESOLVED', 'CLOSED')),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (order_id, user_id) REFERENCES customer_order(id, user_id),
    FOREIGN KEY (source_confirmation_id, user_id) REFERENCES confirmation(id, user_id),
    UNIQUE (id, source_confirmation_id)
);
-- 双向引用推迟到事务提交检查：插入 PENDING 确认 -> 插入工单 -> 更新确认为 APPROVED。
ALTER TABLE confirmation ADD CONSTRAINT fk_confirmation_ticket
    FOREIGN KEY (ticket_id, id) REFERENCES ticket(id, source_confirmation_id) DEFERRABLE INITIALLY DEFERRED;
CREATE UNIQUE INDEX uq_active_ticket_order ON ticket(order_id) WHERE status IN ('OPEN', 'PROCESSING');
CREATE INDEX ix_ticket_user_created ON ticket(user_id, created_at DESC, id);
CREATE INDEX ix_ticket_status_created ON ticket(status, created_at DESC, id);

CREATE TABLE ticket_status_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id uuid NOT NULL REFERENCES ticket(id),
    from_status varchar(16) CHECK (from_status IN ('OPEN', 'PROCESSING', 'RESOLVED', 'CLOSED')),
    to_status varchar(16) NOT NULL CHECK (to_status IN ('OPEN', 'PROCESSING', 'RESOLVED', 'CLOSED')),
    actor_id uuid NOT NULL REFERENCES app_user(id),
    note varchar(500),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((from_status IS NULL AND to_status = 'OPEN')
        OR (from_status IS NOT NULL AND (
            (from_status = 'OPEN' AND to_status = 'PROCESSING')
            OR (from_status = 'PROCESSING' AND to_status = 'RESOLVED')
            OR (from_status = 'RESOLVED' AND to_status = 'CLOSED'))))
);
CREATE INDEX ix_ticket_history_time ON ticket_status_history(ticket_id, created_at, id);

CREATE TABLE idempotency_record (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES app_user(id),
    operation varchar(100) NOT NULL,
    idempotency_key varchar(128) NOT NULL CHECK (length(btrim(idempotency_key)) > 0),
    request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    resource_id uuid NOT NULL,
    http_status smallint NOT NULL CHECK (http_status BETWEEN 200 AND 599),
    response jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    UNIQUE (user_id, operation, idempotency_key),
    CHECK (expires_at >= created_at + interval '24 hours')
);
CREATE INDEX ix_idempotency_expiry ON idempotency_record(expires_at);

COMMENT ON TABLE conversation IS '客户会话；绑定订单必须属于会话客户';
COMMENT ON TABLE chat_message IS '客户可见消息；序号递增由锁定会话后的事务分配';
COMMENT ON TABLE agent_run IS 'Agent 可恢复执行状态；model_calls=0 表示没有真实生成调用';
COMMENT ON TABLE tool_call IS '工具输入输出审计；持久化前应过滤敏感信息';
COMMENT ON TABLE run_event IS '可回放 SSE 事件；提交数据库后再通知订阅者';
COMMENT ON TABLE confirmation IS '客户确认卡片；10 分钟有效期、不可变草稿与身份校验由业务层落实';
COMMENT ON TABLE ticket IS '维修工单；同订单只允许一张 OPEN/PROCESSING 工单';
COMMENT ON TABLE ticket_status_history IS '工单状态审计；推进状态与插入历史必须在同一事务';
COMMENT ON TABLE idempotency_record IS '接口幂等结果快照；保留至少 24 小时';
