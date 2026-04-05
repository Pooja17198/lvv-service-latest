package com.oracle.pic.networking.lvv.service.dependencies.ncp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oracle.pic.commons.exceptions.server.ErrorCode;
import com.oracle.pic.commons.exceptions.server.RenderableException;
import com.oracle.pic.commons.metrics.MetricsScope;
import com.oracle.pic.networking.lvv.service.dependencies.metrics.MetricNames;
import com.oracle.pic.networking.lvv.service.dependencies.ncp.extractor.FanTestResultExtractor;
import com.oracle.pic.networking.lvv.service.dependencies.ncp.extractor.FecBerTestResultExtractor;
import com.oracle.pic.networking.lvv.service.dependencies.ncp.extractor.InterfaceTestResultExtractor;
import com.oracle.pic.networking.lvv.service.dependencies.ncp.extractor.LldpTestResultExtractor;
import com.oracle.pic.networking.lvv.service.dependencies.ncp.extractor.OpticsTestResultExtractor;
import com.oracle.pic.networking.lvv.service.dependencies.ncp.extractor.PowerTestResultExtractor;
import com.oracle.pic.networking.lvv.service.kiev.JobStatus;
import com.oracle.pic.networking.lvv.service.kiev.NcpJobDetailsDao;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@Getter
@Setter
@ToString
@Slf4j
public class NcpJobResultProcessor {

    InputStream inputStream;
    Map<String, Map<String, List<Map<String, String>>>> deviceResults;
    ObjectMapper mapper;
    NcpJobDetailsDao ncpJobDetailsDao;

    private static final String TEST_LLDP = "test_lldp";
    private static final String TEST_POWER = "test_power";
    private static final String TEST_OPTICS = "test_optics";
    private static final String TEST_INTERFACES = "test_interfaces";
    private static final String TEST_FEC_BER = "test_fec_ber_threshold";
    private static final String TEST_FANS = "test_fans";

    private static final String PASSED = "PASSED";
    private static final String FAILED = "FAILED";

    private static final String UNKNOWN = "Unknown";
    private static final int FEC_BER_LOG_PREVIEW_CHARS = 1024;
    private static final int FEC_BER_WARN_PREVIEW_CHARS = 400;

    public NcpJobResultProcessor(InputStream inputStream, NcpJobDetailsDao ncpJobDetailsDao) {
        this.inputStream = inputStream;
        this.mapper = new ObjectMapper();
        this.deviceResults = new LinkedHashMap<>();
        this.ncpJobDetailsDao = ncpJobDetailsDao;
    }

    private boolean checkDeviceUnreachable(String errorMsg) {
        return errorMsg.contains("Unable to connect to");
    }

    private boolean checkDeviceClientError(String errorMsg) {
        return errorMsg.contains("400 Client Error");
    }

    public void processJobResult(MetricsScope scope) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root;

        try {
            root = mapper.readTree(inputStream);
        } catch (IOException e) {
            log.error("Failed to parse job result as JSON: {}", e.getMessage());
            scope.emit(MetricNames.ProcessNcpResult.JsonParseFail, 1.0);
            throw new RenderableException(
                    ErrorCode.InternalError, "Failed to parse NCP job result as JSON");
        }

        JsonNode testResults = root.path("testResults");
        if (testResults.isMissingNode()) {
            log.info("No 'testResults' found in the payload.");
            scope.emit(MetricNames.ProcessNcpResult.NoTestResultFound, 1.0);
            throw new RenderableException(
                    ErrorCode.ExternalServerInvalidResponse,
                    "No 'testResults' found in the payload.");
        }

        Iterator<Map.Entry<String, JsonNode>> deviceIterator = testResults.fields();
        log.info(
                "\nProcessing FAILED {} {} {} {} {} {} Test Cases:",
                TEST_LLDP,
                TEST_OPTICS,
                TEST_POWER,
                TEST_INTERFACES,
                TEST_FEC_BER,
                TEST_FANS);

