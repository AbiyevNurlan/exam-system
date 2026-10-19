package com.examportal.exam.service;

import com.examportal.common.domain.dto.instructor.InstructorDtos;
import com.examportal.common.exception.ApplicationException;
import com.examportal.exam.repository.InstructorLimitsRepository;
import com.examportal.exam.repository.InstructorLimitsRepository.Stored;
import com.examportal.exam.repository.InstructorLimitsRepository.Usage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Instructor Limits (B13)
 * How much one teacher may own: groups, members (distinct people in the own groups, pending invites counted as
 * members to come), exams and media bytes. The system default comes from exam.instructor-limits.*; an administrator
 * sets the own limits of a teacher (GET/PUT /api/admin/instructor-limits/{id}). ADMIN has no limits.
 *
 * Checked when a teacher acts: a new group, a new exam or copy, an upload, members added by id, by code, in bulk,
 * an invite (single or CSV), and the approval of join requests. A limit that is reached answers 409
 * TEACHER_LIMIT_REACHED with which limit, its value and what is used, and nothing is written. Lowering a limit never
 * removes anything.
 */
@Slf4j
@Service
public class InstructorLimitsService {

    public static final String GROUPS = "GROUPS";
    public static final String MEMBERS = "MEMBERS";
    public static final String EXAMS = "EXAMS";
    public static final String MEDIA_BYTES = "MEDIA_BYTES";

    private final InstructorLimitsRepository repository;
    private final GroupAuditService auditService;
    private final TransactionalOperator transactionalOperator;
    private final int defaultGroups;
    private final int defaultMembers;
    private final int defaultExams;
    private final long defaultMediaBytes;

    public InstructorLimitsService(InstructorLimitsRepository repository, GroupAuditService auditService,
                                   TransactionalOperator transactionalOperator,
                                   @Value("${exam.instructor-limits.max-groups:50}") int defaultGroups,
                                   @Value("${exam.instructor-limits.max-members:2000}") int defaultMembers,
                                   @Value("${exam.instructor-limits.max-exams:200}") int defaultExams,
                                   @Value("${exam.instructor-limits.max-media-bytes:1073741824}") long defaultMediaBytes) {
        this.repository = repository;
        this.auditService = auditService;
        this.transactionalOperator = transactionalOperator;
        this.defaultGroups = defaultGroups;
        this.defaultMembers = defaultMembers;
        this.defaultExams = defaultExams;
        this.defaultMediaBytes = defaultMediaBytes;
    }

    /**
     * Room for adding more of one kind (GROUPS, MEMBERS, EXAMS, MEDIA_BYTES) for the caller; empty for ADMIN and for
     * anybody who is not a teacher (their requests are refused elsewhere), 409 TEACHER_LIMIT_REACHED otherwise
     */
    public Mono<Void> requireRoom(UUID userId, String role, String limit, long adding) {
        if (!OwnershipPolicy.isInstructor(role) || adding <= 0) {
            return Mono.empty();
        }
        return Mono.zip(effective(userId), repository.usage(userId))
                .flatMap(both -> {
                    long max = max(both.getT1(), limit);
                    long used = used(both.getT2(), limit);
                    return used + adding > max ? Mono.<Void>error(reached(limit, max, used)) : Mono.<Void>empty();
                });
    }

    /**
     * The limits of a teacher as they apply now, with what is used
     */
    public Mono<InstructorDtos.Limits> get(UUID instructorId) {
        return Mono.zip(repository.find(instructorId).map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty()),
                        repository.usage(instructorId))
                .map(both -> toLimits(instructorId, both.getT1().orElse(new Stored(null, null, null, null)), both.getT2()));
    }

    /**
     * New own limits of a teacher (null = the system default) and an audit row with before and after, together
     */
    public Mono<InstructorDtos.Limits> set(UUID instructorId, InstructorDtos.Limits request, UUID adminId) {
        Stored next = new Stored(request.getMaxGroups(), request.getMaxMembers(), request.getMaxExams(), request.getMaxMediaBytes());
        Mono<Void> change = repository.find(instructorId).defaultIfEmpty(new Stored(null, null, null, null))
                .flatMap(before -> repository.save(instructorId, next, adminId)
                        .then(auditService.record(adminId, GroupAuditService.INSTRUCTOR_LIMITS_CHANGED, null, instructorId,
                                "before=" + describe(before) + " after=" + describe(next))));
        return transactionalOperator.transactional(change).then(get(instructorId));
    }

    private Mono<Stored> effective(UUID instructorId) {
        return repository.find(instructorId).defaultIfEmpty(new Stored(null, null, null, null));
    }

    private long max(Stored own, String limit) {
        return switch (limit) {
            case GROUPS -> own.maxGroups() != null ? own.maxGroups() : defaultGroups;
            case MEMBERS -> own.maxMembers() != null ? own.maxMembers() : defaultMembers;
            case EXAMS -> own.maxExams() != null ? own.maxExams() : defaultExams;
            case MEDIA_BYTES -> own.maxMediaBytes() != null ? own.maxMediaBytes() : defaultMediaBytes;
            default -> throw new IllegalArgumentException("Unknown limit " + limit);
        };
    }

    private static long used(Usage usage, String limit) {
        return switch (limit) {
            case GROUPS -> usage.groups();
            // Pending invites become members when their owner signs in: they count already
            case MEMBERS -> usage.members() + usage.invites();
            case EXAMS -> usage.exams();
            case MEDIA_BYTES -> usage.mediaBytes();
            default -> throw new IllegalArgumentException("Unknown limit " + limit);
        };
    }

    private InstructorDtos.Limits toLimits(UUID instructorId, Stored own, Usage usage) {
        List<String> defaults = new ArrayList<>();
        if (own.maxGroups() == null) defaults.add(GROUPS);
        if (own.maxMembers() == null) defaults.add(MEMBERS);
        if (own.maxExams() == null) defaults.add(EXAMS);
        if (own.maxMediaBytes() == null) defaults.add(MEDIA_BYTES);
        return InstructorDtos.Limits.builder()
                .instructorId(instructorId)
                .maxGroups((int) max(own, GROUPS))
                .maxMembers((int) max(own, MEMBERS))
                .maxExams((int) max(own, EXAMS))
                .maxMediaBytes(max(own, MEDIA_BYTES))
                .defaults(defaults)
                .usedGroups(usage.groups())
                .usedMembers(usage.members() + usage.invites())
                .usedExams(usage.exams())
                .usedMediaBytes(usage.mediaBytes())
                .build();
    }

    private static String describe(Stored limits) {
        return "groups:" + text(limits.maxGroups()) + ",members:" + text(limits.maxMembers()) + ",exams:"
                + text(limits.maxExams()) + ",mediaBytes:" + text(limits.maxMediaBytes());
    }

    private static String text(Number value) {
        return value == null ? "default" : value.toString();
    }

    /**
     * 409 TEACHER_LIMIT_REACHED with the limit, its value and what is used
     */
    static ApplicationException reached(String limit, long max, long used) {
        return new ApplicationException("The teacher's limit of " + limit + " is reached (" + used + " of " + max + ")",
                HttpStatus.CONFLICT, "TEACHER_LIMIT_REACHED")
                .withDetails(Map.of("limit", limit, "max", max, "used", used));
    }
}
