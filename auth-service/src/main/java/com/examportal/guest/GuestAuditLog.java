package com.examportal.guest;

import com.examportal.common.utils.LogMaskUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * B13a item 6: one row of guest_audit_log (V12) per event of a guest, part of the caller's transaction when there is
 * one. The email is stored masked, never a code, a token or the value of a field.
 */
@Component
@RequiredArgsConstructor
public class GuestAuditLog {

    public static final String OTP_REQUESTED = "GUEST_OTP_REQUESTED";
    public static final String OTP_VERIFIED = "GUEST_OTP_VERIFIED";
    public static final String CREATED = "GUEST_CREATED";
    public static final String LOGIN = "GUEST_LOGIN";
    public static final String UPDATED = "GUEST_UPDATED";
    public static final String BLOCKED = "GUEST_BLOCKED";
    public static final String UNBLOCKED = "GUEST_UNBLOCKED";

    public enum ActorKind { ANONYMOUS, GUEST, ADMIN }

    /** Who did it: nobody known yet (before the sign-in), the guest, or an administrator */
    public record Actor(ActorKind kind, UUID id, Integer code) {

        public static Actor anonymous() {
            return new Actor(ActorKind.ANONYMOUS, null, null);
        }

        public static Actor guest(Guest guest) {
            return new Actor(ActorKind.GUEST, guest.id(), guest.participantCode());
        }

        public static Actor admin(UUID id, Integer code) {
            return new Actor(ActorKind.ADMIN, id, code);
        }
    }

    private final DatabaseClient databaseClient;

    /** target may be null (a code asked for an email that is no guest yet) */
    public Mono<Void> record(String action, Actor actor, Guest target, String email, String details) {
        DatabaseClient.GenericExecuteSpec insert = databaseClient.sql("INSERT INTO guest_audit_log (action, actor_kind, actor_id, actor_code, "
                        + "target_guest_id, target_code, email_masked, details) "
                        + "VALUES (:action, :actorKind, :actorId, :actorCode, :targetId, :targetCode, :email, :details)")
                .bind("action", action)
                .bind("actorKind", actor.kind().name());
        insert = actor.id() == null ? insert.bindNull("actorId", UUID.class) : insert.bind("actorId", actor.id());
        insert = actor.code() == null ? insert.bindNull("actorCode", Integer.class) : insert.bind("actorCode", actor.code());
        insert = target == null ? insert.bindNull("targetId", UUID.class).bindNull("targetCode", Integer.class)
                : insert.bind("targetId", target.id()).bind("targetCode", target.participantCode());
        String masked = LogMaskUtils.maskEmail(email != null ? email : target != null ? target.email() : null);
        insert = masked == null ? insert.bindNull("email", String.class) : insert.bind("email", masked);
        insert = details == null ? insert.bindNull("details", String.class) : insert.bind("details", details);
        return insert.then();
    }

    /** The 7-digit code of the administrator's account, for actor_code; null when the account is gone */
    public Mono<Integer> accountCode(UUID accountId) {
        return databaseClient.sql("SELECT participant_code FROM users WHERE id = :id")
                .bind("id", accountId)
                .map(row -> row.get(0, Integer.class))
                .one();
    }
}
