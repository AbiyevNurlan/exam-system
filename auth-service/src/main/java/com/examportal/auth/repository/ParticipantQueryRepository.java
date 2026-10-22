package com.examportal.auth.repository;

import com.examportal.common.domain.ParticipantCode;
import com.examportal.common.domain.dto.internal.ParticipantQuery;
import com.examportal.common.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Participant Query Repository
 * Participants (users with the role STUDENT) for the admin's participant list in exam-service:
 * filtered and paged here, in SQL, so that neither service ever holds the whole user table.
 * Every value is bound as a parameter; only fixed text is put into a statement.
 */
@Repository
@RequiredArgsConstructor
public class ParticipantQueryRepository {

    // A temporary lock that has run out is only cleared on the next login, so "locked" means still in force
    private static final String LOCKED_NOW =
            "(account_locked = true AND (locked_until IS NULL OR locked_until > LOCALTIMESTAMP))";

    private final DatabaseClient databaseClient;

    public Mono<ParticipantQuery.PageResponse> findPage(ParticipantQuery.PageRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = where(request.getFilter(), params);
        int pageNumber = Math.max(request.getPage(), 0);
        int pageSize = Math.min(Math.max(request.getSize(), 1), ParticipantQuery.MAX_PAGE_SIZE);
        String order = orderBy(request.getSort(), request.isAscending());

        DatabaseClient.GenericExecuteSpec count = bindAll(databaseClient.sql("SELECT COUNT(*) FROM users" + where), params);
        DatabaseClient.GenericExecuteSpec page = bindAll(databaseClient.sql(
                        "SELECT id, email, participant_code, first_name, last_name, COALESCE(email_verified, false) AS email_verified, "
                                + LOCKED_NOW + " AS blocked, "
                                + "CASE WHEN " + LOCKED_NOW + " THEN locked_until END AS locked_until, created_at "
                                + "FROM users" + where + " ORDER BY " + order + " LIMIT :limit OFFSET :offset"), params)
                .bind("limit", pageSize)
                .bind("offset", (long) pageNumber * pageSize);

        // B13: the count and the page run side by side (each on its own connection): a teacher's list binds up to
        // 20 000 ids to both, one after the other doubled the time
        Mono<List<ParticipantQuery.Participant>> users = page
                        .map(row -> ParticipantQuery.Participant.builder()
                                .id(row.get("id", UUID.class))
                                .email(row.get("email", String.class))
                                .participantCode(row.get("participant_code", Integer.class))
                                .firstName(row.get("first_name", String.class))
                                .lastName(row.get("last_name", String.class))
                                .emailVerified(Boolean.TRUE.equals(row.get("email_verified", Boolean.class)))
                                .blocked(Boolean.TRUE.equals(row.get("blocked", Boolean.class)))
                                .lockedUntil(row.get("locked_until", LocalDateTime.class))
                                .createdAt(row.get("created_at", LocalDateTime.class))
                                .build())
                        .all()
                        .collectList();

        return Mono.zip(count.map(row -> row.get(0, Long.class)).one(), users)
                .map(loaded -> ParticipantQuery.PageResponse.builder().total(loaded.getT1()).users(loaded.getT2()).build());
    }

    /**
     * How many participants match, and the ids of at most `limit` of them (newest first)
     */
    public Mono<ParticipantQuery.IdsResponse> findIds(ParticipantQuery.IdsRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = where(request.getFilter(), params);
        int limit = Math.min(Math.max(request.getLimit(), 1), ParticipantQuery.MAX_IDS);

        DatabaseClient.GenericExecuteSpec count = bindAll(databaseClient.sql("SELECT COUNT(*) FROM users" + where), params);
        DatabaseClient.GenericExecuteSpec ids = bindAll(databaseClient.sql(
                        "SELECT id FROM users" + where + " ORDER BY created_at DESC, id LIMIT :limit"), params)
                .bind("limit", limit);

        return count.map(row -> row.get(0, Long.class)).one()
                .flatMap(total -> ids.map(row -> row.get("id", UUID.class)).all().collectList()
                        .map(found -> ParticipantQuery.IdsResponse.builder().total(total).ids(found).build()));
    }

