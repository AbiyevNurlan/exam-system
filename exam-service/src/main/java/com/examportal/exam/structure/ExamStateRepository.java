package com.examportal.exam.structure;

import com.examportal.common.domain.entity.ExamAccessType;
import com.examportal.common.domain.entity.ExamStatus;
import com.examportal.common.domain.entity.QuestionType;
import com.examportal.common.domain.entity.TimingMode;
import com.examportal.exam.structure.ExamState.BlockState;
import com.examportal.exam.structure.ExamState.MediaRef;
import com.examportal.exam.structure.ExamState.OptionState;
import com.examportal.exam.structure.ExamState.QuestionState;
import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads an ExamState: the exam row, its blocks, its active questions with options and the media links
 * of both, in five statements whatever the size of the exam. Run inside the caller's transaction when
 * the state is compared with another one (the history of a change).
 */
@Repository
@RequiredArgsConstructor
public class ExamStateRepository {

    private final DatabaseClient databaseClient;

    private record ExamRow(Long id, UUID createdBy, String title, String description, ExamStatus status,
                           Integer durationMinutes, LocalDateTime startTime, LocalDateTime endTime,
                           BigDecimal passingScore, Boolean shuffleQuestions, Boolean shuffleOptions,
                           Boolean showResultsImmediately, Integer maxAttempts, Integer maxAttemptsPerDay,
                           Long categoryId, ExamAccessType accessType, TimingMode timingMode,
                           boolean allowReturn, boolean allowSkipBreak, boolean showBlockBreakdown) {
    }

    private record BlockRow(Long id, int position, String title, String instructions, Integer timeLimit,
                            int breakAfter, boolean freeNavigation, boolean shuffle, boolean showResult) {
    }

    private record QuestionRow(Long id, Long blockId, int orderIndex, String text, QuestionType type, BigDecimal points,
                               boolean multiple, boolean required, String explanation) {
    }

    private record OptionRow(Long id, Long questionId, int orderIndex, String text, boolean correct) {
    }

    private record LinkRow(String target, Long targetId, UUID mediaId, int position, Integer maxPlays, String status) {
    }

    /**
     * The state of an exam, or empty when no such exam exists (a soft-deleted one is still read)
     */
    public Mono<ExamState> load(Long examId) {
        Mono<ExamRow> exam = databaseClient.sql("""
                        SELECT id, created_by, title, description, status, duration_minutes, start_time, end_time,
                               passing_score, shuffle_questions, shuffle_options, show_results_immediately, max_attempts,
                               max_attempts_per_day::int AS max_attempts_per_day, category_id, access_type, timing_mode,
                               allow_return_to_previous_blocks, allow_skip_break, show_block_breakdown
                        FROM exams WHERE id = :examId
                        """)
                .bind("examId", examId)
                .map(row -> new ExamRow(row.get("id", Long.class), row.get("created_by", UUID.class),
                        row.get("title", String.class), row.get("description", String.class),
                        ExamStatus.valueOf(row.get("status", String.class)), row.get("duration_minutes", Integer.class),
                        row.get("start_time", LocalDateTime.class), row.get("end_time", LocalDateTime.class),
                        row.get("passing_score", BigDecimal.class), row.get("shuffle_questions", Boolean.class),
                        row.get("shuffle_options", Boolean.class), row.get("show_results_immediately", Boolean.class),
                        row.get("max_attempts", Integer.class), row.get("max_attempts_per_day", Integer.class),
                        row.get("category_id", Long.class),
                        row.get("access_type", String.class) == null ? ExamAccessType.PUBLIC
                                : ExamAccessType.valueOf(row.get("access_type", String.class)),
                        TimingMode.valueOf(row.get("timing_mode", String.class)),
                        flag(row, "allow_return_to_previous_blocks"), flag(row, "allow_skip_break"),
                        flag(row, "show_block_breakdown")))
                .one();

        Mono<List<BlockRow>> blocks = databaseClient.sql("""
                        SELECT id, position::int AS position, title, instructions, time_limit_minutes::int AS time_limit,
                               break_after_minutes::int AS break_after, free_navigation, shuffle_questions,
                               show_result_to_participant
                        FROM exam_blocks WHERE exam_id = :examId ORDER BY position, id
                        """)
                .bind("examId", examId)
                .map(row -> new BlockRow(row.get("id", Long.class), number(row, "position"), row.get("title", String.class),
                        row.get("instructions", String.class), row.get("time_limit", Integer.class), number(row, "break_after"),
                        flag(row, "free_navigation"), flag(row, "shuffle_questions"), flag(row, "show_result_to_participant")))
                .all().collectList();

        Mono<List<QuestionRow>> questions = databaseClient.sql("""
                        SELECT id, block_id, order_index, question_text, question_type, points, allow_multiple_answers,
                               is_required, explanation
                        FROM questions WHERE exam_id = :examId AND active = true ORDER BY order_index, id
                        """)
                .bind("examId", examId)
                .map(row -> new QuestionRow(row.get("id", Long.class), row.get("block_id", Long.class),
                        number(row, "order_index"), row.get("question_text", String.class),
                        QuestionType.valueOf(row.get("question_type", String.class)), row.get("points", BigDecimal.class),
                        flag(row, "allow_multiple_answers"), row.get("is_required", Boolean.class) == null
                                || row.get("is_required", Boolean.class), row.get("explanation", String.class)))
                .all().collectList();

        Mono<List<OptionRow>> options = databaseClient.sql("""
                        SELECT o.id, o.question_id, o.order_index, o.option_text, o.is_correct
                        FROM question_options o JOIN questions q ON q.id = o.question_id
                        WHERE q.exam_id = :examId AND q.active = true ORDER BY o.question_id, o.order_index, o.id
                        """)
                .bind("examId", examId)
                .map(row -> new OptionRow(row.get("id", Long.class), row.get("question_id", Long.class),
                        number(row, "order_index"), row.get("option_text", String.class), flag(row, "is_correct")))
                .all().collectList();

        // Every link, whatever the status of its media: a link to a media that is no longer ACTIVE is
        // what the readiness check reports as missing
        Mono<List<LinkRow>> links = databaseClient.sql("""
                        SELECT 'QUESTION' AS target, qm.question_id AS target_id, qm.media_id, qm.position::int AS position,
                               qm.max_plays::int AS max_plays, m.status
                        FROM question_media qm JOIN questions q ON q.id = qm.question_id
                        JOIN media_assets m ON m.id = qm.media_id
                        WHERE q.exam_id = :examId
                        UNION ALL
                        SELECT 'OPTION', om.option_id, om.media_id, 0, NULL::int, m.status
                        FROM option_media om JOIN question_options o ON o.id = om.option_id
                        JOIN questions q ON q.id = o.question_id JOIN media_assets m ON m.id = om.media_id
                        WHERE q.exam_id = :examId
                        ORDER BY 1, 2, 4, 3
                        """)
                .bind("examId", examId)
                .map(row -> new LinkRow(row.get("target", String.class), row.get("target_id", Long.class),
                        row.get("media_id", UUID.class), number(row, "position"), row.get("max_plays", Integer.class),
                        row.get("status", String.class)))
                .all().collectList();

        // One statement at a time: inside a transaction they share one connection
        return exam.flatMap(e -> blocks.flatMap(b -> questions.flatMap(q -> options.flatMap(o -> links
                .map(l -> assemble(e, b, q, o, l))))));
    }

