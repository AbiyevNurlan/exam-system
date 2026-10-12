package com.examportal.exam.service;

import com.examportal.common.domain.dto.SubmitAnswerRequest;
import com.examportal.common.domain.dto.SubmitAnswerResponse;
import com.examportal.common.domain.dto.ExamResultResponse;
import com.examportal.common.domain.entity.ExamSession;
import com.examportal.common.domain.entity.Question;
import com.examportal.common.domain.entity.QuestionOption;
import com.examportal.common.domain.entity.QuestionType;
import com.examportal.common.domain.entity.SessionStatus;
import com.examportal.common.domain.entity.UserAnswer;
import com.examportal.exam.exception.ExamException;
import com.examportal.exam.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Answer Submission Service
 * Handles answer submissions and exam completion.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerSubmissionService {

    private final ExamRepository examRepository;
    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final ExamSessionRepository sessionRepository;
    private final UserAnswerRepository answerRepository;
    private final ExamCacheService cacheService;

    /**
     * Submit an answer for a question
     */
    @Transactional
    public Mono<SubmitAnswerResponse> submitAnswer(String sessionId, SubmitAnswerRequest request, Long userId) {
        log.debug("Submitting answer for session {} question {}", sessionId, request.getQuestionId());

        return sessionRepository.findBySessionId(sessionId)
                .switchIfEmpty(Mono.error(ExamException.sessionNotFound(sessionId)))
                .<ExamSession>flatMap(session -> {
                    // Someone else's session answers 404, same as an unknown id
                    if (!session.getUserId().equals(userId)) {
                        return Mono.error(ExamException.sessionNotFound(sessionId));
                    }
                    if (!session.isActive()) {
                        return Mono.error(ExamException.sessionAlreadyCompleted());
                    }
                    if (session.isExpired()) {
                        return submitExamInternal(session, true)
                                .flatMap(result -> Mono.error(ExamException.sessionExpired()));
                    }
                    return Mono.just(session);
                })
                .flatMap(session -> processAnswer(session, request))
                .doOnSuccess(response -> log.debug("Answer submitted successfully for question {}", 
                        request.getQuestionId()))
                .doOnError(err -> log.error("Failed to submit answer: {}", err.getMessage()));
    }

    /**
     * Submit the exam and calculate results
     */
    @Transactional
    public Mono<ExamResultResponse> submitExam(String sessionId, Long userId) {
        log.info("Submitting exam for session {}", sessionId);

        return sessionRepository.findBySessionId(sessionId)
                .switchIfEmpty(Mono.error(ExamException.sessionNotFound(sessionId)))
                .flatMap(session -> {
                    if (!session.getUserId().equals(userId)) {
                        return Mono.error(ExamException.sessionNotFound(sessionId));
                    }
                    if (session.getStatus() == SessionStatus.COMPLETED) {
                        return Mono.error(ExamException.sessionAlreadyCompleted());
                    }
                    return submitExamInternal(session, false);
                })
                .doOnSuccess(result -> log.info("Exam submitted. Score: {}%", result.getPercentageScore()))
                .doOnError(err -> log.error("Failed to submit exam {}: {}", sessionId, err.getMessage()));
    }

    /**
     * Auto-submit expired exam sessions
     */
    @Transactional
    public Mono<Void> autoSubmitExpiredSessions() {
        log.info("Auto-submitting expired sessions");

        return sessionRepository.findExpiredSessions(LocalDateTime.now())
                .flatMap(session -> submitExamInternal(session, true)
                        .doOnSuccess(r -> log.info("Auto-submitted session {}", session.getSessionId()))
                        .onErrorContinue((err, obj) -> 
                                log.error("Failed to auto-submit session: {}", err.getMessage())))
                .then();
    }

    // ==================== Private Helper Methods ====================

    private Mono<SubmitAnswerResponse> processAnswer(ExamSession session, SubmitAnswerRequest request) {
        Long questionId = request.getQuestionId();
        
        // The question must belong to the exam of this session
        return questionRepository.findByIdAndExamId(questionId, session.getExamId())
                .switchIfEmpty(Mono.error(ExamException.questionNotFound(questionId)))
                .flatMap(question -> {
                    LocalDateTime now = LocalDateTime.now();

                    if (isTextQuestion(question)) {
                        String text = request.getTextAnswer() != null ? request.getTextAnswer().trim() : null;
                        return answerRepository.upsertAnswer(session.getId(), questionId, null, text, now);
                    }

                    List<Long> optionIds = requestedOptionIds(request);
                    if (optionIds.isEmpty()) {
                        return Mono.error(ExamException.selectionRequired());
                    }
                    if (optionIds.size() > 1 && !allowsMultipleAnswers(question)) {
                        return Mono.error(ExamException.singleSelectionOnly());
                    }
                    return optionRepository.findByQuestionId(questionId)
                            .map(QuestionOption::getId)
                            .collect(Collectors.toSet())
                            .flatMap(validIds -> {
                                if (!validIds.containsAll(optionIds)) {
                                    return Mono.error(ExamException.invalidOption());
                                }
                                return answerRepository.upsertAnswer(session.getId(), questionId,
                                        optionIds.toArray(new Long[0]), null, now);
                            });
                })
                .then(Mono.defer(() -> answerRepository.countBySessionId(session.getId())))
                .map(answeredCount -> SubmitAnswerResponse.builder()
                        .success(true)
                        .questionId(questionId)
                        .answeredQuestions(answeredCount.intValue())
                        .message("Answer saved successfully")
                        .build());
    }

    /**
     * Option ids from the request, without duplicates. The legacy selectedOptionId becomes a one-element list.
     */
    private List<Long> requestedOptionIds(SubmitAnswerRequest request) {
        List<Long> ids = request.getSelectedOptionIds();
        if (ids == null || ids.isEmpty()) {
            ids = request.getSelectedOptionId() != null ? List.of(request.getSelectedOptionId()) : List.of();
        }
        return ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    private boolean isTextQuestion(Question question) {
        return question.getQuestionType() == QuestionType.FILL_BLANK
                || question.getQuestionType() == QuestionType.OPEN_ENDED;
    }

    private boolean allowsMultipleAnswers(Question question) {
        return question.getQuestionType() == QuestionType.MULTIPLE_CHOICE
                && Boolean.TRUE.equals(question.getAllowMultipleAnswers());
    }

    private Mono<ExamResultResponse> submitExamInternal(ExamSession session, boolean autoSubmit) {
        return examRepository.findById(session.getExamId())
                .flatMap(exam -> calculateScore(session, exam.getPassingScore())
                        .flatMap(result -> {
                            // Update session status
                            session.setStatus(SessionStatus.COMPLETED);
                            session.setCompletedAt(LocalDateTime.now());
                            session.setTotalScore(result.getTotalScore());
                            session.setPercentageScore(result.getPercentageScore());
                            session.setPassed(result.getPassed());
                            session.setUpdatedAt(LocalDateTime.now());
                            
                            return sessionRepository.save(session)
                                    .then(cacheService.invalidateSessionCache(session.getSessionId()))
                                    .thenReturn(result);
                        }));
    }

    private Mono<ExamResultResponse> calculateScore(ExamSession session, BigDecimal passingScore) {
        return answerRepository.findBySessionId(session.getId())
                .collectList()
                .flatMap(answers -> {
                    List<Long> questionIds = answers.stream()
                            .map(UserAnswer::getQuestionId)
                            .collect(Collectors.toList());
                    
                    return questionRepository.findAllById(questionIds)
                            .collectMap(q -> q.getId(), q -> q)
                            .flatMap(questionMap -> {
                                // Get correct options for all questions
                                return optionRepository.findCorrectOptionsByQuestionIds(questionIds)
                                        .collectMultimap(QuestionOption::getQuestionId)
                                        .map(correctOptionsMap -> {
                                            BigDecimal totalPoints = BigDecimal.ZERO;
                                            BigDecimal earnedPoints = BigDecimal.ZERO;
                                            int correctCount = 0;
                                            int incorrectCount = 0;
                                            
                                            for (UserAnswer answer : answers) {
                                                var question = questionMap.get(answer.getQuestionId());
                                                if (question == null) continue;
                                                
                                                totalPoints = totalPoints.add(question.getPoints());
                                                
                                                var correctOptions = correctOptionsMap.getOrDefault(
                                                        answer.getQuestionId(), List.of());
                                                
                                                boolean isCorrect = isAnswerCorrect(question, answer, correctOptions);
                                                
                                                if (isCorrect) {
                                                    earnedPoints = earnedPoints.add(question.getPoints());
                                                    correctCount++;
                                                    answer.setPointsEarned(question.getPoints());
                                                    answer.setCorrect(true);
                                                } else {
                                                    incorrectCount++;
                                                    answer.setPointsEarned(BigDecimal.ZERO);
                                                    answer.setCorrect(false);
                                                }
                                            }
                                            
                                            // Calculate percentage
                                            BigDecimal percentage = totalPoints.compareTo(BigDecimal.ZERO) > 0
                                                    ? earnedPoints.multiply(BigDecimal.valueOf(100))
                                                            .divide(totalPoints, 2, RoundingMode.HALF_UP)
                                                    : BigDecimal.ZERO;
                                            
                                            boolean passed = percentage.compareTo(passingScore) >= 0;
                                            
                                            return ExamResultResponse.builder()
                                                    .sessionId(session.getSessionId())
                                                    .examId(session.getExamId())
                                                    .totalQuestions(answers.size())
                                                    .correctAnswers(correctCount)
                                                    .incorrectAnswers(incorrectCount)
                                                    .totalScore(earnedPoints)
                                                    .maxScore(totalPoints)
                                                    .percentageScore(percentage)
                                                    .passed(passed)
                                                    .passingScore(passingScore)
                                                    .startTime(session.getStartTime())
                                                    .completedAt(LocalDateTime.now())
                                                    .build();
                                        });
                            });
                });
    }

    private boolean isAnswerCorrect(Question question, UserAnswer answer,
                                    java.util.Collection<QuestionOption> correctOptions) {
        if (correctOptions.isEmpty()) {
            // No answer key: nothing to compare against (manual grading is not implemented)
            return false;
        }

        if (isTextQuestion(question)) {
            // Exact match ignoring case and surrounding whitespace
            String text = answer.getTextAnswer();
            if (text == null || text.isBlank()) {
                return false;
            }
            return correctOptions.stream()
                    .anyMatch(opt -> opt.getOptionText() != null
                            && opt.getOptionText().trim().equalsIgnoreCase(text.trim()));
        }

        // Choice questions are all-or-nothing: the selected set must equal the correct set
        Long[] selected = answer.getSelectedOptionIds();
        if (selected == null || selected.length == 0) {
            return false;
        }
        Set<Long> correctIds = correctOptions.stream().map(QuestionOption::getId).collect(Collectors.toSet());
        return correctIds.equals(new HashSet<>(Arrays.asList(selected)));
    }
}
