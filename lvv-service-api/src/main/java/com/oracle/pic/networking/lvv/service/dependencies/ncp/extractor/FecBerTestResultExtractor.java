package com.oracle.pic.networking.lvv.service.dependencies.ncp.extractor;

import com.oracle.pic.commons.metrics.MetricsScope;
import com.oracle.pic.networking.lvv.service.dependencies.metrics.MetricNames;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/** Extractor for FEC_BER failures. */
@Slf4j
public class FecBerTestResultExtractor implements TestResultExtractor {

    private static final String TEST_FEC_BER = "test_fec_ber_threshold";
    private static final String FEC_BER = "FEC_BER Errors";

    // FEC_BER Result Column Names
    private static final String DEVICE_RACK = "Device Rack";
    private static final String DEVICE_NAME = "Device Name";
    private static final String DEVICE_PORT = "Device Port";
    private static final String PRE_FEC_BER = "PRE_FEC_BER";
    private static final String LOCK_STATUS = "Lock Status";
    private static final String REMOTE_DEVICE = "Remote Device";
    private static final String REMOTE_INTERFACE = "Remote Interface";
    private static final String ERROR_MESSAGE = "Error Message";

    private static final String UNKNOWN = "Unknown";
    private static final Pattern BLOCK_START_PATTERN =
            Pattern.compile("\\{\\s*(['\"])([^'\"]+)\\1\\s*:\\s*\\{");

    @Override
    public String testName() {
        return TEST_FEC_BER;
    }

    @Override
    public void extract(
            String deviceId,
            String message,
            MetricsScope scope,
            Map<String, Map<String, List<Map<String, String>>>> deviceResults) {
        Map<String, List<Map<String, String>>> perDeviceMap =
                deviceResults.computeIfAbsent(deviceId, k -> new HashMap<>());

        List<Map<String, String>> fecBerResults =
                perDeviceMap.computeIfAbsent(FEC_BER, k -> new ArrayList<>());

        if (message == null || message.isBlank()) {
            return;
        }

        log.info("[FEC_BER] Processing FEC_BER Error message for device {}: {}", deviceId, message);

        boolean anyRowAdded = false;

        List<FecBerBlock> blocks = extractBlocks(message);
        if (blocks.isEmpty()) {
            String normalizedMessage = normalizeEscapedQuotes(message);
            if (!normalizedMessage.equals(message)) {
                blocks = extractBlocks(normalizedMessage);
            }
        }

        for (FecBerBlock block : blocks) {
            try {
                String portName = block.portName();
                String innerMap = block.innerMap();

                Map<String, String> row = new HashMap<>();
                row.put(DEVICE_RACK, extractFieldValue(innerMap, "rack", UNKNOWN));
                row.put(DEVICE_NAME, extractFieldValue(innerMap, "device_name", deviceId));
                row.put(DEVICE_PORT, portName);
                row.put(PRE_FEC_BER, extractFieldValue(innerMap, "pre_fec_ber", UNKNOWN));
                row.put(
                        LOCK_STATUS,
                        normalizeLockStatus(extractFieldValue(innerMap, "lock_status", UNKNOWN)));
                row.put(REMOTE_DEVICE, extractFieldValue(innerMap, "remote_device", UNKNOWN));
                row.put(REMOTE_INTERFACE, extractFieldValue(innerMap, "remote_interface", UNKNOWN));
                fecBerResults.add(row);
                anyRowAdded = true;
            } catch (RuntimeException e) {
                log.warn(
                        "[FEC_BER] Failed to parse extracted block for device {} and port {}: {}",
                        deviceId,
                        block.portName(),
                        block.innerMap(),
                        e);
            }
        }

        if (!anyRowAdded) {
            logBlockExtractionDiagnostics(message);
            log.warn("[FEC_BER] FEC_BER Error in unexpected format: {}", message);
            scope.emit(MetricNames.ProcessNcpResult.FecBerErrorFormatUnexpected, 1.0);
            fecBerResults.add(createUnknownFecBerResult(deviceId, message));
        }
    }

