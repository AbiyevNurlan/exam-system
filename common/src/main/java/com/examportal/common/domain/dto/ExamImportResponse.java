package com.examportal.common.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * What was read from a Word file of questions (POST /api/exams/import/docx). Nothing is stored: the creator shows
 * it, the person checks it, and the questions become part of an exam only when that exam is saved.
 *
 * blocks: the sections in the order of the file ("Bölmə: ..." lines or Word headings); one block without a title
 * when the file has none. warnings: what the person should look at, with the number of the question.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExamImportResponse {

    private int questionCount;
    private List<Block> blocks;
    private List<Warning> warnings;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Block {
        private String title;
        private List<QuestionDTO> questions;
    }

    /**
     * code: NO_CORRECT_ANSWER, ONE_OPTION, ANSWER_NOT_AN_OPTION, IMAGE_SKIPPED, TEXT_SKIPPED, TOO_MANY_OPTIONS,
     * TOO_MANY_QUESTIONS. questionNumber: over the whole file, from 1; null for the file itself. text: the start of
     * the line in question, so it can be found in the file.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Warning {
        private String code;
        private Integer questionNumber;
        private String text;
    }
}
