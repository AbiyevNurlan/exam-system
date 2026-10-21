package com.examportal.exam.structure;

import com.examportal.common.domain.dto.OptionDTO;
import com.examportal.common.domain.dto.QuestionDTO;
import com.examportal.common.domain.dto.media.QuestionMediaRef;
import com.examportal.common.domain.entity.QuestionType;
import com.examportal.exam.exception.MediaException;
import com.examportal.exam.repository.MediaLinkRepository;
import com.examportal.exam.structure.ExamState.BlockState;
import com.examportal.exam.structure.ExamState.MediaRef;
import com.examportal.exam.structure.ExamState.OptionState;
import com.examportal.exam.structure.ExamState.QuestionState;
import com.examportal.exam.structure.StructurePlan.BlockPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Brings the blocks, questions and options of an exam to a StructurePlan IN PLACE (B12a): a row whose id
 * is in the plan is updated (only when something of it changed) and keeps its id, a row without an id
 * is inserted, a row of the exam that the plan no longer has is deleted. A question can move to another
 * block of the same exam. Media links of a question or option are written again only when they changed.
 *
 * Only for an exam without sessions: the caller holds the exam row lock and has checked that no session
 * exists (ExamEditLock), so nothing answered refers to a question that goes. Runs inside the caller's
 * transaction; every statement is sequential (one connection). Block positions are written 1..n; their
 * unique key is checked at commit, so blocks can swap places.
 */
@Repository
@RequiredArgsConstructor
public class ExamStructureWriter {

    private final DatabaseClient databaseClient;
    private final MediaLinkRepository linkRepository;

    public Mono<Void> apply(Long examId, ExamState current, StructurePlan plan) {
        Map<Long, BlockState> blocks = new HashMap<>();
        Map<Long, QuestionState> questions = new HashMap<>();
        for (BlockState block : current.blocks()) {
            blocks.put(block.id(), block);
            for (QuestionState question : block.questions()) {
                questions.put(question.id(), question);
            }
        }
        Set<Long> keptBlocks = new HashSet<>();
        Set<Long> keptQuestions = new HashSet<>();
        int[] order = {0};

        Flux<Void> writes = Flux.fromIterable(indexed(plan.blocks()))
                .concatMap(entry -> {
                    BlockPlan block = entry.value();
                    int position = entry.index() + 1;
                    // An id given twice: the second one is a new block (StructurePlanner already prevents it)
                    Mono<Long> blockId = block.id() != null && blocks.containsKey(block.id()) && !keptBlocks.contains(block.id())
                            ? updateBlock(examId, blocks.get(block.id()), block, position).thenReturn(block.id())
                            : insertBlock(examId, block, position);
                    return blockId.flatMapMany(id -> {
                        keptBlocks.add(id);
                        return Flux.fromIterable(block.questions())
                                .concatMap(question -> {
                                    int orderIndex = order[0]++;
                                    question.setOrderIndex(orderIndex);
                                    QuestionState stored = question.getId() != null ? questions.get(question.getId()) : null;
                                    if (stored != null && keptQuestions.add(stored.id())) {
                                        return updateQuestion(examId, id, orderIndex, stored, question);
                                    }
                                    return insertQuestion(examId, id, orderIndex, question);
                                });
                    });
                });

        return writes.then(Mono.defer(() -> {
            List<Long> goneQuestions = questions.keySet().stream().filter(id -> !keptQuestions.contains(id)).toList();
            List<Long> goneBlocks = blocks.keySet().stream().filter(id -> !keptBlocks.contains(id)).toList();
            Mono<Void> deleteQuestions = goneQuestions.isEmpty() ? Mono.empty()
                    : databaseClient.sql("DELETE FROM questions WHERE exam_id = :examId AND id = ANY(:ids)")
                            .bind("examId", examId).bind("ids", goneQuestions.toArray(new Long[0])).then();
            Mono<Void> deleteBlocks = goneBlocks.isEmpty() ? Mono.empty()
                    : databaseClient.sql("DELETE FROM exam_blocks WHERE exam_id = :examId AND id = ANY(:ids)")
                            .bind("examId", examId).bind("ids", goneBlocks.toArray(new Long[0])).then();
            return deleteQuestions.then(deleteBlocks);
        }));
    }

