package com.examportal.exam.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * One Excel worksheet (.xlsx, Office Open XML) written without a library: what a results export needs and
 * nothing more. The header row is bold on a coloured fill, stays on screen while scrolling and carries a filter;
 * every column has its width; numbers are numbers, date-times are real date cells (dd.mm.yyyy hh:mm), text is
 * text. Text is stored as inline strings, never as a formula, so a cell starting with "=" cannot run anything.
 *
 * Usage: new XlsxSheet("Nəticələr", columns) then row(...) for every row, then bytes().
 */
public final class XlsxSheet {

    /**
     * A column: its header and its width in characters
     */
    public record Column(String header, double width) {
    }

    /**
     * A date-time cell (already in the zone it should be shown in)
     */
    public record DateTime(LocalDateTime value) {
    }

    // Style ids of styles.xml: 0 plain, 1 header, 2 date-time, 3 decimal, 4 whole number
    private static final int HEADER = 1;
    private static final int DATE = 2;
    private static final int DECIMAL = 3;
    private static final int WHOLE = 4;
    // Day 0 of the Excel date system (1900, with its leap-year quirk folded in)
    private static final LocalDateTime EPOCH = LocalDateTime.of(1899, 12, 30, 0, 0);

    private final String sheetName;
    private final List<Column> columns;
    private final StringBuilder rows = new StringBuilder();
    private int rowCount;

    public XlsxSheet(String sheetName, List<Column> columns) {
        this.sheetName = sheetName;
        this.columns = List.copyOf(columns);
        List<Object> headers = new ArrayList<>();
        columns.forEach(column -> headers.add(column.header()));
        append(headers, true);
    }

    /**
     * One row of values in the order of the columns: String, Number (Integer, Long, BigDecimal), DateTime or null
     */
    public XlsxSheet row(List<?> values) {
        append(values, false);
        return this;
    }

    public int rows() {
        return rowCount - 1;
    }

    private void append(List<?> values, boolean header) {
        rowCount++;
        rows.append("<row r=\"").append(rowCount).append("\">");
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value == null) {
                continue;
            }
            String ref = column(i) + rowCount;
            if (header) {
                inline(ref, value.toString(), HEADER);
            } else if (value instanceof DateTime dateTime) {
                if (dateTime.value() != null) {
                    rows.append("<c r=\"").append(ref).append("\" s=\"").append(DATE).append("\"><v>")
                            .append(serial(dateTime.value())).append("</v></c>");
                }
            } else if (value instanceof BigDecimal decimal) {
                number(ref, decimal.stripTrailingZeros().toPlainString(), DECIMAL);
            } else if (value instanceof Integer || value instanceof Long) {
                number(ref, value.toString(), WHOLE);
            } else {
                inline(ref, value.toString(), 0);
            }
        }
        rows.append("</row>");
    }

    private void inline(String ref, String text, int style) {
        rows.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"");
        if (style != 0) {
            rows.append(" s=\"").append(style).append('"');
        }
        rows.append("><is><t xml:space=\"preserve\">").append(escape(text)).append("</t></is></c>");
    }

    private void number(String ref, String value, int style) {
        rows.append("<c r=\"").append(ref).append("\" s=\"").append(style).append("\"><v>").append(value).append("</v></c>");
    }

    /**
     * Days since the Excel epoch with the time of day as the fraction
     */
    static String serial(LocalDateTime value) {
        long seconds = ChronoUnit.SECONDS.between(EPOCH, value);
        return BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(86_400), 10, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    // A, B, ..., Z, AA, AB, ...
    static String column(int index) {
        StringBuilder name = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            int rest = (n - 1) % 26;
            name.insert(0, (char) ('A' + rest));
            n = (n - 1) / 26;
        }
        return name.toString();
    }

    // XML text; characters XML 1.0 does not allow are dropped
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> {
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /**
     * The whole file
     */
    public byte[] bytes() {
        String lastColumn = column(columns.size() - 1);
        StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                .append("<dimension ref=\"A1:").append(lastColumn).append(Math.max(rowCount, 1)).append("\"/>")
                // The header stays in view while scrolling
                .append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
                .append("<selection pane=\"bottomLeft\" activeCell=\"A2\" sqref=\"A2\"/></sheetView></sheetViews>")
                .append("<sheetFormatPr defaultRowHeight=\"18\"/><cols>");
        for (int i = 0; i < columns.size(); i++) {
            sheet.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1).append("\" width=\"")
                    .append(columns.get(i).width()).append("\" customWidth=\"1\"/>");
        }
        sheet.append("</cols><sheetData>").append(rows).append("</sheetData>")
                .append("<autoFilter ref=\"A1:").append(lastColumn).append(Math.max(rowCount, 1)).append("\"/>")
                .append("<pageMargins left=\"0.5\" right=\"0.5\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>")
                .append("</worksheet>");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            entry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                    <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                    </Types>""");
            entry(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>""");
            entry(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <sheets><sheet name="%s" sheetId="1" r:id="rId1"/></sheets>
                    <definedNames><definedName name="_xlnm._FilterDatabase" localSheetId="0" hidden="1">'%s'!$A$1:$%s$%d</definedName></definedNames>
                    </workbook>""".formatted(escape(sheetName), escape(sheetName).replace("'", "''"), lastColumn, Math.max(rowCount, 1)));
            entry(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                    </Relationships>""");
            entry(zip, "xl/styles.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    <numFmts count="1"><numFmt numFmtId="164" formatCode="dd.mm.yyyy hh:mm"/></numFmts>
                    <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts>
                    <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>
                    <fill><patternFill patternType="solid"><fgColor rgb="FF526DCB"/><bgColor indexed="64"/></patternFill></fill></fills>
                    <borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border>
                    <border><left/><right/><top/><bottom style="thin"><color rgb="FFD0D5E8"/></bottom><diagonal/></border></borders>
                    <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                    <cellXfs count="5">
                    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1"><alignment vertical="center"/></xf>
                    <xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"><alignment vertical="center" wrapText="1"/></xf>
                    <xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1"><alignment horizontal="left" vertical="center"/></xf>
                    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1"><alignment vertical="center"/></xf>
                    <xf numFmtId="1" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1"><alignment vertical="center"/></xf>
                    </cellXfs>
                    <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
                    </styleSheet>""");
            entry(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
