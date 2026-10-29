package com.examportal.paid.web;

import com.examportal.paid.order.OrderRepository;
import com.examportal.paid.order.OrderStatus;
import com.examportal.paid.payment.PaymentWebhookService;
import com.examportal.paid.provider.PaymentProvider;
import com.examportal.paid.provider.SandboxPaymentProvider;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * B13b item 6a: the sandbox's own test payment page, the DEMO payment (development, or production in DEMO mode). 404
 * unless the sandbox is on and the link's signature belongs to the order. A large "DEMO ÖDƏNİŞ" heading, "no money is
 * taken from your card", no card data asked. "Uğurlu ödəniş (demo)" / "Uğursuz ödəniş (demo)" send a notification signed like a
 * provider's through the same webhook handling; the page then only says to go back, where the site asks the order's
 * status (the browser never decides).
 */
@Hidden
@RestController
@RequestMapping("/api/payments/sandbox/checkout")
@RequiredArgsConstructor
public class SandboxCheckoutController {

    static final String TITLE = "DEMO ÖDƏNİŞ";
    // Said on every page of the sandbox: it is the payment of the DEMO mode (PAYMENTS_DEMO_MODE) as well as of development
    static final String DEMO_NOTICE = "<p style=\"background:#fff4c2;border:2px solid #e0b100;padding:12px 14px;font-size:16px\">"
            + "<b>Bu sınaq ödənişidir, kartınızdan pul tutulmur.</b> Real ödəniş alınmır; düymə yalnız ödənişin nəticəsini "
            + "göstərir.</p>";

    private final SandboxPaymentProvider sandbox;
    private final OrderRepository orders;
    private final PaymentWebhookService webhooks;

    @GetMapping(value = "/{orderNo}", produces = MediaType.TEXT_HTML_VALUE)
    public Mono<ResponseEntity<String>> page(@PathVariable String orderNo, @RequestParam(required = false) String sig) {
        if (!sandbox.available() || !orderNo.matches("[A-Z0-9-]{1,24}") || !sandbox.linkSignatureMatches(orderNo, sig)) {
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
        }
        return orders.findByNo(orderNo)
                .map(order -> html(TITLE, DEMO_NOTICE + "<p>Sifariş: <b>" + order.orderNo() + "</b></p><p>Məbləğ: <b>"
                        + money(order.amountMinor()) + " " + order.currency() + "</b></p>"
                        + (order.status() == OrderStatus.PENDING
                        ? "<p>Kart məlumatı soruşulmur. Nəticəni özünüz seçin:</p>"
                        + form(orderNo, sig, "success", "Uğurlu ödəniş (demo)") + form(orderNo, sig, "failure", "Uğursuz ödəniş (demo)")
                        : "<p>Sifarişin statusu: <b>" + order.status().name() + "</b></p>")))
                .defaultIfEmpty(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @PostMapping(value = "/{orderNo}/complete", produces = MediaType.TEXT_HTML_VALUE)
    public Mono<ResponseEntity<String>> complete(@PathVariable String orderNo, @RequestParam(required = false) String sig,
                                                 @RequestParam(defaultValue = "failure") String result) {
        if (!sandbox.available() || !orderNo.matches("[A-Z0-9-]{1,24}") || !sandbox.linkSignatureMatches(orderNo, sig)) {
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
        }
        return orders.findByNo(orderNo)
                .flatMap(order -> {
                    PaymentProvider.EventType type = "success".equals(result)
                            ? PaymentProvider.EventType.PAYMENT_SUCCEEDED : PaymentProvider.EventType.PAYMENT_FAILED;
                    SandboxPaymentProvider.SignedNotification note = sandbox.notification(type, order.orderNo(), order.amountMinor(),
                            order.currency(), order.providerRef());
                    HttpHeaders headers = new HttpHeaders();
                    headers.add(SandboxPaymentProvider.SIGNATURE_HEADER, note.signature());
                    return webhooks.handle(SandboxPaymentProvider.NAME, note.body(), headers)
                            .map(handled -> html(TITLE, DEMO_NOTICE + "<p>Bildiriş göndərildi. Sayta qayıdın: sifarişin statusu orada "
                                    + "görünəcək.</p>"));
                })
                .defaultIfEmpty(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    private static String form(String orderNo, String sig, String result, String label) {
        return "<form method=\"post\" action=\"/api/payments/sandbox/checkout/" + orderNo + "/complete?sig=" + sig + "&result=" + result
                + "\" style=\"display:inline-block;margin-right:12px\"><button type=\"submit\" style=\"padding:10px 18px;font-size:15px\">"
                + label + "</button></form>";
    }

    private static String money(int minor) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.ROOT);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        format.setGroupingUsed(false);
        return format.format(minor / 100.0).replace('.', ',');
    }

    private static ResponseEntity<String> html(String title, String body) {
        return ResponseEntity.ok().contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                .header("Cache-Control", "no-store")
                .body("<!DOCTYPE html><html lang=\"az\"><head><meta charset=\"UTF-8\"><title>" + title + "</title></head>"
                        + "<body style=\"font-family:Arial,sans-serif;max-width:560px;margin:40px auto;padding:0 16px;color:#1c2552\">"
                        + "<h1 style=\"font-size:34px;letter-spacing:1px\">" + title + "</h1>"
                        + body + "</body></html>");
    }
}
