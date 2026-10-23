package com.examportal.guest;

import com.examportal.common.utils.LogMaskUtils;
import com.examportal.guest.security.GuestRefreshTokens;
import com.examportal.guest.web.GuestDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * B13a item 5: what an administrator does with guests: the list, the card, block and unblock. A block ends every
 * sign-in of the guest at once (refresh tokens revoked) and every request of an access token still running is refused
 * (GuestService reads the guest each time). The accounts' participant list (/api/admin/participants) is not changed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestAdminService {

    private final GuestAdminRepository list;
    private final GuestRepository guests;
    private final GuestRefreshTokens refreshTokens;
    private final TransactionalOperator transactionalOperator;
    private final GuestAuditLog audit;

    public Mono<GuestDtos.AdminGuestPage> page(String q, String sortBy, String dir, int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), GuestAdminRepository.MAX_PAGE_SIZE);
        int pageNumber = Math.max(page, 0);
        return list.findPage(q, sortBy, dir, pageNumber, pageSize)
                .map(found -> new GuestDtos.AdminGuestPage(found.guests().stream().map(GuestDtos.AdminGuestRow::of).toList(),
                        found.total(), pageNumber, pageSize));
    }

    public Mono<Guest> card(UUID id) {
        return guests.findById(id).switchIfEmpty(Mono.error(GuestErrors.notFound()));
    }

    public Mono<Guest> block(UUID adminId, UUID guestId, GuestDtos.BlockRequest request) {
        String reason = request.reason().strip();
        boolean blocked = Boolean.TRUE.equals(request.blocked());
        Mono<Guest> change = guests.findById(guestId)
                .switchIfEmpty(Mono.error(GuestErrors.notFound()))
                .flatMap(guest -> guests.setBlocked(guestId, blocked, reason))
                .flatMap(guest -> blocked ? refreshTokens.revokeAll(guestId).thenReturn(guest) : Mono.just(guest))
                .flatMap(guest -> audit.accountCode(adminId).map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty())
                        .flatMap(code -> audit.record(blocked ? GuestAuditLog.BLOCKED : GuestAuditLog.UNBLOCKED,
                                GuestAuditLog.Actor.admin(adminId, code.orElse(null)), guest, null, "reason: " + reason))
                        .thenReturn(guest))
                .doOnNext(guest -> log.info("Guest {} ({}) {} by {}", guest.participantCode(), LogMaskUtils.maskEmail(guest.email()),
                        blocked ? "blocked" : "unblocked", adminId));
        return transactionalOperator.transactional(change);
    }
}