    // ==================== Blocks ====================

    private Mono<Long> insertBlock(Long examId, BlockPlan block, int position) {
        return bindBlock(databaseClient.sql("""
                        INSERT INTO exam_blocks (exam_id, position, title, instructions, time_limit_minutes, break_after_minutes,
                                                 free_navigation, shuffle_questions, show_result_to_participant)
                        VALUES (:examId, :position, :title, :instructions, :timeLimit, :breakAfter, :freeNavigation,
                                :shuffle, :showResult)
                        RETURNING id
                        """), examId, block, position)
                .map(row -> row.get("id", Long.class))
                .one();
    }

    private Mono<Void> updateBlock(Long examId, BlockState stored, BlockPlan block, int position) {
        boolean same = stored.position() == position
                && Objects.equals(stored.title(), block.title())
                && Objects.equals(stored.instructions(), block.instructions())
                && Objects.equals(stored.timeLimitMinutes(), block.timeLimitMinutes())
                && stored.breakAfterMinutes() == block.breakAfterMinutes()
                && stored.freeNavigation() == block.freeNavigation()
                && stored.shuffleQuestions() == block.shuffleQuestions()
                && stored.showResultToParticipant() == block.showResultToParticipant();
        if (same) {
            return Mono.empty();
        }
        return bindBlock(databaseClient.sql("""
                        UPDATE exam_blocks
                        SET position = :position, title = :title, instructions = :instructions,
                            time_limit_minutes = :timeLimit, break_after_minutes = :breakAfter,
                            free_navigation = :freeNavigation, shuffle_questions = :shuffle,
                            show_result_to_participant = :showResult, updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id AND exam_id = :examId
                        """).bind("id", stored.id()), examId, block, position)
                .then();
    }

    private DatabaseClient.GenericExecuteSpec bindBlock(DatabaseClient.GenericExecuteSpec spec, Long examId, BlockPlan block,
                                                        int position) {
        spec = spec.bind("examId", examId)
                .bind("position", position)
                .bind("title", block.title())
                .bind("breakAfter", block.breakAfterMinutes())
                .bind("freeNavigation", block.freeNavigation())
                .bind("shuffle", block.shuffleQuestions())
                .bind("showResult", block.showResultToParticipant());
        spec = block.instructions() != null ? spec.bind("instructions", block.instructions()) : spec.bindNull("instructions", String.class);
        return block.timeLimitMinutes() != null ? spec.bind("timeLimit", block.timeLimitMinutes()) : spec.bindNull("timeLimit", Integer.class);
    }

    // ==================== Questions ====================

    private Mono<Void> insertQuestion(Long examId, Long blockId, int orderIndex, QuestionDTO question) {
        return bindQuestion(databaseClient.sql("""
                        INSERT INTO questions (exam_id, block_id, question_text, question_type, points, order_index,
                                               allow_multiple_answers, is_required, explanation, active)
                        VALUES (:examId, :blockId, :text, :type, :points, :orderIndex, :multiple, :required, :explanation, true)
                        RETURNING id
                        """), examId, blockId, orderIndex, question)
                .map(row -> row.get("id", Long.class))
                .one()
                .flatMap(id -> attachQuestionMedia(id, question)
                        .then(writeOptions(id, question.getQuestionType(), List.of(), question.getOptions())));
    }

