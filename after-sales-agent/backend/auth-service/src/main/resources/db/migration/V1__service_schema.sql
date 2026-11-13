-- Fresh microservice database only; do not apply to the legacy database.
-- 用户、商品、不可变政策版本和订单。金额单位：人民币元；时间统一使用 timestamptz。
CREATE TABLE app_user (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username varchar(64) NOT NULL UNIQUE CHECK (length(btrim(username)) > 0),
    password_hash varchar(100) NOT NULL,
    role varchar(16) NOT NULL CHECK (role IN ('CUSTOMER', 'ADMIN')),
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE app_user IS '应用账号；password_hash 存 BCrypt，不是 PostgreSQL 登录密码';

