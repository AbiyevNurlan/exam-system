package com.examportal.exam.history;

import java.util.Map;

/**
 * One history row before it is written (B12a): what happened (action), to what (entityType, entityId),
 * field by field (changes: {"field": [before, after]}), whether it is sensitive, and the key and
 * parameters the frontend builds its sentence from. ExamHistoryService adds the actor and writes it.
 */
public record RevisionDraft(String action, String entityType, Long entityId, Map<String, Object> changes,
                            boolean sensitive, String summaryKey, Map<String, Object> summaryParams) {

    public static final String EXAM = "EXAM";
    public static final String SETTINGS = "SETTINGS";
    public static final String AUDIENCE = "AUDIENCE";
    public static final String BLOCK = "BLOCK";
    public static final String QUESTION = "QUESTION";
    public static final String OPTION = "OPTION";
}