    private Mono<Void> updateQuestion(Long examId, Long blockId, int orderIndex, QuestionState stored, QuestionDTO question) {
        boolean same = Objects.equals(stored.blockId(), blockId)
                && stored.orderIndex() == orderIndex
                && Objects.equals(stored.questionText(), question.getQuestionText())
                && stored.questionType() == question.getQuestionType()
                && samePoints(stored.points(), points(question))
                && stored.allowMultipleAnswers() == Boolean.TRUE.equals(question.getAllowMultipleAnswers())
                && stored.required() == required(question)
                && Objects.equals(stored.explanation(), question.getExplanation());
        Mono<Void> row = same ? Mono.empty() : bindQuestion(databaseClient.sql("""
                        UPDATE questions
                        SET block_id = :blockId, question_text = :text, question_type = :type, points = :points,
                            order_index = :orderIndex, allow_multiple_answers = :multiple, is_required = :required,
                            explanation = :explanation, updated_at = CURRENT_TIMESTAMP, version = COALESCE(version, 0) + 1
                        WHERE id = :id AND exam_id = :examId
                        """).bind("id", stored.id()), examId, blockId, orderIndex, question).then();
        Mono<Void> media = sameMedia(stored.media(), requestedMedia(question)) ? Mono.empty()
                : databaseClient.sql("DELETE FROM question_media WHERE question_id = :id").bind("id", stored.id()).then()
                        .then(attachQuestionMedia(stored.id(), question));
        return row.then(media).then(writeOptions(stored.id(), question.getQuestionType(), stored.options(), question.getOptions()));
    }

    private DatabaseClient.GenericExecuteSpec bindQuestion(DatabaseClient.GenericExecuteSpec spec, Long examId, Long blockId,
                                                           int orderIndex, QuestionDTO question) {
        spec = spec.bind("examId", examId)
                .bind("blockId", blockId)
                .bind("text", question.getQuestionText())
                .bind("type", question.getQuestionType().name())
                .bind("points", points(question))
                .bind("orderIndex", orderIndex)
                .bind("multiple", Boolean.TRUE.equals(question.getAllowMultipleAnswers()))
                .bind("required", required(question));
        return question.getExplanation() != null ? spec.bind("explanation", question.getExplanation())
                : spec.bindNull("explanation", String.class);
    }

    private Mono<Void> attachQuestionMedia(Long questionId, QuestionDTO question) {
        List<MediaRef> media = requestedMedia(question);
        return Flux.fromIterable(media)
                .concatMap(ref -> linkRepository.attachToQuestion(questionId, ref.mediaId(), ref.position(), ref.maxPlays())
                        .flatMap(ExamStructureWriter::requireAttached))
                .then();
    }

    // ==================== Options ====================

    private Mono<Void> writeOptions(Long questionId, QuestionType type, List<OptionState> stored, List<OptionDTO> requested) {
        // Every item of ORDERING and every pair of MATCHING is part of the answer key, and so is every accepted
        // answer of SHORT_ANSWER; the right side of a pair exists only for MATCHING
        boolean allCorrect = type == QuestionType.ORDERING || type == QuestionType.MATCHING || type == QuestionType.SHORT_ANSWER;
        Map<Long, OptionState> byId = new HashMap<>();
        stored.forEach(option -> byId.put(option.id(), option));
        List<OptionDTO> options = requested == null ? List.of() : requested;
        Set<Long> kept = new HashSet<>();

        Flux<Void> writes = Flux.fromIterable(indexed(options))
                .concatMap(entry -> {
                    OptionDTO option = entry.value();
                    int orderIndex = entry.index();
                    option.setOrderIndex(orderIndex);
                    if (allCorrect) {
                        option.setIsCorrect(true);
                    }
                    String matchText = type == QuestionType.MATCHING ? blankToNull(option.getMatchText()) : null;
                    OptionState existing = option.getId() != null && !kept.contains(option.getId()) ? byId.get(option.getId()) : null;
                    if (existing == null) {
                        return bindMatch(databaseClient.sql("""
                                        INSERT INTO question_options (question_id, option_text, match_text, is_correct, order_index)
                                        VALUES (:questionId, :text, :match, :correct, :orderIndex) RETURNING id
                                        """), matchText)
                                .bind("questionId", questionId)
                                .bind("text", option.getOptionText())
                                .bind("correct", Boolean.TRUE.equals(option.getIsCorrect()))
                                .bind("orderIndex", orderIndex)
                                .map(row -> row.get("id", Long.class))
                                .one()
                                .flatMap(id -> attachOptionMedia(id, option.getMediaIds()));
                    }
                    kept.add(existing.id());
                    boolean same = existing.orderIndex() == orderIndex
                            && Objects.equals(existing.optionText(), option.getOptionText())
                            && Objects.equals(existing.matchText(), matchText)
                            && existing.correct() == Boolean.TRUE.equals(option.getIsCorrect());
                    Mono<Void> row = same ? Mono.empty() : bindMatch(databaseClient.sql("""
                                    UPDATE question_options SET option_text = :text, match_text = :match, is_correct = :correct,
                                           order_index = :orderIndex, updated_at = CURRENT_TIMESTAMP
                                    WHERE id = :id AND question_id = :questionId
                                    """), matchText)
                            .bind("id", existing.id())
                            .bind("questionId", questionId)
                            .bind("text", option.getOptionText())
                            .bind("correct", Boolean.TRUE.equals(option.getIsCorrect()))
                            .bind("orderIndex", orderIndex)
                            .then();
                    List<java.util.UUID> wanted = option.getMediaIds() == null ? List.of() : option.getMediaIds();
                    boolean sameMedia = existing.media().stream().map(MediaRef::mediaId).toList().equals(wanted);
                    Mono<Void> media = sameMedia ? Mono.empty()
                            : databaseClient.sql("DELETE FROM option_media WHERE option_id = :id").bind("id", existing.id()).then()
                                    .then(attachOptionMedia(existing.id(), wanted));
                    return row.then(media);
                });

        return writes.then(Mono.defer(() -> {
            List<Long> gone = byId.keySet().stream().filter(id -> !kept.contains(id)).toList();
            return gone.isEmpty() ? Mono.<Void>empty()
                    : databaseClient.sql("DELETE FROM question_options WHERE question_id = :questionId AND id = ANY(:ids)")
                            .bind("questionId", questionId).bind("ids", gone.toArray(new Long[0])).then();
        }));
    }

