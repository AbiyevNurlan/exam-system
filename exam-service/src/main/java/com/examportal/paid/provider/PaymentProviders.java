package com.examportal.paid.provider;

import com.examportal.paid.PaidErrors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * B13b item 6: the providers by name, and the one that makes the checkout of a new order (PAYMENTS_PROVIDER, default
 * SANDBOX; it must be available: the sandbox only with PAYMENTS_SANDBOX_ALLOWED, otherwise 503
 * PAYMENT_PROVIDER_UNAVAILABLE and no order is made).
 */
@Component
public class PaymentProviders {

    private final List<PaymentProvider> providers;
    private final String checkoutProvider;

    public PaymentProviders(List<PaymentProvider> providers, @Value("${payments.provider:SANDBOX}") String checkoutProvider) {
        this.providers = providers;
        this.checkoutProvider = checkoutProvider == null ? "" : checkoutProvider.trim().toUpperCase(Locale.ROOT);
    }

    public Optional<PaymentProvider> byName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String wanted = name.trim().toUpperCase(Locale.ROOT);
        return providers.stream().filter(provider -> provider.name().equals(wanted)).findFirst();
    }

    /** The provider of new orders, when it may be used now */
    public Mono<PaymentProvider> forCheckout() {
        return Mono.justOrEmpty(byName(checkoutProvider).filter(PaymentProvider::available)
                        .filter(provider -> !ManualPaymentProvider.NAME.equals(provider.name())))
                .switchIfEmpty(Mono.error(PaidErrors.providerUnavailable()));
    }
}
