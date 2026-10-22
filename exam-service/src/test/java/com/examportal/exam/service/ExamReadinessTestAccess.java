package com.examportal.exam.service;

import com.examportal.common.domain.dto.ExamReadinessResponse;
import com.examportal.exam.structure.ExamState;

import java.util.ArrayList;
import java.util.List;

/**
 * The readiness codes of one question, without a database: ExamReadinessService.questionProblems on a block that
 * holds only that question.
 */
final class ExamReadinessTestAccess {

    private ExamReadinessTestAccess() {
    }

    static List<String> codes(ExamState.QuestionState question) {
        ExamState.BlockState block = new ExamState.BlockState(question.blockId(), 0, "Əsas blok", null, null, 0, true,
                false, true, List.of(question));
        List<ExamReadinessResponse.Item> blocking = new ArrayList<>();
        ExamReadinessService.questionProblems(question, block, 1, 1, blocking);
        return blocking.stream().map(ExamReadinessResponse.Item::getCode).toList();
    }
}
