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

CREATE TABLE product (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sku varchar(64) NOT NULL UNIQUE,
    name varchar(200) NOT NULL,
    category varchar(32) NOT NULL CHECK (category IN ('EARPHONE', 'KEYBOARD')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, category)
);
COMMENT ON TABLE product IS '示例商品；每个订单仅包含一件商品';

CREATE TABLE policy_rule (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(64) NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    product_category varchar(32) NOT NULL CHECK (product_category IN ('EARPHONE', 'KEYBOARD')),
    warranty_days integer NOT NULL CHECK (warranty_days > 0),
    allowed_reason_codes jsonb NOT NULL CHECK (jsonb_typeof(allowed_reason_codes) = 'array'),
    description text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (code, version),
    UNIQUE (id, product_category)
);
COMMENT ON TABLE policy_rule IS '订单绑定的模拟维修政策版本；发布后在业务层禁止修改，变更时新增版本';

CREATE TABLE customer_order (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_no varchar(64) NOT NULL UNIQUE,
    user_id uuid NOT NULL REFERENCES app_user(id),
    product_id uuid NOT NULL,
    policy_rule_id uuid NOT NULL,
    product_category varchar(32) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('DELIVERED', 'CANCELED')),
    price numeric(12,2) NOT NULL CHECK (price >= 0),
    delivered_at timestamptz,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (product_id, product_category) REFERENCES product(id, category),
    FOREIGN KEY (policy_rule_id, product_category) REFERENCES policy_rule(id, product_category),
    UNIQUE (id, user_id),
    CHECK (status <> 'DELIVERED' OR delivered_at IS NOT NULL)
);
CREATE INDEX ix_customer_order_user_created ON customer_order(user_id, created_at DESC, id);
COMMENT ON TABLE customer_order IS '客户订单；冗余 category 配合复合外键保证商品与政策类别一致';
