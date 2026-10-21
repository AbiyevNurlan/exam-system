package com.examportal.exam.history;

import com.examportal.common.domain.entity.ExamAccessType;
import com.examportal.common.domain.entity.ExamStatus;
import com.examportal.exam.structure.ExamState;
import com.examportal.exam.structure.ExamState.BlockState;
import com.examportal.exam.structure.ExamState.OptionState;
import com.examportal.exam.structure.ExamState.QuestionState;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * What changed in an exam, as history rows (B12a). Computed on the server from two ExamStates read in the
 * transaction of the change (before and after), never from what a client says it changed.
 *
 * One row per changed thing: the exam's title and description (META_CHANGED), its settings (SETTINGS_CHANGED),
 * its audience, each block, each question. A question with several kinds of change is one row
 * (QUESTION_UPDATED) whose changedFields name them all; with one kind the action names it
 * (CORRECT_ANSWER_CHANGED, POINTS_CHANGED, OPTION_CHANGED, QUESTION_MOVED, QUESTION_REORDERED). Numbers are
 * the question's place over all blocks at that moment (1-based). A reordering is reported for the questions
 * that really moved (not in the longest common order), not for every question that only shifted.
 * Texts of edits are cut to MAX_TEXT characters; added and deleted questions are kept whole.
 *
 * Sensitive (highlighted in the UI only): the answer key, points, the duration and the time window, block
 * timers, breaks and navigation rules, publishing and archiving.
 */
public final class ExamDiff {

    public static final int MAX_TEXT = 2000;
    private static final int PREVIEW = 80;

    private ExamDiff() {
    }

    // ==================== Exam created, cloned, deleted ====================

    public static List<RevisionDraft> created(ExamState after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("title", pair(null, after.title()));
        changes.put("timingMode", pair(null, name(after.timingMode())));
        changes.put("blocks", pair(null, after.blocks().size()));
        changes.put("questions", pair(null, after.questions().size()));
        changes.put("totalMinutes", pair(null, after.totalMinutes()));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("title", after.title());
        params.put("blockCount", after.blocks().size());
        params.put("questionCount", after.questions().size());
        return List.of(new RevisionDraft("EXAM_CREATED", RevisionDraft.EXAM, after.id(), changes, false, "EXAM_CREATED", params));
    }

