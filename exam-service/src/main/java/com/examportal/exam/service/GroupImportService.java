package com.examportal.exam.service;

import com.examportal.common.domain.dto.group.CsvImportResponse;
import com.examportal.common.domain.dto.group.CsvImportResponse.InvalidRow;
import com.examportal.common.domain.dto.internal.UserLookupRequest;
import com.examportal.common.domain.dto.internal.UserLookupResponse.UserRef;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.client.UserDirectoryClient;
import com.examportal.exam.exception.GroupException;
import com.examportal.exam.repository.GroupImportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Group Import Service
 * CSV import of group members: one email per line, optional header, at most MAX_ROWS rows and
 * MAX_FILE_BYTES bytes.
 *
 * Two phases. Classification only reads: every distinct valid email is resolved through auth-service
 * and compared with the group. A dry run stops there. A real run then writes everything in one
 * transaction with idempotent statements, so repeating an import changes nothing.
 *
 * An email with a VERIFIED account becomes a member (source CSV). Every other email, including one
 * whose account is not verified yet, becomes a pending invite that is applied when that address
 * logs in verified (GroupInviteService).
 *
 * Email addresses are never logged, and the audit entry holds counts only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupImportService {

    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_ROWS = 20_000;

    private static final int BATCH_SIZE = UserLookupRequest.MAX_ITEMS;
    private static final int MAX_INVALID_ROWS_REPORTED = 100;
    private static final int MAX_INVALID_VALUE_LENGTH = 100;
    private static final int MAX_EMAIL_LENGTH = 255;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@\"<>,;]+@[^\\s@\"<>,;]+\\.[^\\s@\"<>,;]+$");
    private static final Pattern LINE_BREAK = Pattern.compile("\\r\\n|\\r|\\n");
    private static final Pattern FIELD_SEPARATOR = Pattern.compile("[,;\\t]");

    private final GroupImportRepository importRepository;
    private final GroupAuditService auditService;
    private final UserDirectoryClient userDirectory;
    private final TransactionalOperator transactionalOperator;
    private final GroupAccessPolicy accessPolicy;

    private record ParsedCsv(List<String> emails, int totalRows, int invalid, int duplicates,
                             List<InvalidRow> invalidRows) {
    }

    /** What the import would do with the distinct valid emails of the file */
    private record Classification(Map<String, UUID> newMembers, List<String> newInvites,
                                  int alreadyMember, int alreadyInvited) {
    }

    /**
     * Only into a group the actor may manage (GroupAccessPolicy; another owner's group is a 404)
     */
    public Mono<CsvImportResponse> importMembers(Long groupId, FilePart file, boolean dryRun, UserPrincipal actor) {
        UUID actorId = actor.getId();
        return accessPolicy.findManageableGroup(groupId, actorId, actor.getRole())
                // Fails with DataBufferLimitException (413) as soon as the file exceeds the limit
                .flatMap(group -> DataBufferUtils.join(file.content(), MAX_FILE_BYTES))
                .map(this::readAndRelease)
                .flatMap(text -> Mono.fromCallable(() -> parse(text)))
                // B13: a teacher's import never looks the accounts up, so its figures cannot tell whether an email has
                // an account: every new email is a pending invite (matched and alreadyMember stay 0) and becomes a
                // membership when that person signs in or verifies the address, as for an unknown email
                .flatMap(parsed -> classify(groupId, parsed.emails(), OwnershipPolicy.isAdmin(actor.getRole()))
                        .flatMap(classification -> {
                            CsvImportResponse response = CsvImportResponse.builder()
                                    .dryRun(dryRun)
                                    .totalRows(parsed.totalRows())
                                    .matched(classification.newMembers().size())
                                    .pendingInvite(classification.newInvites().size())
                                    .alreadyMember(classification.alreadyMember())
                                    .alreadyInvited(classification.alreadyInvited())
                                    .invalid(parsed.invalid())
                                    .duplicates(parsed.duplicates())
                                    .invalidRows(parsed.invalidRows())
                                    .build();
                            // Nothing new (e.g. the same file again): no write and no audit row either
                            boolean nothingToStore = classification.newMembers().isEmpty()
                                    && classification.newInvites().isEmpty();
                            return dryRun || nothingToStore
                                    ? Mono.just(response)
                                    : apply(groupId, classification, response, actorId).thenReturn(response);
                        }))
                .doOnSuccess(r -> log.info("CSV import for group {} by {} (dryRun={}): rows={} matched={} "
                                + "pendingInvite={} alreadyMember={} alreadyInvited={} invalid={} duplicates={}",
                        groupId, actorId, dryRun, r.getTotalRows(), r.getMatched(), r.getPendingInvite(),
                        r.getAlreadyMember(), r.getAlreadyInvited(), r.getInvalid(), r.getDuplicates()));
    }

    // ==================== Private Helper Methods ====================

    private String readAndRelease(DataBuffer buffer) {
        try {
            String text = buffer.toString(StandardCharsets.UTF_8);
            // Byte order mark written by spreadsheet exports
            return text.startsWith("﻿") ? text.substring(1) : text;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    /**
     * One email per line; when a line has several columns the first one is used. The first
     * non-blank line is a header when it holds no '@'. Blank lines are ignored.
     */
    private ParsedCsv parse(String text) {
        Set<String> emails = new LinkedHashSet<>();
        List<InvalidRow> invalidRows = new ArrayList<>();
        int totalRows = 0;
        int invalid = 0;
        int duplicates = 0;
        boolean first = true;

        String[] lines = LINE_BREAK.split(text, -1);
        for (int i = 0; i < lines.length; i++) {
            String value = firstField(lines[i]);
            if (value.isEmpty()) {
                continue;
            }
            if (first) {
                first = false;
                if (value.indexOf('@') < 0) {
                    continue;
                }
            }
            if (++totalRows > MAX_ROWS) {
                throw GroupException.csvTooManyRows(MAX_ROWS);
            }

            String email = value.toLowerCase(Locale.ROOT);
            if (email.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(email).matches()) {
                invalid++;
                if (invalidRows.size() < MAX_INVALID_ROWS_REPORTED) {
                    invalidRows.add(InvalidRow.builder()
                            .line(i + 1)
                            .value(value.length() > MAX_INVALID_VALUE_LENGTH
                                    ? value.substring(0, MAX_INVALID_VALUE_LENGTH) : value)
                            .build());
                }
            } else if (!emails.add(email)) {
                duplicates++;
            }
        }
        return new ParsedCsv(List.copyOf(emails), totalRows, invalid, duplicates, invalidRows);
    }

    private String firstField(String line) {
        String value = FIELD_SEPARATOR.split(line, 2)[0].trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private Mono<Classification> classify(Long groupId, List<String> emails, boolean lookupAccounts) {
        return Flux.fromIterable(batches(emails))
                // One batch at a time: bounded load on auth-service and on the connection pool
                .concatMap(batch -> lookupAccounts ? classifyBatch(groupId, batch) : classifyAsInvites(groupId, batch))
                .reduceWith(() -> new Classification(new HashMap<>(), new ArrayList<>(), 0, 0), (total, part) -> {
                    total.newMembers().putAll(part.newMembers());
                    total.newInvites().addAll(part.newInvites());
                    return new Classification(total.newMembers(), total.newInvites(),
                            total.alreadyMember() + part.alreadyMember(),
                            total.alreadyInvited() + part.alreadyInvited());
                });
    }

    // B13, a teacher: only the invites of this group are looked at, never the accounts
    private Mono<Classification> classifyAsInvites(Long groupId, List<String> batch) {
        return importRepository.findInvitedEmails(groupId, batch).collect(Collectors.toSet())
                .map(invited -> {
                    List<String> newInvites = new ArrayList<>(batch.stream().filter(email -> !invited.contains(email)).toList());
                    return new Classification(new HashMap<>(), newInvites, 0, batch.size() - newInvites.size());
                });
    }

    private Mono<Classification> classifyBatch(Long groupId, List<String> batch) {
        return userDirectory.lookup(batch, List.of()).flatMap(users -> {
            // Only verified accounts count as a match; the rest stays an invite until verified
            Map<String, UUID> verified = new HashMap<>();
            for (UserRef user : users) {
                if (user.isEmailVerified() && user.getEmail() != null) {
                    verified.put(user.getEmail().toLowerCase(Locale.ROOT), user.getId());
                }
            }
            List<String> unmatched = batch.stream().filter(email -> !verified.containsKey(email)).toList();

            Mono<Set<UUID>> members = verified.isEmpty()
                    ? Mono.just(Set.of())
                    : importRepository.findMemberIds(groupId, verified.values()).collect(Collectors.toSet());
            Mono<Set<String>> invited = unmatched.isEmpty()
                    ? Mono.just(Set.of())
                    : importRepository.findInvitedEmails(groupId, unmatched).collect(Collectors.toSet());

            return Mono.zip(members, invited).map(existing -> {
                Map<String, UUID> newMembers = new HashMap<>();
                verified.forEach((email, userId) -> {
                    if (!existing.getT1().contains(userId)) {
                        newMembers.put(email, userId);
                    }
                });
                List<String> newInvites = new ArrayList<>(unmatched.stream()
                        .filter(email -> !existing.getT2().contains(email))
                        .toList());
                return new Classification(newMembers, newInvites,
                        verified.size() - newMembers.size(), unmatched.size() - newInvites.size());
            });
        });
    }

    /**
     * Writes the classified import: memberships, invites and one audit row, all or nothing
     */
    private Mono<Void> apply(Long groupId, Classification classification, CsvImportResponse counts, UUID actorId) {
        List<String> memberEmails = List.copyOf(classification.newMembers().keySet());

        Mono<Void> members = Flux.fromIterable(batches(memberEmails))
                .concatMap(batch -> importRepository.addMembers(groupId,
                                batch.stream().map(classification.newMembers()::get).toList(), actorId)
                        // A membership replaces an earlier invite for the same address
                        .then(importRepository.removeInvites(groupId, batch)))
                .then();
        Mono<Void> invites = Flux.fromIterable(batches(classification.newInvites()))
                .concatMap(batch -> importRepository.addInvites(groupId, batch, actorId))
                .then();
        Mono<Void> audit = auditService.record(actorId, GroupAuditService.CSV_IMPORT, groupId, null,
                "rows=" + counts.getTotalRows() + " matched=" + counts.getMatched()
                        + " pendingInvite=" + counts.getPendingInvite()
                        + " alreadyMember=" + counts.getAlreadyMember()
                        + " alreadyInvited=" + counts.getAlreadyInvited()
                        + " invalid=" + counts.getInvalid() + " duplicates=" + counts.getDuplicates());

        return transactionalOperator.transactional(members.then(invites).then(audit));
    }

    private <T> List<List<T>> batches(List<T> items) {
        List<List<T>> batches = new ArrayList<>();
        for (int from = 0; from < items.size(); from += BATCH_SIZE) {
            batches.add(items.subList(from, Math.min(from + BATCH_SIZE, items.size())));
        }
        return batches;
    }
}