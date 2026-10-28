package com.examportal.paid.order;

import com.examportal.paid.PaidErrors;
import com.examportal.paid.exam.PaidExamRepository;
import com.examportal.paid.guest.GuestDirectory;
import com.examportal.paid.provider.PaymentProvider;
import com.examportal.paid.provider.PaymentProviders;
import com.examportal.paid.security.PaidGuest;
import com.examportal.paid.web.PaidDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * B13b item 5: a guest's orders. POST makes one (idempotent: the open PENDING order of the same exam is the answer
 * again, with its checkout), refused for a blocked guest (403), an exam not on sale (409 SALES_CLOSED), a guest who
 * may take it already (409 ALREADY_ENTITLED) and when no seat is left (409 SOLD_OUT). When the provider cannot make
 * the checkout, the order fails and gives its seat back (502). A guest sees only the own orders: another's is the same
 * 404 as an unknown one. The status page of the browser asks GET /{id}; it never decides anything.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestOrderService {

    public static final int MAX_LIST = 100;

    private final GuestDirectory directory;
    private final PaymentProviders providers;
    private final SeatReservation seats;
    private final OrderRepository orders;
    private final PaidExamRepository exams;
    private final TransactionalOperator transactionalOperator;
    private final com.examportal.paid.payment.PaidNotifications notifications;

    public Mono<PaidDtos.OrderView> create(PaidGuest guest, Long examId) {
        return directory.active(guest.id())
                .flatMap(known -> providers.forCheckout()
                        .flatMap(provider -> seats.reserve(known.id(), known.participantCode(), examId, provider.name())
                                .flatMap(reservation -> reservation.created()
                                        ? checkout(provider, reservation.order())
                                        .flatMap(view -> notifications.event(reservation.order(), "ORDER_CREATED", null).thenReturn(view))
                                        : provider.resumeCheckout(reservation.order())
                                        .flatMap(checkout -> view(reservation.order(), checkout.checkoutUrl())))));
    }

    private Mono<PaidDtos.OrderView> checkout(PaymentProvider provider, Order order) {
        return provider.createCheckout(order)
                .flatMap(checkout -> orders.setProviderRef(order.id(), checkout.providerRef())
                        .flatMap(saved -> view(saved, checkout.checkoutUrl())))
                .onErrorResume(error -> !(error instanceof com.examportal.common.exception.ApplicationException), error -> {
                    log.warn("Checkout of order {} failed at {}: {}", order.orderNo(), provider.name(), error.getClass().getSimpleName());
                    return transactionalOperator.transactional(orders.move(order.id(), OrderStatus.FAILED, null)
                                    .then(orders.releaseSeat(order.id())))
                            .then(Mono.error(PaidErrors.providerError()));
                });
    }

    public Mono<List<PaidDtos.OrderView>> list(PaidGuest guest) {
        return orders.findOwnAll(guest.id(), MAX_LIST).concatMap(order -> view(order, null)).collectList();
    }

    public Mono<PaidDtos.OrderView> get(PaidGuest guest, Long orderId) {
        return orders.findOwn(orderId, guest.id())
                .switchIfEmpty(Mono.error(PaidErrors.orderNotFound()))
                .flatMap(order -> order.status() == OrderStatus.PENDING
                        ? providers.byName(order.provider()).filter(PaymentProvider::available)
                        .map(provider -> provider.resumeCheckout(order).map(PaymentProvider.Checkout::checkoutUrl).onErrorResume(e -> Mono.empty()))
                        .orElse(Mono.empty())
                        .flatMap(url -> view(order, url))
                        .switchIfEmpty(Mono.defer(() -> view(order, null)))
                        : view(order, null));
    }

    Mono<PaidDtos.OrderView> view(Order order, String checkoutUrl) {
        String url = order.status() == OrderStatus.PENDING ? checkoutUrl : null;
        return exams.find(order.examId())
                .map(exam -> PaidDtos.OrderView.of(order, exam.title(), url, exam.startTime(), exam.endTime()))
                .defaultIfEmpty(PaidDtos.OrderView.of(order, "", url));
    }
}
