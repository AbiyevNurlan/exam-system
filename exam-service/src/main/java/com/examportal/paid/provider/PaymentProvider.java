package com.examportal.paid.provider;

import com.examportal.paid.order.Order;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

/**
 * B13b item 6: a payment provider. The system never sees card data: the guest pays on the provider's page (checkout),
 * and only the provider's signed notification (webhook) or its own status answer makes an order PAID, never the
 * browser coming back.
 *
 * Implementations: SANDBOX (development only, PAYMENTS_SANDBOX_ALLOWED), MANUAL (an administrator marks an order paid:
 * cash or bank transfer; no checkout, no webhook). A real provider is a third implementation with its keys from the
 * environment only (ExternalProviderSettings); it is not written yet.
 */
public interface PaymentProvider {

    /** SANDBOX, MANUAL or EXTERNAL: stored on the order and in its payment events */
    String name();

    /** Whether this provider may be used now (the sandbox only with PAYMENTS_SANDBOX_ALLOWED) */
    boolean available();

    /** Where the guest pays, and the provider's own reference of the payment */
    Mono<Checkout> createCheckout(Order order);

    /**
     * The provider's notification, checked: its signature over the raw body, and what it says. Never throws for a bad
     * notification: Verification.valid() is false then.
     */
    Verification verifyWebhook(byte[] body, HttpHeaders headers);

    /** What the provider knows of the order's payment now (the reconcile asks it for old PENDING orders) */
    Mono<ProviderStatus> fetchStatus(Order order);

    /** Gives the money back; SUCCEEDED, PENDING (the provider will notify) or FAILED */
    Mono<RefundOutcome> refund(Order order, String reason);

    record Checkout(String checkoutUrl, String providerRef) {
    }

    /** What a valid notification says; amount in whole minor units */
    record WebhookEvent(String eventId, EventType type, String orderNo, Integer amountMinor, String currency, String providerRef) {
    }

    enum EventType { PAYMENT_SUCCEEDED, PAYMENT_FAILED, REFUND_SUCCEEDED }

    /** valid: the signature matched and the body could be read; payload: the body without card fields, for the record */
    record Verification(boolean valid, WebhookEvent event, String payload) {

        public static Verification invalid(String payload) {
            return new Verification(false, null, payload);
        }
    }

    enum PaymentState { PAID, FAILED, PENDING, UNKNOWN }

    record ProviderStatus(PaymentState state, Integer amountMinor, String currency, String eventId) {

        public static ProviderStatus unknown() {
            return new ProviderStatus(PaymentState.UNKNOWN, null, null, null);
        }
    }

    enum RefundOutcome { SUCCEEDED, PENDING, FAILED }
}
