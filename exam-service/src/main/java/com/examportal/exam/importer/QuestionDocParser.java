package com.examportal.exam.importer;

import com.examportal.common.domain.dto.ExamImportResponse;
import com.examportal.common.domain.dto.OptionDTO;
import com.examportal.common.domain.dto.QuestionDTO;
import com.examportal.common.domain.entity.QuestionType;
import com.examportal.exam.importer.DocxLines.Line;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Questions from the lines of a Word file, written the way teachers write them:
 *
 * <pre>
 * Bölmə: Riyaziyyat          (optional; a Word heading does the same)
 * 1. 2 + 2 neçədir?           (a number, or an item of a numbered Word list)
 * A) 3                        (A) A. a) A: or Cyrillic А) Б) ...; or a second-level Word list item)
 * B) 4
 * Cavab: B                    (or the right option bold / highlighted / starting with *; "B, D" for several)
 * Bal: 2                      (optional, 1 by default)
 * </pre>
 *
 * Two options Doğru / Yanlış make a true/false question; no options with "Cavab: ..." a short answer (several accepted
 * answers separated by ; or |); no options and no answer an open question. Text before the first question (the title
 * of the exam, instructions) is left out; a heading before the first question names the first block. Nothing is decided silently: whatever needs a look comes back as a warning
 * with the number of its question.
 */
public final class QuestionDocParser {

    static final int MAX_QUESTIONS = 200;
    static final int MAX_OPTIONS = 10;
    private static final int TEXT_MAX = 5000;
    private static final int OPTION_MAX = 1000;
    private static final String LETTERS = "ABCDEFGHIJ";
    private static final String CYRILLIC = "АБВГДЕЖЗИК";

