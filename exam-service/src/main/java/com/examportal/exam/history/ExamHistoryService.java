package com.examportal.exam.history;

import com.examportal.common.domain.entity.ExamStatus;
import com.examportal.exam.exception.ExamException;
import com.examportal.exam.service.ExamFeatures;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The one place every change of an exam is written to its history (B12a): create, update (settings, blocks,
 * questions, options, audience), title and description, reschedule, publish, archive, the audience, a delete
 * (also a refused one) and a clone. Call it inside the transaction of the change: the change and its rows
 * commit together, and a failure here rolls the change back.
 *
 * changeReason is optional. With REQUIRE_CHANGE_REASON_AFTER_PUBLISH on, a sensitive change of an exam that was
 * SCHEDULED or ACTIVE before the change needs one (400 CHANGE_REASON_REQUIRED, nothing is changed).
 */
@Service
@RequiredArgsConstructor
public class ExamHistoryService {

    // "Published": participants can see it or will at its start
    private static final Set<ExamStatus> PUBLISHED = Set.of(ExamStatus.SCHEDULED, ExamStatus.ACTIVE);

    private final ExamRevisionRepository revisions;
    private final ExamFeatures features;
    private final ExamHistoryQueryRepository queries;

    /**
     * Who edited each question of the exam last, and when (question id -> its newest history row)
     */
    public Mono<Map<Long, ExamHistoryQueryRepository.LastEdit>> lastQuestionEdits(Long examId) {
        return queries.lastQuestionEdits(examId);
    }

    /**
     * statusBefore: the exam's status before this change (null for a new exam); ownerId: who owns the exam
     */
    public Mono<Void> record(Long examId, UUID ownerId, ExamStatus statusBefore, ChangeContext context,
                             List<RevisionDraft> drafts) {
        if (drafts.isEmpty()) {
            return Mono.empty();
        }
        return Mono.defer(() -> {
            if (features.requireChangeReasonAfterPublish() && statusBefore != null && PUBLISHED.contains(statusBefore)
                    && context.reason() == null && drafts.stream().anyMatch(RevisionDraft::sensitive)) {
                List<Map<String, Object>> sensitive = drafts.stream()
                        .filter(RevisionDraft::sensitive)
                        .filter(draft -> !"EXAM_UPDATED".equals(draft.action()))
                        .map(draft -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("action", draft.action());
                            item.put("entityType", draft.entityType());
                            item.put("entityId", draft.entityId());
                            item.put("changedFields", draft.changes().keySet());
                            return item;
                        })
                        .toList();
                return Mono.error(ExamException.changeReasonRequired(Map.of("status", statusBefore.name(), "sensitiveChanges", sensitive)));
            }
            List<RevisionDraft> withActor = drafts.stream().map(draft -> {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("actorName", context.actorName());
                params.put("actorCode", context.actorCode());
                params.put("actorRole", context.role());
                params.putAll(draft.summaryParams());
                return new RevisionDraft(draft.action(), draft.entityType(), draft.entityId(), draft.changes(),
                        draft.sensitive(), draft.summaryKey(), params);
            }).toList();
            return revisions.append(examId, ownerId, context, withActor);
        });
    }
}
