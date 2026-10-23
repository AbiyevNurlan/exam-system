package com.examportal.exam.importer;

import com.examportal.common.domain.dto.ExamImportResponse;
import com.examportal.common.domain.dto.OptionDTO;
import com.examportal.common.domain.dto.QuestionDTO;
import com.examportal.common.domain.entity.QuestionType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Questions from a Word file as teachers write them: numbered questions, A) B) options, "Cavab: B", a bold right
 * option, points, sections, true/false, short and open answers, Word's own numbered lists, and warnings.
 */
class QuestionDocParserTest {

    // A paragraph: plain, or with the run bold
    private static String p(String text) {
        return "<w:p><w:r><w:t xml:space=\"preserve\">" + escape(text) + "</w:t></w:r></w:p>";
    }

    private static String bold(String text) {
        return "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>" + escape(text) + "</w:t></w:r></w:p>";
    }

    private static String heading(String text) {
        return "<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>" + escape(text) + "</w:t></w:r></w:p>";
    }

    // An item of a Word numbered list (the number is not in the text)
    private static String listItem(int level, String text) {
        return listItem(level, 1, text);
    }

    private static String listItem(int level, int list, String text) {
        return "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"" + level + "\"/><w:numId w:val=\"" + list + "\"/></w:numPr></w:pPr><w:r><w:t>"
                + escape(text) + "</w:t></w:r></w:p>";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static byte[] docx(String... paragraphs) throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + String.join("", paragraphs) + "</w:body></w:document>";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static ExamImportResponse read(String... paragraphs) throws Exception {
        return QuestionDocParser.parse(DocxLines.read(docx(paragraphs)));
    }

    private static List<String> correct(QuestionDTO question) {
        return question.getOptions().stream().filter(OptionDTO::getIsCorrect).map(OptionDTO::getOptionText).toList();
    }

    @Test
    void numberedQuestionsWithLetteredOptionsAndAnAnswerLine() throws Exception {
        ExamImportResponse result = read(
                p("Riyaziyyat yoxlaması"),            // the title: left out
                p("1. 2 + 2 neçədir?"),
                p("A) 3"), p("B) 4"), p("C) 5"),
                p("Cavab: B"),
                p("Bal: 2,5"),
                p("2) Hansılar cüt ədəddir?"),
                p("a. 2"), p("b. 3"), p("c. 4"),
                p("Düzgün cavab: A, C"));

        assertThat(result.getQuestionCount()).isEqualTo(2);
        assertThat(result.getWarnings()).isEmpty();
        List<QuestionDTO> questions = result.getBlocks().get(0).getQuestions();
        QuestionDTO first = questions.get(0);
        assertThat(first.getQuestionText()).isEqualTo("2 + 2 neçədir?");
        assertThat(first.getQuestionType()).isEqualTo(QuestionType.MULTIPLE_CHOICE);
        assertThat(first.getOptions()).extracting(OptionDTO::getOptionText).containsExactly("3", "4", "5");
        assertThat(correct(first)).containsExactly("4");
        assertThat(first.getPoints()).isEqualByComparingTo("2.5");
        assertThat(first.getAllowMultipleAnswers()).isFalse();
        QuestionDTO second = questions.get(1);
        assertThat(second.getAllowMultipleAnswers()).isTrue();
        assertThat(correct(second)).containsExactly("2", "4");
        assertThat(second.getPoints()).isEqualByComparingTo("1");
    }

    @Test
    void theRightOptionInBoldOrWithAStar() throws Exception {
        ExamImportResponse result = read(
                p("1. Azərbaycanın paytaxtı?"), p("A) Gəncə"), bold("B) Bakı"), p("C) Şəki"),
                p("2. Ən böyük göl?"), p("A) Göygöl"), p("*B) Xəzər"));

        List<QuestionDTO> questions = result.getBlocks().get(0).getQuestions();
        assertThat(correct(questions.get(0))).containsExactly("Bakı");
        assertThat(correct(questions.get(1))).containsExactly("Xəzər");
    }

    @Test
    void trueFalseShortAndOpenQuestions() throws Exception {
        ExamImportResponse result = read(
                p("1. Yer kürəsi dairəvidir."), p("A) Doğru"), p("B) Yanlış"), p("Cavab: A"),
                p("2. Azərbaycanın paytaxtı hansı şəhərdir?"), p("Cavab: Bakı; Baku"),
                p("3. Fikrinizi yazın:"), p("ikinci sətir"));

        List<QuestionDTO> questions = result.getBlocks().get(0).getQuestions();
        assertThat(questions).extracting(QuestionDTO::getQuestionType)
                .containsExactly(QuestionType.TRUE_FALSE, QuestionType.SHORT_ANSWER, QuestionType.OPEN_ENDED);
        assertThat(correct(questions.get(0))).containsExactly("Doğru");
        assertThat(questions.get(1).getOptions()).extracting(OptionDTO::getOptionText).containsExactly("Bakı", "Baku");
        assertThat(questions.get(2).getQuestionText()).isEqualTo("Fikrinizi yazın:\nikinci sətir");
    }

    @Test
    void sectionsByLineOrByWordHeading() throws Exception {
        ExamImportResponse result = read(
                p("Bölmə: Riyaziyyat"), p("1. 1+1?"), p("A) 2"), p("B) 3"), p("Cavab: A"),
                heading("Məntiq"), p("2. Növbəti?"), p("A) x"), p("B) y"), p("Cavab: B"));

        assertThat(result.getBlocks()).extracting(ExamImportResponse.Block::getTitle).containsExactly("Riyaziyyat", "Məntiq");
        assertThat(result.getBlocks().get(1).getQuestions()).hasSize(1);
    }

    @Test
    void wordsOwnNumberedListsAreQuestionsAndOptions() throws Exception {
        ExamImportResponse result = read(
                listItem(0, "Hansı rəqəm təkdir?"), listItem(1, "2"), listItem(1, "3"), listItem(1, "4"), p("Cavab: B"));

        QuestionDTO question = result.getBlocks().get(0).getQuestions().get(0);
        assertThat(question.getQuestionText()).isEqualTo("Hansı rəqəm təkdir?");
        assertThat(question.getOptions()).extracting(OptionDTO::getOptionText).containsExactly("2", "3", "4");
        assertThat(correct(question)).containsExactly("3");
    }

    @Test
    void aLetteredListUnderANumberedListIsTheOptionsEvenOnTheSameLevel() throws Exception {
        ExamImportResponse result = read(
                listItem(0, 1, "Azərbaycanın paytaxtı?"), listItem(0, 2, "Gəncə"), bold("").replace("<w:p>", "<w:p>"),
                listItem(0, 2, "Bakı"), listItem(0, 2, "Şəki"), p("Cavab: B"),
                listItem(0, 1, "Ən uzun çay?"), listItem(0, 3, "Kür"), listItem(0, 3, "Araz"), p("Cavab: A"));

        List<QuestionDTO> questions = result.getBlocks().get(0).getQuestions();
        assertThat(questions).hasSize(2);
        assertThat(questions.get(0).getOptions()).extracting(OptionDTO::getOptionText).containsExactly("Gəncə", "Bakı", "Şəki");
        assertThat(correct(questions.get(0))).containsExactly("Bakı");
        assertThat(correct(questions.get(1))).containsExactly("Kür");
    }

    @Test
    void whatNeedsALookComesBackAsAWarning() throws Exception {
        ExamImportResponse result = read(
                p("1. Cavabsız sual?"), p("A) bir"), p("B) iki"),
                p("2. Səhv cavab?"), p("A) bir"), p("B) iki"), p("Cavab: D"));

        assertThat(result.getWarnings()).extracting(ExamImportResponse.Warning::getCode, ExamImportResponse.Warning::getQuestionNumber)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("NO_CORRECT_ANSWER", 1),
                        org.assertj.core.groups.Tuple.tuple("ANSWER_NOT_AN_OPTION", 2),
                        org.assertj.core.groups.Tuple.tuple("NO_CORRECT_ANSWER", 2));
    }

    @Test
    void anythingButAWordFileIsRefused() {
        assertThatThrownBy(() -> DocxLines.read("not a zip".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(DocxLines.NotADocxException.class);
        // A zip without a Word body
        assertThatThrownBy(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("a.txt"));
                zip.write(1);
                zip.closeEntry();
            }
            DocxLines.read(out.toByteArray());
        }).isInstanceOf(DocxLines.NotADocxException.class);
        // An external entity is never resolved
        assertThatThrownBy(() -> DocxLines.parse(("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>&e;</w:t></w:r></w:p></w:body></w:document>")
                .getBytes(StandardCharsets.UTF_8))).isInstanceOf(DocxLines.NotADocxException.class);
    }
}