    private String extractFieldValue(String innerMap, String fieldName, String defaultValue) {
        if (innerMap == null || innerMap.isBlank()) {
            return defaultValue;
        }

        int valueStart = findFieldValueStart(innerMap, fieldName);
        if (valueStart < 0) {
            return defaultValue;
        }

        char firstChar = innerMap.charAt(valueStart);
        if (firstChar == '\'' || firstChar == '"') {
            int valueEnd = findClosingQuote(innerMap, valueStart + 1, firstChar);
            if (valueEnd < 0) {
                return defaultValue;
            }
            return innerMap.substring(valueStart + 1, valueEnd);
        }

        int valueEnd = valueStart;
        while (valueEnd < innerMap.length()) {
            char currentChar = innerMap.charAt(valueEnd);
            if (currentChar == ',' || currentChar == '}') {
                break;
            }
            valueEnd++;
        }

        String rawValue = innerMap.substring(valueStart, valueEnd).trim();
        return rawValue.isEmpty() ? defaultValue : rawValue;
    }

    private String normalizeEscapedQuotes(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        String normalized = value;
        // Some callers persist messages with more than one escaping layer (e.g. \\\"...).
        // Peel escaped quotes repeatedly so block extraction can still detect {"port": {...}}.
        for (int attempt = 0; attempt < 5; attempt++) {
            String next = normalized.replace("\\\"", "\"").replace("\\'", "'");
            if (next.equals(normalized)) {
                break;
            }
            normalized = next;
        }
        return normalized;
    }

    private int findFieldValueStart(String innerMap, String fieldName) {
        String quotedOrBareFieldPattern =
                "(?:['\"]"
                        + Pattern.quote(fieldName)
                        + "['\"]|\\b"
                        + Pattern.quote(fieldName)
                        + "\\b)\\s*:";

        Matcher matcher = Pattern.compile(quotedOrBareFieldPattern).matcher(innerMap);
        if (!matcher.find()) {
            return -1;
        }
        return findNextNonWhitespaceChar(innerMap, matcher.end());
    }

    private int findClosingQuote(String value, int startIndex, char quoteChar) {
        for (int index = startIndex; index < value.length(); index++) {
            if (value.charAt(index) == quoteChar && !isEscaped(value, index)) {
                return index;
            }
        }
        return -1;
    }

    private String normalizeLockStatus(String lockStatus) {
        if ("True".equals(lockStatus)) {
            return "true";
        }
        if ("False".equals(lockStatus)) {
            return "false";
        }
        return lockStatus;
    }

    private List<FecBerBlock> extractBlocks(String message) {
        List<FecBerBlock> blocks = new ArrayList<>();

        Matcher matcher = BLOCK_START_PATTERN.matcher(message);
        while (matcher.find()) {
            String portName = matcher.group(2).trim();
            int innerStart = matcher.end() - 1;

            int innerEnd = findMatchingBrace(message, innerStart);
            if (innerEnd < 0) {
                break;
            }

            int outerEnd = findNextNonWhitespaceChar(message, innerEnd + 1);

            String innerMap = message.substring(innerStart + 1, innerEnd).trim();
            blocks.add(new FecBerBlock(portName, innerMap));
            // Support both forms:
            // 1) {"port": {...}}  (outer wrapper present)
            // 2) {"port": {...}   (outer wrapper omitted in message text)
            int nextSearchStart =
                    (outerEnd >= 0 && message.charAt(outerEnd) == '}')
                            ? outerEnd + 1
                            : innerEnd + 1;
            matcher.region(nextSearchStart, message.length());
        }

        return blocks;
    }