    /**
     * Counts over all participants, in one statement
     */
    public Mono<ParticipantQuery.SummaryResponse> summary(ParticipantQuery.SummaryRequest request) {
        UUID[] memberIds = request.getMemberIds() == null
                ? new UUID[0]
                : request.getMemberIds().toArray(new UUID[0]);
        return databaseClient.sql("SELECT COUNT(*) AS total, "
                        + "COUNT(*) FILTER (WHERE created_at >= :todayFrom) AS registered_today, "
                        + "COUNT(*) FILTER (WHERE COALESCE(email_verified, false) = false) AS unverified, "
                        + "COUNT(*) FILTER (WHERE id <> ALL(:memberIds)) AS no_group, "
                        + "COUNT(*) FILTER (WHERE " + LOCKED_NOW + " AND locked_until IS NOT NULL) AS temporarily_blocked, "
                        + "COUNT(*) FILTER (WHERE " + LOCKED_NOW + ") AS blocked "
                        + "FROM users WHERE role = 'STUDENT'")
                .bind("todayFrom", request.getTodayFrom())
                .bind("memberIds", memberIds)
                .map(row -> ParticipantQuery.SummaryResponse.builder()
                        .total(row.get("total", Long.class))
                        .registeredToday(row.get("registered_today", Long.class))
                        .unverified(row.get("unverified", Long.class))
                        .noGroup(row.get("no_group", Long.class))
                        .temporarilyBlocked(row.get("temporarily_blocked", Long.class))
                        .blocked(row.get("blocked", Long.class))
                        .build())
                .one();
    }

    // ==================== Private Helper Methods ====================

    // Names in Azerbaijani alphabetical order, ignoring case and the spaces around them (az_ci, V6)
    private static final String FIRST_NAME = "btrim(first_name) COLLATE az_ci";
    private static final String LAST_NAME = "btrim(last_name) COLLATE az_ci";

    /**
     * The ORDER BY of a page. Only fixed text from this switch is put into the statement (the key is a
     * whitelist, the direction one of two words); an unknown key is a 400.
     * Every order ends with participant_code (unique, never changes), so rows equal in the sort key keep one
     * place and no row repeats or goes missing between pages. Each order has its partial index (V7); names and
     * email are NOT NULL, a registration time without a value comes last in both directions.
     */
    static String orderBy(String sortKey, boolean ascending) {
        String d = ascending ? "ASC" : "DESC";
        String code = "participant_code " + d;
        String sort = sortKey == null ? "registeredat" : sortKey.trim().toLowerCase(Locale.ROOT);
        return switch (sort) {
            case "code" -> code;
            case "firstname" -> FIRST_NAME + " " + d + ", " + LAST_NAME + " " + d + ", " + code;
            case "lastname", "name" -> LAST_NAME + " " + d + ", " + FIRST_NAME + " " + d + ", " + code;
            // Addresses are ASCII: byte order of the lower-case text, the same on every platform
            case "email" -> "lower(email) COLLATE \"C\" " + d + ", " + code;
            case "registeredat", "createdat", "" -> "created_at " + d + " NULLS LAST, " + code;
            default -> throw new ValidationException("sortBy", "must be code, firstName, lastName, email or registeredAt");
        };
    }