    private static ExamState assemble(ExamRow exam, List<BlockRow> blocks, List<QuestionRow> questions,
                                      List<OptionRow> options, List<LinkRow> links) {
        Map<Long, List<MediaRef>> questionMedia = new HashMap<>();
        Map<Long, List<MediaRef>> optionMedia = new HashMap<>();
        for (LinkRow link : links) {
            ("QUESTION".equals(link.target()) ? questionMedia : optionMedia)
                    .computeIfAbsent(link.targetId(), id -> new ArrayList<>())
                    .add(new MediaRef(link.mediaId(), link.position(), link.maxPlays(), link.status()));
        }
        Map<Long, List<OptionState>> optionsByQuestion = new HashMap<>();
        for (OptionRow option : options) {
            optionsByQuestion.computeIfAbsent(option.questionId(), id -> new ArrayList<>())
                    .add(new OptionState(option.id(), option.orderIndex(), option.text(), option.correct(),
                            List.copyOf(optionMedia.getOrDefault(option.id(), List.of()))));
        }
        Map<Long, List<QuestionState>> questionsByBlock = new HashMap<>();
        for (QuestionRow question : questions) {
            questionsByBlock.computeIfAbsent(question.blockId(), id -> new ArrayList<>())
                    .add(new QuestionState(question.id(), question.blockId(), question.orderIndex(), question.text(),
                            question.type(), question.points(), question.multiple(), question.required(),
                            question.explanation(), List.copyOf(questionMedia.getOrDefault(question.id(), List.of())),
                            List.copyOf(optionsByQuestion.getOrDefault(question.id(), List.of()))));
        }
        List<BlockState> blockStates = blocks.stream()
                .map(block -> new BlockState(block.id(), block.position(), block.title(), block.instructions(),
                        block.timeLimit(), block.breakAfter(), block.freeNavigation(), block.shuffle(), block.showResult(),
                        List.copyOf(questionsByBlock.getOrDefault(block.id(), List.of()))))
                .toList();
        return new ExamState(exam.id(), exam.createdBy(), exam.title(), exam.description(), exam.status(),
                exam.durationMinutes(), exam.startTime(), exam.endTime(), exam.passingScore(), exam.shuffleQuestions(),
                exam.shuffleOptions(), exam.showResultsImmediately(), exam.maxAttempts(), exam.maxAttemptsPerDay(),
                exam.categoryId(), exam.accessType(), exam.timingMode(), exam.allowReturn(), exam.allowSkipBreak(),
                exam.showBlockBreakdown(), blockStates);
    }

    private static boolean flag(Readable row, String column) {
        return Boolean.TRUE.equals(row.get(column, Boolean.class));
    }

    private static int number(Readable row, String column) {
        Integer value = row.get(column, Integer.class);
        return value == null ? 0 : value;
    }
}
