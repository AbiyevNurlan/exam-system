package com.examportal.guest;

import com.examportal.common.domain.ParticipantCode;
import com.examportal.common.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * B13a item 5: the admin's list of guests, filtered and paged in SQL. Only fixed text from the switches below is put
 * into a statement; every value is bound. Names sort in Azerbaijani order (az_ci, V6) and every order ends with the
 * participant code (unique, never changes), as the participants' list of B11b.
 */
@Repository
@RequiredArgsConstructor
public class GuestAdminRepository {

    public static final int MIN_SEARCH_LENGTH = 2;
    public static final int MAX_SEARCH_LENGTH = 100;
    public static final int MAX_PAGE_SIZE = 100;

    private static final String FIRST_NAME = "btrim(first_name) COLLATE az_ci";
    private static final String LAST_NAME = "btrim(last_name) COLLATE az_ci";

    private final DatabaseClient databaseClient;

    public record Page(List<Guest> guests, long total) {
    }

    /**
     * sortBy: code, firstName, lastName (or name), email, createdAt (default), lastLoginAt; dir: asc or desc
     * (default desc). Anything else is 400 VALIDATION_ERROR.
     */
    static String orderBy(String sortBy, String dir) {
        String direction = dir == null || dir.isBlank() ? "desc" : dir.trim().toLowerCase(Locale.ROOT);
        if (!direction.equals("asc") && !direction.equals("desc")) {
            throw new ValidationException("dir", "must be asc or desc");
        }
        String d = direction.toUpperCase(Locale.ROOT);
        String code = "participant_code " + d;
        String sort = sortBy == null ? "createdat" : sortBy.trim().toLowerCase(Locale.ROOT);
        return switch (sort) {
            case "code" -> code;
            case "firstname" -> FIRST_NAME + " " + d + ", " + LAST_NAME + " " + d + ", " + code;
            case "lastname", "name" -> LAST_NAME + " " + d + ", " + FIRST_NAME + " " + d + ", " + code;
            case "email" -> "email COLLATE \"C\" " + d + ", " + code;
            case "createdat", "" -> "created_at " + d + ", " + code;
            // A guest who never signed in after the first time has the time of creation; null last either way
            case "lastloginat" -> "last_login_at " + d + " NULLS LAST, " + code;
            default -> throw new ValidationException("sortBy", "must be code, firstName, lastName, email, createdAt or lastLoginAt");
        };
    }

    /**
     * q: exactly 7 digits is a participant code (that guest); anything else of 2-100 characters is the start of the
     * email, the first name, the last name or "first last"
     */
    static String where(String q, Map<String, Object> params) {
        if (q == null || q.isBlank()) {
            return "";
        }
        String term = q.trim().toLowerCase(Locale.ROOT);
        Integer code = ParticipantCode.parse(term);
        if (code != null) {
            params.put("code", code);
            return " WHERE participant_code = :code";
        }
        if (term.length() < MIN_SEARCH_LENGTH || term.length() > MAX_SEARCH_LENGTH) {
            throw new ValidationException("q", "must be between " + MIN_SEARCH_LENGTH + " and " + MAX_SEARCH_LENGTH + " characters");
        }
        // % and _ typed by the admin are searched for as they are (escape character '!')
        params.put("q", term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        return " WHERE (email LIKE :q ESCAPE '!' OR lower(first_name) LIKE :q ESCAPE '!' OR lower(last_name) LIKE :q ESCAPE '!'"
                + " OR lower(first_name || ' ' || last_name) LIKE :q ESCAPE '!')";
    }

    public Mono<Page> findPage(String q, String sortBy, String dir, int page, int size) {
        return Mono.defer(() -> {
            Map<String, Object> params = new LinkedHashMap<>();
            String where = where(q, params);
            String order = orderBy(sortBy, dir);
            int pageNumber = Math.max(page, 0);
            int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

            DatabaseClient.GenericExecuteSpec count = bind(databaseClient.sql("SELECT count(*) FROM guest_participants" + where), params);
            DatabaseClient.GenericExecuteSpec rows = bind(databaseClient.sql("SELECT " + GuestRepository.COLUMNS
                            + " FROM guest_participants" + where + " ORDER BY " + order + " LIMIT :limit OFFSET :offset"), params)
                    .bind("limit", pageSize)
                    .bind("offset", (long) pageNumber * pageSize);
            return Mono.zip(count.map(row -> row.get(0, Long.class)).one(),
                            rows.map(GuestRepository::guest).all().collectList())
                    .map(loaded -> new Page(loaded.getT2(), loaded.getT1()));
        });
    }

    private static DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec spec, Map<String, Object> params) {
        for (Map.Entry<String, Object> param : params.entrySet()) {
            spec = spec.bind(param.getKey(), param.getValue());
        }
        return spec;
    }
}
