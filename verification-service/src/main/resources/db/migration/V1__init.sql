CREATE TABLE verification_request (
    id                  UUID         PRIMARY KEY,
    subject_ref         VARCHAR(64)  NOT NULL,
    state               VARCHAR(32)  NOT NULL,
    vendor_ref          VARCHAR(128),
    session_expires_at  TIMESTAMPTZ,
    reconcile_attempts  INTEGER      NOT NULL DEFAULT 0,
    state_entered_at    TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0
);

-- One vendor reference maps to at most one request. Enforced rather than assumed: the webhook
-- resolves a request by vendor_ref, so a duplicate here would make that lookup ambiguous.
CREATE UNIQUE INDEX ux_verification_request_vendor_ref
    ON verification_request (vendor_ref) WHERE vendor_ref IS NOT NULL;

-- Supports the expiry sweeper and the stuck-signal cohort query, both of which filter on state and
-- order by how long the row has been sitting there.
CREATE INDEX ix_verification_request_state_entered
    ON verification_request (state, state_entered_at);

CREATE TABLE state_change_record (
    id               BIGSERIAL    PRIMARY KEY,
    verification_id  UUID         NOT NULL REFERENCES verification_request (id),
    event            VARCHAR(48)  NOT NULL,
    from_state       VARCHAR(32)  NOT NULL,
    to_state         VARCHAR(32)  NOT NULL,
    detail           VARCHAR(512),
    occurred_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_state_change_verification
    ON state_change_record (verification_id, occurred_at, id);

-- Webhook de-duplication. The vendor is entitled to retry, so the same (vendor_ref, event_type)
-- arriving twice must be cheap and harmless. A unique constraint makes the second insert fail fast
-- instead of triggering a second round of downstream work.
CREATE TABLE webhook_receipt (
    id           BIGSERIAL    PRIMARY KEY,
    vendor_ref   VARCHAR(128) NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    received_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ux_webhook_receipt UNIQUE (vendor_ref, event_type)
);

-- Read model. Deliberately a separate table with its own freshness stamp rather than a view, so the
-- read path can decide whether to trust it or rebuild it.
CREATE TABLE verification_summary (
    verification_id     UUID         PRIMARY KEY REFERENCES verification_request (id),
    subject_ref         VARCHAR(64)  NOT NULL,
    state               VARCHAR(32)  NOT NULL,
    transition_count    INTEGER      NOT NULL,
    first_seen_at       TIMESTAMPTZ  NOT NULL,
    last_transition_at  TIMESTAMPTZ  NOT NULL,
    settled             BOOLEAN      NOT NULL,
    refreshed_at        TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_verification_summary_refreshed ON verification_summary (refreshed_at);
