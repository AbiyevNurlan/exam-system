package com.examportal.common.domain.entity;

/**
 * Media Status
 * PENDING: an upload URL was issued, the content is not verified yet.
 * ACTIVE: the stored file was checked (size, magic bytes) and can be attached.
 * DELETED: soft-deleted; the object is removed from the storage.
 */
public enum MediaStatus {
    PENDING,
    ACTIVE,
    DELETED
}
