package com.examportal.paid.payment;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * payment_events (V24), append only: a row is written once, with what was decided (outcome), and never changed.
 */
@Repository
@RequiredArgsConstructor
public class PaymentEventRepository {

    public record PaymentEvent(Long id, Long orderId, String provider, String providerEventId, String eventType, boolean signatureValid,
                               Integer amountMinor, String currency, String outcome, LocalDateTime createdAt) {
    }

    private final DatabaseClient databaseClient;

    /**
     * Writes an event; empty when the provider's event id is there already among valid events (the same notification
     * twice). Part of the caller's transaction.
     */
    public Mono<Long> insert(Long orderId, String provider, String providerEventId, String eventType, boolean signatureValid,
                             Integer amountMinor, String currency, String outcome, String payload) {
        DatabaseClient.GenericExecuteSpec sql = databaseClient.sql("INSERT INTO payment_events (order_id, provider, provider_event_id, event_type, "
                        + "signature_valid, amount_minor, currency, outcome, payload) VALUES (:order, :provider, :eventId, :type, :valid, :amount, "
                        + ":currency, :outcome, :payload) ON CONFLICT (provider, provider_event_id) WHERE signature_valid AND provider_event_id IS NOT NULL "
                        + "DO NOTHING RETURNING id")
                .bind("provider", provider)
                .bind("type", eventType)
                .bind("valid", signatureValid);
        sql = orderId == null ? sql.bindNull("order", Long.class) : sql.bind("order", orderId);
        sql = providerEventId == null ? sql.bindNull("eventId", String.class) : sql.bind("eventId", providerEventId);
        sql = amountMinor == null ? sql.bindNull("amount", Integer.class) : sql.bind("amount", amountMinor);
        sql = currency == null ? sql.bindNull("currency", String.class) : sql.bind("currency", currency);
        sql = outcome == null ? sql.bindNull("outcome", String.class) : sql.bind("outcome", outcome);
        sql = payload == null ? sql.bindNull("payload", String.class) : sql.bind("payload", payload);
        return sql.map(row -> row.get(0, Long.class)).one();
    }

    public Flux<PaymentEvent> findOfOrder(Long orderId) {
        return databaseClient.sql("SELECT id, order_id, provider, provider_event_id, event_type, signature_valid, amount_minor, currency, "
                        + "outcome, created_at FROM payment_events WHERE order_id = :order ORDER BY created_at, id")
                .bind("order", orderId)
                .map(row -> new PaymentEvent(row.get("id", Long.class), row.get("order_id", Long.class), row.get("provider", String.class),
                        row.get("provider_event_id", String.class), row.get("event_type", String.class),
                        Boolean.TRUE.equals(row.get("signature_valid", Boolean.class)), row.get("amount_minor", Integer.class),
                        row.get("currency", String.class), row.get("outcome", String.class), row.get("created_at", LocalDateTime.class)))
                .all();
    }
}
