package com.speedcallerbot.service;

import com.speedcallerbot.model.ParsedBatch;
import com.speedcallerbot.model.ParsedContact;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NumberImportService {
    private static final Pattern PHONE_CHUNK_PATTERN = Pattern.compile("(?:\\+|\\d)[\\d\\s()\\-]{8,25}\\d");

    public ParsedBatch parseByFileName(String fileName, InputStream inputStream) throws IOException {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx")) {
            return parseExcel(inputStream);
        }
        if (lower.endsWith(".txt") || lower.endsWith(".csv")) {
            return parsePlainTextStream(inputStream);
        }
        throw new IllegalArgumentException("Unsupported file format. Please upload .xlsx, .txt or .csv.");
    }

    public ParsedBatch parsePlainText(String text) {
        List<ParsedContact> contacts = new ArrayList<>();
        int invalid = 0;

        if (text == null || text.isBlank()) {
            return new ParsedBatch(contacts, 1);
        }

        String[] lines = text.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            ParsedContact parsed = parseLine(trimmed);
            if (parsed == null) {
                invalid++;
            } else {
                contacts.add(parsed);
            }
        }

        return new ParsedBatch(contacts, invalid);
    }

    private ParsedBatch parsePlainTextStream(InputStream inputStream) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return parsePlainText(sb.toString());
    }

    private ParsedBatch parseExcel(InputStream inputStream) throws IOException {
        List<ParsedContact> contacts = new ArrayList<>();
        int invalid = 0;
        DataFormatter formatter = new DataFormatter(Locale.ROOT);

        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                List<String> values = new ArrayList<>();
                boolean hasAnyText = false;

                short lastCell = row.getLastCellNum();
                if (lastCell < 0) {
                    continue;
                }

                for (int i = 0; i < lastCell; i++) {
                    Cell cell = row.getCell(i);
                    if (cell == null) {
                        continue;
                    }

                    String value = formatter.formatCellValue(cell);
                    if (value == null) {
                        continue;
                    }

                    String trimmed = value.trim();
                    if (!trimmed.isEmpty()) {
                        values.add(trimmed);
                        hasAnyText = true;
                    }
                }

                if (!hasAnyText) {
                    continue;
                }

                ParsedContact parsed = parseRowValues(values);
                if (parsed == null) {
                    invalid++;
                } else {
                    contacts.add(parsed);
                }
            }
        }

        return new ParsedBatch(contacts, invalid);
    }

    private ParsedContact parseRowValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }

        String phone = null;
        String selectedRawPhone = null;
        for (String value : values) {
            String normalized = PhoneUtils.normalizePhone(value);
            if (normalized != null) {
                phone = normalized;
                selectedRawPhone = value;
                break;
            }

            Matcher matcher = PHONE_CHUNK_PATTERN.matcher(value);
            while (matcher.find()) {
                String maybePhone = matcher.group();
                String maybeNormalized = PhoneUtils.normalizePhone(maybePhone);
                if (maybeNormalized != null) {
                    phone = maybeNormalized;
                    selectedRawPhone = maybePhone;
                    break;
                }
            }

            if (phone != null) {
                break;
            }
        }

        if (phone == null) {
            return null;
        }

        String name = null;
        for (String value : values) {
            if (value.equals(selectedRawPhone)) {
                continue;
            }
            if (PhoneUtils.normalizePhone(value) != null) {
                continue;
            }
            if (value.matches(".*\\d{4,}.*")) {
                continue;
            }
            name = value.trim();
            if (!name.isEmpty()) {
                break;
            }
        }

        if (name == null || name.isBlank()) {
            name = "No Name";
        }

        return new ParsedContact(name, phone);
    }

    private ParsedContact parseLine(String line) {
        String[] chunks = line.split("[\\t,;|]");
        String phone = null;
        String rawPhone = null;

        for (String chunk : chunks) {
            String trimmed = chunk.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            String normalized = PhoneUtils.normalizePhone(trimmed);
            if (normalized != null) {
                phone = normalized;
                rawPhone = trimmed;
                break;
            }
        }

        if (phone == null) {
            Matcher matcher = PHONE_CHUNK_PATTERN.matcher(line);
            while (matcher.find()) {
                String candidate = matcher.group();
                String normalized = PhoneUtils.normalizePhone(candidate);
                if (normalized != null) {
                    phone = normalized;
                    rawPhone = candidate;
                    break;
                }
            }
        }

        if (phone == null) {
            return null;
        }

        String name = null;
        for (String chunk : chunks) {
            String trimmed = chunk.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.equals(rawPhone)) {
                continue;
            }
            if (PhoneUtils.normalizePhone(trimmed) != null) {
                continue;
            }
            name = trimmed;
            break;
        }

        if (name == null || name.isBlank()) {
            String fromLine = line.replace(rawPhone, "").replaceAll("^[\\s:;,.|\\-]+|[\\s:;,.|\\-]+$", "").trim();
            name = fromLine.isBlank() ? "No Name" : fromLine;
        }

        return new ParsedContact(name, phone);
    }
}
