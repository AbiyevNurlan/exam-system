package com.examportal.common.domain.entity;

/**
 * How a user became a member of a group
 */
public enum GroupMemberSource {
    /** Added by an administrator, one at a time */
    ADMIN,
    /** Added by a CSV import, directly or through a pending invite */
    CSV,
    /** Added by an administrator in bulk: everyone matching a filter, or a page selection */
    BULK,
    /** Joined with the group's link, directly or after an administrator approved the request */
    LINK
}