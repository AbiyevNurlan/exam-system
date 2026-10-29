package com.examportal.paid.provider;

import com.examportal.paid.order.Order;
import com.examportal.paid.order.OrderRepository;
import com.examportal.paid.order.OrderStatus;
import com.examportal.paid.payment.PaymentWebhookService;
import com.examportal.paid.web.SandboxCheckoutController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.SimpleStatusAggregator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The sandbox in production without a database: the profile "production" closes it whatever PAYMENTS_SANDBOX_ALLOWED
 * says (no checkout, hidden: its page and notifications 404), the health component warns while the overall health
 * stays UP; only PAYMENTS_SANDBOX_ALLOW_IN_PRODUCTION=I-UNDERSTAND (exactly) keeps it on; outside production the
 * sandbox is what PAYMENTS_SANDBOX_ALLOWED says, as before.
 */
class SandboxProductionGuardTest {

    private static final String ORDER_NO = "YK-261029-000001";

    private static SandboxProductionGuard production(boolean allowed, String provider, boolean paid, String override) {
        return new SandboxProductionGuard(true, allowed, provider, paid, override);
    }

    private static SandboxPaymentProvider sandbox(SandboxProductionGuard guard) {
        return new SandboxPaymentProvider(true, "secret-secret-secret", "", guard);
    }

    private static Order order() {
        return new Order(1L, ORDER_NO, UUID.randomUUID(), 1234567, 7L, 2500, "AZN", OrderStatus.PENDING, "SANDBOX", "sbx_1",
                true, null, null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now().plusMinutes(30), null, null, null);
    }

    @Test
    void theProfileDecidesWhatProductionIs() {
        MockEnvironment live = new MockEnvironment();
        live.setActiveProfiles("production");
        assertThat(new SandboxProductionGuard(live, true, "SANDBOX", true, "").hidden()).isTrue();
        MockEnvironment docker = new MockEnvironment();
        docker.setActiveProfiles("docker");
        assertThat(new SandboxProductionGuard(docker, true, "SANDBOX", true, "").hidden()).isFalse();
        assertThat(new SandboxProductionGuard(new MockEnvironment(), true, "SANDBOX", true, "").permitted()).isTrue();
    }

    @Test
    void inProductionTheSandboxIsClosedWhateverItsSwitchSays() {
        SandboxProductionGuard guard = production(true, "SANDBOX", true, "");
        assertThat(guard.misconfigured()).isTrue();
        assertThat(guard.state()).isEqualTo(SandboxProductionGuard.State.BLOCKED_IN_PRODUCTION);

        SandboxPaymentProvider closed = sandbox(guard);
        assertThat(closed.available()).isFalse();
        assertThat(closed.hidden()).isTrue();
        assertThatThrownBy(() -> closed.createCheckout(order()).block()).isInstanceOf(IllegalStateException.class);
        var note = closed.notification(PaymentProvider.EventType.PAYMENT_SUCCEEDED, ORDER_NO, 2500, "AZN", "sbx_1");
        var headers = new org.springframework.http.HttpHeaders();
        headers.add(SandboxPaymentProvider.SIGNATURE_HEADER, note.signature());
        assertThat(closed.verifyWebhook(note.body(), headers).valid()).isFalse();
        assertThat(closed.refund(order(), "x").block()).isEqualTo(PaymentProvider.RefundOutcome.FAILED);
        assertThatThrownBy(() -> new PaymentProviders(java.util.List.of(closed), "SANDBOX").forCheckout().block())
                .satisfies(error -> assertThat(((com.examportal.common.exception.ApplicationException) error).getErrorCode())
                        .isEqualTo("PAYMENT_PROVIDER_UNAVAILABLE"));
    }

    @Test
    void whatCountsAsAskingForTheSandboxInProduction() {
        // PAYMENTS_SANDBOX_ALLOWED=true, or the sandbox as checkout provider of an open paid feature
        assertThat(production(true, "EXTERNAL", false, null).misconfigured()).isTrue();
        assertThat(production(false, " sandbox ", true, null).misconfigured()).isTrue();
        assertThat(production(false, "SANDBOX", true, null).state()).isEqualTo(SandboxProductionGuard.State.BLOCKED_IN_PRODUCTION);
        // Not asked for: the paid feature off, or another provider; the sandbox is still hidden there
        SandboxProductionGuard quiet = production(false, "SANDBOX", false, null);
        assertThat(quiet.misconfigured()).isFalse();
        assertThat(quiet.state()).isEqualTo(SandboxProductionGuard.State.OFF);
        assertThat(quiet.hidden()).isTrue();
        assertThat(production(false, "EXTERNAL", true, null).misconfigured()).isFalse();
    }

