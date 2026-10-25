CREATE TABLE webhook_endpoints (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT webhook_endpoints_name_not_blank CHECK (name ~ '[^[:space:]]'),
    CONSTRAINT webhook_endpoints_url_not_blank CHECK (url ~ '[^[:space:]]')
);
