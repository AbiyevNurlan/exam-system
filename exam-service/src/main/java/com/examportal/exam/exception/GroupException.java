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

    public static GroupException userDirectoryUnavailable(Throwable cause) {
        return new GroupException("User lookup is currently unavailable", HttpStatus.SERVICE_UNAVAILABLE,
                "USER_DIRECTORY_UNAVAILABLE", cause);
    }
}