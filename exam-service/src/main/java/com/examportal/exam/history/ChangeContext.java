package com.examportal.exam.history;

import java.util.UUID;

/**
 * Who changes an exam, from where and why (B12a): written into every history row of one save. saveId
 * groups the rows of one request ("Saxla"). actorName and actorCode are what auth-service knew of the
 * actor at that moment (null when it could not be asked); ip and userAgent come from the request.
 * reason: changeReason of the request, already checked (3-500 characters) or null.
 */
public record ChangeContext(UUID actorId, String role, String actorName, Integer actorCode, String ip, String userAgent,
                            String requestId, String reason, UUID saveId) {

    /**
     * A context without request details (services called from code, tests)
     */
    public static ChangeContext of(UUID actorId, String role) {
        return new ChangeContext(actorId, role, null, null, null, null, null, null, UUID.randomUUID());
    }

    public ChangeContext withReason(String reason) {
        return new ChangeContext(actorId, role, actorName, actorCode, ip, userAgent, requestId, reason, saveId);
    }
}