    private static DatabaseClient.GenericExecuteSpec bindMatch(DatabaseClient.GenericExecuteSpec spec, String matchText) {
        return matchText != null ? spec.bind("match", matchText) : spec.bindNull("match", String.class);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private Mono<Void> attachOptionMedia(Long optionId, List<java.util.UUID> mediaIds) {
        if (mediaIds == null || mediaIds.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(mediaIds)
                .concatMap(mediaId -> linkRepository.attachToOption(optionId, mediaId).flatMap(ExamStructureWriter::requireAttached))
                .then();
    }

    // ==================== Helpers ====================

    /**
     * The media of a question as the request wants them: position as given, otherwise the place in the list
     */
    static List<MediaRef> requestedMedia(QuestionDTO question) {
        if (question.getMedia() == null) {
            return List.of();
        }
        List<MediaRef> media = new ArrayList<>();
        for (int i = 0; i < question.getMedia().size(); i++) {
            QuestionMediaRef ref = question.getMedia().get(i);
            media.add(new MediaRef(ref.getMediaId(), ref.getPosition() != null ? ref.getPosition() : i, ref.getMaxPlays(), null));
        }
        return media;
    }

    private static boolean sameMedia(List<MediaRef> stored, List<MediaRef> wanted) {
        if (stored.size() != wanted.size()) {
            return false;
        }
        Set<String> a = new HashSet<>();
        stored.forEach(ref -> a.add(ref.mediaId() + "/" + ref.position() + "/" + ref.maxPlays()));
        Set<String> b = new HashSet<>();
        wanted.forEach(ref -> b.add(ref.mediaId() + "/" + ref.position() + "/" + ref.maxPlays()));
        return a.equals(b);
    }

    static BigDecimal points(QuestionDTO question) {
        return question.getPoints() != null ? question.getPoints() : BigDecimal.ONE;
    }

    static boolean required(QuestionDTO question) {
        return question.getIsRequired() == null || question.getIsRequired();
    }

    private static boolean samePoints(BigDecimal a, BigDecimal b) {
        return a != null && b != null ? a.compareTo(b) == 0 : a == b;
    }

    /**
     * A media that stopped being ACTIVE between the check and the write fails the whole save
     */
    private static Mono<Long> requireAttached(Long written) {
        return written == 1 ? Mono.just(written) : Mono.error(MediaException.notAttachable("it was deleted in the meantime"));
    }

    private record Indexed<T>(int index, T value) {
    }

    private static <T> List<Indexed<T>> indexed(List<T> list) {
        List<Indexed<T>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            result.add(new Indexed<>(i, list.get(i)));
        }
        return result;
    }
}
