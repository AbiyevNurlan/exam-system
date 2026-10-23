package com.examportal.paid.provider;

import com.examportal.paid.order.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * B13b item 6b: payments outside the system (cash, bank transfer) that an administrator records: POST
 * /api/admin/orders/{id}/mark-paid with a reason and the number of the document. No checkout, no notification
 * (every notification for MANUAL is refused). A refund is the administrator giving the money back outside the
 * system and recording it, so it succeeds here.
 */
@Component
public class ManualPaymentProvider implements PaymentProvider {

    public static final String NAME = "MANUAL";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Mono<Checkout> createCheckout(Order order) {
        return Mono.error(new UnsupportedOperationException("MANUAL has no checkout"));
    }

    @Override
    public Verification verifyWebhook(byte[] body, HttpHeaders headers) {
        return Verification.invalid(PayloadSanitizer.sanitize(body));
    }

    @Override
    public Mono<ProviderStatus> fetchStatus(Order order) {
        return Mono.just(ProviderStatus.unknown());
    }

    @Override
    public Mono<RefundOutcome> refund(Order order, String reason) {
        return Mono.just(RefundOutcome.SUCCEEDED);
    }
}
