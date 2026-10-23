-- =====================================================
-- V13 (B13b): events of paid orders in the guest's audit
-- guest_audit_log (V12) gets order and payment events from exam-service (ORDER_CREATED, ORDER_PAID, ORDER_EXPIRED,
-- REFUND_REQUESTED, ORDER_REFUNDED, ENTITLEMENT_GRANTED, ...). Some have no person behind them (a provider's
-- notification, the reconcile): the actor kind SYSTEM is added. Only the CHECK of the actor kind changes; no row does.
-- Applied by Flyway. NEVER edit this file once applied anywhere.
-- =====================================================

ALTER TABLE guest_audit_log DROP CONSTRAINT ck_guest_audit_log_actor_kind;
ALTER TABLE guest_audit_log ADD CONSTRAINT ck_guest_audit_log_actor_kind CHECK (actor_kind IN ('ANONYMOUS', 'GUEST', 'ADMIN', 'SYSTEM'));
