package com.examportal.exam.export;

import com.examportal.common.domain.dto.SessionSummaryResponse;
import com.examportal.common.domain.entity.GradingStatus;
import com.examportal.common.domain.entity.RiskLevel;
import com.examportal.common.domain.entity.SessionStatus;
import com.examportal.exam.service.ExamResultsServiceAccess;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Excel file of the results: a valid package, a header that stays in view with filters and widths, numbers as
 * numbers, date-times as date cells, text that can never be a formula, and the words a person reads.
 */
class XlsxSheetTest {

    private static Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> files = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                files.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    @Test
    void aWorkbookWithOneSheetAHeaderInViewAndTypedCells() throws Exception {
        XlsxSheet sheet = new XlsxSheet("Nəticələr", List.of(new XlsxSheet.Column("Ad Soyad", 26), new XlsxSheet.Column("Bal", 8),
                new XlsxSheet.Column("Başlama", 19), new XlsxSheet.Column("Qeyd", 10)));
        sheet.row(java.util.Arrays.asList("Şəkər <Zeynalova> & co", new BigDecimal("66.670"), new XlsxSheet.DateTime(LocalDateTime.of(2026, 10, 22, 18, 0)), "=1+1"));
        sheet.row(java.util.Arrays.asList("Ömər", 3, null, null));

        Map<String, String> files = unzip(sheet.bytes());

        assertThat(files).containsKeys("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels",
                "xl/styles.xml", "xl/worksheets/sheet1.xml");
        assertThat(files.get("xl/workbook.xml")).contains("name=\"Nəticələr\"");
        String xml = files.get("xl/worksheets/sheet1.xml");
        assertThat(xml).contains("state=\"frozen\"").contains("<autoFilter ref=\"A1:D3\"/>")
                .contains("<col min=\"1\" max=\"1\" width=\"26.0\" customWidth=\"1\"/>");
        // The header in the header style
        assertThat(xml).contains("<c r=\"A1\" t=\"inlineStr\" s=\"1\"><is><t xml:space=\"preserve\">Ad Soyad</t></is></c>");
        // Text escaped, a number as a number, a date as the Excel serial number in the date style
        assertThat(xml).contains("Şəkər &lt;Zeynalova&gt; &amp; co").contains("<c r=\"B2\" s=\"3\"><v>66.67</v></c>")
                .contains("<c r=\"C2\" s=\"2\"><v>46317.75</v></c>").contains("<c r=\"B3\" s=\"4\"><v>3</v></c>");
        // "=1+1" stays text: there is no formula anywhere in the file
        assertThat(xml).contains("<t xml:space=\"preserve\">=1+1</t>").doesNotContain("<f>");
        assertThat(sheet.rows()).isEqualTo(2);
    }

    @Test
    void columnNamesAndSerials() {
        assertThat(XlsxSheet.column(0)).isEqualTo("A");
        assertThat(XlsxSheet.column(25)).isEqualTo("Z");
        assertThat(XlsxSheet.column(26)).isEqualTo("AA");
        assertThat(XlsxSheet.serial(LocalDateTime.of(1900, 3, 1, 0, 0))).isEqualTo("61");
        assertThat(XlsxSheet.escape("a\u0001b")).isEqualTo("ab");
    }

    @Test
    void aRowSaysWhatAPersonReads() {
        SessionSummaryResponse row = SessionSummaryResponse.builder()
                .sessionId("7399129c").participantName("Şəkər Zeynalova").participantCode(1363832).email("t@x.test")
                .attemptNumber(1).status(SessionStatus.COMPLETED)
                .startedAt(LocalDateTime.of(2026, 10, 22, 6, 0)).submittedAt(LocalDateTime.of(2026, 10, 22, 6, 30, 11))
                .durationSeconds(1811L).score(BigDecimal.ZERO).maxScore(new BigDecimal("3")).percentage(BigDecimal.ZERO)
                .passed(false).gradingStatus(GradingStatus.NOT_REQUIRED).submitKind("AUTO_EXPIRED").violationCount(1)
                .riskLevel(RiskLevel.LOW).build();

        List<Object> cells = ExamResultsServiceAccess.xlsxRow(1, row);

        assertThat(cells.get(0)).isEqualTo(1);
        assertThat(cells.get(1)).isEqualTo("Şəkər Zeynalova");
        assertThat(cells.get(2)).isEqualTo(1363832L);
        assertThat(cells.get(5)).isEqualTo("Bitib");
        // 06:00 UTC is 10:00 in Baku
        assertThat(((XlsxSheet.DateTime) cells.get(6)).value()).isEqualTo(LocalDateTime.of(2026, 10, 22, 10, 0));
        assertThat(cells.get(8)).isEqualTo("30:11");
        assertThat(cells.get(12)).isEqualTo("Xeyr");
        assertThat(cells.get(13)).isEqualTo("Avtomatik");
        assertThat(cells.get(14)).isEqualTo("Vaxt bitib");
        assertThat(cells.get(16)).isEqualTo("Aşağı");
        assertThat(cells.get(17)).isEqualTo("7399129c");
        assertThat(ExamResultsServiceAccess.duration(3725L)).isEqualTo("1:02:05");
    }
}
