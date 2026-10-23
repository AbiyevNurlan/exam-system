package com.examportal.paid.payment;

import com.examportal.common.domain.entity.ExamStatus;
import com.examportal.paid.exam.PaidExamRepository;
import com.examportal.paid.exam.PaidExamSettings;
import com.examportal.paid.guest.GuestDirectory;
import com.examportal.paid.order.Order;
import com.examportal.paid.order.OrderStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B13b item 12: a paid order asks auth-service for a receipt (RECEIPT, order number, exam title, amount, the payment
 * time), a refunded one for a notice (REFUND, the refund time); auth-service not answering never fails the caller.
 */
class PaidNotificationsTest {

    private static Order order(LocalDateTime paidAt, LocalDateTime refundedAt) {
        return new Order(9L, "YK-261023-000009", UUID.randomUUID(), 4_141_592, 3L, 2500, "AZN", OrderStatus.PAID, "SANDBOX", "sbx",
                true, null, null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now(), paidAt, null, refundedAt);
    }

    @Test
    void receiptsAndRefundNoticesGoToAuthService() {
        GuestDirectory directory = mock(GuestDirectory.class);
        PaidExamRepository exams = mock(PaidExamRepository.class);
        when(exams.find(3L)).thenReturn(Mono.just(new PaidExamSettings(3L, "Kimya", ExamStatus.SCHEDULED, UUID.randomUUID(), null, null,
                "PAID_OPEN", 2500, "AZN", null, 0, null, null, 24, 1)));
        when(directory.notice(any(), any())).thenReturn(Mono.empty());
        PaidNotifications notifications = new PaidNotifications(directory, exams);
        LocalDateTime paidAt = LocalDateTime.of(2026, 10, 23, 9, 15);
        Order order = order(paidAt, LocalDateTime.of(2026, 10, 24, 10, 0));

        notifications.paid(order).block();
        notifications.refunded(order).block();
        ArgumentCaptor<GuestDirectory.Notice> sent = ArgumentCaptor.forClass(GuestDirectory.Notice.class);
        verify(directory, org.mockito.Mockito.times(2)).notice(eq(order.guestId()), sent.capture());
        assertThat(sent.getAllValues().get(0)).isEqualTo(new GuestDirectory.Notice("RECEIPT", "YK-261023-000009", "Kimya", 2500, "AZN", paidAt));
        assertThat(sent.getAllValues().get(1).kind()).isEqualTo("REFUND");
        assertThat(sent.getAllValues().get(1).at()).isEqualTo(LocalDateTime.of(2026, 10, 24, 10, 0));

        when(directory.notice(any(), any())).thenReturn(Mono.error(new IllegalStateException("auth-service down")));
        notifications.paid(order).block();
    }
}
