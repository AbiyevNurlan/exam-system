package com.examportal.common.domain.dto.group;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Add Group Member Response DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AddGroupMemberResponse {

    public enum Status {
        /** The user is now a member */
        ADDED,
        /** The user was a member already; nothing changed */
        ALREADY_MEMBER,
        /** No verified account has this email yet; it becomes a member once one does */
        INVITED,
        /** The email was invited already; nothing changed */
        ALREADY_INVITED
    }

    private Status status;
    private UUID userId;
}