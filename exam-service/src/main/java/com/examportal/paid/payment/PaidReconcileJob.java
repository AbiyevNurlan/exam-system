package com.examportal.paid.payment;

import com.examportal.paid.PaidFeature;
import com.examportal.paid.order.Order;
import com.examportal.paid.order.OrderRepository;
import com.examportal.paid.provider.PaymentProvider;
import com.examportal.paid.provider.PaymentProviders;
import io.r2dbc.spi.Connection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * B13b item 8: every 5 minutes (payments.reconcile.cron), only while PAID_ACCESS_ENABLED:
 * 1. every PENDING order whose time is over becomes EXPIRED and gives its seat back (one statement);
 * 2. the providers of PENDING orders older than 10 minutes are asked what they know (a notification that never came):
 *    PAID and FAILED are handled exactly as their notification would be (same event id, so never twice).
 * Several instances never run it at the same time: a PostgreSQL advisory lock taken with pg_try_advisory_lock on one
 * connection, held for the run and given back on the same connection; an instance that does not get it skips the run.
 */
@Slf4j
@Component
public class PaidReconcileJob {

    static final long LOCK_KEY = 0x0B13B_5EC0L;
    private static final int BATCH = 200;

    private final PaidFeature feature;
    private final DatabaseClient databaseClient;
    private final OrderRepository orders;
    private final PaymentProviders providers;
    private final PaymentWebhookService webhooks;
    private final PaidNotifications notifications;
    private final TransactionalOperator transactionalOperator;

    @Value("${payments.reconcile.pending-older-than-minutes:10}")
    private int pendingOlderThanMinutes = 10;

    public PaidReconcileJob(PaidFeature feature, DatabaseClient databaseClient, OrderRepository orders, PaymentProviders providers,
                            PaymentWebhookService webhooks, PaidNotifications notifications, TransactionalOperator transactionalOperator) {
        this.feature = feature;
        this.databaseClient = databaseClient;
        this.orders = orders;
        this.providers = providers;
        this.webhooks = webhooks;
        this.notifications = notifications;
        this.transactionalOperator = transactionalOperator;
    }

    /** What one run did; ran is false when another instance held the lock (or the feature is off) */
    public record Summary(boolean ran, int expired, int asked, int settled) {
    }

    @Scheduled(cron = "${payments.reconcile.cron:0 */5 * * * *}")
    public void scheduled() {
        runOnce().subscribe(summary -> {
            if (summary.ran() && (summary.expired() > 0 || summary.settled() > 0)) {
                log.info("Paid reconcile: {} expired, {} asked, {} settled by their providers", summary.expired(), summary.asked(), summary.settled());
            }
        }, error -> log.warn("Paid reconcile failed: {}", error.getClass().getSimpleName()));
    }

    public Mono<Summary> runOnce() {
        if (!feature.isEnabled()) {
            return Mono.just(new Summary(false, 0, 0, 0));
        }
        return databaseClient.inConnection(connection -> tryLock(connection)
                .flatMap(locked -> locked
                        ? work().flatMap(summary -> unlock(connection).thenReturn(summary))
                        .onErrorResume(error -> unlock(connection).then(Mono.error(error)))
                        : Mono.just(new Summary(false, 0, 0, 0))));
    }

    private Mono<Summary> work() {
        Mono<Integer> expired = transactionalOperator.transactional(orders.expireStale(null).collectList())
                .flatMap(list -> Flux.fromIterable(list).concatMap(order -> notifications.event(order, "ORDER_EXPIRED", null)).then(Mono.just(list.size())));
        return expired.flatMap(count -> orders.findPendingOlderThan(LocalDateTime.now().minusMinutes(pendingOlderThanMinutes), BATCH)
                .concatMap(this::ask)
                .collectList()
                .map(results -> new Summary(true, count, results.size(), (int) results.stream().filter(Boolean::booleanValue).count())));
    }

    /** Asks the order's provider; true when the order was settled by its answer */
    private Mono<Boolean> ask(Order order) {
        PaymentProvider provider = providers.byName(order.provider()).filter(PaymentProvider::available).orElse(null);
        if (provider == null) {
            return Mono.just(false);
        }
        return provider.fetchStatus(order)
                .flatMap(status -> {
                    PaymentProvider.EventType type = switch (status.state()) {
                        case PAID -> PaymentProvider.EventType.PAYMENT_SUCCEEDED;
                        case FAILED -> PaymentProvider.EventType.PAYMENT_FAILED;
                        default -> null;
                    };
                    if (type == null) {
                        return Mono.just(false);
                    }
                    String eventId = status.eventId() != null ? status.eventId() : "reconcile-" + order.orderNo() + "-" + status.state();
                    return webhooks.process(provider.name(), new PaymentProvider.WebhookEvent(eventId, type, order.orderNo(),
                                    status.amountMinor(), status.currency(), order.providerRef()), null)
                            .map(result -> result.outcome() != PaymentWebhookService.Outcome.DUPLICATE);
                })
                .onErrorResume(error -> {
                    log.warn("Paid reconcile: provider {} not reachable for {}: {}", provider.name(), order.orderNo(), error.getClass().getSimpleName());
                    return Mono.just(false);
                });
    }

    private static Mono<Boolean> tryLock(Connection connection) {
        return Mono.from(connection.createStatement("SELECT pg_try_advisory_lock(" + LOCK_KEY + ")").execute())
                .flatMap(result -> Mono.from(result.map((row, meta) -> Boolean.TRUE.equals(row.get(0, Boolean.class)))));
    }

    private static Mono<Void> unlock(Connection connection) {
        return Mono.from(connection.createStatement("SELECT pg_advisory_unlock(" + LOCK_KEY + ")").execute())
                .flatMap(result -> Mono.from(result.map((row, meta) -> Boolean.TRUE)))
                .then();
    }
}
