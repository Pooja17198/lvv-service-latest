package com.oracle.pic.networking.lvv.service.dependencies.ncp.extractor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.oracle.pic.commons.metrics.MetricsScope;
import com.oracle.pic.networking.lvv.service.dependencies.metrics.MetricNames;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FecBerResultExtractorTest {

    private FecBerTestResultExtractor extractor;
    private MetricsScope metricsScope;

    @BeforeEach
    void setup() {
        extractor = new FecBerTestResultExtractor();
        metricsScope = mock(MetricsScope.class);
        when(metricsScope.emit(any(Enum.class), anyDouble())).thenReturn(metricsScope);
    }

    @Test
    void testName_returnsTestFecBerThreshold() {
        assertEquals("test_fec_ber_threshold", extractor.testName());
    }

    @Test
    void extract_nullMessage_createsEmptyFecBerList() {
        Map<String, Map<String, List<Map<String, String>>>> deviceResults = new HashMap<>();
        extractor.extract("dev1", null, metricsScope, deviceResults);

        assertTrue(deviceResults.containsKey("dev1"));
        Map<String, List<Map<String, String>>> perDevice = deviceResults.get("dev1");
        assertTrue(perDevice.containsKey("FEC_BER Errors"));
        assertTrue(perDevice.get("FEC_BER Errors").isEmpty());
        verifyNoInteractions(metricsScope);
    }

    @Test
    void extract_blankMessage_createsEmptyFecBerList() {
        Map<String, Map<String, List<Map<String, String>>>> deviceResults = new HashMap<>();
        extractor.extract("dev1", "   \n\t", metricsScope, deviceResults);

        assertTrue(deviceResults.containsKey("dev1"));
        Map<String, List<Map<String, String>>> perDevice = deviceResults.get("dev1");
        assertTrue(perDevice.containsKey("FEC_BER Errors"));
        assertTrue(perDevice.get("FEC_BER Errors").isEmpty());
        verifyNoInteractions(metricsScope);
    }

    @Test
    void extract_singleValidBlock_parsesAllFields_andNoMetric() {
        Map<String, Map<String, List<Map<String, String>>>> deviceResults = new HashMap<>();
        String message =
                "Failed: { 'Ethernet1/1': { "
                        + "'rack': 'R01', 'device_name': 'sw-01', 'pre_fec_ber': '1.2e-5', "
                        + "'lock_status': True, 'remote_device': 'sw-02', 'remote_interface': 'Ethernet1/2', "
                        + "} }";

        extractor.extract("devX", message, metricsScope, deviceResults);

        Map<String, List<Map<String, String>>> perDevice = deviceResults.get("devX");
        List<Map<String, String>> rows = perDevice.get("FEC_BER Errors");
        assertEquals(1, rows.size());
        Map<String, String> r = rows.get(0);

        assertEquals("R01", r.get("Device Rack"));
        assertEquals("sw-01", r.get("Device Name"));
        assertEquals("Ethernet1/1", r.get("Device Port"));
        assertEquals("1.2e-5", r.get("PRE_FEC_BER"));
        assertEquals("true", r.get("Lock Status"));
        assertEquals("sw-02", r.get("Remote Device"));
        assertEquals("Ethernet1/2", r.get("Remote Interface"));

        verifyNoInteractions(metricsScope);
    }

    @Test
    void extract_multipleBlocks_processesAll() {
        Map<String, Map<String, List<Map<String, String>>>> deviceResults = new HashMap<>();
        String message =
                "{ 'Ethernet1/1': { 'rack': 'R01', 'device_name': 'A', 'pre_fec_ber': '1e-6', 'lock_status': False } } "
                        + "noise "
                        + "{ 'Ethernet1/2': { 'rack': 'R02', 'device_name': 'B', 'pre_fec_ber': '2e-6', 'lock_status': True } }";

        extractor.extract("dev1", message, metricsScope, deviceResults);

        List<Map<String, String>> rows = deviceResults.get("dev1").get("FEC_BER Errors");
        assertEquals(2, rows.size());

        assertEquals("R01", rows.get(0).get("Device Rack"));
        assertEquals("A", rows.get(0).get("Device Name"));
        assertEquals("Ethernet1/1", rows.get(0).get("Device Port"));
        assertEquals("1e-6", rows.get(0).get("PRE_FEC_BER"));
        assertEquals("false", rows.get(0).get("Lock Status"));

        assertEquals("R02", rows.get(1).get("Device Rack"));
        assertEquals("B", rows.get(1).get("Device Name"));
        assertEquals("Ethernet1/2", rows.get(1).get("Device Port"));
        assertEquals("2e-6", rows.get(1).get("PRE_FEC_BER"));
        assertEquals("true", rows.get(1).get("Lock Status"));

        verifyNoInteractions(metricsScope);
    }

    @Test
    void extract_prefixedSingleInterfaceMessage_processesProvidedExample() {
        Map<String, Map<String, List<Map<String, String>>>> deviceResults = new HashMap<>();
        String message =
                "Failed: The following 1 interfaces do not have fec alignment lock or had errors above 1e-07: {'Ethernet42/1': {'lock_status': True, 'pre_fec_ber': 2.55257167638123e-05, 'elevation': '38', 'rack': '0908', 'device_name': 'dxb3-q1-b3-t0-r8', 'remote_device': 'dxb3-q1-b3-t1-r10', 'remote_interface': 'Ethernet57/1', 'warning': 'action required, check host status'}}";

        extractor.extract("fallbackDev", message, metricsScope, deviceResults);

        List<Map<String, String>> rows = deviceResults.get("fallbackDev").get("FEC_BER Errors");
        assertEquals(1, rows.size());

        Map<String, String> row = rows.get(0);
        assertEquals("0908", row.get("Device Rack"));
        assertEquals("dxb3-q1-b3-t0-r8", row.get("Device Name"));
        assertEquals("Ethernet42/1", row.get("Device Port"));
        assertEquals("2.55257167638123e-05", row.get("PRE_FEC_BER"));
        assertEquals("true", row.get("Lock Status"));
        assertEquals("dxb3-q1-b3-t1-r10", row.get("Remote Device"));
        assertEquals("Ethernet57/1", row.get("Remote Interface"));
        assertNull(row.get("Error Message"));

        verifyNoInteractions(metricsScope);
    }

    @Test
    void extract_prefixedMultipleInterfaceMessage_addsOneRowPerInterface() {
        Map<String, Map<String, List<Map<String, String>>>> deviceResults = new HashMap<>();
        String message =
                "Failed: The following 2 interfaces do not have fec alignment lock or had errors above 1e-07: {'Ethernet26/1': {'lock_status': True, 'pre_fec_ber': 1.7873778271625481e-06, 'elevation': '11', 'rack': '4210', 'device_name': 'cwl16-q1-b2-t0-r22', 'remote_device': 'cwl16-q1-b2-t1-r25', 'remote_interface': 'Ethernet11/5', 'warning': 'action required, check host status'}} {'Ethernet27/5': {'lock_status': True, 'pre_fec_ber': 1.7161905458676178e-07, 'elevation': '11', 'rack': '4210', 'device_name': 'cwl16-q1-b2-t0-r22', 'remote_device': 'cwl16-q1-b2-t1-r28', 'remote_interface': 'Ethernet11/5', 'warning': 'action required, check host status'}}";

        extractor.extract("fallbackDev", message, metricsScope, deviceResults);

        List<Map<String, String>> rows = deviceResults.get("fallbackDev").get("FEC_BER Errors");
        assertEquals(2, rows.size());

        assertEquals("Ethernet26/1", rows.get(0).get("Device Port"));
        assertEquals("1.7873778271625481e-06", rows.get(0).get("PRE_FEC_BER"));
        assertEquals("cwl16-q1-b2-t1-r25", rows.get(0).get("Remote Device"));
        assertEquals("Ethernet11/5", rows.get(0).get("Remote Interface"));

        assertEquals("Ethernet27/5", rows.get(1).get("Device Port"));
        assertEquals("1.7161905458676178e-07", rows.get(1).get("PRE_FEC_BER"));
        assertEquals("cwl16-q1-b2-t1-r28", rows.get(1).get("Remote Device"));
        assertEquals("Ethernet11/5", rows.get(1).get("Remote Interface"));

        verifyNoInteractions(metricsScope);
    }

    @Test
    void extract_jsonStyleDoubleQuotedMessage_addsOneRowPerInterface() {
        Map<String, Map<String, List<Map<String, String>>>> deviceResults = new HashMap<>();
        String message =
                "Failed: The following 2 interfaces did not meet criteria (Duration >= 4h, PRE_FEC_BER < 1e-07, FEC-BIN-COUNT = 0): "
                        + "{\"swp20s3\": {\"device_name\": \"aga5-q2-p1-t0-r89\", \"elevation\": \"1\", \"fec_bin\": 0, \"last_clear_counter\": 0.08, \"pre_fec_ber\": 5e-13, \"rack\": \"3403\", \"remote_device\": \"aga5-c1-b12-t0-r19-compute4\", \"remote_interface\": \"slot1/port1-1\", \"up_time\": 0.1, \"warning\": \"UP_TIME=0.1 < 4h; LAST_CLEAR=0.08 < 4h\"}} "
                        + "{\"swp40s0\": {\"device_name\": \"aga5-q2-p1-t0-r89\", \"elevation\": \"1\", \"fec_bin\": 2, \"last_clear_counter\": 14.57, \"pre_fec_ber\": 1e-10, \"rack\": \"3403\", \"remote_device\": \"aga5-c1-b12-t0-r23-compute5\", \"remote_interface\": \"slot1/port1-1\", \"up_time\": 14.54, \"warning\": \"FEC_BIN_9_COUNT=2 (expected 0)\"}}";

        extractor.extract("fallbackDev", message, metricsScope, deviceResults);

        List<Map<String, String>> rows = deviceResults.get("fallbackDev").get("FEC_BER Errors");
        assertEquals(2, rows.size());

        Map<String, String> row1 =
                rows.stream()
                        .filter(r -> "swp20s3".equals(r.get("Device Port")))
                        .findFirst()
                        .orElse(null);
        assertNotNull(row1);
        assertEquals("3403", row1.get("Device Rack"));
        assertEquals("aga5-q2-p1-t0-r89", row1.get("Device Name"));
        assertEquals("5e-13", row1.get("PRE_FEC_BER"));
        assertEquals("Unknown", row1.get("Lock Status"));
        assertEquals("aga5-c1-b12-t0-r19-compute4", row1.get("Remote Device"));
        assertEquals("slot1/port1-1", row1.get("Remote Interface"));

        Map<String, String> row2 =
                rows.stream()
                        .filter(r -> "swp40s0".equals(r.get("Device Port")))
                        .findFirst()
                        .orElse(null);
        assertNotNull(row2);
        assertEquals("3403", row2.get("Device Rack"));
        assertEquals("aga5-q2-p1-t0-r89", row2.get("Device Name"));
        assertEquals("1e-10", row2.get("PRE_FEC_BER"));
        assertEquals("Unknown", row2.get("Lock Status"));
        assertEquals("aga5-c1-b12-t0-r23-compute5", row2.get("Remote Device"));
        assertEquals("slot1/port1-1", row2.get("Remote Interface"));

        verifyNoInteractions(metricsScope);
    }

    @Test
    void extract_jsonStyleEscapedQuotesMessage_addsOneRowPerInterface() {
        Map<String, Map<String, List<Map<String, String>>>> deviceResults = new HashMap<>();
        String message =
                "Failed: The following 2 interfaces did not meet criteria (Duration >= 4h, PRE_FEC_BER < 1e-07, FEC-BIN-COUNT = 0): "
                        + "{\\\"swp45s0\\\": {\\\"device_name\\\": \\\"aga5-q2-p2-t0-r97\\\", \\\"elevation\\\": \\\"3\\\", \\\"fec_bin\\\": 0, \\\"last_clear_counter\\\": 2.0, \\\"pre_fec_ber\\\": 1e-09, \\\"rack\\\": \\\"3404\\\", \\\"remote_device\\\": \\\"aga5-c1-b13-t0-r2-compute8\\\", \\\"remote_interface\\\": \\\"slot1/port1-2\\\", \\\"up_time\\\": 2.0}} "
                        + "{\\\"swp60s0\\\": {\\\"device_name\\\": \\\"aga5-q2-p2-t0-r97\\\", \\\"elevation\\\": \\\"3\\\", \\\"fec_bin\\\": 0, \\\"last_clear_counter\\\": 2.0, \\\"pre_fec_ber\\\": 4e-10, \\\"rack\\\": \\\"3404\\\", \\\"remote_device\\\": \\\"aga5-c1-b13-t0-r5-compute9\\\", \\\"remote_interface\\\": \\\"slot1/port1-2\\\", \\\"up_time\\\": 2.0}}";

        extractor.extract("fallbackDev", message, metricsScope, deviceResults);

        List<Map<String, String>> rows = deviceResults.get("fallbackDev").get("FEC_BER Errors");
        assertEquals(2, rows.size());

        Map<String, String> row1 =
                rows.stream()
                        .filter(r -> "swp45s0".equals(r.get("Device Port")))
                        .findFirst()
                        .orElse(null);
        assertNotNull(row1);
        assertEquals("3404", row1.get("Device Rack"));
        assertEquals("aga5-q2-p2-t0-r97", row1.get("Device Name"));
        assertEquals("1e-09", row1.get("PRE_FEC_BER"));
        assertEquals("aga5-c1-b13-t0-r2-compute8", row1.get("Remote Device"));
        assertEquals("slot1/port1-2", row1.get("Remote Interface"));

        Map<String, String> row2 =
                rows.stream()
                        .filter(r -> "swp60s0".equals(r.get("Device Port")))
                        .findFirst()
                        .orElse(null);
        assertNotNull(row2);
        assertEquals("3404", row2.get("Device Rack"));
        assertEquals("aga5-q2-p2-t0-r97", row2.get("Device Name"));
        assertEquals("4e-10", row2.get("PRE_FEC_BER"));
        assertEquals("aga5-c1-b13-t0-r5-compute9", row2.get("Remote Device"));
        assertEquals("slot1/port1-2", row2.get("Remote Interface"));

        verifyNoInteractions(metricsScope);
    }

    @Test
    void extract_jsonStyleDoubleEscapedQuotesMessage_addsOneRowPerInterface() {
        Map<String, Map<String, List<Map<String, String>>>> deviceResults = new HashMap<>();
        String singleEscapedMessage =
                "Failed: The following 2 interfaces did not meet criteria (Duration >= 4h, PRE_FEC_BER < 1e-07, FEC-BIN-COUNT = 0): "
                        + "{\\\"swp45s0\\\": {\\\"device_name\\\": \\\"aga5-q2-p2-t0-r97\\\", \\\"pre_fec_ber\\\": 1e-09, \\\"rack\\\": \\\"3404\\\", \\\"remote_device\\\": \\\"aga5-c1-b13-t0-r2-compute8\\\", \\\"remote_interface\\\": \\\"slot1/port1-2\\\"}} "
                        + "{\\\"swp60s0\\\": {\\\"device_name\\\": \\\"aga5-q2-p2-t0-r97\\\", \\\"pre_fec_ber\\\": 4e-10, \\\"rack\\\": \\\"3404\\\", \\\"remote_device\\\": \\\"aga5-c1-b13-t0-r5-compute9\\\", \\\"remote_interface\\\": \\\"slot1/port1-2\\\"}}";
        String message = singleEscapedMessage.replace("\\\"", "\\\\\"");

        extractor.extract("fallbackDev", message, metricsScope, deviceResults);

        List<Map<String, String>> rows = deviceResults.get("fallbackDev").get("FEC_BER Errors");
        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(r -> "swp45s0".equals(r.get("Device Port"))));
        assertTrue(rows.stream().anyMatch(r -> "swp60s0".equals(r.get("Device Port"))));
        verifyNoInteractions(metricsScope);
    }

    @Test
    void extract_jsonStyleUnicodeEscapedQuotesMessage_addsOneRowPerInterface() {
        Map<String, Map<String, List<Map<String, String>>>> deviceResults = new HashMap<>();
        String message =
                "Failed: The following 2 interfaces did not meet criteria (Duration >= 4h, PRE_FEC_BER < 1e-07, FEC-BIN-COUNT = 0): "
                        + "{\\u0022swp20s3\\u0022: {\\u0022device_name\\u0022: \\u0022aga5-q2-p1-t0-r89\\u0022, \\u0022pre_fec_ber\\u0022: 5e-13, \\u0022rack\\u0022: \\u00223403\\u0022, \\u0022remote_device\\u0022: \\u0022aga5-c1-b12-t0-r19-compute4\\u0022, \\u0022remote_interface\\u0022: \\u0022slot1/port1-1\\u0022}} "
                        + "{\\u0022swp40s0\\u0022: {\\u0022device_name\\u0022: \\u0022aga5-q2-p1-t0-r89\\u0022, \\u0022pre_fec_ber\\u0022: 1e-10, \\u0022rack\\u0022: \\u00223403\\u0022, \\u0022remote_device\\u0022: \\u0022aga5-c1-b12-t0-r23-compute5\\u0022, \\u0022remote_interface\\u0022: \\u0022slot1/port1-1\\u0022}}";

        extractor.extract("fallbackDev", message, metricsScope, deviceResults);

        List<Map<String, String>> rows = deviceResults.get("fallbackDev").get("FEC_BER Errors");
        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(r -> "swp20s3".equals(r.get("Device Port"))));
        assertTrue(rows.stream().anyMatch(r -> "swp40s0".equals(r.get("Device Port"))));
        verifyNoInteractions(metricsScope);
    }

    @Test
    void extract_jsonStyleMessage_withoutExtraOuterBraceStillParses() {
        Map<String, Map<String, List<Map<String, String>>>> deviceResults = new HashMap<>();
        String message =
                "Failed: The following 2 interfaces did not meet criteria (Duration >= 4h, PRE_FEC_BER < 1e-07, FEC-BIN-COUNT = 0): "
                        + "{\"swp26s0\": {\"device_name\": \"aga5-q2-p3-t0-r68\", \"pre_fec_ber\": 4e-14, \"rack\": \"2204\", \"remote_device\": \"aga5-q2-p3-t1-r25\", \"remote_interface\": \"swp34s1\"} "
                        + "{\"swp3s1\": {\"device_name\": \"aga5-q2-p3-t0-r68\", \"pre_fec_ber\": 1e-14, \"rack\": \"2204\", \"remote_device\": \"aga5-q2-p3-t1-r4\", \"remote_interface\": \"swp34s1\"}";

        extractor.extract("fallbackDev", message, metricsScope, deviceResults);

        List<Map<String, String>> rows = deviceResults.get("fallbackDev").get("FEC_BER Errors");
        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(r -> "swp26s0".equals(r.get("Device Port"))));
        assertTrue(rows.stream().anyMatch(r -> "swp3s1".equals(r.get("Device Port"))));
        verifyNoInteractions(metricsScope);
    }

    @Test
    void extract_supportsArbitraryQuotedInterfaceNames() {
        Map<String, Map<String, List<Map<String, String>>>> deviceResults = new HashMap<>();
        String message =
                "Failed: {'et-0/0/0': {'rack': 'R10', 'device_name': 'router-1', 'pre_fec_ber': 9.9e-08, 'lock_status': False, 'remote_device': 'router-2', 'remote_interface': 'xe-0/0/1'}}";

        extractor.extract("fallbackDev", message, metricsScope, deviceResults);

        List<Map<String, String>> rows = deviceResults.get("fallbackDev").get("FEC_BER Errors");
        assertEquals(1, rows.size());

        Map<String, String> row = rows.get(0);
        assertEquals("et-0/0/0", row.get("Device Port"));
        assertEquals("router-1", row.get("Device Name"));
        assertEquals("9.9e-08", row.get("PRE_FEC_BER"));
        assertEquals("false", row.get("Lock Status"));
        assertEquals("router-2", row.get("Remote Device"));
        assertEquals("xe-0/0/1", row.get("Remote Interface"));

        verifyNoInteractions(metricsScope);
    }

    @Test
    void extract_unexpectedFormat_addsUnknownRow_andEmitsMetric() {
        Map<String, Map<String, List<Map<String, String>>>> deviceResults = new HashMap<>();
        extractor.extract(
                "devZ", "not matching the expected block structure", metricsScope, deviceResults);

        List<Map<String, String>> rows = deviceResults.get("devZ").get("FEC_BER Errors");
        assertEquals(1, rows.size());
        Map<String, String> r = rows.get(0);

        assertEquals("devZ", r.get("Device Name"));
        assertEquals("Unknown", r.get("Device Rack"));
        assertEquals("Unknown", r.get("Device Port"));
        assertEquals("Unknown", r.get("PRE_FEC_BER"));
        assertEquals("Unknown", r.get("Lock Status"));
        assertEquals("Unknown", r.get("Remote Device"));
        assertEquals("Unknown", r.get("Remote Interface"));

        verify(metricsScope).emit(MetricNames.ProcessNcpResult.FecBerErrorFormatUnexpected, 1.0);
    }

    @Test
    void extract_missingFields_defaultsAndUnknowns() {
        Map<String, Map<String, List<Map<String, String>>>> deviceResults = new HashMap<>();
        String message = "{ 'Eth9/9': { 'rack': 'R99', 'lock_status': False } }";

        extractor.extract("fallbackDev", message, metricsScope, deviceResults);

        List<Map<String, String>> rows = deviceResults.get("fallbackDev").get("FEC_BER Errors");
        assertEquals(1, rows.size());
        Map<String, String> r = rows.get(0);

        assertEquals("R99", r.get("Device Rack"));
        assertEquals("fallbackDev", r.get("Device Name"));
        assertEquals("Eth9/9", r.get("Device Port"));
        assertEquals("Unknown", r.get("PRE_FEC_BER"));
        assertEquals("false", r.get("Lock Status"));
        assertEquals("Unknown", r.get("Remote Device"));
        assertEquals("Unknown", r.get("Remote Interface"));

        verifyNoInteractions(metricsScope);
    }
}
