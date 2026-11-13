CREATE TABLE evaluation_job (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_version varchar(100) NOT NULL,
    dataset_hash varchar(64) NOT NULL CHECK (dataset_hash ~ '^[0-9a-f]{64}$'),
    config jsonb NOT NULL CHECK (jsonb_typeof(config) = 'object'),
    status varchar(16) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    total integer NOT NULL CHECK (total > 0),
    completed integer NOT NULL DEFAULT 0 CHECK (completed >= 0 AND completed <= total),
    metrics jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metrics) = 'object'),
    error_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    CHECK (status <> 'SUCCEEDED' OR (completed = total AND finished_at IS NOT NULL))
);
CREATE INDEX ix_evaluation_status_created ON evaluation_job(status, created_at DESC, id);

CREATE TABLE evaluation_case_result (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_id uuid NOT NULL REFERENCES evaluation_job(id),
    case_id varchar(100) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('PASS', 'FAIL', 'SKIPPED')),
    assertions jsonb NOT NULL CHECK (jsonb_typeof(assertions) = 'array'),
    observed jsonb NOT NULL CHECK (jsonb_typeof(observed) = 'object'),
    run_id uuid REFERENCES agent_run(id),
    latency_ms bigint CHECK (latency_ms >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (evaluation_id, case_id)
);
COMMENT ON TABLE evaluation_job IS '评测任务与模型/参数快照；正式评测应连接独立 after_sales_eval';
COMMENT ON TABLE evaluation_case_result IS '逐案例断言结果；空表表示尚未执行评测，不预置虚构 PASS';
