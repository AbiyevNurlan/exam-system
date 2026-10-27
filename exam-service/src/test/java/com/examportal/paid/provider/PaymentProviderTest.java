package com.examportal.paid.provider;

import com.examportal.common.exception.ApplicationException;
import com.examportal.paid.order.Order;
import com.examportal.paid.order.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B13b item 6 without a database: the sandbox is closed without PAYMENTS_SANDBOX_ALLOWED (no checkout, every
 * notification invalid, not chosen for checkout); with it, its notifications verify only with the right signature
 * over the exact body; the checkout link is signed per order; MANUAL never takes a notification; card fields never
 * reach the record; the keys of a real provider never show in its settings' text.
 */
class PaymentProviderTest {

    private static Order order() {
        return new Order(1L, "YK-261023-000001", UUID.randomUUID(), 1234567, 7L, 2500, "AZN", OrderStatus.PENDING, "SANDBOX", null,
                true, null, null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now().plusMinutes(30), null, null, null);
    }

    private static HttpHeaders signed(String signature) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(SandboxPaymentProvider.SIGNATURE_HEADER, signature);
        return headers;
    }

    private static String code(Throwable error) {
        return ((ApplicationException) error).getErrorCode();
    }

    @Test
    void theSandboxIsClosedWithoutItsSwitch() {
        SandboxPaymentProvider closed = new SandboxPaymentProvider(false, "secret-secret-secret", "");
        assertThat(closed.available()).isFalse();
        assertThatThrownBy(() -> closed.createCheckout(order()).block()).isInstanceOf(IllegalStateException.class);
        var note = closed.notification(PaymentProvider.EventType.PAYMENT_SUCCEEDED, "YK-1", 2500, "AZN", "ref");
        // Even a correctly signed notification is refused while the sandbox is closed
        assertThat(closed.verifyWebhook(note.body(), signed(note.signature())).valid()).isFalse();
        PaymentProviders providers = new PaymentProviders(List.of(closed, new ManualPaymentProvider()), "SANDBOX");
        assertThatThrownBy(() -> providers.forCheckout().block()).satisfies(error -> assertThat(code(error)).isEqualTo("PAYMENT_PROVIDER_UNAVAILABLE"));
        // MANUAL is never a checkout provider
        assertThatThrownBy(() -> new PaymentProviders(List.of(new ManualPaymentProvider()), "MANUAL").forCheckout().block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("PAYMENT_PROVIDER_UNAVAILABLE"));
    }

    @Test
    void sandboxNotificationsVerifyOnlyWithTheirSignature() {
        SandboxPaymentProvider sandbox = new SandboxPaymentProvider(true, "secret-secret-secret", "https://example.test/");
        assertThat(new PaymentProviders(List.of(sandbox), "sandbox").forCheckout().block()).isSameAs(sandbox);

        var checkout = sandbox.createCheckout(order()).block();
        assertThat(checkout.checkoutUrl()).startsWith("https://example.test/api/payments/sandbox/checkout/YK-261023-000001?sig=");
        assertThat(checkout.providerRef()).startsWith("sbx_");
        String sig = checkout.checkoutUrl().substring(checkout.checkoutUrl().indexOf("sig=") + 4);
        assertThat(sandbox.linkSignatureMatches("YK-261023-000001", sig)).isTrue();
        assertThat(sandbox.linkSignatureMatches("YK-261023-000002", sig)).isFalse();

        var note = sandbox.notification(PaymentProvider.EventType.PAYMENT_SUCCEEDED, "YK-261023-000001", 2500, "AZN", "sbx_1");
        var verified = sandbox.verifyWebhook(note.body(), signed(note.signature()));
        assertThat(verified.valid()).isTrue();
        assertThat(verified.event().orderNo()).isEqualTo("YK-261023-000001");
        assertThat(verified.event().amountMinor()).isEqualTo(2500);
        assertThat(verified.event().type()).isEqualTo(PaymentProvider.EventType.PAYMENT_SUCCEEDED);

        // A changed body, a wrong signature, no signature, another secret
        byte[] tampered = new String(note.body(), StandardCharsets.UTF_8).replace("2500", "1").getBytes(StandardCharsets.UTF_8);
        assertThat(sandbox.verifyWebhook(tampered, signed(note.signature())).valid()).isFalse();
        assertThat(sandbox.verifyWebhook(note.body(), signed("00" + note.signature().substring(2))).valid()).isFalse();
        assertThat(sandbox.verifyWebhook(note.body(), new HttpHeaders()).valid()).isFalse();
        SandboxPaymentProvider other = new SandboxPaymentProvider(true, "another-secret-another", "");
        assertThat(other.verifyWebhook(note.body(), signed(note.signature())).valid()).isFalse();
        // Without a configured secret every start has its own random one
        assertThat(new SandboxPaymentProvider(true, "", "").verifyWebhook(note.body(), signed(note.signature())).valid()).isFalse();
    }

    @Test
    void manualTakesNoNotificationAndCardDataIsNeverKept() {
        assertThat(new ManualPaymentProvider().verifyWebhook("{}".getBytes(StandardCharsets.UTF_8), new HttpHeaders()).valid()).isFalse();
        String kept = PayloadSanitizer.sanitize(("{\"orderNo\":\"YK-1\",\"card\":{\"pan\":\"4111111111111111\"},\"cardNumber\":\"4111111111111111\","
                + "\"cvv\":\"123\",\"payer\":{\"Card-Holder\":\"X\",\"exp_month\":\"12\",\"email\":\"a@b.c\"},\"items\":[{\"pan\":\"4111\"}]}")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(kept).contains("YK-1").doesNotContain("4111", "123\"", "exp_month", "Card-Holder");
        assertThat(PayloadSanitizer.sanitize("not json 4111111111111111".getBytes(StandardCharsets.UTF_8))).doesNotContain("4111").contains("notJson");

        ExternalProviderSettings external = new ExternalProviderSettings("bank", "https://pay.example", "m-1", "API-KEY-SECRET", "HOOK-SECRET");
        assertThat(external.configured()).isTrue();
        assertThat(external.toString()).doesNotContain("API-KEY-SECRET", "HOOK-SECRET");
        assertThat(new ExternalProviderSettings("", "", "", "", "").configured()).isFalse();
    }
}
