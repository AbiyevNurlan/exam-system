package com.examportal.paid.provider;

import com.examportal.paid.order.Order;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * B13b item 6a: the sandbox provider, for development only. It exists only with PAYMENTS_SANDBOX_ALLOWED=true (default
 * false): otherwise available() is false, no checkout is made and every sandbox notification and page is refused.
 * Its checkout is exam-service's own test page (/api/payments/sandbox/checkout/{orderNo}, "Uğurlu ödəniş" /
 * "Uğursuz ödəniş"); a button sends a notification signed exactly like a provider's (HMAC-SHA256 of the raw body in
 * X-Sandbox-Signature) through the same webhook handling as a real one. The secret is PAYMENTS_SANDBOX_SECRET, or a
 * random one made at start when it is not set (development). Refunds succeed at once.
 */
@Slf4j
@Component
public class SandboxPaymentProvider implements PaymentProvider {

    public static final String NAME = "SANDBOX";
    public static final String SIGNATURE_HEADER = "X-Sandbox-Signature";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final boolean allowed;
    private final byte[] secret;
    private final String publicBaseUrl;

    public SandboxPaymentProvider(@Value("${payments.sandbox.allowed:false}") boolean allowed,
                                  @Value("${payments.sandbox.webhook-secret:}") String secret,
                                  @Value("${payments.public-base-url:}") String publicBaseUrl) {
        this.allowed = allowed;
        if (secret != null && !secret.isBlank()) {
            this.secret = secret.trim().getBytes(StandardCharsets.UTF_8);
        } else {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.secret = random;
        }
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
        if (allowed) {
            log.warn("The SANDBOX payment provider is ON (PAYMENTS_SANDBOX_ALLOWED=true): for development only, no money moves");
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean available() {
        return allowed;
    }

    @Override
    public Mono<Checkout> createCheckout(Order order) {
        if (!allowed) {
            return Mono.error(new IllegalStateException("sandbox not allowed"));
        }
        String providerRef = "sbx_" + UUID.randomUUID().toString().replace("-", "");
        return Mono.just(new Checkout(checkoutUrl(order.orderNo()), providerRef));
    }

    /** The sandbox page of an order, with a signature so nobody can open the page of another order */
    public String checkoutUrl(String orderNo) {
        return publicBaseUrl + "/api/payments/sandbox/checkout/" + orderNo + "?sig=" + linkSignature(orderNo);
    }

    public String linkSignature(String orderNo) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(("checkout:" + orderNo).getBytes(StandardCharsets.UTF_8)));
    }

    public boolean linkSignatureMatches(String orderNo, String signature) {
        return signature != null && MessageDigest.isEqual(linkSignature(orderNo).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }

    /** A notification as the sandbox sends it: the JSON body and its signature header value */
    public record SignedNotification(byte[] body, String signature) {
    }

    public SignedNotification notification(EventType type, String orderNo, int amountMinor, String currency, String providerRef) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", "sbx_evt_" + UUID.randomUUID().toString().replace("-", ""));
        body.put("type", type.name());
        body.put("orderNo", orderNo);
        body.put("amountMinor", amountMinor);
        body.put("currency", currency);
        body.put("providerRef", providerRef);
        try {
            byte[] bytes = JSON.writeValueAsBytes(body);
            return new SignedNotification(bytes, sign(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("notification", e);
        }
    }

    public String sign(byte[] body) {
        return HexFormat.of().formatHex(hmac(body));
    }

    @Override
    public Verification verifyWebhook(byte[] body, HttpHeaders headers) {
        String payload = PayloadSanitizer.sanitize(body);
        if (!allowed || body == null) {
            return Verification.invalid(payload);
        }
        String presented = headers.getFirst(SIGNATURE_HEADER);
        if (presented == null || !MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.US_ASCII),
                presented.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII))) {
            return Verification.invalid(payload);
        }
        try {
            JsonNode json = JSON.readTree(body);
            WebhookEvent event = new WebhookEvent(text(json, "eventId"), EventType.valueOf(text(json, "type")), text(json, "orderNo"),
                    json.hasNonNull("amountMinor") ? json.get("amountMinor").intValue() : null, text(json, "currency"), text(json, "providerRef"));
            if (event.eventId() == null || event.orderNo() == null) {
                return Verification.invalid(payload);
            }
            return new Verification(true, event, payload);
        } catch (Exception unreadable) {
            return Verification.invalid(payload);
        }
    }

    @Override
    public Mono<ProviderStatus> fetchStatus(Order order) {
        // The sandbox keeps no state of its own: only its notifications tell
        return Mono.just(ProviderStatus.unknown());
    }

    @Override
    public Mono<RefundOutcome> refund(Order order, String reason) {
        return Mono.just(allowed ? RefundOutcome.SUCCEEDED : RefundOutcome.FAILED);
    }

    private static String text(JsonNode json, String field) {
        return json.hasNonNull(field) ? json.get(field).asText() : null;
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
