package com.examportal.common.domain.dto.internal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * User Lookup Response DTO (service to service)
 * Only the users that exist and are active; unknown emails and ids are simply absent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLookupResponse {

    private List<UserRef> users;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserRef {
        private UUID id;
        /** Normalized (trimmed, lower case) */
        private String email;
        private boolean emailVerified;
    }
}