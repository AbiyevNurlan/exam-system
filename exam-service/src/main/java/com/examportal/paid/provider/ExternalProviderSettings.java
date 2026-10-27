package com.examportal.paid.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * B13b item 6c: the settings of a real payment provider, read from the environment only (never from a file in the
 * repository, never logged): PAYMENTS_EXTERNAL_NAME, _BASE_URL, _MERCHANT_ID, _API_KEY, _WEBHOOK_SECRET. No
 * implementation exists yet: a real provider is a PaymentProvider named EXTERNAL that uses these, and
 * PAYMENTS_PROVIDER=EXTERNAL selects it. configured() only says whether everything is there; toString never shows a key.
 */
@Component
public class ExternalProviderSettings {

    private final String name;
    private final String baseUrl;
    private final String merchantId;
    private final String apiKey;
    private final String webhookSecret;

    public ExternalProviderSettings(@Value("${payments.external.name:}") String name,
                                    @Value("${payments.external.base-url:}") String baseUrl,
                                    @Value("${payments.external.merchant-id:}") String merchantId,
                                    @Value("${payments.external.api-key:}") String apiKey,
                                    @Value("${payments.external.webhook-secret:}") String webhookSecret) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.merchantId = merchantId;
        this.apiKey = apiKey;
        this.webhookSecret = webhookSecret;
    }

    public boolean configured() {
        return !blank(name) && !blank(baseUrl) && !blank(merchantId) && !blank(apiKey) && !blank(webhookSecret);
    }

    public String name() {
        return name;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String merchantId() {
        return merchantId;
    }

    /** For the implementation only; never to be logged */
    public String apiKey() {
        return apiKey;
    }

    /** For the implementation only; never to be logged */
    public String webhookSecret() {
        return webhookSecret;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public String toString() {
        return "ExternalProviderSettings{name=" + name + ", baseUrl=" + baseUrl + ", configured=" + configured() + "}";
    }
}
