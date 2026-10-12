package com.examportal.exam.service;

import com.examportal.common.domain.dto.SubmitAnswerRequest;
import com.examportal.common.domain.dto.SubmitAnswerResponse;
import com.examportal.common.domain.dto.ExamResultResponse;
import com.examportal.common.domain.entity.ExamSession;
import com.examportal.common.domain.entity.QuestionOption;
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
import java.util.List;
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
        
        return questionRepository.findById(questionId)
                .switchIfEmpty(Mono.error(ExamException.questionNotFound(questionId)))
                .flatMap(question -> {
                    // Check if answer already exists
                    return answerRepository.findBySessionIdAndQuestionId(session.getId(), questionId)
                            .flatMap(existing -> {
                                // Update existing answer
                                existing.setSelectedOptionId(request.getSelectedOptionId());
                                existing.setTextAnswer(request.getTextAnswer());
                                existing.setUpdatedAt(LocalDateTime.now());
                                return answerRepository.save(existing);
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                // Create new answer
                                UserAnswer answer = UserAnswer.builder()
                                        .sessionId(session.getId())
                                        .questionId(questionId)
                                        .selectedOptionId(request.getSelectedOptionId())
                                        .textAnswer(request.getTextAnswer())
                                        .createdAt(LocalDateTime.now())
                                        .updatedAt(LocalDateTime.now())
                                        .build();
                                return answerRepository.save(answer);
                            }))
                            .thenReturn(question);
                })
                .flatMap(question -> {
                    // Count answered questions
                    return answerRepository.countBySessionId(session.getId())
                            .map(answeredCount -> SubmitAnswerResponse.builder()
                                    .success(true)
                                    .questionId(questionId)
                                    .answeredQuestions(answeredCount.intValue())
                                    .message("Answer saved successfully")
                                    .build());
                });
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
                                                
                                                boolean isCorrect = isAnswerCorrect(answer, correctOptions);
                                                
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

    private boolean isAnswerCorrect(UserAnswer answer, java.util.Collection<QuestionOption> correctOptions) {
        if (correctOptions.isEmpty()) {
            // For text-based questions, return false (manual grading needed)
            return false;
        }
        
        // For single choice questions
        if (answer.getSelectedOptionId() != null) {
            return correctOptions.stream()
                    .anyMatch(opt -> opt.getId().equals(answer.getSelectedOptionId()));
        }
        
        // For text answers - basic comparison (could be enhanced with fuzzy matching)
        if (answer.getTextAnswer() != null) {
            return correctOptions.stream()
                    .anyMatch(opt -> opt.getOptionText() != null && 
                            opt.getOptionText().equalsIgnoreCase(answer.getTextAnswer().trim()));
        }
        
        return false;
    }
}
