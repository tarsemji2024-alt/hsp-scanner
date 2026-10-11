package com.rsn.scanner;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * बिना किसी बाहरी library के XLSX लिखना और पढ़ना।
 * लिखना: दो कॉलम (#, RSN) वाली एक शीट "RSN"।
 * पढ़ना: पहली शीट की सारी पंक्तियाँ (shared strings, inline strings, नंबर)।
 */
final class XlsxUtil {
    private XlsxUtil() { }

    private static final int MAX_ENTRY_BYTES = 30 * 1024 * 1024;

    // ------------------------------------------------------------------ पहचान

    static boolean isXlsx(byte[] d) {
        return d != null && d.length > 4 && d[0] == 'P' && d[1] == 'K';
    }

    /** पुराना बाइनरी .xls (OLE2) */
    static boolean isLegacyXls(byte[] d) {
        return d != null && d.length > 8
                && (d[0] & 0xFF) == 0xD0 && (d[1] & 0xFF) == 0xCF
                && (d[2] & 0xFF) == 0x11 && (d[3] & 0xFF) == 0xE0;
    }

    // ------------------------------------------------------------------ लिखना

    static byte[] write(List<String> rsns) throws IOException {
        StringBuilder sheet = new StringBuilder(64 + rsns.size() * 80);
        sheet.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                .append("<cols><col min=\"1\" max=\"1\" width=\"8\" customWidth=\"1\"/>")
                .append("<col min=\"2\" max=\"2\" width=\"28\" customWidth=\"1\"/></cols>")
                .append("<sheetData>");
        sheet.append("<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>#</t></is></c>")
                .append("<c r=\"B1\" t=\"inlineStr\"><is><t>RSN</t></is></c></row>");
        for (int i = 0; i < rsns.size(); i++) {
            int r = i + 2;
            sheet.append("<row r=\"").append(r).append("\">")
                    .append("<c r=\"A").append(r).append("\"><v>").append(i + 1).append("</v></c>")
                    .append("<c r=\"B").append(r).append("\" t=\"inlineStr\"><is><t>")
                    .append(escape(rsns.get(i))).append("</t></is></c></row>");
        }
        sheet.append("</sheetData></worksheet>");

        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "</Types>";

        String rootRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>";

        String workbook = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"RSN\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>";

        String workbookRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "</Relationships>";

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "[Content_Types].xml", contentTypes);
            put(zip, "_rels/.rels", rootRels);
            put(zip, "xl/workbook.xml", workbook);
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels);
            put(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        }
        return bytes.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                default:
                    // XML में अमान्य control characters हटा दें
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ पढ़ना

    /** पहली शीट की पंक्तियाँ (हर पंक्ति = कोशिकाओं की सूची, खाली कॉलम "" ) */
    static List<List<String>> read(byte[] data) throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith("xl/")
                        || !(name.endsWith(".xml") || name.endsWith(".rels"))) continue;
                files.put(name, readLimited(zip));
            }
        }

        String sheetPath = findFirstSheetPath(files);
        byte[] sheetXml = files.get(sheetPath);
        if (sheetXml == null) throw new IOException("शीट नहीं मिली");

        List<String> shared = new ArrayList<>();
        byte[] ss = files.get("xl/sharedStrings.xml");
        if (ss != null) parse(ss, new SharedStringsHandler(shared));

        SheetHandler sheet = new SheetHandler(shared);
        parse(sheetXml, sheet);
        return sheet.rows;
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > MAX_ENTRY_BYTES) throw new IOException("फाइल बहुत बड़ी है");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** workbook.xml की पहली शीट -> rels से असली path */
    private static String findFirstSheetPath(Map<String, byte[]> files) throws Exception {
        String fallback = "xl/worksheets/sheet1.xml";
        byte[] wb = files.get("xl/workbook.xml");
        byte[] rels = files.get("xl/_rels/workbook.xml.rels");
        if (wb != null && rels != null) {
            final String[] rid = {null};
            parse(wb, new Base() {
                @Override public void startElement(String uri, String l, String q, Attributes a) {
                    if (rid[0] == null && "sheet".equals(local(q))) {
                        rid[0] = attr(a, "r:id", "id");
                    }
                }
            });
            if (rid[0] != null) {
                final String id = rid[0];
                final String[] target = {null};
                parse(rels, new Base() {
                    @Override public void startElement(String uri, String l, String q, Attributes a) {
                        if ("Relationship".equals(local(q)) && id.equals(a.getValue("Id"))) {
                            target[0] = a.getValue("Target");
                        }
                    }
                });
                if (target[0] != null) {
                    String t = target[0];
                    String path = t.startsWith("/") ? t.substring(1) : "xl/" + t;
                    if (files.containsKey(path)) return path;
                }
            }
        }
        if (files.containsKey(fallback)) return fallback;
        // आख़िरी उपाय: जो भी पहली worksheet मिले
        List<String> names = new ArrayList<>();
        for (String n : files.keySet()) if (n.startsWith("xl/worksheets/") && n.endsWith(".xml")) names.add(n);
        java.util.Collections.sort(names);
        return names.isEmpty() ? fallback : names.get(0);
    }

    // ------------------------------------------------------------------ SAX

    private static void parse(byte[] xml, DefaultHandler handler) throws Exception {
        SAXParserFactory f = SAXParserFactory.newInstance();
        f.setNamespaceAware(false);
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
            // Android का parser यह feature नहीं मानता; resolveEntity नीचे सुरक्षा देता है
        }
        SAXParser p = f.newSAXParser();
        p.parse(new ByteArrayInputStream(xml), handler);
    }

    private static class Base extends DefaultHandler {
        /** बाहरी entity कभी लोड न हो */
        @Override public InputSource resolveEntity(String publicId, String systemId) {
            return new InputSource(new StringReader(""));
        }

        static String local(String qName) {
            int i = qName.indexOf(':');
            return i < 0 ? qName : qName.substring(i + 1);
        }

        static String attr(Attributes a, String... names) {
            for (String n : names) {
                String v = a.getValue(n);
                if (v != null) return v;
            }
            return null;
        }
    }

    private static final class SharedStringsHandler extends Base {
        private final List<String> out;
        private StringBuilder current;
        private boolean inT;
        private int phoneticDepth;

        SharedStringsHandler(List<String> out) { this.out = out; }

        @Override public void startElement(String uri, String l, String q, Attributes a) {
            String n = local(q);
            if (n.equals("si")) current = new StringBuilder();
            else if (n.equals("rPh")) phoneticDepth++;
            else if (n.equals("t") && current != null && phoneticDepth == 0) inT = true;
        }

        @Override public void characters(char[] ch, int s, int len) {
            if (inT && current != null) current.append(ch, s, len);
        }

        @Override public void endElement(String uri, String l, String q) {
            String n = local(q);
            if (n.equals("t")) inT = false;
            else if (n.equals("rPh")) phoneticDepth--;
            else if (n.equals("si") && current != null) {
                out.add(current.toString());
                current = null;
            }
        }
    }

    private static final class SheetHandler extends Base {
        final List<List<String>> rows = new ArrayList<>();
        private final List<String> shared;
        private List<String> row;
        private int col;
        private String type;
        private StringBuilder text;
        private boolean inV, inInlineT;

        SheetHandler(List<String> shared) { this.shared = shared; }

        @Override public void startElement(String uri, String l, String q, Attributes a) {
            switch (local(q)) {
                case "row":
                    row = new ArrayList<>();
                    break;
                case "c":
                    col = columnIndex(a.getValue("r"), row == null ? 0 : row.size());
                    type = a.getValue("t");
                    text = new StringBuilder();
                    break;
                case "v":
                    inV = true;
                    break;
                case "t":
                    if (text != null && "inlineStr".equals(type)) inInlineT = true;
                    break;
                default:
                    break;
            }
        }

        @Override public void characters(char[] ch, int s, int len) {
            if ((inV || inInlineT) && text != null) text.append(ch, s, len);
        }

        @Override public void endElement(String uri, String l, String q) {
            switch (local(q)) {
                case "v":
                    inV = false;
                    break;
                case "t":
                    inInlineT = false;
                    break;
                case "c":
                    if (row != null && text != null) {
                        while (row.size() < col) row.add("");
                        row.add(resolve(text.toString().trim(), type));
                    }
                    text = null;
                    break;
                case "row":
                    if (row != null) rows.add(row);
                    row = null;
                    break;
                default:
                    break;
            }
        }

        private String resolve(String raw, String t) {
            if ("s".equals(t)) {
                try {
                    int i = Integer.parseInt(raw);
                    return i >= 0 && i < shared.size() ? shared.get(i) : "";
                } catch (NumberFormatException e) {
                    return "";
                }
            }
            if ("b".equals(t)) return "1".equals(raw) ? "TRUE" : "FALSE";
            return raw;
        }

        /** "C7" -> 2 ; r नहीं हो तो अगला कॉलम */
        private static int columnIndex(String ref, int fallback) {
            if (ref == null) return fallback;
            int n = 0, i = 0;
            while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
                n = n * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
                i++;
            }
            return i == 0 ? fallback : n - 1;
        }
    }
}
