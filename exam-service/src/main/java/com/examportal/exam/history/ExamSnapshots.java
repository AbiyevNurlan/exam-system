package com.examportal.exam.history;

import com.examportal.common.config.UtcDateTimeModule;
import com.examportal.exam.structure.ExamState;
import com.examportal.exam.structure.ExamState.BlockState;
import com.examportal.exam.structure.ExamState.MediaRef;
import com.examportal.exam.structure.ExamState.OptionState;
import com.examportal.exam.structure.ExamState.QuestionState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An exam as plain JSON values (B12a): maps, lists, strings, numbers and booleans only, so that the same
 * content always gives the same canonical JSON and the same SHA-256. Used for published snapshots, for the
 * content of a deleted exam or question in the history, and for the source of a clone.
 * Canonical JSON: keys sorted, no white space, numbers as stored (2.00 stays 2.00), times as UTC ISO strings.
 */
public final class ExamSnapshots {

    // Option labels A, B, C ... as participants see them
    private static final String LABELS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .build();

    private ExamSnapshots() {
    }

    public static String label(int index) {
        return index < LABELS.length() ? String.valueOf(LABELS.charAt(index)) : String.valueOf(index + 1);
    }

    public static String time(LocalDateTime value) {
        return value == null ? null : UtcDateTimeModule.format(value);
    }

    /**
     * The whole exam: settings, audience, blocks with their questions, options with the answer key, media ids
     */
    public static Map<String, Object> exam(ExamState state) {
        Map<String, Object> exam = new LinkedHashMap<>();
        exam.put("id", state.id());
        exam.put("title", state.title());
        exam.put("description", state.description());
        exam.put("status", state.status() == null ? null : state.status().name());
        exam.put("createdBy", state.createdBy() == null ? null : state.createdBy().toString());
        exam.put("settings", settings(state));
        Map<String, Object> audience = new LinkedHashMap<>();
        audience.put("accessType", state.accessType() == null ? null : state.accessType().name());
        audience.put("groupIds", state.audienceGroupIds());
        exam.put("audience", audience);
        List<Object> blocks = new ArrayList<>();
        int number = 0;
        for (int b = 0; b < state.blocks().size(); b++) {
            BlockState block = state.blocks().get(b);
            Map<String, Object> blockMap = block(block, b + 1);
            List<Object> questions = new ArrayList<>();
            for (QuestionState question : block.questions()) {
                questions.add(question(question, ++number, null));
            }
            blockMap.put("questions", questions);
            blocks.add(blockMap);
        }
        exam.put("blocks", blocks);
        exam.put("questionCount", number);
        exam.put("totalMinutes", state.totalMinutes());
        return exam;
    }

    public static Map<String, Object> settings(ExamState state) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("durationMinutes", state.durationMinutes());
        settings.put("startTime", time(state.startTime()));
        settings.put("endTime", time(state.endTime()));
        settings.put("passingScore", state.passingScore());
        settings.put("shuffleQuestions", state.shuffleQuestions());
        settings.put("shuffleOptions", state.shuffleOptions());
        settings.put("showResultsImmediately", state.showResultsImmediately());
        settings.put("maxAttempts", state.maxAttempts());
        settings.put("maxAttemptsPerDay", state.maxAttemptsPerDay());
        settings.put("categoryId", state.categoryId());
        settings.put("timingMode", state.timingMode() == null ? null : state.timingMode().name());
        settings.put("allowReturnToPreviousBlocks", state.allowReturnToPreviousBlocks());
        settings.put("allowSkipBreak", state.allowSkipBreak());
        settings.put("showBlockBreakdown", state.showBlockBreakdown());
        return settings;
    }

    public static Map<String, Object> block(BlockState block, int number) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", block.id());
        map.put("number", number);
        map.put("title", block.title());
        map.put("instructions", block.instructions());
        map.put("timeLimitMinutes", block.timeLimitMinutes());
        map.put("breakAfterMinutes", block.breakAfterMinutes());
        map.put("freeNavigation", block.freeNavigation());
        map.put("shuffleQuestions", block.shuffleQuestions());
        map.put("showResultToParticipant", block.showResultToParticipant());
        return map;
    }

    /**
     * A question with everything that decides its result. blockRef (id, title, number) is added when given.
     */
    public static Map<String, Object> question(QuestionState question, int number, Map<String, Object> blockRef) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", question.id());
        map.put("number", number);
        if (blockRef != null) {
            map.put("block", blockRef);
        }
        map.put("type", question.questionType() == null ? null : question.questionType().name());
        map.put("text", question.questionText());
        map.put("points", question.points());
        map.put("allowMultipleAnswers", question.allowMultipleAnswers());
        map.put("required", question.required());
        map.put("explanation", question.explanation());
        // LINEAR_SCALE only: every other question keeps exactly the keys it was hashed with
        if (question.scale() != null) {
            map.put("scale", scale(question.scale()));
        }
        map.put("media", media(question.media()));
        map.put("options", options(question.options()));
        return map;
    }

    // The scale of LINEAR_SCALE as plain values (null for every other question)
    public static Map<String, Object> scale(ExamState.ScaleState scale) {
        if (scale == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("min", scale.min());
        map.put("max", scale.max());
        map.put("minLabel", scale.minLabel());
        map.put("maxLabel", scale.maxLabel());
        return map;
    }

    public static List<Object> options(List<OptionState> options) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            OptionState option = options.get(i);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", option.id());
            map.put("label", label(i));
            map.put("text", option.optionText());
            // MATCHING only: older versions (and every other type) keep exactly the keys they were hashed with
            if (option.matchText() != null) {
                map.put("matchText", option.matchText());
            }
            map.put("correct", option.correct());
            map.put("mediaIds", option.media().stream().map(ref -> ref.mediaId().toString()).toList());
            list.add(map);
        }
        return list;
    }

    public static List<Object> media(List<MediaRef> media) {
        List<Object> list = new ArrayList<>();
        for (MediaRef ref : media) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("mediaId", ref.mediaId().toString());
            map.put("position", ref.position());
            map.put("maxPlays", ref.maxPlays());
            list.add(map);
        }
        return list;
    }

    /**
     * Keys sorted, no white space
     */
    public static String canonicalJson(Object value) {
        try {
            return CANONICAL.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Snapshot is not JSON", e);
        }
    }

    /**
     * The canonical form of JSON read back from the database (JSONB reorders keys and spaces them)
     */
    public static String canonicalize(String json) {
        try {
            return canonicalJson(CANONICAL.readValue(json, Object.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored snapshot is not JSON", e);
        }
    }

    public static String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
