-- =====================================================
-- V24 (B13b): orders, payment events and entitlements of paid open exams
-- Additive only: three new tables, one sequence, three trigger functions. No existing table is changed.
--
-- orders           one purchase of one exam by one guest (guest_id: guest_participants of auth-service, another
--                  database, so no foreign key; guest_code is the guest's 7-digit code at the time). The amount is
--                  copied from the exam when ordered, in whole minor units, so a later price change never touches it.
--                  seat_held: whether the order holds a seat now (taken once, given back once).
--                  Status changes only in the allowed directions (trigger trg_orders_status_transition):
--                    PENDING        -> PAID, FAILED, EXPIRED, CANCELLED, NEEDS_REVIEW
--                    FAILED         -> NEEDS_REVIEW            (a payment came after all)
--                    EXPIRED        -> NEEDS_REVIEW            (a payment came after the order ran out)
--                    CANCELLED      -> NEEDS_REVIEW
--                    PAID           -> REFUND_PENDING, REFUNDED
--                    REFUND_PENDING -> REFUNDED, PAID          (refunded, or the refund refused / given up)
--                    NEEDS_REVIEW   -> PAID, REFUND_PENDING, REFUNDED
--                    REFUNDED       -> (none)
-- payment_events   what a payment provider (or an administrator, MANUAL) said about an order, append only: UPDATE is
--                  always refused; DELETE only inside a transaction that set exam.purge_payment_events = 'on' (the
--                  removal of test data, scripts\cleanup-test-data.ps1). An event id of a provider counts once, among
--                  the events whose signature was valid (an invalid one cannot block the real event). No card data:
--                  the payload is stored after the known card fields are removed.
-- entitlements     the right of a guest to take an exam: ACTIVE with attempts_left, USED when they are spent, REVOKED
--                  by a refund. At most one ACTIVE per guest and exam. order_id NULL: given by an administrator.
-- Applied by Flyway. NEVER edit this file once applied anywhere.
-- =====================================================

CREATE SEQUENCE order_no_seq AS BIGINT MINVALUE 1 START WITH 1 NO CYCLE;

CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    order_no VARCHAR(24) NOT NULL,
    guest_id UUID NOT NULL,
    guest_code INTEGER NOT NULL,
    exam_id BIGINT NOT NULL REFERENCES exams(id),
    amount_minor INTEGER NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    provider VARCHAR(20) NOT NULL,
    provider_ref VARCHAR(120),
    seat_held BOOLEAN NOT NULL DEFAULT false,
    review_reason VARCHAR(500),
    refund_reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    paid_at TIMESTAMP,
    refund_requested_at TIMESTAMP,
    refunded_at TIMESTAMP,
    CONSTRAINT uq_orders_order_no UNIQUE (order_no),
    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'EXPIRED', 'CANCELLED', 'REFUND_PENDING',
                                                  'REFUNDED', 'NEEDS_REVIEW')),
    CONSTRAINT ck_orders_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_orders_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_orders_guest_code CHECK (guest_code BETWEEN 1000000 AND 9999999),
    CONSTRAINT ck_orders_provider CHECK (provider IN ('SANDBOX', 'MANUAL', 'EXTERNAL', 'NONE'))
);

-- One open order per guest and exam (the idempotent POST /api/guest/orders)
CREATE UNIQUE INDEX ux_orders_pending_guest_exam ON orders (guest_id, exam_id) WHERE status = 'PENDING';
CREATE INDEX idx_orders_guest_created ON orders (guest_id, created_at DESC);
CREATE INDEX idx_orders_exam_status ON orders (exam_id, status);
CREATE INDEX idx_orders_created ON orders (created_at DESC, id DESC);
CREATE INDEX idx_orders_status_created ON orders (status, created_at DESC, id DESC);
CREATE INDEX idx_orders_guest_code ON orders (guest_code, created_at DESC);
CREATE INDEX idx_orders_pending_expires ON orders (expires_at) WHERE status = 'PENDING';

CREATE FUNCTION orders_status_transition() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;
    IF (OLD.status = 'PENDING' AND NEW.status IN ('PAID', 'FAILED', 'EXPIRED', 'CANCELLED', 'NEEDS_REVIEW'))
        OR (OLD.status IN ('FAILED', 'EXPIRED', 'CANCELLED') AND NEW.status = 'NEEDS_REVIEW')
        OR (OLD.status = 'PAID' AND NEW.status IN ('REFUND_PENDING', 'REFUNDED'))
        OR (OLD.status = 'REFUND_PENDING' AND NEW.status IN ('REFUNDED', 'PAID'))
        OR (OLD.status = 'NEEDS_REVIEW' AND NEW.status IN ('PAID', 'REFUND_PENDING', 'REFUNDED')) THEN
        NEW.updated_at := LOCALTIMESTAMP;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'order % cannot go from % to %', OLD.order_no, OLD.status, NEW.status
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER trg_orders_status_transition
    BEFORE UPDATE OF status ON orders
    FOR EACH ROW EXECUTE FUNCTION orders_status_transition();

CREATE TABLE payment_events (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT REFERENCES orders(id),
    provider VARCHAR(20) NOT NULL,
    provider_event_id VARCHAR(120),
    event_type VARCHAR(40) NOT NULL,
    signature_valid BOOLEAN NOT NULL,
    amount_minor INTEGER,
    currency VARCHAR(3),
    outcome VARCHAR(40),
    payload TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    CONSTRAINT ck_payment_events_payload_size CHECK (payload IS NULL OR octet_length(payload) <= 65536)
);

CREATE UNIQUE INDEX ux_payment_events_provider_event ON payment_events (provider, provider_event_id)
    WHERE signature_valid AND provider_event_id IS NOT NULL;
CREATE INDEX idx_payment_events_order ON payment_events (order_id, created_at);

CREATE FUNCTION payment_events_append_only() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('exam.purge_payment_events', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'payment_events is append only (% refused)', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER trg_payment_events_append_only
    BEFORE UPDATE OR DELETE ON payment_events
    FOR EACH ROW EXECUTE FUNCTION payment_events_append_only();

CREATE TABLE entitlements (
    id BIGSERIAL PRIMARY KEY,
    guest_id UUID NOT NULL,
    exam_id BIGINT NOT NULL REFERENCES exams(id),
    order_id BIGINT REFERENCES orders(id),
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    attempts_left INTEGER NOT NULL,
    granted_by UUID,
    grant_reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    CONSTRAINT ck_entitlements_status CHECK (status IN ('ACTIVE', 'USED', 'REVOKED')),
    CONSTRAINT ck_entitlements_attempts CHECK (attempts_left >= 0),
    CONSTRAINT ck_entitlements_source CHECK (order_id IS NOT NULL OR (granted_by IS NOT NULL AND grant_reason IS NOT NULL))
);

CREATE UNIQUE INDEX ux_entitlements_active_guest_exam ON entitlements (guest_id, exam_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_entitlements_guest ON entitlements (guest_id, created_at DESC);
CREATE INDEX idx_entitlements_order ON entitlements (order_id);
CREATE INDEX idx_entitlements_exam ON entitlements (exam_id, status);