    private void logBlockExtractionDiagnostics(String message) {
        Matcher matcher = BLOCK_START_PATTERN.matcher(message);
        int candidates = 0;
        while (matcher.find()) {
            candidates++;
            String portName = matcher.group(2).trim();
            int innerStart = matcher.end() - 1;
            int innerEnd = findMatchingBrace(message, innerStart);
            int outerEnd = innerEnd < 0 ? -1 : findNextNonWhitespaceChar(message, innerEnd + 1);
            String outerChar =
                    outerEnd < 0
                            ? "<eos>"
                            : "'" + message.charAt(outerEnd) + "'(code=" + (int) message.charAt(outerEnd) + ")";
            log.warn(
                    "[FEC_BER_DIAG] candidatePort={} innerStart={} innerEnd={} outerEnd={} outerChar={}",
                    portName,
                    innerStart,
                    innerEnd,
                    outerEnd,
                    outerChar);

            if (innerEnd < 0) {
                break;
            }
            int nextSearchStart =
                    (outerEnd >= 0 && message.charAt(outerEnd) == '}')
                            ? outerEnd + 1
                            : innerEnd + 1;
            matcher.region(nextSearchStart, message.length());
        }

        if (candidates == 0) {
            int firstBrace = message.indexOf('{');
            log.warn(
                    "[FEC_BER_DIAG] no block starts found; firstBraceIndex={}, messagePreview={}",
                    firstBrace,
                    toLogPreview(message, 300));
        }
    }

    private String toLogPreview(String value, int maxChars) {
        if (value == null) {
            return "<null>";
        }
        String flattened = value.replace("\r", "\\r").replace("\n", "\\n");
        if (flattened.length() <= maxChars) {
            return flattened;
        }
        return flattened.substring(0, maxChars)
                + "...(truncated,totalChars="
                + flattened.length()
                + ")";
    }

    private int findNextNonWhitespaceChar(String value, int startIndex) {
        for (int index = startIndex; index < value.length(); index++) {
            if (!Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private int findMatchingBrace(String value, int openingBraceIndex) {
        int braceDepth = 0;
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int index = openingBraceIndex; index < value.length(); index++) {
            char currentChar = value.charAt(index);

            if (currentChar == '\'' && !inDoubleQuotes && !isEscaped(value, index)) {
                inSingleQuotes = !inSingleQuotes;
                continue;
            }

            if (currentChar == '"' && !inSingleQuotes && !isEscaped(value, index)) {
                inDoubleQuotes = !inDoubleQuotes;
                continue;
            }

            if (inSingleQuotes || inDoubleQuotes) {
                continue;
            }

            if (currentChar == '{') {
                braceDepth++;
            } else if (currentChar == '}') {
                braceDepth--;
                if (braceDepth == 0) {
                    return index;
                }
            }
        }

        return -1;
    }

    private boolean isEscaped(String value, int index) {
        int backslashCount = 0;
        for (int current = index - 1; current >= 0 && value.charAt(current) == '\\'; current--) {
            backslashCount++;
        }
        return backslashCount % 2 != 0;
    }

    private static final class FecBerBlock {
        private final String portName;
        private final String innerMap;

        private FecBerBlock(String portName, String innerMap) {
            this.portName = portName;
            this.innerMap = innerMap;
        }

        private String portName() {
            return portName;
        }

        private String innerMap() {
            return innerMap;
        }
    }

    private Map<String, String> createUnknownFecBerResult(String deviceId, String rawMessage) {
        Map<String, String> result = new HashMap<>();
        result.put(DEVICE_NAME, deviceId);
        result.put(DEVICE_RACK, UNKNOWN);
        result.put(DEVICE_PORT, UNKNOWN);
        result.put(PRE_FEC_BER, UNKNOWN);
        result.put(LOCK_STATUS, UNKNOWN);
        result.put(REMOTE_DEVICE, UNKNOWN);
        result.put(REMOTE_INTERFACE, UNKNOWN);
        result.put(ERROR_MESSAGE, rawMessage == null ? UNKNOWN : rawMessage);
        return result;
    }
}
