package com.examportal.exam.service;

import com.examportal.common.domain.dto.SubmitAnswerRequest;
import com.examportal.common.domain.dto.SubmitAnswerResponse;
import com.examportal.common.domain.dto.ExamResultResponse;
import com.examportal.common.domain.dto.SessionResultResponse;
import com.examportal.common.domain.entity.SessionStatus;
import com.examportal.common.domain.entity.ExamSession;
import com.examportal.common.domain.entity.Question;
import com.examportal.common.domain.entity.QuestionOption;
import com.examportal.common.domain.entity.QuestionType;
import com.examportal.common.domain.entity.UserAnswer;
import com.examportal.exam.exception.ExamException;
import com.examportal.exam.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private final TransactionalOperator transactionalOperator;

    /**
     * Submit an answer for a question
     */
    public Mono<SubmitAnswerResponse> submitAnswer(String sessionId, SubmitAnswerRequest request, Long userId) {
        log.debug("Submitting answer for session {} question {}", sessionId, request.getQuestionId());

        return sessionRepository.findBySessionId(sessionId)
                .switchIfEmpty(Mono.error(ExamException.sessionNotFound(sessionId)))
                .<ExamSession>flatMap(session -> {
                    // Someone else's session answers 404, same as an unknown id
                    if (!session.getUserId().equals(userId)) {
                        return Mono.error(ExamException.sessionNotFound(sessionId));
                    }
                    if (session.isExpired()) {
                        // No time beyond the server-side endTime. The auto-submit commits in its own
                        // transaction before the error is raised, and is a no-op if the scheduler got there first.
                        return completeSession(session)
                                .then(Mono.<ExamSession>error(ExamException.sessionExpired()));
                    }
                    if (!session.isActive()) {
                        return Mono.error(ExamException.sessionAlreadyCompleted());
                    }
                    return Mono.just(session);
                })
                .flatMap(session -> processAnswer(session, request))
                .doOnSuccess(response -> log.debug("Answer submitted successfully for question {}", 
                        request.getQuestionId()))
                .doOnError(err -> log.error("Failed to submit answer: {}", err.getMessage()));
    }

    /**
     * Submit the exam and calculate results.
     * Idempotent: a session that is already completed returns its stored result.
     */
    public Mono<ExamResultResponse> submitExam(String sessionId, Long userId) {
        log.info("Submitting exam for session {}", sessionId);

        return sessionRepository.findBySessionId(sessionId)
                .switchIfEmpty(Mono.error(ExamException.sessionNotFound(sessionId)))
                .flatMap(session -> {
                    if (!session.getUserId().equals(userId)) {
                        return Mono.error(ExamException.sessionNotFound(sessionId));
                    }
                    if (!session.isActive()) {
                        return storedResult(session);
                    }
                    // Empty means a concurrent submit completed it first: answer with what that one stored
                    return completeSession(session)
                            .switchIfEmpty(Mono.defer(() -> sessionRepository.findBySessionId(sessionId)
                                    .flatMap(this::storedResult)));
                })
                .doOnSuccess(result -> log.info("Exam submitted. Score: {}%", result.getPercentageScore()))
                .doOnError(err -> log.error("Failed to submit exam {}: {}", sessionId, err.getMessage()));
    }

    /**
     * Result of a completed session for its owner. When the exam does not show results immediately,
     * only a submission summary is returned until the exam end time has passed.
     */
    public Mono<SessionResultResponse> getSessionResult(String sessionId, Long userId) {
        log.debug("Getting result for session {}", sessionId);

        return sessionRepository.findBySessionId(sessionId)
                .switchIfEmpty(Mono.error(ExamException.sessionNotFound(sessionId)))
                .flatMap(session -> {
                    // Someone else's session answers 404, same as an unknown id
                    if (!session.getUserId().equals(userId)) {
                        return Mono.error(ExamException.sessionNotFound(sessionId));
                    }
                    if (session.getStatus() != SessionStatus.COMPLETED) {
                        return Mono.error(ExamException.sessionNotCompleted());
                    }
                    return examRepository.findById(session.getExamId())
                            .flatMap(exam -> {
                                SessionResultResponse.SessionResultResponseBuilder summary = SessionResultResponse.builder()
                                        .sessionId(session.getSessionId())
                                        .examId(session.getExamId())
                                        .status(session.getStatus())
                                        .submittedAt(session.getSubmittedAt());

                                boolean withheld = !Boolean.TRUE.equals(exam.getShowResultsImmediately())
                                        && LocalDateTime.now().isBefore(exam.getEndTime());
                                if (withheld) {
                                    return Mono.just(summary
                                            .resultsAvailable(false)
                                            .resultsAvailableAt(exam.getEndTime())
                                            .build());
                                }
                                return storedResult(session)
                                        .map(result -> summary
                                                .resultsAvailable(true)
                                                .totalQuestions(result.getTotalQuestions())
                                                .correctAnswers(result.getCorrectAnswers())
                                                .incorrectAnswers(result.getIncorrectAnswers())
                                                .unansweredQuestions(result.getUnansweredQuestions())
                                                .totalScore(result.getTotalScore())
                                                .maxScore(result.getMaxScore())
                                                .percentageScore(result.getPercentageScore())
                                                .passed(result.getPassed())
                                                .passingScore(result.getPassingScore())
                                                .build());
                            });
                });
    }

    /**
     * Auto-submit expired exam sessions (same completion path as a manual submit)
     */
    public Mono<Void> autoSubmitExpiredSessions() {
        log.info("Auto-submitting expired sessions");

        return sessionRepository.findExpiredSessions(LocalDateTime.now())
                .concatMap(session -> completeSession(session)
                        .doOnNext(r -> log.info("Auto-submitted session {}", session.getSessionId()))
                        .onErrorResume(err -> {
                            log.error("Failed to auto-submit session {}: {}", session.getSessionId(), err.getMessage());
                            return Mono.empty();
                        }))
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
                // Nothing written: the session was completed or expired between the check and the write
                .flatMap(written -> written == 1 ? Mono.just(written) : rejectLateAnswer(session.getSessionId()))
                .then(Mono.defer(() -> answerRepository.countBySessionId(session.getId())))
                .map(answeredCount -> SubmitAnswerResponse.builder()
                        .success(true)
                        .questionId(questionId)
                        .answeredQuestions(answeredCount.intValue())
                        .message("Answer saved successfully")
                        .build());
    }

    /**
     * Error for an answer the conditional upsert refused: 410 when the session ran out of time
     * (auto-submitting it first, as on the normal expiry path), 409 when it was already completed.
     */
    private Mono<Integer> rejectLateAnswer(String sessionId) {
        return sessionRepository.findBySessionId(sessionId)
                .flatMap(current -> current.isExpired()
                        ? completeSession(current).then(Mono.<Integer>error(ExamException.sessionExpired()))
                        : Mono.<Integer>error(ExamException.sessionAlreadyCompleted()));
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

    private record GradedSession(ExamResultResponse result, List<UserAnswer> answers) {
    }

    /**
     * The only completion path: manual submit, expiry detected on an answer, and the scheduler.
     * Emits the result for the caller that moved the session out of IN_PROGRESS; empty when the
     * session had already been completed by someone else.
     */
    private Mono<ExamResultResponse> completeSession(ExamSession session) {
        Mono<ExamResultResponse> completion = sessionRepository.markCompleted(session.getId(), LocalDateTime.now())
                .filter(updated -> updated == 1)
                .flatMap(updated -> examRepository.findById(session.getExamId()))
                .flatMap(exam -> calculateScore(session, exam.getPassingScore()))
                .flatMap(graded -> {
                    ExamResultResponse result = graded.result();
                    return Flux.fromIterable(graded.answers())
                            .concatMap(answer -> answerRepository.saveGrade(
                                    answer.getId(), answer.getIsCorrect(), answer.getPointsEarned()))
                            .then(sessionRepository.saveScore(session.getId(), result.getTotalScore(),
                                    result.getMaxScore(), result.getPercentageScore(), result.getPassed()))
                            .thenReturn(result);
                });

        // @Transactional is not applied at runtime in this service, so the TransactionalOperator is the real boundary
        return transactionalOperator.transactional(completion)
                .flatMap(result -> cacheService.invalidateSessionCache(session.getSessionId())
                        .thenReturn(result));
    }

    /**
     * Result of a session that is no longer in progress, rebuilt from what was stored at completion
     */
    private Mono<ExamResultResponse> storedResult(ExamSession session) {
        return Mono.zip(
                        examRepository.findById(session.getExamId()),
                        answerRepository.countGradedBySessionId(session.getId()),
                        answerRepository.countCorrectBySessionId(session.getId()),
                        questionRepository.countActiveByExamId(session.getExamId()))
                .map(tuple -> {
                    int answered = tuple.getT2().intValue();
                    int correct = tuple.getT3().intValue();
                    int total = tuple.getT4().intValue();
                    return ExamResultResponse.builder()
                            .sessionId(session.getSessionId())
                            .examId(session.getExamId())
                            .totalQuestions(total)
                            .correctAnswers(correct)
                            .incorrectAnswers(answered - correct)
                            .unansweredQuestions(Math.max(0, total - answered))
                            .totalScore(session.getScore())
                            .maxScore(session.getTotalPoints())
                            .percentageScore(session.getPercentage())
                            .passed(session.getPassed())
                            .passingScore(tuple.getT1().getPassingScore())
                            .startTime(session.getStartTime())
                            .completedAt(session.getSubmittedAt())
                            .build();
                });
    }

    /**
     * Grade a session against ALL active questions of its exam: unanswered questions earn zero
     * but still count towards the maximum score and the percentage.
     */
    private Mono<GradedSession> calculateScore(ExamSession session, BigDecimal passingScore) {
        return Mono.zip(
                        questionRepository.findActiveByExamIdOrderByOrderIndex(session.getExamId()).collectList(),
                        answerRepository.findBySessionId(session.getId()).collectList())
                .flatMap(tuple -> {
                    List<Question> questions = tuple.getT1();
                    List<UserAnswer> answers = tuple.getT2();
                    if (questions.isEmpty()) {
                        // No questions: nothing to grade, and no IN () query
                        return Mono.just(grade(session, passingScore, questions, answers, Map.of()));
                    }
                    List<Long> questionIds = questions.stream().map(Question::getId).toList();
                    return optionRepository.findCorrectOptionsByQuestionIds(questionIds)
                            .collectMultimap(QuestionOption::getQuestionId)
                            .map(correctOptions -> grade(session, passingScore, questions, answers, correctOptions));
                });
    }

    private GradedSession grade(ExamSession session, BigDecimal passingScore, List<Question> questions,
                                List<UserAnswer> answers,
                                Map<Long, java.util.Collection<QuestionOption>> correctOptions) {
        Map<Long, UserAnswer> answerByQuestion = answers.stream()
                .collect(Collectors.toMap(UserAnswer::getQuestionId, a -> a, (first, second) -> first));

        BigDecimal totalPoints = BigDecimal.ZERO;
        BigDecimal earnedPoints = BigDecimal.ZERO;
        int correctCount = 0;
        int incorrectCount = 0;
        int unansweredCount = 0;
        List<UserAnswer> graded = new ArrayList<>();

        for (Question question : questions) {
            totalPoints = totalPoints.add(question.getPoints());

            UserAnswer answer = answerByQuestion.get(question.getId());
            if (answer == null) {
                unansweredCount++;
                continue;
            }

            boolean isCorrect = isAnswerCorrect(question, answer,
                    correctOptions.getOrDefault(question.getId(), List.of()));
            if (isCorrect) {
                earnedPoints = earnedPoints.add(question.getPoints());
                correctCount++;
                answer.setPointsEarned(question.getPoints());
            } else {
                incorrectCount++;
                answer.setPointsEarned(BigDecimal.ZERO);
            }
            answer.setCorrect(isCorrect);
            graded.add(answer);
        }

        BigDecimal percentage = totalPoints.compareTo(BigDecimal.ZERO) > 0
                ? earnedPoints.multiply(BigDecimal.valueOf(100)).divide(totalPoints, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new GradedSession(ExamResultResponse.builder()
                .sessionId(session.getSessionId())
                .examId(session.getExamId())
                .totalQuestions(questions.size())
                .correctAnswers(correctCount)
                .incorrectAnswers(incorrectCount)
                .unansweredQuestions(unansweredCount)
                .totalScore(earnedPoints)
                .maxScore(totalPoints)
                .percentageScore(percentage)
                .passed(percentage.compareTo(passingScore) >= 0)
                .passingScore(passingScore)
                .startTime(session.getStartTime())
                .completedAt(LocalDateTime.now())
                .build(), graded);
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
