package com.examportal.exam.service;

import java.util.UUID;

/**
 * Ownership Policy (B13)
 * The one rule of who sees what a teacher made, shared by every policy, list and counter of exam-service:
 *
 * - ADMIN: everything (owner filter null).
 * - INSTRUCTOR: only what they own: exams they created (exams.created_by), groups they own
 *   (user_groups.owner_id), media they uploaded (media_assets.owner_id), and through those the sessions,
 *   results, answers, grading, monitoring, analytics, history and audit rows that belong to them.
 * - Anybody else: nothing of that kind (an owner no row has, so a list is empty and a lookup is a 404).
 *
 * Something of another owner is answered exactly like something that does not exist (the same 404 with the
 * same body), never with a 403, so a teacher cannot even learn that it exists. Lookups by id put the owner in
 * the SQL condition and lists filter in SQL, never in memory. A new endpoint is classified in the endpoint
 * registry (EndpointRegistry) or the coverage test fails.
 */
public final class OwnershipPolicy {

    public static final String ADMIN = "ADMIN";
    public static final String INSTRUCTOR = "INSTRUCTOR";

    /** An owner no row has: what a role without ownership is limited to */
    public static final UUID NOBODY = new UUID(0L, 0L);

    private OwnershipPolicy() {
    }

    public static boolean isAdmin(String role) {
        return ADMIN.equals(role);
    }

    public static boolean isInstructor(String role) {
        return INSTRUCTOR.equals(role);
    }

    /**
     * The owner a lookup or a list is limited to: null for ADMIN (every owner), the caller for INSTRUCTOR,
     * NOBODY for anybody else
     */
    public static UUID ownerFilter(UUID userId, String role) {
        if (isAdmin(role)) {
            return null;
        }
        return isInstructor(role) && userId != null ? userId : NOBODY;
    }

    /**
     * Whether the caller owns (or, as ADMIN, may act for the owner of) something owned by owner
     */
    public static boolean owns(UUID userId, String role, UUID owner) {
        if (isAdmin(role)) {
            return true;
        }
        return isInstructor(role) && userId != null && userId.equals(owner);
    }
}
