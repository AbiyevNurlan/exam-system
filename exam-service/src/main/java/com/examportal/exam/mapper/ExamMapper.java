package com.examportal.exam.mapper;

import com.examportal.common.domain.dto.*;
import com.examportal.common.domain.entity.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Exam Mapper
 * Maps between entities and DTOs.
 */
@Component
public class ExamMapper {

    /**
     * Map CreateExamRequest to Exam entity
     */
    public Exam toEntity(CreateExamRequest request, UUID userId) {
        return Exam.builder()
                .title(request.getTitle())
                .description(request.getDescription())
                .durationMinutes(request.getDurationMinutes())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .status(ExamStatus.DRAFT)
                .totalQuestions(request.getQuestions() != null ? request.getQuestions().size() : 0)
                .passingScore(request.getPassingScore() != null ? request.getPassingScore() : BigDecimal.valueOf(60))
                .shuffleQuestions(request.getShuffleQuestions())
                .shuffleOptions(request.getShuffleOptions())
                .showResultsImmediately(request.getShowResultsImmediately())
                .maxAttempts(request.getMaxAttempts() != null ? request.getMaxAttempts() : 1)
                .maxAttemptsPerDay(request.getMaxAttemptsPerDay())
                .categoryId(request.getCategoryId())
                .createdBy(userId)
                // Default; ExamService replaces it when the create request names an audience
                .accessType(ExamAccessType.PUBLIC)
                // B12a: omitted = the behaviour before blocks
                .timingMode(request.getTimingMode() != null ? request.getTimingMode() : TimingMode.EXAM_TIMER)
                .allowReturnToPreviousBlocks(Boolean.TRUE.equals(request.getAllowReturnToPreviousBlocks()))
                .allowSkipBreak(request.getAllowSkipBreak() == null || request.getAllowSkipBreak())
                .showBlockBreakdown(request.getShowBlockBreakdown() == null || request.getShowBlockBreakdown())
                .active(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Update Exam entity from UpdateExamRequest
     */
    public void updateEntity(Exam exam, UpdateExamRequest request) {
        exam.setTitle(request.getTitle());
        exam.setDescription(request.getDescription());
        exam.setDurationMinutes(request.getDurationMinutes());
        exam.setStartTime(request.getStartTime());
        exam.setEndTime(request.getEndTime());
        exam.setTotalQuestions(request.getQuestions() != null ? request.getQuestions().size() : 0);
        exam.setPassingScore(request.getPassingScore());
        exam.setShuffleQuestions(request.getShuffleQuestions());
        exam.setShuffleOptions(request.getShuffleOptions());
        exam.setShowResultsImmediately(request.getShowResultsImmediately());
        exam.setMaxAttempts(request.getMaxAttempts());
        // Omitted = unchanged (a client that does not know the field must not remove the limit)
        if (request.isMaxAttemptsPerDayPresent()) {
            exam.setMaxAttemptsPerDay(request.getMaxAttemptsPerDay());
        }
        exam.setCategoryId(request.getCategoryId());
        // B12a: omitted = unchanged
        if (request.getTimingMode() != null) {
            exam.setTimingMode(request.getTimingMode());
        }
        if (request.getAllowReturnToPreviousBlocks() != null) {
            exam.setAllowReturnToPreviousBlocks(request.getAllowReturnToPreviousBlocks());
        }
        if (request.getAllowSkipBreak() != null) {
            exam.setAllowSkipBreak(request.getAllowSkipBreak());
        }
        if (request.getShowBlockBreakdown() != null) {
            exam.setShowBlockBreakdown(request.getShowBlockBreakdown());
        }
        exam.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * The blocks of an exam for a response, each with its questions' ids in order
     */
    public List<ExamBlockResponse> toBlockResponses(List<ExamBlock> blocks, List<QuestionResponse> questions) {
        if (blocks == null) {
            return null;
        }
        return blocks.stream()
                .map(block -> {
                    List<Long> ids = questions == null ? List.of() : questions.stream()
                            .filter(q -> block.getId().equals(q.getBlockId()))
                            .map(QuestionResponse::getId)
                            .toList();
                    return ExamBlockResponse.builder()
                            .id(block.getId())
                            .position(block.getPosition())
                            .title(block.getTitle())
                            .instructions(block.getInstructions())
                            .timeLimitMinutes(block.getTimeLimitMinutes())
                            .breakAfterMinutes(block.getBreakAfterMinutes())
                            .freeNavigation(block.getFreeNavigation())
                            .shuffleQuestions(block.getShuffleQuestions())
                            .showResultToParticipant(block.getShowResultToParticipant())
                            .questionCount(ids.size())
                            .questionIds(ids)
                            .build();
                })
                .toList();
    }

    /**
     * Map QuestionDTO to Question entity
     */
    public Question toEntity(QuestionDTO dto, Long examId) {
        return Question.builder()
                .examId(examId)
                .questionText(dto.getQuestionText())
                .questionType(dto.getQuestionType())
                .points(dto.getPoints() != null ? dto.getPoints() : BigDecimal.ONE)
                .orderIndex(dto.getOrderIndex())
                .allowMultipleAnswers(dto.getAllowMultipleAnswers())
                .isRequired(dto.getIsRequired() != null ? dto.getIsRequired() : true)
                .explanation(dto.getExplanation())
                .active(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Map OptionDTO to QuestionOption entity
     */
    public QuestionOption toEntity(OptionDTO dto, Long questionId) {
        return QuestionOption.builder()
                .questionId(questionId)
                .optionText(dto.getOptionText())
                .isCorrect(dto.getIsCorrect() != null ? dto.getIsCorrect() : false)
                .orderIndex(dto.getOrderIndex())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Map Exam entity to ExamResponse
     */
    public ExamResponse toResponse(Exam exam) {
        return ExamResponse.builder()
                .id(exam.getId())
                .title(exam.getTitle())
                .description(exam.getDescription())
                .durationMinutes(exam.getDurationMinutes())
                .startTime(exam.getStartTime())
                .endTime(exam.getEndTime())
                .status(exam.getStatus())
                .totalQuestions(exam.getTotalQuestions())
                .passingScore(exam.getPassingScore())
                .shuffleQuestions(exam.getShuffleQuestions())
                .shuffleOptions(exam.getShuffleOptions())
                .showResultsImmediately(exam.getShowResultsImmediately())
                .maxAttempts(exam.getMaxAttempts())
                .maxAttemptsPerDay(exam.getMaxAttemptsPerDay())
                .categoryId(exam.getCategoryId())
                .createdBy(exam.getCreatedBy())
                .accessType(exam.getAccessType())
                .createdAt(exam.getCreatedAt())
                .updatedAt(exam.getUpdatedAt())
                .canStart(exam.canStart())
                .timingMode(exam.getTimingMode() != null ? exam.getTimingMode() : TimingMode.EXAM_TIMER)
                .allowReturnToPreviousBlocks(exam.getAllowReturnToPreviousBlocks())
                .allowSkipBreak(exam.getAllowSkipBreak())
                .showBlockBreakdown(exam.getShowBlockBreakdown())
                // With block timers the stored duration is the computed total (blocks and breaks)
                .totalMinutes(exam.getDurationMinutes())
                .build();
    }

    /**
     * Map Exam entity to ExamResponse with questions and blocks
     */
    public ExamResponse toResponse(Exam exam, List<QuestionResponse> questions, List<ExamBlock> blocks) {
        ExamResponse response = toResponse(exam, questions);
        response.setBlocks(toBlockResponses(blocks, questions));
        return response;
    }

    /**
     * Map Exam entity to ExamResponse with questions
     */
    public ExamResponse toResponse(Exam exam, List<QuestionResponse> questions) {
        ExamResponse response = toResponse(exam);
        response.setQuestions(questions);
        if (questions != null && !questions.isEmpty()) {
            BigDecimal totalPoints = questions.stream()
                    .map(QuestionResponse::getPoints)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            response.setTotalPoints(totalPoints);
        }
        return response;
    }

    /**
     * Map Question entity to QuestionResponse
     */
    public QuestionResponse toResponse(Question question) {
        return QuestionResponse.builder()
                .id(question.getId())
                .examId(question.getExamId())
                .blockId(question.getBlockId())
                .questionText(question.getQuestionText())
                .questionType(question.getQuestionType())
                .points(question.getPoints())
                .orderIndex(question.getOrderIndex())
                .allowMultipleAnswers(question.getAllowMultipleAnswers())
                .isRequired(question.getIsRequired())
                .explanation(question.getExplanation())
                .build();
    }

    /**
     * Map Question entity to QuestionResponse with options
     */
    public QuestionResponse toResponse(Question question, List<OptionResponse> options) {
        QuestionResponse response = toResponse(question);
        response.setOptions(options);
        return response;
    }

    /**
     * Map Question entity to QuestionResponse for participants (hide explanation)
     */
    public QuestionResponse toParticipantResponse(Question question, List<OptionResponse> options) {
        return QuestionResponse.builder()
                .id(question.getId())
                .examId(question.getExamId())
                .blockId(question.getBlockId())
                .questionText(question.getQuestionText())
                .questionType(question.getQuestionType())
                .points(question.getPoints())
                .orderIndex(question.getOrderIndex())
                .allowMultipleAnswers(question.getAllowMultipleAnswers())
                .isRequired(question.getIsRequired())
                .options(options)
                // explanation is hidden for participants during exam
                .build();
    }

    /**
     * Copy of a full ExamResponse without the answer key (isCorrect, explanation, open-ended answers).
     * The source is left untouched because it may be the cached instance.
     */
    public ExamResponse toParticipantView(ExamResponse full) {
        List<QuestionResponse> questions = full.getQuestions() == null ? null : full.getQuestions().stream()
                .map(q -> QuestionResponse.builder()
                        .id(q.getId())
                        .examId(q.getExamId())
                        .blockId(q.getBlockId())
                        .questionText(q.getQuestionText())
                        .questionType(q.getQuestionType())
                        .points(q.getPoints())
                        .orderIndex(q.getOrderIndex())
                        .allowMultipleAnswers(q.getAllowMultipleAnswers())
                        .isRequired(q.getIsRequired())
                        .options(participantOptions(q.getQuestionType(), q.getOptions()))
                        .build())
                .toList();

        return ExamResponse.builder()
                .id(full.getId())
                .title(full.getTitle())
                .description(full.getDescription())
                .durationMinutes(full.getDurationMinutes())
                .startTime(full.getStartTime())
                .endTime(full.getEndTime())
                .status(full.getStatus())
                .totalQuestions(full.getTotalQuestions())
                .passingScore(full.getPassingScore())
                .shuffleQuestions(full.getShuffleQuestions())
                .shuffleOptions(full.getShuffleOptions())
                .showResultsImmediately(full.getShowResultsImmediately())
                .maxAttempts(full.getMaxAttempts())
                .maxAttemptsPerDay(full.getMaxAttemptsPerDay())
                .categoryId(full.getCategoryId())
                .categoryName(full.getCategoryName())
                .createdBy(full.getCreatedBy())
                .creatorUsername(full.getCreatorUsername())
                .createdAt(full.getCreatedAt())
                .updatedAt(full.getUpdatedAt())
                .questions(questions)
                .remainingAttempts(full.getRemainingAttempts())
                .remainingAttemptsToday(full.getRemainingAttemptsToday())
                .nextAttemptAt(full.getNextAttemptAt())
                .canStart(full.getCanStart())
                .totalPoints(full.getTotalPoints())
                // Blocks carry no answer key: participants see titles, instructions, limits and breaks
                .timingMode(full.getTimingMode())
                .allowReturnToPreviousBlocks(full.getAllowReturnToPreviousBlocks())
                .allowSkipBreak(full.getAllowSkipBreak())
                .showBlockBreakdown(full.getShowBlockBreakdown())
                .totalMinutes(full.getTotalMinutes())
                .blocks(full.getBlocks())
                .build();
    }

    /**
     * Options a participant may see. For text questions the option text is the expected answer,
     * so those options are not sent at all.
     */
    public List<OptionResponse> participantOptions(QuestionType type, List<OptionResponse> options) {
        if (options == null || isTextQuestion(type)) {
            return null;
        }
        return options.stream()
                .map(o -> OptionResponse.builder()
                        .id(o.getId())
                        .questionId(o.getQuestionId())
                        .optionText(o.getOptionText())
                        .orderIndex(o.getOrderIndex())
                        .build())
                .toList();
    }

    public boolean isTextQuestion(QuestionType type) {
        return type == QuestionType.FILL_BLANK || type == QuestionType.OPEN_ENDED;
    }

    /**
     * Map QuestionOption entity to OptionResponse
     */
    public OptionResponse toResponse(QuestionOption option) {
        return OptionResponse.builder()
                .id(option.getId())
                .questionId(option.getQuestionId())
                .optionText(option.getOptionText())
                .orderIndex(option.getOrderIndex())
                .isCorrect(option.getIsCorrect())
                .build();
    }

    /**
     * Map QuestionOption entity to OptionResponse for participants (hide isCorrect)
     */
    public OptionResponse toParticipantResponse(QuestionOption option) {
        return OptionResponse.builder()
                .id(option.getId())
                .questionId(option.getQuestionId())
                .optionText(option.getOptionText())
                .orderIndex(option.getOrderIndex())
                // isCorrect is hidden for participants
                .build();
    }

    /**
     * Shuffle a list
     */
    public <T> List<T> shuffleList(List<T> list) {
        if (list == null || list.isEmpty()) {
            return list;
        }
        List<T> shuffled = new java.util.ArrayList<>(list);
        Collections.shuffle(shuffled);
        return shuffled;
    }
}