    @Test
    void onlyTheExactOverrideKeepsItOnInProduction() {
        for (String wrong : new String[]{null, "", "yes", "true", "i-understand", "I-UNDERSTAND!"}) {
            assertThat(production(true, "SANDBOX", true, wrong).hidden()).as(String.valueOf(wrong)).isTrue();
        }
        SandboxProductionGuard overridden = production(true, "SANDBOX", true, " I-UNDERSTAND ");
        assertThat(overridden.permitted()).isTrue();
        assertThat(overridden.hidden()).isFalse();
        assertThat(overridden.misconfigured()).isFalse();
        assertThat(overridden.state()).isEqualTo(SandboxProductionGuard.State.ON_IN_PRODUCTION);
        SandboxPaymentProvider on = sandbox(overridden);
        assertThat(on.available()).isTrue();
        assertThat(on.hidden()).isFalse();
        assertThat(on.createCheckout(order()).block().checkoutUrl()).contains("/api/payments/sandbox/checkout/" + ORDER_NO);
        // The override alone does not turn the sandbox on: PAYMENTS_SANDBOX_ALLOWED still decides
        SandboxProductionGuard overrideOnly = production(false, "SANDBOX", true, "I-UNDERSTAND");
        assertThat(new SandboxPaymentProvider(false, "secret-secret-secret", "", overrideOnly).available()).isFalse();
        assertThat(overrideOnly.state()).isEqualTo(SandboxProductionGuard.State.OFF);
    }

    @Test
    void outsideProductionNothingChanges() {
        SandboxProductionGuard development = new SandboxProductionGuard(false, true, "SANDBOX", true, null);
        assertThat(development.permitted()).isTrue();
        assertThat(development.hidden()).isFalse();
        assertThat(development.misconfigured()).isFalse();
        assertThat(development.state()).isEqualTo(SandboxProductionGuard.State.ON);
        assertThat(sandbox(development).available()).isTrue();
        assertThat(new SandboxPaymentProvider(false, "secret-secret-secret", "", development).available()).isFalse();
        // The constructor of before is development
        assertThat(new SandboxPaymentProvider(true, "secret-secret-secret", "").available()).isTrue();
        assertThat(new SandboxPaymentProvider(true, "secret-secret-secret", "").hidden()).isFalse();
        assertThat(SandboxProductionGuard.development().state()).isEqualTo(SandboxProductionGuard.State.OFF);
        assertThat(new ManualPaymentProvider().hidden()).isFalse();
    }

    @Test
    void theHealthComponentWarnsButTheServiceStaysUp() {
        var blocked = new PaymentsSandboxHealthIndicator(production(true, "SANDBOX", true, "")).health();
        assertThat(blocked.getStatus()).isEqualTo(PaymentsSandboxHealthIndicator.WARNING);
        assertThat(blocked.getDetails()).containsEntry("sandbox", "BLOCKED_IN_PRODUCTION")
                .containsEntry("warning", "Sandbox payments are not allowed in production");
        var overridden = new PaymentsSandboxHealthIndicator(production(true, "SANDBOX", true, "I-UNDERSTAND")).health();
        assertThat(overridden.getStatus()).isEqualTo(PaymentsSandboxHealthIndicator.WARNING);
        assertThat(overridden.getDetails()).containsEntry("sandbox", "ON_IN_PRODUCTION");
        var fine = new PaymentsSandboxHealthIndicator(production(false, "EXTERNAL", true, "")).health();
        assertThat(fine.getStatus()).isEqualTo(Status.UP);
        assertThat(fine.getDetails()).containsEntry("sandbox", "OFF").doesNotContainKey("warning");
        assertThat(new PaymentsSandboxHealthIndicator(new SandboxProductionGuard(false, true, "SANDBOX", true, null)).health().getStatus())
                .isEqualTo(Status.UP);
        // The overall health of the service: WARNING is not one of its statuses, so it stays UP (no restart)
        assertThat(new SimpleStatusAggregator().getAggregateStatus(Status.UP, PaymentsSandboxHealthIndicator.WARNING)).isEqualTo(Status.UP);
    }

    private static WebTestClient client(SandboxPaymentProvider sandbox) {
        OrderRepository orders = mock(OrderRepository.class);
        when(orders.findByNo(anyString())).thenReturn(Mono.just(order()));
        PaymentWebhookService webhooks = mock(PaymentWebhookService.class);
        when(webhooks.handle(anyString(), any(), any())).thenReturn(Mono.just(
                new PaymentWebhookService.Result(PaymentWebhookService.Outcome.PAID, 1L, false)));
        return WebTestClient.bindToController(new SandboxCheckoutController(sandbox, orders, webhooks)).build();
    }

    @Test
    void theSandboxPageIs404InProductionEvenWithAValidLink() {
        SandboxPaymentProvider closed = sandbox(production(true, "SANDBOX", true, ""));
        String sig = closed.linkSignature(ORDER_NO);
        WebTestClient web = client(closed);
        web.get().uri("/api/payments/sandbox/checkout/{no}?sig={sig}", ORDER_NO, sig).exchange().expectStatus().isNotFound();
        web.post().uri("/api/payments/sandbox/checkout/{no}/complete?sig={sig}&result=success", ORDER_NO, sig).exchange()
                .expectStatus().isNotFound();

        // With the override (and in development) the same link opens the page
        for (SandboxProductionGuard open : new SandboxProductionGuard[]{production(true, "SANDBOX", true, "I-UNDERSTAND"),
                SandboxProductionGuard.development()}) {
            SandboxPaymentProvider on = sandbox(open);
            WebTestClient page = client(on);
            page.get().uri("/api/payments/sandbox/checkout/{no}?sig={sig}", ORDER_NO, on.linkSignature(ORDER_NO)).exchange()
                    .expectStatus().isOk().expectBody(String.class).value(body -> assertThat(body).contains("Uğurlu ödəniş"));
            page.post().uri("/api/payments/sandbox/checkout/{no}/complete?sig={sig}&result=success", ORDER_NO, on.linkSignature(ORDER_NO))
                    .exchange().expectStatus().isOk();
        }
    }
}