    private String where(ParticipantQuery.Filter filter, Map<String, Object> params) {
        StringBuilder where = new StringBuilder(" WHERE role = 'STUDENT'");
        if (filter == null) {
            return where.toString();
        }
        if (StringUtils.hasText(filter.getQ())) {
            String term = filter.getQ().trim().toLowerCase(Locale.ROOT);
            if (term.length() < ParticipantQuery.MIN_SEARCH_LENGTH || term.length() > ParticipantQuery.MAX_SEARCH_LENGTH) {
                throw new ValidationException("q", "must be between " + ParticipantQuery.MIN_SEARCH_LENGTH
                        + " and " + ParticipantQuery.MAX_SEARCH_LENGTH + " characters");
            }
            // % and _ typed by the user are searched for as they are, not as wildcards (escape character '!')
            params.put("q", term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
            where.append(" AND (lower(first_name) LIKE :q ESCAPE '!' OR lower(last_name) LIKE :q ESCAPE '!'"
                    + " OR lower(first_name || ' ' || last_name) LIKE :q ESCAPE '!' OR lower(email) LIKE :q ESCAPE '!'");
            try {
                params.put("qId", UUID.fromString(term));
                where.append(" OR id = :qId");
            } catch (IllegalArgumentException notAnId) {
                // q is a name or an email
            }
            // Exactly a 7-digit participant code: that account (exact match; display and search only)
            Integer code = ParticipantCode.parse(term);
            if (code != null) {
                params.put("qCode", code);
                where.append(" OR participant_code = :qCode");
            }
            where.append(")");
        }
        // Separate searches, each on one field and all of them ANDed with q and the rest (the name box, the email box and
        // the code box of the list)
        if (StringUtils.hasText(filter.getName())) {
            String term = filter.getName().trim().toLowerCase(Locale.ROOT);
            if (term.length() < ParticipantQuery.MIN_SEARCH_LENGTH || term.length() > ParticipantQuery.MAX_SEARCH_LENGTH) {
                throw new ValidationException("name", "must be between " + ParticipantQuery.MIN_SEARCH_LENGTH
                        + " and " + ParticipantQuery.MAX_SEARCH_LENGTH + " characters");
            }
            params.put("name", term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
            where.append(" AND (lower(first_name) LIKE :name ESCAPE '!' OR lower(last_name) LIKE :name ESCAPE '!'"
                    + " OR lower(first_name || ' ' || last_name) LIKE :name ESCAPE '!')");
        }
        if (StringUtils.hasText(filter.getEmail())) {
            String term = filter.getEmail().trim().toLowerCase(Locale.ROOT);
            if (term.length() < ParticipantQuery.MIN_SEARCH_LENGTH || term.length() > ParticipantQuery.MAX_SEARCH_LENGTH) {
                throw new ValidationException("email", "must be between " + ParticipantQuery.MIN_SEARCH_LENGTH
                        + " and " + ParticipantQuery.MAX_SEARCH_LENGTH + " characters");
            }
            params.put("emailPrefix", term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
            where.append(" AND lower(email) LIKE :emailPrefix ESCAPE '!'");
        }
        if (StringUtils.hasText(filter.getCode())) {
            // The start of the 7-digit code as a range of numbers (the unique index of participant_code serves it):
            // 12 is every code from 1200000 to 1299999
            String digits = filter.getCode().trim();
            if (!digits.matches("[0-9]{2,7}")) {
                throw new ValidationException("code", "must be 2 to 7 digits");
            }
            long scale = 1;
            for (int i = digits.length(); i < 7; i++) {
                scale *= 10;
            }
            long start = Long.parseLong(digits);
            params.put("codeFrom", (int) (start * scale));
            params.put("codeTo", (int) ((start + 1) * scale));
            where.append(" AND participant_code >= :codeFrom AND participant_code < :codeTo");
        }
        if (filter.getVerified() != null) {
            where.append(filter.getVerified()
                    ? " AND email_verified = true"
                    : " AND COALESCE(email_verified, false) = false");
        }
        if (filter.getBlocked() != null) {
            where.append(filter.getBlocked() ? " AND " : " AND NOT ").append(LOCKED_NOW);
        }
        if (filter.getRegisteredFrom() != null) {
            where.append(" AND created_at >= :registeredFrom");
            params.put("registeredFrom", filter.getRegisteredFrom());
        }
        if (filter.getRegisteredTo() != null) {
            where.append(" AND created_at < :registeredTo");
            params.put("registeredTo", filter.getRegisteredTo());
        }
        if (filter.getIncludeIds() != null) {
            where.append(" AND id = ANY(:includeIds)");
            params.put("includeIds", filter.getIncludeIds().toArray(new UUID[0]));
        }
        if (filter.getExcludeIds() != null) {
            where.append(" AND id <> ALL(:excludeIds)");
            params.put("excludeIds", filter.getExcludeIds().toArray(new UUID[0]));
        }
        // B11: the profile (participant_profiles, one row per user); an account without one never matches
        boolean byCategory = StringUtils.hasText(filter.getCategory());
        boolean byInstitution = StringUtils.hasText(filter.getInstitution());
        if (byCategory || byInstitution) {
            where.append(" AND EXISTS (SELECT 1 FROM participant_profiles p WHERE p.user_id = users.id");
            if (byCategory) {
                String category = filter.getCategory().trim().toUpperCase(Locale.ROOT);
                if (!category.matches("[A-Z][A-Z_]{1,39}")) {
                    throw new ValidationException("category", "must be a category code such as UNIVERSITY_STUDENT");
                }
                where.append(" AND p.category = :category");
                params.put("category", category);
            }
            if (byInstitution) {
                String institution = filter.getInstitution().trim().toLowerCase(Locale.ROOT);
                if (institution.length() < ParticipantQuery.MIN_SEARCH_LENGTH || institution.length() > ParticipantQuery.MAX_SEARCH_LENGTH) {
                    throw new ValidationException("institution", "must be between " + ParticipantQuery.MIN_SEARCH_LENGTH
                            + " and " + ParticipantQuery.MAX_SEARCH_LENGTH + " characters");
                }
                // The start of the name (idx_participant_profiles_institution_lower); % and _ are searched for as typed
                where.append(" AND lower(p.institution) LIKE :institution ESCAPE '!'");
                params.put("institution", institution.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
            }
            where.append(")");
        }
        return where.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindAll(DatabaseClient.GenericExecuteSpec spec, Map<String, Object> params) {
        for (Map.Entry<String, Object> param : params.entrySet()) {
            spec = spec.bind(param.getKey(), param.getValue());
        }
        return spec;
    }
}
