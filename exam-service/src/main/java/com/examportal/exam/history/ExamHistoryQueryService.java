package com.examportal.exam.history;

import com.examportal.common.domain.ParticipantCode;
import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.history.ExamHistoryDtos;
import com.examportal.common.domain.entity.Exam;
import com.examportal.common.exception.ValidationException;
import com.examportal.exam.exception.ExamException;
import com.examportal.exam.repository.ExamRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Reading the history and the published versions of an exam (B12a). Who may: ADMIN every exam, an INSTRUCTOR
 * only the own one (anybody else's is 404, like an exam that does not exist); STUDENT never (403 by path and
 * role). The owner is taken from the exam, or for an exam that was deleted from its newest history row, so the
 * history stays readable after the exam is gone. ip, user agent and request id of a row only for ADMIN.
 */
@Service
@RequiredArgsConstructor
public class ExamHistoryQueryService {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 100;

    private static final List<String> ENTITY_TYPES = List.of(RevisionDraft.EXAM, RevisionDraft.SETTINGS,
            RevisionDraft.AUDIENCE, RevisionDraft.BLOCK, RevisionDraft.QUESTION, RevisionDraft.OPTION);

    private final ExamRepository examRepository;
    private final ExamHistoryQueryRepository history;
    private final ExamSnapshotRepository snapshots;

    public Mono<PageResponse<ExamHistoryDtos.Entry>> page(Long examId, UUID userId, String role, String actor, String action,
                                                          String from, String to, Boolean sensitiveOnly, String entityType,
                                                          int page, int size) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return Mono.fromCallable(() -> filter(actor, action, from, to, sensitiveOnly, entityType))
                .flatMap(filter -> requireReadable(examId, userId, role)
                        .then(history.count(examId, filter))
                        .flatMap(total -> history.page(examId, filter, pageSize, (long) pageNumber * pageSize).collectList()
                                .map(rows -> PageResponse.of(rows, pageNumber, pageSize, total))));
    }

    public Mono<ExamHistoryDtos.Entry> one(Long examId, Long revisionId, UUID userId, String role) {
        return requireReadable(examId, userId, role)
                .then(history.one(examId, revisionId, "ADMIN".equals(role)))
                .switchIfEmpty(Mono.error(ExamException.notFound(examId)));
    }

    public Mono<List<ExamHistoryDtos.Version>> versions(Long examId, UUID userId, String role) {
        return requireReadable(examId, userId, role)
                .then(snapshots.list(examId).map(snapshot -> version(snapshot, false)).collectList());
    }

    public Mono<ExamHistoryDtos.Version> version(Long examId, int versionNo, UUID userId, String role) {
        return requireReadable(examId, userId, role)
                .then(snapshots.find(examId, versionNo))
                .map(snapshot -> version(snapshot, true))
                .switchIfEmpty(Mono.error(ExamException.notFound(examId)));
    }

    /**
     * ADMIN: any exam that exists or has a history; INSTRUCTOR: only the own; anything else 404
     */
    Mono<Void> requireReadable(Long examId, UUID userId, String role) {
        Mono<UUID> owner = examRepository.findById(examId).map(Exam::getCreatedBy)
                .switchIfEmpty(Mono.defer(() -> history.owner(examId)));
        return owner
                .filter(found -> "ADMIN".equals(role) || ("INSTRUCTOR".equals(role) && found.equals(userId)))
                .switchIfEmpty(Mono.error(ExamException.notFound(examId)))
                .then();
    }

    private static ExamHistoryDtos.Version version(ExamSnapshotRepository.Snapshot snapshot, boolean detail) {
        ExamHistoryDtos.Version.VersionBuilder version = ExamHistoryDtos.Version.builder()
                .examId(snapshot.examId())
                .versionNo(snapshot.versionNo())
                .sha256(snapshot.sha256())
                .createdAt(snapshot.createdAt())
                .actor(ExamHistoryDtos.Actor.builder().userId(snapshot.actorUserId()).name(snapshot.actorName())
                        .code(snapshot.actorCode()).build());
        if (detail) {
            try {
                version.snapshot(ExamRevisionRepository.JSON.readValue(snapshot.snapshotJson(), Object.class))
                        .hashValid(ExamSnapshotService.verify(snapshot));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Stored snapshot is not JSON", e);
            }
        }
        return version.build();
    }

    /**
     * actor: a user id or a 7-digit participant code; from/to: a day (yyyy-MM-dd, to inclusive) or a moment in UTC
     */
    static ExamHistoryQueryRepository.Filter filter(String actor, String action, String from, String to,
                                                    Boolean sensitiveOnly, String entityType) {
        UUID actorId = null;
        Integer actorCode = null;
        if (actor != null && !actor.isBlank()) {
            String value = actor.trim();
            actorCode = ParticipantCode.parse(value);
            if (actorCode == null) {
                try {
                    actorId = UUID.fromString(value);
                } catch (IllegalArgumentException e) {
                    throw new ValidationException("actor", "must be a user id or a 7-digit participant code");
                }
            }
        }
        String wantedAction = null;
        if (action != null && !action.isBlank()) {
            wantedAction = action.trim().toUpperCase(Locale.ROOT);
            if (!ExamHistoryQueryRepository.actions().contains(wantedAction)) {
                throw new ValidationException("action", "must be one of " + ExamHistoryQueryRepository.actions());
            }
        }
        String wantedType = null;
        if (entityType != null && !entityType.isBlank()) {
            wantedType = entityType.trim().toUpperCase(Locale.ROOT);
            if (!ENTITY_TYPES.contains(wantedType)) {
                throw new ValidationException("entityType", "must be one of " + ENTITY_TYPES);
            }
        }
        LocalDateTime fromTime = moment("from", from, false);
        LocalDateTime toTime = moment("to", to, true);
        if (fromTime != null && toTime != null && !toTime.isAfter(fromTime)) {
            throw new ValidationException("to", "must be after from");
        }
        return new ExamHistoryQueryRepository.Filter(actorId, actorCode, wantedAction, fromTime, toTime,
                Boolean.TRUE.equals(sensitiveOnly), wantedType);
    }

    private static LocalDateTime moment(String field, String value, boolean endOfDay) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            if (text.length() == 10) {
                LocalDate day = LocalDate.parse(text);
                return endOfDay ? day.plusDays(1).atStartOfDay() : day.atStartOfDay();
            }
            if (text.endsWith("Z") || text.matches(".*[+-]\\d\\d:\\d\\d$")) {
                return OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            }
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException e) {
            throw new ValidationException(field, "must be a day (2026-10-19) or a moment (2026-10-19T08:00:00Z)");
        }
    }
}
