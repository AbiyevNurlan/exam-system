package com.examportal.paid.web;

import com.examportal.paid.payment.PaymentWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * B13b item 7: the providers' notifications, without a token (PaidSecurityConfig): the provider's signature over the
 * raw body decides (401 SIGNATURE_INVALID otherwise). Its own rate-limit bucket at the gateway (paid-webhook).
 */
@RestController
@RequestMapping("/api/payments/webhook")
@RequiredArgsConstructor
@Tag(name = "Payment notifications", description = "Signed notifications of payment providers")
public class PaymentWebhookController {

    private final PaymentWebhookService webhooks;

    @PostMapping("/{provider}")
    @Operation(summary = "A provider's notification", description = "200 also for a notification seen before (nothing changes); "
            + "401 SIGNATURE_INVALID for a bad signature; 404 for an unknown provider")
    public Mono<Map<String, Object>> notification(@PathVariable String provider, @RequestHeader HttpHeaders headers,
                                                  @RequestBody(required = false) byte[] body) {
        return webhooks.handle(provider, body == null ? new byte[0] : body, headers)
                .map(result -> Map.of("received", true));
    }
}
