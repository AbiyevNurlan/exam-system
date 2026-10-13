package com.examportal.exam.service;

import com.examportal.common.domain.dto.internal.UserLookupRequest;
import com.examportal.common.domain.dto.internal.UserLookupResponse.UserRef;
import com.examportal.common.domain.dto.internal.UserSearchRequest;
import com.examportal.common.exception.ValidationException;
import com.examportal.exam.client.UserDirectoryClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Participant Directory
 * Names and emails for the result and grading lists. Users live in auth-service, so a list is
 * named with one lookup per page (never one per row), and a text search first asks auth-service
 * which users match and then filters the exam's sessions by those ids.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParticipantDirectory {

    private final UserDirectoryClient userDirectory;

    /**
     * The search text of a list request: null when none was given, otherwise trimmed.
     * Fewer than 2 or more than 100 characters is a 400.
     */
    public String searchTerm(String q) {
        String term = q == null ? "" : q.trim();
        if (term.isEmpty()) {
            return null;
        }
        if (term.length() < UserSearchRequest.MIN_LENGTH || term.length() > UserSearchRequest.MAX_LENGTH) {
            throw new ValidationException("Search text must be between " + UserSearchRequest.MIN_LENGTH
                    + " and " + UserSearchRequest.MAX_LENGTH + " characters");
        }
        return term;
    }

    /**
     * Users matching the search text (at most 200), by id. A failing directory fails the request
     * (503 USER_DIRECTORY_UNAVAILABLE): a search must not silently answer "nobody".
     */
    public Mono<Map<UUID, UserRef>> search(String term) {
        return userDirectory.search(term, UserSearchRequest.MAX_LIMIT).map(this::byId);
    }

    /**
     * Users by id, in as few calls as the lookup allows. A list is still useful without names, so
     * a failed lookup only leaves them out.
     */
    public Mono<Map<UUID, UserRef>> names(Collection<UUID> userIds) {
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(userIds.stream().filter(Objects::nonNull).toList()));
        if (ids.isEmpty()) {
            return Mono.just(Map.of());
        }
        List<List<UUID>> batches = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += UserLookupRequest.MAX_ITEMS) {
            batches.add(ids.subList(from, Math.min(ids.size(), from + UserLookupRequest.MAX_ITEMS)));
        }
        return Flux.fromIterable(batches)
                .concatMap(batch -> userDirectory.lookup(List.of(), batch))
                .flatMapIterable(Function.identity())
                .collectList()
                .map(this::byId)
                .onErrorResume(e -> {
                    log.warn("Participant names could not be loaded: {}", e.getClass().getSimpleName());
                    return Mono.just(Map.of());
                });
    }

    /**
     * "First Last", or null for an unknown user
     */
    public static String fullName(UserRef user) {
        if (user == null) {
            return null;
        }
        String name = ((user.getFirstName() == null ? "" : user.getFirstName().trim()) + " "
                + (user.getLastName() == null ? "" : user.getLastName().trim())).trim();
        return name.isEmpty() ? null : name;
    }

    private Map<UUID, UserRef> byId(List<UserRef> users) {
        return users.stream().collect(Collectors.toMap(UserRef::getId, Function.identity(), (a, b) -> a));
    }
}
