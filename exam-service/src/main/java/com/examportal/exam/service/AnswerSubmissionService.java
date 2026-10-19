package com.examportal.exam.service;

import com.examportal.common.domain.dto.BlockResultResponse;
import com.examportal.common.domain.dto.SubmitAnswerRequest;
import com.examportal.common.domain.dto.SubmitAnswerResponse;
import com.examportal.common.domain.dto.ExamResultResponse;
import com.examportal.common.domain.dto.MySessionResponse;
import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.SavedAnswerResponse;
import com.examportal.common.domain.dto.SessionResultResponse;
import com.examportal.common.domain.entity.Exam;
import com.examportal.common.domain.entity.GradingStatus;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

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
    private final ExamAccessPolicy accessPolicy;
    private final TransactionalOperator transactionalOperator;
    // B12a: results per block
    private final BlockResultRepository blockResults;

    private static final int MAX_PAGE_SIZE = 50;

    /**
     * Submit an answer for a question
     */
    public Mono<SubmitAnswerResponse> submitAnswer(String sessionId, SubmitAnswerRequest request, UUID userId) {
        log.debug("Submitting answer for session {} question {}", sessionId, request.getQuestionId());

        return sessionRepository.findBySessionId(sessionId)
                .switchIfEmpty(Mono.error(ExamException.sessionNotFound(sessionId)))
                // Someone else's session answers 404, same as an unknown id
                .flatMap(session -> accessPolicy.requireOwnSession(userId, session))
                .<ExamSession>flatMap(session -> {
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
                        request.getQuestionId()));
    }

    /**
     * Submit the exam and calculate results.
     * Idempotent: a session that is already completed answers with its stored result.
     * The response follows the same visibility rule as the result endpoint.
     */
    public Mono<SessionResultResponse> submitExam(String sessionId, UUID userId) {
        log.info("Submitting exam for session {}", sessionId);

        return sessionRepository.findBySessionId(sessionId)
                .switchIfEmpty(Mono.error(ExamException.sessionNotFound(sessionId)))
                .flatMap(session -> accessPolicy.requireOwnSession(userId, session))
                .flatMap(session -> {
                    // A no-op when the session is already completed (also by a concurrent submit);
                    // either way the answer is built from what is stored
                    Mono<Void> completion = session.isActive() ? completeSession(session).then() : Mono.empty();
                    return completion
                            .then(Mono.defer(() -> sessionRepository.findBySessionId(sessionId)))
                            .flatMap(this::visibleResult);
                })
                .doOnSuccess(result -> log.info("Exam submitted for session {}", sessionId));
    }

    /**
     * Result of a completed session for its owner
     */
    public Mono<SessionResultResponse> getSessionResult(String sessionId, UUID userId) {
        log.debug("Getting result for session {}", sessionId);

        return sessionRepository.findBySessionId(sessionId)
                .switchIfEmpty(Mono.error(ExamException.sessionNotFound(sessionId)))
                // Someone else's session answers 404, same as an unknown id
                .flatMap(session -> accessPolicy.requireOwnSession(userId, session))
                .flatMap(session -> {
                    if (session.getStatus() != SessionStatus.COMPLETED) {
                        return Mono.error(ExamException.sessionNotCompleted());
                    }
                    return visibleResult(session);
                });
    }

    /**
     * The answers stored in one of the caller's own sessions, in any status, exactly as they were
     * given. For restoring the participant's screen after a reload: no grading, no answer key.
     */
    public Mono<List<SavedAnswerResponse>> getSavedAnswers(String sessionId, UUID userId) {
        return sessionRepository.findBySessionId(sessionId)
                .switchIfEmpty(Mono.error(ExamException.sessionNotFound(sessionId)))
                // Someone else's session answers 404, same as an unknown id
                .flatMap(session -> accessPolicy.requireOwnSession(userId, session))
                .flatMap(this::savedAnswers);
    }

    /**
     * The stored answers of a session whose owner the caller has already checked
     */
    public Mono<List<SavedAnswerResponse>> savedAnswers(ExamSession session) {
        return answerRepository.findBySessionId(session.getId())
                .map(answer -> SavedAnswerResponse.builder()
                        .questionId(answer.getQuestionId())
                        .selectedOptionIds(answer.getSelectedOptionIds() != null
                                ? Arrays.asList(answer.getSelectedOptionIds())
                                : List.of())
                        .textAnswer(answer.getTextAnswer())
                        .answeredAt(answer.getAnsweredAt())
                        .build())
                .sort(Comparator.comparing(SavedAnswerResponse::getQuestionId))
                .collectList();
    }

    /**
     * One page of the caller's own sessions, newest first. A score is shown only where the result
     * endpoint would show it (resultsWithheld is the one rule for both).
     */
    public Mono<PageResponse<MySessionResponse>> getMySessions(UUID userId, int page, int size) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        return sessionRepository.countByUserId(userId)
                .flatMap(total -> sessionRepository.findPageByUserId(userId, pageSize, (long) pageNumber * pageSize)
                        .collectList()
                        .flatMap(sessions -> {
                            if (sessions.isEmpty()) {
                                return Mono.just(PageResponse.<MySessionResponse>of(List.of(), pageNumber, pageSize, total));
                            }
                            Set<Long> examIds = sessions.stream().map(ExamSession::getExamId).collect(Collectors.toSet());
                            // Also exams that were deleted since: the session and its title stay visible to its owner
                            return examRepository.findAllById(examIds)
                                    .collectMap(Exam::getId)
                                    .map(exams -> PageResponse.of(
                                            sessions.stream()
                                                    .map(session -> toMySession(session, exams.get(session.getExamId())))
                                                    .toList(),
                                            pageNumber, pageSize, total));
                        }));
    }

    private MySessionResponse toMySession(ExamSession session, Exam exam) {
        MySessionResponse.MySessionResponseBuilder row = MySessionResponse.builder()
                .sessionId(session.getSessionId())
                .examId(session.getExamId())
                .examTitle(exam != null ? exam.getTitle() : null)
                .status(session.getStatus())
                .startTime(session.getStartTime())
                .endTime(session.getEndTime())
                .submittedAt(session.getSubmittedAt())
                .gradingStatus(gradingStatus(session));

        boolean completed = session.getStatus() == SessionStatus.COMPLETED;
        if (!completed || exam == null || resultsWithheld(exam, session)) {
            // No date while a grader is awaited: nobody knows when that will be done
            return row.resultsAvailable(false)
                    .resultsAvailableAt(completed && exam != null && !awaitsGrading(session) ? exam.getEndTime() : null)
                    .build();
        }
        // The same stored figures the result endpoint reports
        return row.resultsAvailable(true)
                .totalScore(session.getScore())
                .maxScore(session.getTotalPoints())
                .percentageScore(session.getPercentage())
                .passed(session.getPassed())
                .build();
    }

    /**
     * Whether the score of a finished session must not be shown to the participant yet: an
     * open-ended answer still waits for a grader (the score is not final), or the exam does not
     * show results immediately and has not ended. The one visibility rule, used by submit, the
     * result endpoint and the list of own sessions.
     */
    private boolean resultsWithheld(Exam exam, ExamSession session) {
        return awaitsGrading(session)
                || (!Boolean.TRUE.equals(exam.getShowResultsImmediately())
                && LocalDateTime.now().isBefore(exam.getEndTime()));
    }

    private boolean awaitsGrading(ExamSession session) {
        return session.getGradingStatus() == GradingStatus.PENDING;
    }

    private GradingStatus gradingStatus(ExamSession session) {
        return session.getGradingStatus() != null ? session.getGradingStatus() : GradingStatus.NOT_REQUIRED;
    }

    /**
     * What the participant may see of a finished session; shared by submit and the result endpoint.
     * While the result is withheld (resultsWithheld) only a submission summary is returned.
     * Never contains the answer key.
     */
    private Mono<SessionResultResponse> visibleResult(ExamSession session) {
        return examRepository.findById(session.getExamId())
                .flatMap(exam -> {
                    SessionResultResponse.SessionResultResponseBuilder summary = SessionResultResponse.builder()
                            .sessionId(session.getSessionId())
                            .examId(session.getExamId())
                            .status(session.getStatus())
                            .submittedAt(session.getSubmittedAt())
                            .gradingStatus(gradingStatus(session));

                    if (resultsWithheld(exam, session)) {
                        return Mono.just(summary
                                .resultsAvailable(false)
                                // No date while a grader is awaited: nobody knows when that will be done
                                .resultsAvailableAt(awaitsGrading(session) ? null : exam.getEndTime())
                                .build());
                    }
                    return storedResult(session)
                            .flatMap(result -> participantBlocks(exam, session)
                                    .map(blocks -> summary
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
                                            .blocks(blocks.orElse(null))
                                            .build()));
                });
    }

    /**
     * B12a: the blocks a participant may see of a finished session. Only when the exam shows a breakdown
     * (show_block_breakdown), and only the blocks shown to participants (show_result_to_participant): the others
     * are not in the answer at all, decided here on the server. Empty Optional = no breakdown for this exam.
     */
    private Mono<java.util.Optional<List<BlockResultResponse>>> participantBlocks(Exam exam, ExamSession session) {
        if (Boolean.FALSE.equals(exam.getShowBlockBreakdown())) {
            return Mono.just(java.util.Optional.empty());
        }
        return blockResults.forSession(exam.getId(), session.getId())
                .filter(block -> Boolean.TRUE.equals(block.getShowResultToParticipant()))
                // The flag itself is for the people who manage the exam
                .map(block -> block.toBuilder().showResultToParticipant(null).build())
                .collectList()
                .map(java.util.Optional::of);
    }

    /**
     * Complete one session that ran out of time (same completion path as a manual submit).
     * A no-op when it has already been completed.
     */
    public Mono<Void> completeExpiredSession(ExamSession session) {
        return completeSession(session).then();
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

    /**
     * awaitsGrader: at least one open-ended answer could not be graded here and waits for a person
     */
    private record GradedSession(ExamResultResponse result, List<UserAnswer> answers, boolean awaitsGrader) {
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
                                    answer.getId(), answer.getIsCorrect(), answer.getPointsEarned(),
                                    Boolean.TRUE.equals(answer.getNeedsManualReview())))
                            // The score is provisional while an answer waits for a grader (PENDING)
                            .then(sessionRepository.saveScore(session.getId(), result.getTotalScore(),
                                    result.getMaxScore(), result.getPercentageScore(), result.getPassed(),
                                    (graded.awaitsGrader() ? GradingStatus.PENDING : GradingStatus.NOT_REQUIRED).name()))
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
     * An open-ended answer with text is not graded here: it earns zero for now and is marked for a
     * person (needs_manual_review); until then it counts as neither correct nor incorrect.
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
        boolean awaitsGrader = false;
        List<UserAnswer> graded = new ArrayList<>();

        for (Question question : questions) {
            totalPoints = totalPoints.add(question.getPoints());

            UserAnswer answer = answerByQuestion.get(question.getId());
            if (answer == null) {
                unansweredCount++;
                continue;
            }

            if (question.getQuestionType() == QuestionType.OPEN_ENDED
                    && answer.getTextAnswer() != null && !answer.getTextAnswer().isBlank()) {
                answer.setPointsEarned(BigDecimal.ZERO);
                answer.setIsCorrect(null);
                answer.setNeedsManualReview(true);
                awaitsGrader = true;
                graded.add(answer);
                continue;
            }
            answer.setNeedsManualReview(false);

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
                .build(), graded, awaitsGrader);
    }

    private boolean isAnswerCorrect(Question question, UserAnswer answer,
                                    java.util.Collection<QuestionOption> correctOptions) {
        if (correctOptions.isEmpty()) {
            // No answer key: nothing to compare against
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