        while (deviceIterator.hasNext()) {
            Map.Entry<String, JsonNode> deviceEntry = deviceIterator.next();
            String deviceId = deviceEntry.getKey();
            JsonNode healthCheckReport = deviceEntry.getValue().path("healthCheckReport");
            JsonNode testCases = healthCheckReport.path("testCases");

            log.info("Processing results for device {}", deviceId);

            if (testCases.isArray()) {
                String lldpError = null;
                String opticsError = null;
                String powerError = null;
                String interfaceError = null;
                String fecBerError = null;
                String fanError = null;
                boolean deviceHasFailures = false;

                for (JsonNode testCaseNode : testCases) {
                    String testCaseName = testCaseNode.path("testCase").asText();
                    String status = testCaseNode.path("status").asText();

                    if (TEST_LLDP.equals(testCaseName) && FAILED.equals(status)) {
                        lldpError = testCaseNode.path("message").asText();
                    } else if (TEST_LLDP.equals(testCaseName) && PASSED.equals(status)) {
                        lldpError = PASSED;
                    }

                    if (TEST_OPTICS.equals(testCaseName) && FAILED.equals(status)) {
                        opticsError = testCaseNode.path("message").asText();
                    } else if (TEST_OPTICS.equals(testCaseName) && PASSED.equals(status)) {
                        opticsError = PASSED;
                    }

                    if (TEST_POWER.equals(testCaseName) && FAILED.equals(status)) {
                        powerError = FAILED;
                    } else if (TEST_POWER.equals(testCaseName) && PASSED.equals(status)) {
                        powerError = PASSED;
                    }

                    if (TEST_INTERFACES.equals(testCaseName) && FAILED.equals(status)) {
                        interfaceError = testCaseNode.path("message").asText();
                    } else if (TEST_INTERFACES.equals(testCaseName) && PASSED.equals(status)) {
                        interfaceError = PASSED;
                    }

                    if (TEST_FEC_BER.equals(testCaseName) && FAILED.equals(status)) {
                        JsonNode fecBerMessageNode = testCaseNode.path("message");
                        fecBerError = fecBerMessageNode.asText();
                        log.warn(
                                "[FEC_BER_TRACE] device={} status=FAILED nodeType={} asTextLen={} asTextPreview={}",
                                deviceId,
                                fecBerMessageNode.getNodeType(),
                                fecBerError == null ? 0 : fecBerError.length(),
                                toLogPreview(fecBerError, FEC_BER_WARN_PREVIEW_CHARS));
                        log.warn(
                                "[FEC_BER_TRACE] device={} rawJsonNodePreview={}",
                                deviceId,
                                toLogPreview(
                                        fecBerMessageNode.toString(), FEC_BER_WARN_PREVIEW_CHARS));
                        log.info(
                                "[FEC_BER] Captured failed message for device {} (nodeType={}, asTextLen={}, asTextPreview={}, jsonNodePreview={})",
                                deviceId,
                                fecBerMessageNode.getNodeType(),
                                fecBerError == null ? 0 : fecBerError.length(),
                                toLogPreview(fecBerError, FEC_BER_LOG_PREVIEW_CHARS),
                                toLogPreview(
                                        fecBerMessageNode.toString(), FEC_BER_LOG_PREVIEW_CHARS));
                    } else if (TEST_FEC_BER.equals(testCaseName) && PASSED.equals(status)) {
                        fecBerError = PASSED;
                    }

                    if (TEST_FANS.equals(testCaseName) && FAILED.equals(status)) {
                        fanError = testCaseNode.path("message").asText();
                    } else if (TEST_FANS.equals(testCaseName) && PASSED.equals(status)) {
                        fanError = PASSED;
                    }
                }

                // If device is unreachable, all 3 tests(lldp, power, optics) will show the same
                // error message. We just check for LLDP error to confirm
                // If unreachable, we move on to next device, after updating the database
                if (lldpError != null && checkDeviceUnreachable(lldpError)) {
                    log.info("Device {} unreachable. Updating DB", deviceId);
                    ncpJobDetailsDao.updateNcpJobStatus(deviceId, JobStatus.DEVICE_UNREACHABLE);
                    continue;
                }

                if (interfaceError != null && checkDeviceClientError(interfaceError)) {
                    log.info("Device {} showing 400 Client Error message. Updating DB", deviceId);
                    ncpJobDetailsDao.updateNcpJobStatus(deviceId, JobStatus.DEVICE_UNREACHABLE);
                    continue;
                }

                if (lldpError != null && !lldpError.equals(PASSED)) {
                    deviceHasFailures = true;
                    scope.emit(MetricNames.ProcessNcpResult.LldpError, 1.0);
                    LldpTestResultExtractor lldpTestResultExtractor = new LldpTestResultExtractor();
                    lldpTestResultExtractor.extract(deviceId, lldpError, scope, deviceResults);
                }

                if (opticsError != null && !opticsError.equals(PASSED)) {
                    deviceHasFailures = true;
                    scope.emit(MetricNames.ProcessNcpResult.OpticError, 1.0);
                    OpticsTestResultExtractor opticsTestResultExtractor =
                            new OpticsTestResultExtractor();
                    opticsTestResultExtractor.extract(deviceId, opticsError, scope, deviceResults);
                }

                if (Objects.equals(powerError, FAILED)) {
                    deviceHasFailures = true;
                    scope.emit(MetricNames.ProcessNcpResult.PsuError, 1.0);
                    PowerTestResultExtractor powerTestResultExtractor =
                            new PowerTestResultExtractor();
                    powerTestResultExtractor.extract(deviceId, powerError, scope, deviceResults);
                }

                if (interfaceError != null && !interfaceError.equals(PASSED)) {
                    deviceHasFailures = true;
                    scope.emit(MetricNames.ProcessNcpResult.InterfaceError, 1.0);
                    InterfaceTestResultExtractor interfaceTestResultExtractor =
                            new InterfaceTestResultExtractor();
                    interfaceTestResultExtractor.extract(
                            deviceId, interfaceError, scope, deviceResults);
                }

                if (fecBerError != null && !fecBerError.equals(PASSED)) {
                    deviceHasFailures = true;
                    scope.emit(MetricNames.ProcessNcpResult.FecBerError, 1.0);
                    FecBerTestResultExtractor fecBerTestResultExtractor =
                            new FecBerTestResultExtractor();
                    fecBerTestResultExtractor.extract(deviceId, fecBerError, scope, deviceResults);
                }

                if (fanError != null && !fanError.equals(PASSED)) {
                    deviceHasFailures = true;
                    scope.emit(MetricNames.ProcessNcpResult.FanError, 1.0);
                    FanTestResultExtractor fanTestResultExtractor = new FanTestResultExtractor();
                    fanTestResultExtractor.extract(deviceId, fanError, scope, deviceResults);
                }

                if (!deviceHasFailures) {

                    log.info("Device has no failures");
                    scope.emit(MetricNames.ProcessNcpResult.Pass, 1.0);

                    // If a device has no failures, we create an empty map for this device
                    // to overwrite its previous error map in the DB
                    deviceResults.put(deviceId, new HashMap<>());
                }
            }
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
}
