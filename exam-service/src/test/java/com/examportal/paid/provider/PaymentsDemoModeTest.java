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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PAYMENTS_DEMO_MODE without a database: in production the sandbox runs as the DEMO payment only with all three
 * switches (checkout, page and notifications work, the health component says DEMO while the service stays UP, the
 * public flag paymentsDemo is true); with any of them missing it stays closed as before; the demo page says DEMO
 * ÖDƏNİŞ and that no money is taken from the card.
 */
class PaymentsDemoModeTest {

    private static final String ORDER_NO = "YK-261029-000002";

    private static SandboxProductionGuard production(boolean allowed, String provider, boolean paid, boolean demo) {
        return new SandboxProductionGuard(true, allowed, provider, paid, null, demo);
    }

    private static SandboxPaymentProvider sandbox(SandboxProductionGuard guard) {
        return new SandboxPaymentProvider(true, "secret-secret-secret", "", guard);
    }

    private static Order order() {
        return new Order(1L, ORDER_NO, UUID.randomUUID(), 1234567, 7L, 3000, "AZN", OrderStatus.PENDING, "SANDBOX", "sbx_1",
                true, null, null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now().plusMinutes(30), null, null, null);
    }

    @Test
    void inProductionTheDemoNeedsAllThreeSwitches() {
        SandboxProductionGuard demo = production(true, "SANDBOX", true, true);
        assertThat(demo.state()).isEqualTo(SandboxProductionGuard.State.DEMO);
        assertThat(demo.permitted()).isTrue();
        assertThat(demo.hidden()).isFalse();
        assertThat(demo.misconfigured()).isFalse();
        assertThat(demo.paymentsDemo()).isTrue();

        SandboxPaymentProvider on = sandbox(demo);
        assertThat(on.available()).isTrue();
        assertThat(on.hidden()).isFalse();
        assertThat(new PaymentProviders(List.of(on, new ManualPaymentProvider()), "SANDBOX").forCheckout().block()).isSameAs(on);
        assertThat(on.createCheckout(order()).block().checkoutUrl()).contains("/api/payments/sandbox/checkout/" + ORDER_NO + "?sig=");

        // Any switch missing: closed and hidden as without the demo
        for (SandboxProductionGuard closed : new SandboxProductionGuard[]{production(false, "SANDBOX", true, true),
                production(true, "EXTERNAL", true, true), production(true, "SANDBOX", true, false)}) {
            assertThat(closed.state()).isEqualTo(SandboxProductionGuard.State.BLOCKED_IN_PRODUCTION);
            assertThat(closed.hidden()).isTrue();
            assertThat(closed.paymentsDemo()).isFalse();
            assertThat(sandbox(closed).available()).isFalse();
            assertThatThrownBy(() -> new PaymentProviders(List.of(sandbox(closed), new ManualPaymentProvider()), "SANDBOX").forCheckout().block())
                    .satisfies(error -> assertThat(((com.examportal.common.exception.ApplicationException) error).getErrorCode())
                            .isEqualTo("PAYMENT_PROVIDER_UNAVAILABLE"));
        }
    }

    @Test
    void theSwitchComesFromTheEnvironmentAndIsOffByDefault() {
        MockEnvironment live = new MockEnvironment();
        live.setActiveProfiles("production");
        assertThat(new SandboxProductionGuard(live, true, "SANDBOX", true, "", true).state()).isEqualTo(SandboxProductionGuard.State.DEMO);
        // The constructor of before has no demo
        assertThat(new SandboxProductionGuard(live, true, "SANDBOX", true, "").state()).isEqualTo(SandboxProductionGuard.State.BLOCKED_IN_PRODUCTION);
        assertThat(new SandboxProductionGuard(true, true, "SANDBOX", true, "").paymentsDemo()).isFalse();
        // The older override still opens the sandbox, and the paid flow then pays with it too
        SandboxProductionGuard override = new SandboxProductionGuard(true, true, "SANDBOX", true, SandboxProductionGuard.OVERRIDE);
        assertThat(override.state()).isEqualTo(SandboxProductionGuard.State.ON_IN_PRODUCTION);
        assertThat(override.paymentsDemo()).isTrue();
    }

    @Test
    void developmentIsAsBefore() {
        SandboxProductionGuard on = new SandboxProductionGuard(false, true, "SANDBOX", true, null, false);
        assertThat(on.state()).isEqualTo(SandboxProductionGuard.State.ON);
        assertThat(on.paymentsDemo()).isTrue();
        SandboxProductionGuard demoSwitchOnly = new SandboxProductionGuard(false, false, "SANDBOX", true, null, true);
        assertThat(demoSwitchOnly.state()).isEqualTo(SandboxProductionGuard.State.OFF);
        assertThat(demoSwitchOnly.hidden()).isFalse();
        assertThat(SandboxProductionGuard.development().paymentsDemo()).isFalse();
    }

    @Test
    void theHealthComponentSaysDemoAndTheServiceStaysUp() {
        var health = new PaymentsSandboxHealthIndicator(production(true, "SANDBOX", true, true)).health();
        assertThat(health.getStatus()).isEqualTo(PaymentsSandboxHealthIndicator.DEMO);
        assertThat(health.getStatus().getCode()).isEqualTo("DEMO");
        assertThat(health.getDetails()).containsEntry("sandbox", "DEMO").containsEntry("demo", "Payments DEMO mode is ON: no real money is charged")
                .doesNotContainKey("warning");
        assertThat(new SimpleStatusAggregator().getAggregateStatus(Status.UP, PaymentsSandboxHealthIndicator.DEMO)).isEqualTo(Status.UP);
        // Closed: a warning, as before
        assertThat(new PaymentsSandboxHealthIndicator(production(false, "SANDBOX", true, false)).health().getStatus())
                .isEqualTo(PaymentsSandboxHealthIndicator.WARNING);
    }

    @Test
    void theDemoPageSaysItIsADemo() {
        SandboxPaymentProvider on = sandbox(production(true, "SANDBOX", true, true));
        OrderRepository orders = mock(OrderRepository.class);
        when(orders.findByNo(anyString())).thenReturn(Mono.just(order()));
        PaymentWebhookService webhooks = mock(PaymentWebhookService.class);
        when(webhooks.handle(anyString(), any(), any())).thenReturn(Mono.just(
                new PaymentWebhookService.Result(PaymentWebhookService.Outcome.PAID, 1L, false)));
        WebTestClient web = WebTestClient.bindToController(new SandboxCheckoutController(on, orders, webhooks)).build();

        web.get().uri("/api/payments/sandbox/checkout/{no}?sig={sig}", ORDER_NO, on.linkSignature(ORDER_NO)).exchange()
                .expectStatus().isOk().expectBody(String.class).value(body -> {
                    assertThat(body).contains("<h1", "DEMO ÖDƏNİŞ", "Bu sınaq ödənişidir, kartınızdan pul tutulmur",
                            "Uğurlu ödəniş (demo)", "Uğursuz ödəniş (demo)", "30,00 AZN");
                    // No card field of any kind
                    assertThat(body.toLowerCase()).doesNotContain("<input type=\"text\"", "cvv", "card number", "kart nömrəsi");
                });
        web.post().uri("/api/payments/sandbox/checkout/{no}/complete?sig={sig}&result=success", ORDER_NO, on.linkSignature(ORDER_NO))
                .exchange().expectStatus().isOk().expectBody(String.class).value(body -> assertThat(body).contains("DEMO ÖDƏNİŞ",
                        "kartınızdan pul tutulmur"));
    }
}
