package com.examportal.exam.exception;

import com.examportal.common.exception.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * Group-specific exceptions
 */
public class GroupException extends ApplicationException {

    public GroupException(String message, HttpStatus status, String errorCode) {
        super(message, status, errorCode);
    }

    public GroupException(String message, HttpStatus status, String errorCode, Throwable cause) {
        super(message, status, errorCode, cause);
    }

    public static GroupException notFound(Long groupId) {
        return new GroupException("Group not found with ID: " + groupId, HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
    }

    public static GroupException nameExists() {
        return new GroupException("A group with this name already exists", HttpStatus.CONFLICT, "GROUP_NAME_EXISTS");
    }

    public static GroupException memberNotFound() {
        return new GroupException("The user is not a member of this group", HttpStatus.NOT_FOUND, "GROUP_MEMBER_NOT_FOUND");
    }

    public static GroupException inviteNotFound() {
        return new GroupException("The group has no such pending invite", HttpStatus.NOT_FOUND, "GROUP_INVITE_NOT_FOUND");
    }

    public static GroupException userNotFound() {
        return new GroupException("User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
    }

    public static GroupException memberReferenceRequired() {
        return new GroupException("Exactly one of userId and email is required", HttpStatus.BAD_REQUEST,
                "GROUP_MEMBER_REFERENCE_REQUIRED");
    }

    public static GroupException invalidSearch() {
        return new GroupException("Name filter must not exceed 100 characters", HttpStatus.BAD_REQUEST,
                "GROUP_SEARCH_INVALID");
    }

    public static GroupException audienceGroupsRequired() {
        return new GroupException("A GROUP exam needs at least one audience group", HttpStatus.BAD_REQUEST,
                "AUDIENCE_GROUPS_REQUIRED");
    }

    public static GroupException audienceGroupNotFound() {
        return new GroupException("One or more audience groups do not exist", HttpStatus.BAD_REQUEST,
                "AUDIENCE_GROUP_NOT_FOUND");
    }

    public static GroupException csvTooManyRows(int maxRows) {
        return new GroupException("The file has more than " + maxRows + " rows", HttpStatus.BAD_REQUEST,
                "CSV_TOO_MANY_ROWS");
    }

    /**
     * The number of participants a bulk addition would add is no longer what the administrator saw
     * in the dry run; nothing was written. error.details carries the current figures.
     */
    public static GroupException filterCountChanged(long matched, long alreadyMembers, long willAdd) {
        GroupException exception = new GroupException("The participants matching the filter have changed; review them again",
                HttpStatus.CONFLICT, "FILTER_COUNT_CHANGED");
        exception.withDetails(java.util.Map.of("matched", matched, "alreadyMembers", alreadyMembers, "willAdd", willAdd));
        return exception;
    }

    public static GroupException tooManyMatches(int cap) {
        GroupException exception = new GroupException("More than " + cap + " participants would be added; narrow the filter",
                HttpStatus.UNPROCESSABLE_ENTITY, "TOO_MANY_MATCHES");
        exception.withDetails(java.util.Map.of("cap", cap));
        return exception;
    }

    /**
     * One answer for a join code that is unknown, switched off, expired or used up: the caller
     * learns nothing about why
     */
    public static GroupException joinCodeInvalid() {
        return new GroupException("This join link is not valid", HttpStatus.NOT_FOUND, "JOIN_CODE_INVALID");
    }

    public static GroupException joinLinkNotFound() {
        return new GroupException("The group has no join link", HttpStatus.NOT_FOUND, "JOIN_LINK_NOT_FOUND");
    }

    public static GroupException joinRequestNotFound() {
        return new GroupException("The group has no such open join request", HttpStatus.NOT_FOUND, "JOIN_REQUEST_NOT_FOUND");
    }

    /**
     * The user's last request to this group was rejected less than the cooldown ago;
     * error.details.canRequestAgainAt says from when (UTC)
     */
    public static GroupException joinRequestCooldown(java.time.LocalDateTime canRequestAgainAt) {
        GroupException exception = new GroupException("Your request to join this group was rejected recently; "
                + "you can ask again later", HttpStatus.CONFLICT, "JOIN_REQUEST_COOLDOWN");
        exception.withDetails(java.util.Map.of("canRequestAgainAt", canRequestAgainAt));
        return exception;
    }

    public static GroupException emailNotVerified() {
        return new GroupException("Verify your email address before joining a group", HttpStatus.FORBIDDEN,
                "EMAIL_NOT_VERIFIED");
    }

    public static GroupException participantsOnly() {
        return new GroupException("Only participants can join a group with a link", HttpStatus.FORBIDDEN,
                "JOIN_PARTICIPANTS_ONLY");
    }

    public static GroupException userDirectoryUnavailable(Throwable cause) {
        return new GroupException("User lookup is currently unavailable", HttpStatus.SERVICE_UNAVAILABLE,
                "USER_DIRECTORY_UNAVAILABLE", cause);
    }
}