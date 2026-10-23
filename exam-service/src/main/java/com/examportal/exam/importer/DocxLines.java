package com.examportal.exam.importer;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The text of a Word file (.docx) as lines, without a library: a .docx is a zip with the body in word/document.xml.
 * Each paragraph gives one or more lines (a line break inside it starts a new one). A line remembers whether all of
 * its text is bold or highlighted (how teachers often mark the right answer), whether its paragraph is a heading,
 * whether it is an item of a Word list (and its level), and whether the paragraph holds a picture.
 *
 * Safe against hostile files: no DTD, no external entities, at most MAX_ENTRIES entries, the body read up to
 * MAX_BODY_BYTES; anything else is refused as "not a Word file".
 */
public final class DocxLines {

    static final int MAX_ENTRIES = 5_000;
    static final int MAX_BODY_BYTES = 30 * 1024 * 1024;
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private DocxLines() {
    }

    /**
     * One line of the document. listLevel: null outside a Word list, 0 for the first level, 1 for the second, ...;
     * listId: which Word list it belongs to (w:numId), so a lettered list of options under a numbered question is told apart
     */
    public record Line(String text, boolean marked, boolean heading, Integer listLevel, boolean hasImage, String listId) {

        public Line(String text, boolean marked, boolean heading, Integer listLevel, boolean hasImage) {
            this(text, marked, heading, listLevel, hasImage, null);
        }
    }

    public static class NotADocxException extends Exception {
        public NotADocxException(String message) {
            super(message);
        }
    }

    public static List<Line> read(byte[] file) throws NotADocxException {
        return parse(body(file));
    }

    static byte[] body(byte[] file) throws NotADocxException {
        if (file.length < 4 || file[0] != 'P' || file[1] != 'K') {
            throw new NotADocxException("not a zip file");
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file))) {
            ZipEntry entry;
            int entries = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new NotADocxException("too many entries");
                }
                if ("word/document.xml".equals(entry.getName())) {
                    return readLimited(zip);
                }
            }
        } catch (IOException e) {
            throw new NotADocxException("unreadable zip");
        }
        throw new NotADocxException("no word/document.xml");
    }

    private static byte[] readLimited(InputStream in) throws IOException, NotADocxException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > MAX_BODY_BYTES) {
                throw new NotADocxException("body too large");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    static List<Line> parse(byte[] xml) throws NotADocxException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        List<Line> lines = new ArrayList<>();
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            Paragraph paragraph = null;
            boolean inRunProperties = false;
            boolean runMarked = false;
            boolean inText = false;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT && W.equals(reader.getNamespaceURI())) {
                    String name = reader.getLocalName();
                    switch (name) {
                        case "p" -> paragraph = new Paragraph();
                        case "pStyle" -> {
                            if (paragraph != null) {
                                String style = attribute(reader, "val");
                                paragraph.heading = style != null && (style.toLowerCase().startsWith("heading")
                                        || style.toLowerCase().startsWith("title") || style.toLowerCase().startsWith("başlıq"));
                            }
                        }
                        case "numPr" -> {
                            if (paragraph != null && paragraph.listLevel == null) {
                                paragraph.listLevel = 0;
                            }
                        }
                        case "numId" -> {
                            if (paragraph != null) {
                                paragraph.listId = attribute(reader, "val");
                            }
                        }
                        case "ilvl" -> {
                            if (paragraph != null) {
                                paragraph.listLevel = parseLevel(attribute(reader, "val"));
                            }
                        }
                        case "r" -> runMarked = false;
                        case "rPr" -> inRunProperties = true;
                        case "b" -> {
                            if (inRunProperties) {
                                runMarked = runMarked || isOn(attribute(reader, "val"));
                            }
                        }
                        case "highlight" -> {
                            if (inRunProperties) {
                                String value = attribute(reader, "val");
                                runMarked = runMarked || (value != null && !"none".equalsIgnoreCase(value));
                            }
                        }
                        case "t" -> inText = true;
                        case "tab" -> {
                            if (paragraph != null && !inRunProperties) {
                                paragraph.append(" ", runMarked);
                            }
                        }
                        case "br", "cr" -> {
                            if (paragraph != null) {
                                paragraph.breakLine();
                            }
                        }
                        case "drawing", "pict", "object" -> {
                            if (paragraph != null) {
                                paragraph.hasImage = true;
                            }
                        }
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && inText && paragraph != null) {
                    paragraph.append(reader.getText(), runMarked);
                } else if (event == XMLStreamConstants.END_ELEMENT && W.equals(reader.getNamespaceURI())) {
                    switch (reader.getLocalName()) {
                        case "t" -> inText = false;
                        case "rPr" -> inRunProperties = false;
                        case "p" -> {
                            if (paragraph != null) {
                                paragraph.finish(lines);
                            }
                            paragraph = null;
                        }
                        default -> {
                        }
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new NotADocxException("unreadable document.xml");
        }
        return lines;
    }

    private static String attribute(XMLStreamReader reader, String localName) {
        String value = reader.getAttributeValue(W, localName);
        return value != null ? value : reader.getAttributeValue(null, localName);
    }

    // <w:b/> is on; <w:b w:val="0"/> or "false" is off
    private static boolean isOn(String value) {
        return value == null || !("0".equals(value) || "false".equalsIgnoreCase(value) || "none".equalsIgnoreCase(value));
    }

    private static Integer parseLevel(String value) {
        try {
            return value == null ? 0 : Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * A paragraph being read: its lines so far, each with how much of its text is marked
     */
    private static final class Paragraph {
        boolean heading;
        Integer listLevel;
        String listId;
        boolean hasImage;
        private final List<StringBuilder> texts = new ArrayList<>(List.of(new StringBuilder()));
        private final List<int[]> marks = new ArrayList<>(List.of(new int[]{0, 0}));

        void append(String text, boolean marked) {
            texts.get(texts.size() - 1).append(text);
            int visible = text.strip().length();
            int[] mark = marks.get(marks.size() - 1);
            mark[0] += visible;
            if (marked) {
                mark[1] += visible;
            }
        }

        void breakLine() {
            texts.add(new StringBuilder());
            marks.add(new int[]{0, 0});
        }

        void finish(List<Line> into) {
            boolean first = true;
            for (int i = 0; i < texts.size(); i++) {
                String text = texts.get(i).toString().replace(' ', ' ').replaceAll("\\s+", " ").strip();
                if (text.isEmpty()) {
                    continue;
                }
                int[] mark = marks.get(i);
                // Only the first line of a list item is the item; the rest continues it
                into.add(new Line(text, mark[0] > 0 && mark[1] == mark[0], heading, first ? listLevel : null, hasImage, first ? listId : null));
                first = false;
            }
            if (first && hasImage) {
                into.add(new Line("", false, false, null, true));
            }
        }
    }
}
