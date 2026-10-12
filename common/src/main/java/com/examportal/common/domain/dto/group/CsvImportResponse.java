package com.examportal.common.domain.dto.group;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * CSV Import Response DTO
 * The same figures for a dry run (nothing stored) and a real run.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CsvImportResponse {

    private boolean dryRun;

    /** Data rows read (blank lines and the header are not counted) */
    private int totalRows;

    /** Verified accounts that are (dry run: would be) added as members */
    private int matched;

    /** Emails without a verified account that are (dry run: would be) stored as pending invites */
    private int pendingInvite;

    /** Verified accounts that are members already */
    private int alreadyMember;

    /** Emails without a verified account that are invited already */
    private int alreadyInvited;

    /** Rows that are not an email address */
    private int invalid;

    /** Repeated rows of an email that appears earlier in the file */
    private int duplicates;

    /** The first invalid rows, at most 100 */
    private List<InvalidRow> invalidRows;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvalidRow {
        /** Line number in the file, starting at 1 */
        private int line;
        /** The rejected value, shortened to 100 characters */
        private String value;
    }
}