    public static RevisionDraft clonedFrom(ExamState source, ExamState copy) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("sourceExamId", pair(null, source.id()));
        changes.put("sourceTitle", pair(null, source.title()));
        changes.put("blocks", pair(null, copy.blocks().size()));
        changes.put("questions", pair(null, copy.questions().size()));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sourceExamId", source.id());
        params.put("sourceTitle", source.title());
        params.put("title", copy.title());
        return new RevisionDraft("CLONED_FROM", RevisionDraft.EXAM, copy.id(), changes, false, "EXAM_CLONED_FROM", params);
    }

    /**
     * outcome DELETED: the whole exam is kept in the row (its rows are gone afterwards); REFUSED_HAS_SESSIONS: only the attempt
     */
    public static RevisionDraft deleteAttempt(ExamState state, String outcome) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("outcome", pair(null, outcome));
        if ("DELETED".equals(outcome)) {
            changes.put("exam", pair(ExamSnapshots.exam(state), null));
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("title", state.title());
        params.put("outcome", outcome);
        params.put("status", name(state.status()));
        return new RevisionDraft("DELETE_ATTEMPTED", RevisionDraft.EXAM, state.id(), changes, false,
                "DELETED".equals(outcome) ? "EXAM_DELETED" : "EXAM_DELETE_REFUSED", params);
    }

    // ==================== Status, schedule, audience, title ====================

    /**
     * PUBLISHED or ARCHIVED (sensitive); extra changes (version, sha256) are added as given
     */
    public static RevisionDraft status(String action, ExamStatus before, ExamStatus after, Long examId, Map<String, Object> extra) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("status", pair(name(before), name(after)));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("statusBefore", name(before));
        params.put("status", name(after));
        if (extra != null) {
            extra.forEach((key, value) -> {
                changes.put(key, pair(null, value));
                params.put(key, value);
            });
        }
        return new RevisionDraft(action, RevisionDraft.EXAM, examId, changes, true,
                "PUBLISHED".equals(action) ? "EXAM_PUBLISHED" : "EXAM_ARCHIVED", params);
    }

    public static RevisionDraft rescheduled(Long examId, ExamStatus statusBefore, ExamStatus statusAfter,
                                            LocalDateTime startBefore, LocalDateTime startAfter,
                                            LocalDateTime endBefore, LocalDateTime endAfter,
                                            Integer attemptsBefore, Integer attemptsAfter,
                                            Integer perDayBefore, Integer perDayAfter) {
        Map<String, Object> changes = new LinkedHashMap<>();
        put(changes, "status", name(statusBefore), name(statusAfter));
        put(changes, "startTime", ExamSnapshots.time(startBefore), ExamSnapshots.time(startAfter));
        put(changes, "endTime", ExamSnapshots.time(endBefore), ExamSnapshots.time(endAfter));
        put(changes, "maxAttempts", attemptsBefore, attemptsAfter);
        put(changes, "maxAttemptsPerDay", perDayBefore, perDayAfter);
        boolean window = changes.containsKey("startTime") || changes.containsKey("endTime");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("changedFields", new ArrayList<>(changes.keySet()));
        params.put("startTime", ExamSnapshots.time(startAfter));
        params.put("endTime", ExamSnapshots.time(endAfter));
        return new RevisionDraft("RESCHEDULED", RevisionDraft.EXAM, examId, changes, window, "EXAM_RESCHEDULED", params);
    }

    /**
     * Empty when nothing changed
     */
    public static List<RevisionDraft> audience(Long examId, ExamAccessType typeBefore, ExamAccessType typeAfter,
                                               List<Long> groupsBefore, List<Long> groupsAfter) {
        Map<String, Object> changes = new LinkedHashMap<>();
        put(changes, "accessType", name(typeBefore), name(typeAfter));
        List<Long> before = groupsBefore == null ? List.of() : groupsBefore.stream().sorted().toList();
        List<Long> after = groupsAfter == null ? List.of() : groupsAfter.stream().sorted().toList();
        put(changes, "groupIds", before, after);
        if (changes.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("accessType", name(typeAfter));
        params.put("groupCount", after.size());
        params.put("changedFields", new ArrayList<>(changes.keySet()));
        return List.of(new RevisionDraft("AUDIENCE_CHANGED", RevisionDraft.AUDIENCE, examId, changes, false,
                "EXAM_AUDIENCE_CHANGED", params));
    }

    /**
     * Title and description (PATCH /meta, or part of an update); empty when nothing changed
     */
    public static List<RevisionDraft> meta(Long examId, String titleBefore, String titleAfter, String descriptionBefore,
                                           String descriptionAfter) {
        Map<String, Object> changes = new LinkedHashMap<>();
        put(changes, "title", titleBefore, titleAfter);
        put(changes, "description", cut(descriptionBefore), cut(descriptionAfter));
        if (!Objects.equals(descriptionBefore, descriptionAfter) && !changes.containsKey("description")) {
            changes.put("description", pair(cut(descriptionBefore), cut(descriptionAfter)));
        }
        if (changes.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("title", titleAfter);
        params.put("changedFields", new ArrayList<>(changes.keySet()));
        return List.of(new RevisionDraft("META_CHANGED", RevisionDraft.EXAM, examId, changes, false, "EXAM_META_CHANGED", params));
    }

    // ==================== An update: settings, blocks, questions ====================

    /**
     * Every row of one update, the save's own row (EXAM_UPDATED) first; empty when nothing changed
     */
    public static List<RevisionDraft> update(ExamState before, ExamState after) {
        List<RevisionDraft> rows = new ArrayList<>();
        rows.addAll(meta(after.id(), before.title(), after.title(), before.description(), after.description()));
        settings(before, after).ifPresent(rows::add);
        rows.addAll(audience(after.id(), before.accessType(), after.accessType(), before.audienceGroupIds(),
                after.audienceGroupIds()));
        rows.addAll(blocks(before, after));
        rows.addAll(questions(before, after));
        if (rows.isEmpty()) {
            return rows;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RevisionDraft row : rows) {
            String key = switch (row.action()) {
                case "QUESTION_ADDED" -> "questionsAdded";
                case "QUESTION_DELETED" -> "questionsDeleted";
                case "BLOCK_ADDED" -> "blocksAdded";
                case "BLOCK_DELETED" -> "blocksDeleted";
                case "BLOCK_UPDATED", "BLOCK_REORDERED" -> "blocksChanged";
                case "META_CHANGED", "SETTINGS_CHANGED", "AUDIENCE_CHANGED" -> "settingsChanged";
                default -> "questionsChanged";
            };
            counts.merge(key, 1, Integer::sum);
        }
        Map<String, Object> params = new LinkedHashMap<>(counts);
        params.put("questionNumbers", rows.stream()
                .filter(row -> RevisionDraft.QUESTION.equals(row.entityType()))
                .map(row -> row.summaryParams().get("questionNumber"))
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        boolean sensitive = rows.stream().anyMatch(RevisionDraft::sensitive);
        rows.add(0, new RevisionDraft("EXAM_UPDATED", RevisionDraft.EXAM, after.id(), Map.of(), sensitive, "EXAM_SAVED", params));
        return rows;
    }

    private record Setting(String name, boolean sensitive, Function<ExamState, Object> value) {
    }

    private static final List<Setting> SETTINGS = List.of(
            new Setting("durationMinutes", true, ExamState::durationMinutes),
            new Setting("startTime", true, state -> ExamSnapshots.time(state.startTime())),
            new Setting("endTime", true, state -> ExamSnapshots.time(state.endTime())),
            new Setting("passingScore", false, ExamState::passingScore),
            new Setting("shuffleQuestions", false, ExamState::shuffleQuestions),
            new Setting("shuffleOptions", false, ExamState::shuffleOptions),
            new Setting("showResultsImmediately", false, ExamState::showResultsImmediately),
            new Setting("maxAttempts", false, ExamState::maxAttempts),
            new Setting("maxAttemptsPerDay", false, ExamState::maxAttemptsPerDay),
            new Setting("categoryId", false, ExamState::categoryId),
            new Setting("timingMode", true, state -> name(state.timingMode())),
            new Setting("allowReturnToPreviousBlocks", true, ExamState::allowReturnToPreviousBlocks),
            new Setting("allowSkipBreak", true, ExamState::allowSkipBreak),
            new Setting("showBlockBreakdown", false, ExamState::showBlockBreakdown));

    private static java.util.Optional<RevisionDraft> settings(ExamState before, ExamState after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        boolean sensitive = false;
        for (Setting setting : SETTINGS) {
            if (put(changes, setting.name(), setting.value().apply(before), setting.value().apply(after))) {
                sensitive |= setting.sensitive();
            }
        }
        if (changes.isEmpty()) {
            return java.util.Optional.empty();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("changedFields", new ArrayList<>(changes.keySet()));
        return java.util.Optional.of(new RevisionDraft("SETTINGS_CHANGED", RevisionDraft.SETTINGS, after.id(), changes,
                sensitive, "EXAM_SETTINGS_CHANGED", params));
    }

    private static List<RevisionDraft> blocks(ExamState before, ExamState after) {
        List<RevisionDraft> rows = new ArrayList<>();
        Map<Long, Integer> numberBefore = blockNumbers(before);
        Map<Long, Integer> numberAfter = blockNumbers(after);
        Map<Long, BlockState> old = new HashMap<>();
        before.blocks().forEach(block -> old.put(block.id(), block));
        Set<Long> moved = moved(before.blocks().stream().map(BlockState::id).toList(),
                after.blocks().stream().map(BlockState::id).toList());

        for (BlockState block : after.blocks()) {
            int number = numberAfter.get(block.id());
            BlockState was = old.get(block.id());
            if (was == null) {
                rows.add(new RevisionDraft("BLOCK_ADDED", RevisionDraft.BLOCK, block.id(),
                        Map.of("block", pair(null, ExamSnapshots.block(block, number))), timed(block), "BLOCK_ADDED",
                        blockParams(block, number, null, List.of())));
                continue;
            }
            Map<String, Object> changes = new LinkedHashMap<>();
            boolean sensitive = false;
            put(changes, "title", was.title(), block.title());
            put(changes, "instructions", cut(was.instructions()), cut(block.instructions()));
            sensitive |= put(changes, "timeLimitMinutes", was.timeLimitMinutes(), block.timeLimitMinutes());
            sensitive |= put(changes, "breakAfterMinutes", was.breakAfterMinutes(), block.breakAfterMinutes());
            sensitive |= put(changes, "freeNavigation", was.freeNavigation(), block.freeNavigation());
            put(changes, "shuffleQuestions", was.shuffleQuestions(), block.shuffleQuestions());
            put(changes, "showResultToParticipant", was.showResultToParticipant(), block.showResultToParticipant());
            boolean reordered = moved.contains(block.id());
            if (reordered) {
                changes.put("blockNumber", pair(numberBefore.get(block.id()), number));
            }
            if (changes.isEmpty()) {
                continue;
            }
            boolean onlyReordered = reordered && changes.size() == 1;
            rows.add(new RevisionDraft(onlyReordered ? "BLOCK_REORDERED" : "BLOCK_UPDATED", RevisionDraft.BLOCK, block.id(),
                    changes, sensitive, onlyReordered ? "BLOCK_REORDERED" : "BLOCK_EDITED",
                    blockParams(block, number, reordered ? numberBefore.get(block.id()) : null, new ArrayList<>(changes.keySet()))));
        }
        Set<Long> kept = new HashSet<>(numberAfter.keySet());
        for (BlockState block : before.blocks()) {
            if (!kept.contains(block.id())) {
                int number = numberBefore.get(block.id());
                rows.add(new RevisionDraft("BLOCK_DELETED", RevisionDraft.BLOCK, block.id(),
                        Map.of("block", pair(ExamSnapshots.block(block, number), null)), timed(block), "BLOCK_DELETED",
                        blockParams(block, number, null, List.of())));
            }
        }
        return rows;
    }

    private static List<RevisionDraft> questions(ExamState before, ExamState after) {
        List<RevisionDraft> rows = new ArrayList<>();
        Map<Long, Integer> blockNumberBefore = blockNumbers(before);
        Map<Long, Integer> blockNumberAfter = blockNumbers(after);
        Map<Long, BlockState> blocksBefore = new HashMap<>();
        before.blocks().forEach(block -> blocksBefore.put(block.id(), block));
        Map<Long, BlockState> blocksAfter = new HashMap<>();
        after.blocks().forEach(block -> blocksAfter.put(block.id(), block));

        List<QuestionState> oldOrder = before.questions();
        List<QuestionState> newOrder = after.questions();
        Map<Long, Integer> numberBefore = new HashMap<>();
        Map<Long, QuestionState> old = new HashMap<>();
        for (int i = 0; i < oldOrder.size(); i++) {
            numberBefore.put(oldOrder.get(i).id(), i + 1);
            old.put(oldOrder.get(i).id(), oldOrder.get(i));
        }
        Set<Long> moved = moved(oldOrder.stream().map(QuestionState::id).toList(),
                newOrder.stream().map(QuestionState::id).toList());

        for (int i = 0; i < newOrder.size(); i++) {
            QuestionState question = newOrder.get(i);
            int number = i + 1;
            BlockState block = blocksAfter.get(question.blockId());
            Integer blockNumber = blockNumberAfter.get(question.blockId());
            QuestionState was = old.get(question.id());
            if (was == null) {
                Map<String, Object> params = questionParams(question, number, null, block, blockNumber, List.of());
                rows.add(new RevisionDraft("QUESTION_ADDED", RevisionDraft.QUESTION, question.id(),
                        Map.of("question", pair(null, ExamSnapshots.question(question, number, blockRef(block, blockNumber)))),
                        false, "QUESTION_ADDED", params));
                continue;
            }

            Map<String, Object> changes = new LinkedHashMap<>();
            Set<String> kinds = new LinkedHashSet<>();
            Map<String, Object> extra = new LinkedHashMap<>();
            if (put(changes, "questionText", cut(was.questionText()), cut(question.questionText()))
                    | put(changes, "explanation", cut(was.explanation()), cut(question.explanation()))
                    | put(changes, "questionType", name(was.questionType()), name(question.questionType()))
                    | put(changes, "allowMultipleAnswers", was.allowMultipleAnswers(), question.allowMultipleAnswers())
                    | put(changes, "required", was.required(), question.required())
                    | put(changes, "media", ExamSnapshots.media(was.media()), ExamSnapshots.media(question.media()))) {
                kinds.add("UPDATED");
            }
            if (was.points() == null ? question.points() != null : question.points() == null || was.points().compareTo(question.points()) != 0) {
                changes.put("points", pair(was.points(), question.points()));
                extra.put("pointsBefore", was.points());
                extra.put("pointsAfter", question.points());
                kinds.add("POINTS");
            }
            List<String> correctBefore = correct(was.options());
            List<String> correctAfter = correct(question.options());
            if (!correctBefore.equals(correctAfter)) {
                changes.put("correctAnswer", pair(correctAnswer(was.options()), correctAnswer(question.options())));
                extra.put("correctBefore", labels(was.options()));
                extra.put("correctAfter", labels(question.options()));
                kinds.add("CORRECT");
            }
            if (!optionsWithoutKey(was.options()).equals(optionsWithoutKey(question.options()))) {
                changes.put("options", pair(ExamSnapshots.options(was.options()), ExamSnapshots.options(question.options())));
                kinds.add("OPTION");
            }
            if (!Objects.equals(was.blockId(), question.blockId())) {
                changes.put("block", pair(blockRef(blocksBefore.get(was.blockId()), blockNumberBefore.get(was.blockId())),
                        blockRef(block, blockNumber)));
                kinds.add("MOVED");
            }
            Integer numberWas = numberBefore.get(question.id());
            if (moved.contains(question.id())) {
                changes.put("questionNumber", pair(numberWas, number));
                kinds.add("REORDERED");
            }
            if (changes.isEmpty()) {
                continue;
            }
            String action;
            String summary = "QUESTION_EDITED";
            if (kinds.equals(Set.of("REORDERED"))) {
                action = "QUESTION_REORDERED";
                summary = "QUESTION_REORDERED";
            } else if (Set.of("MOVED", "REORDERED").containsAll(kinds)) {
                action = "QUESTION_MOVED";
                summary = "QUESTION_MOVED";
            } else if (kinds.size() == 1) {
                action = switch (kinds.iterator().next()) {
                    case "CORRECT" -> "CORRECT_ANSWER_CHANGED";
                    case "POINTS" -> "POINTS_CHANGED";
                    case "OPTION" -> "OPTION_CHANGED";
                    default -> "QUESTION_UPDATED";
                };
            } else {
                action = "QUESTION_UPDATED";
            }
            Map<String, Object> params = questionParams(question, number, numberWas != null && numberWas != number ? numberWas : null,
                    block, blockNumber, new ArrayList<>(changes.keySet()));
            params.putAll(extra);
            rows.add(new RevisionDraft(action, RevisionDraft.QUESTION, question.id(), changes,
                    kinds.contains("POINTS") || kinds.contains("CORRECT"), summary, params));
        }

        Set<Long> kept = new HashSet<>();
        newOrder.forEach(question -> kept.add(question.id()));
        for (QuestionState question : oldOrder) {
            if (!kept.contains(question.id())) {
                int number = numberBefore.get(question.id());
                BlockState block = blocksBefore.get(question.blockId());
                Integer blockNumber = blockNumberBefore.get(question.blockId());
                // The whole question, its options and its answer key: nothing of it is left anywhere else
                rows.add(new RevisionDraft("QUESTION_DELETED", RevisionDraft.QUESTION, question.id(),
                        Map.of("question", pair(ExamSnapshots.question(question, number, blockRef(block, blockNumber)), null)),
                        false, "QUESTION_DELETED", questionParams(question, number, null, block, blockNumber, List.of())));
            }
        }
        return rows;
    }

    // ==================== Helpers ====================

    private static Map<String, Object> questionParams(QuestionState question, int number, Integer numberBefore,
                                                      BlockState block, Integer blockNumber, List<String> changedFields) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("questionNumber", number);
        if (numberBefore != null) {
            params.put("questionNumberBefore", numberBefore);
        }
        params.put("blockTitle", block == null ? null : block.title());
        params.put("blockNumber", blockNumber);
        params.put("questionPreview", preview(question.questionText()));
        params.put("changedFields", changedFields);
        return params;
    }

    private static Map<String, Object> blockParams(BlockState block, int number, Integer numberBefore, List<String> changedFields) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("blockTitle", block.title());
        params.put("blockNumber", number);
        if (numberBefore != null) {
            params.put("blockNumberBefore", numberBefore);
        }
        params.put("changedFields", changedFields);
        return params;
    }

    private static Map<String, Object> blockRef(BlockState block, Integer number) {
        if (block == null) {
            return null;
        }
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("id", block.id());
        ref.put("title", block.title());
        ref.put("number", number);
        return ref;
    }

    private static boolean timed(BlockState block) {
        return block.timeLimitMinutes() != null || block.breakAfterMinutes() > 0 || !block.freeNavigation();
    }

    private static Map<Long, Integer> blockNumbers(ExamState state) {
        Map<Long, Integer> numbers = new HashMap<>();
        for (int i = 0; i < state.blocks().size(); i++) {
            numbers.put(state.blocks().get(i).id(), i + 1);
        }
        return numbers;
    }

    /**
     * The correct options as "label|text": a different option made correct, or the text of a correct
     * option (the expected answer of a text question) changed
     */
    private static List<String> correct(List<OptionState> options) {
        List<String> correct = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).correct()) {
                correct.add(ExamSnapshots.label(i) + "|" + options.get(i).optionText()
                        + (options.get(i).matchText() != null ? "|" + options.get(i).matchText() : ""));
            }
        }
        return correct;
    }

    private static List<Object> correctAnswer(List<OptionState> options) {
        List<Object> correct = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).correct()) {
                Map<String, Object> option = new LinkedHashMap<>();
                option.put("label", ExamSnapshots.label(i));
                option.put("text", cut(options.get(i).optionText()));
                if (options.get(i).matchText() != null) {
                    option.put("matchText", cut(options.get(i).matchText()));
                }
                correct.add(option);
            }
        }
        return correct;
    }

    private static String labels(List<OptionState> options) {
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).correct()) {
                labels.add(ExamSnapshots.label(i));
            }
        }
        return String.join(", ", labels);
    }

    private static List<String> optionsWithoutKey(List<OptionState> options) {
        return options.stream()
                .map(option -> option.optionText() + "|" + option.media().stream().map(ref -> ref.mediaId().toString()).toList())
                .toList();
    }

    /**
     * The ids that changed their place: those not in the longest common order of the ids both lists share
     */
    static Set<Long> moved(List<Long> before, List<Long> after) {
        Set<Long> inBoth = new HashSet<>(before);
        inBoth.retainAll(new HashSet<>(after));
        List<Long> a = before.stream().filter(inBoth::contains).toList();
        List<Long> b = after.stream().filter(inBoth::contains).toList();
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        Set<Long> stayed = new HashSet<>();
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            if (a.get(i).equals(b.get(j))) {
                stayed.add(a.get(i));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        Set<Long> moved = new HashSet<>(inBoth);
        moved.removeAll(stayed);
        return moved;
    }

    /**
     * Adds [before, after] when they differ; true when added
     */
    private static boolean put(Map<String, Object> changes, String field, Object before, Object after) {
        if (same(before, after)) {
            return false;
        }
        changes.put(field, pair(before, after));
        return true;
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
    }

    static List<Object> pair(Object before, Object after) {
        return Arrays.asList(before, after);
    }

    static String cut(String text) {
        if (text == null || text.length() <= MAX_TEXT) {
            return text;
        }
        return text.substring(0, MAX_TEXT) + "… (" + text.length() + " characters)";
    }

    private static String preview(String text) {
        if (text == null) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= PREVIEW ? flat : flat.substring(0, PREVIEW);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