    private static final Pattern BLOCK = Pattern.compile("^(?:bölmə|blok|section|part|раздел|блок)\\s*\\d*\\s*[:.\\-–]\\s*(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ANSWER = Pattern.compile("^(?:düzgün\\s+cavab|doğru\\s+cavab|cavab|answer|correct\\s+answer|правильный\\s+ответ|ответ)\\s*[:\\-–]\\s*(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern POINTS = Pattern.compile("^(?:bal|xal|points?|score|балл\\p{L}*)\\s*[:\\-–]\\s*(\\d{1,4}(?:[.,]\\d{1,2})?)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern OPTION = Pattern.compile("^([*+])?\\s*([A-Ja-jА-Ка-к])\\s*[).:]\\s*(.*)$");
    private static final Pattern QUESTION = Pattern.compile("^(?:sual|question|вопрос)?\\s*(\\d{1,3})\\s*[).:]\\s*(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern LETTER_LIST = Pattern.compile("^[A-Ja-jА-Ка-к](?:\\s*[,;/ ]\\s*[A-Ja-jА-Ка-к])*$");
    private static final Set<String> TRUE_WORDS = Set.of("doğru", "düz", "true", "верно", "правда", "bəli");
    private static final Set<String> FALSE_WORDS = Set.of("yanlış", "səhv", "false", "неверно", "ложь", "xeyr");

    private QuestionDocParser() {
    }

    private static final class Draft {
        final int number;
        final StringBuilder text = new StringBuilder();
        final List<String> options = new ArrayList<>();
        final List<Boolean> marked = new ArrayList<>();
        String answer;
        BigDecimal points;
        boolean hasImage;
        // The Word list the question is an item of, if it is one
        String listId;

        Draft(int number) {
            this.number = number;
        }
    }

    private static final class Builder {
        final List<ExamImportResponse.Block> blocks = new ArrayList<>();
        final List<ExamImportResponse.Warning> warnings = new ArrayList<>();
        int count;
        boolean full;

        Builder() {
            blocks.add(ExamImportResponse.Block.builder().questions(new ArrayList<>()).build());
        }

        void warn(String code, Integer number, String text) {
            warnings.add(ExamImportResponse.Warning.builder().code(code).questionNumber(number)
                    .text(text == null ? null : text.length() > 80 ? text.substring(0, 80) + "…" : text).build());
        }
    }

    public static ExamImportResponse parse(List<Line> lines) {
        Builder out = new Builder();
        Draft draft = null;
        for (Line line : lines) {
            if (out.full) {
                break;
            }
            String text = line.text().strip();
            if (text.isEmpty()) {
                if (line.hasImage() && draft != null) {
                    draft.hasImage = true;
                }
                continue;
            }
            Matcher block = BLOCK.matcher(text);
            if (block.matches() || line.heading()) {
                finish(draft, out);
                draft = null;
                startBlock(out, block.matches() ? block.group(1).strip() : text);
                continue;
            }
            Matcher answer = ANSWER.matcher(text);
            if (answer.matches() && draft != null) {
                draft.answer = answer.group(1).strip();
                continue;
            }
            Matcher points = POINTS.matcher(text);
            if (points.matches() && draft != null) {
                draft.points = new BigDecimal(points.group(1).replace(',', '.'));
                continue;
            }
            Matcher option = OPTION.matcher(text);
            // A deeper list level, or an item of another Word list than the question's own, is an option
            boolean listOption = line.listLevel() != null && (line.listLevel() >= 1
                    || (draft != null && !java.util.Objects.equals(line.listId(), draft.listId)));
            if (draft != null && draft.text.length() > 0 && (option.matches() || listOption)) {
                String optionText = option.matches() ? option.group(3).strip() : text;
                boolean star = option.matches() && option.group(1) != null;
                draft.options.add(optionText);
                draft.marked.add(star || line.marked());
                draft.hasImage |= line.hasImage();
                continue;
            }
            Matcher question = QUESTION.matcher(text);
            boolean listQuestion = line.listLevel() != null && line.listLevel() == 0;
            if (question.matches() || listQuestion) {
                finish(draft, out);
                if (out.full) {
                    break;
                }
                draft = new Draft(out.count + 1);
                draft.text.append(question.matches() ? question.group(2).strip() : text);
                draft.hasImage = line.hasImage();
                draft.listId = line.listLevel() != null ? line.listId() : null;
                continue;
            }
            if (draft == null) {
                // The title of the exam, instructions: before the first question, left out
                continue;
            }
            if (draft.options.isEmpty()) {
                draft.text.append('\n').append(text);
            } else {
                int last = draft.options.size() - 1;
                draft.options.set(last, draft.options.get(last) + " " + text);
            }
            draft.hasImage |= line.hasImage();
        }
        finish(draft, out);

        List<ExamImportResponse.Block> blocks = out.blocks.stream().filter(b -> !b.getQuestions().isEmpty()).toList();
        return ExamImportResponse.builder()
                .questionCount(out.count)
                .blocks(blocks.isEmpty() ? List.of(out.blocks.get(0)) : blocks)
                .warnings(out.warnings)
                .build();
    }

    private static void startBlock(Builder out, String title) {
        String clean = title.length() > 200 ? title.substring(0, 200) : title;
        ExamImportResponse.Block last = out.blocks.get(out.blocks.size() - 1);
        if (last.getQuestions().isEmpty()) {
            last.setTitle(clean);
        } else {
            out.blocks.add(ExamImportResponse.Block.builder().title(clean).questions(new ArrayList<>()).build());
        }
    }

    private static void finish(Draft draft, Builder out) {
        if (draft == null) {
            return;
        }
        if (out.count >= MAX_QUESTIONS) {
            out.full = true;
            out.warn("TOO_MANY_QUESTIONS", null, null);
            return;
        }
        String text = draft.text.toString().strip();
        if (text.length() > TEXT_MAX) {
            text = text.substring(0, TEXT_MAX);
        }
        int number = draft.number;
        if (draft.hasImage) {
            out.warn("IMAGE_SKIPPED", number, text);
        }
        List<String> options = new ArrayList<>(draft.options);
        List<Boolean> marked = new ArrayList<>(draft.marked);
        if (options.size() > MAX_OPTIONS) {
            out.warn("TOO_MANY_OPTIONS", number, text);
            options = options.subList(0, MAX_OPTIONS);
            marked = marked.subList(0, MAX_OPTIONS);
        }

        QuestionDTO.QuestionDTOBuilder question = QuestionDTO.builder()
                .questionText(text)
                .points(draft.points != null && draft.points.signum() > 0 ? draft.points : BigDecimal.ONE)
                .orderIndex(number - 1)
                .isRequired(true)
                .allowMultipleAnswers(false);

        if (options.isEmpty()) {
            if (draft.answer != null && !draft.answer.isBlank()) {
                List<OptionDTO> accepted = new ArrayList<>();
                for (String value : draft.answer.split("[;|]")) {
                    if (!value.isBlank() && accepted.size() < MAX_OPTIONS) {
                        accepted.add(option(value.strip(), true, accepted.size()));
                    }
                }
                question.questionType(QuestionType.SHORT_ANSWER).options(accepted);
            } else {
                question.questionType(QuestionType.OPEN_ENDED).options(List.of());
            }
            add(out, question.build());
            return;
        }

        // Which options are right: the answer line wins; otherwise what is starred, bold or highlighted (not all of them)
        Set<Integer> correct = new LinkedHashSet<>();
        if (draft.answer != null) {
            String answer = draft.answer.strip();
            if (LETTER_LIST.matcher(answer).matches()) {
                for (String letter : answer.split("\\s*[,;/ ]\\s*")) {
                    int index = letterIndex(letter);
                    if (index >= 0 && index < options.size()) {
                        correct.add(index);
                    } else {
                        out.warn("ANSWER_NOT_AN_OPTION", number, draft.answer);
                    }
                }
            } else {
                for (int i = 0; i < options.size(); i++) {
                    if (options.get(i).equalsIgnoreCase(answer)) {
                        correct.add(i);
                    }
                }
                if (correct.isEmpty()) {
                    out.warn("ANSWER_NOT_AN_OPTION", number, draft.answer);
                }
            }
        } else if (marked.contains(true) && marked.contains(false)) {
            for (int i = 0; i < marked.size(); i++) {
                if (marked.get(i)) {
                    correct.add(i);
                }
            }
        }

        if (options.size() == 1) {
            out.warn("ONE_OPTION", number, text);
        }
        if (correct.isEmpty()) {
            out.warn("NO_CORRECT_ANSWER", number, text);
        }
        List<OptionDTO> built = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            String value = options.get(i);
            built.add(option(value.length() > OPTION_MAX ? value.substring(0, OPTION_MAX) : value, correct.contains(i), i));
        }
        boolean trueFalse = options.size() == 2
                && isWord(options.get(0), TRUE_WORDS, FALSE_WORDS) && isWord(options.get(1), TRUE_WORDS, FALSE_WORDS);
        if (trueFalse) {
            question.questionType(QuestionType.TRUE_FALSE);
        } else {
            question.questionType(QuestionType.MULTIPLE_CHOICE).allowMultipleAnswers(correct.size() > 1);
        }
        add(out, question.options(built).build());
    }

    private static void add(Builder out, QuestionDTO question) {
        out.blocks.get(out.blocks.size() - 1).getQuestions().add(question);
        out.count++;
    }

    private static OptionDTO option(String text, boolean correct, int index) {
        return OptionDTO.builder().optionText(text).isCorrect(correct).orderIndex(index).build();
    }

    static int letterIndex(String letter) {
        String upper = letter.strip().toUpperCase(Locale.ROOT);
        int latin = LETTERS.indexOf(upper);
        return latin >= 0 ? latin : CYRILLIC.indexOf(upper);
    }

    private static boolean isWord(String text, Set<String> first, Set<String> second) {
        String word = text.strip().toLowerCase(Locale.forLanguageTag("az")).replaceAll("[.!]$", "");
        return first.contains(word) || second.contains(word);
    }
}
