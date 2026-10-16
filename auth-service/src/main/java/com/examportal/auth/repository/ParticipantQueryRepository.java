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
        String direction = request.isAscending() ? "ASC" : "DESC";
        String sort = request.getSort() == null ? "createdat" : request.getSort().trim().toLowerCase(Locale.ROOT);
        String order = switch (sort) {
            case "name" -> "lower(last_name) " + direction + ", lower(first_name) " + direction + ", id";
            case "email" -> "lower(email) " + direction;
            case "createdat", "" -> "created_at " + direction + ", id";
            default -> throw new ValidationException("sort", "must be createdAt, name or email");
        };

        DatabaseClient.GenericExecuteSpec count = bindAll(databaseClient.sql("SELECT COUNT(*) FROM users" + where), params);
        DatabaseClient.GenericExecuteSpec page = bindAll(databaseClient.sql(
                        "SELECT id, email, participant_code, first_name, last_name, COALESCE(email_verified, false) AS email_verified, "
                                + LOCKED_NOW + " AS blocked, "
                                + "CASE WHEN " + LOCKED_NOW + " THEN locked_until END AS locked_until, created_at "
                                + "FROM users" + where + " ORDER BY " + order + " LIMIT :limit OFFSET :offset"), params)
                .bind("limit", pageSize)
                .bind("offset", (long) pageNumber * pageSize);

        return count.map(row -> row.get(0, Long.class)).one()
                .flatMap(total -> page
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
                        .collectList()
                        .map(users -> ParticipantQuery.PageResponse.builder().total(total).users(users).build()));
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
