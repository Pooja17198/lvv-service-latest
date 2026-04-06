package com.oracle.pic.networking.lvv.service.dependencies.ncp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.oracle.pic.commons.exceptions.server.RenderableException;
import com.oracle.pic.commons.metrics.MetricsScope;
import com.oracle.pic.networking.lvv.service.dependencies.metrics.MetricNames;
import com.oracle.pic.networking.lvv.service.kiev.JobStatus;
import com.oracle.pic.networking.lvv.service.kiev.NcpJobDetailsDao;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NcpJobResultProcessorTest {

    MetricsScope metricsScope;
    NcpJobDetailsDao ncpJobDetailsDao;

    @BeforeEach
    void setup() {
        metricsScope = mock(MetricsScope.class);
        // Make emit invocations lenient and chain-safe if needed
        when(metricsScope.emit(any(Enum.class), anyDouble())).thenReturn(metricsScope);
        when(metricsScope.emit(anyString(), anyDouble())).thenReturn(metricsScope);

        ncpJobDetailsDao = mock(NcpJobDetailsDao.class);
    }

    private NcpJobResultProcessor newProcessorWithJson(String json) {
        return new NcpJobResultProcessor(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), ncpJobDetailsDao);
    }

    private String escapeForJsonValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Test
    void processJobResult_invalidJson_throws_and_emits_metric() {
        NcpJobResultProcessor p = newProcessorWithJson("not_json");

        assertThrows(RenderableException.class, () -> p.processJobResult(metricsScope));
        verify(metricsScope, atLeastOnce())
                .emit(eq(MetricNames.ProcessNcpResult.JsonParseFail), anyDouble());
    }

    @Test
    void processJobResult_missingTestResults_throws_and_emits_metric() {
        String json = "{\"foo\":1}";
        NcpJobResultProcessor p = newProcessorWithJson(json);

        assertThrows(RenderableException.class, () -> p.processJobResult(metricsScope));
        verify(metricsScope, atLeastOnce())
                .emit(eq(MetricNames.ProcessNcpResult.NoTestResultFound), anyDouble());
    }

    @Test
    void processJobResult_deviceUnreachable_updatesDao_and_skips_results() {
        String json =
                """
                        {
                          "testResults": {
                            "device1": {
                              "healthCheckReport": {
                                "testCases": [
                                  { "testCase": "test_lldp", "status": "FAILED", "message": "Unable to connect to device x" }
                                ]
                              }
                            },
                            "device2": {
                              "healthCheckReport": {
                                "testCases": [
                                  { "testCase": "test_lldp", "status": "PASSED" },
                                  { "testCase": "test_optics", "status": "PASSED" },
                                  { "testCase": "test_power", "status": "PASSED" }
                                ]
                              }
                            }
                          }
                        }
                        """;

        NcpJobResultProcessor p = newProcessorWithJson(json);
        p.processJobResult(metricsScope);

        verify(ncpJobDetailsDao, times(1))
                .updateNcpJobStatus(eq("device1"), eq(JobStatus.DEVICE_UNREACHABLE));

        Map<String, Map<String, List<Map<String, String>>>> results = p.getDeviceResults();
        assertFalse(results.containsKey("device1"), "Unreachable device should not have results");
        assertTrue(results.containsKey("device2"));
        assertTrue(results.get("device2").isEmpty(), "All pass should record an empty map");
    }

    @Test
    void processJobResult_allPass_recordsEmptyMap_and_emitsPass() {
        String json =
                """
                        {
                          "testResults": {
                            "deviceA": {
                              "healthCheckReport": {
                                "testCases": [
                                  { "testCase": "test_lldp", "status": "PASSED" },
                                  { "testCase": "test_optics", "status": "PASSED" },
                                  { "testCase": "test_power", "status": "PASSED" }
                                ]
                              }
                            }
                          }
                        }
                        """;
        NcpJobResultProcessor p = newProcessorWithJson(json);
        p.processJobResult(metricsScope);

        Map<String, Map<String, List<Map<String, String>>>> results = p.getDeviceResults();
        assertTrue(results.containsKey("deviceA"));
        assertTrue(results.get("deviceA").isEmpty(), "No failures should overwrite with empty map");
        verify(metricsScope, atLeastOnce())
                .emit(eq(MetricNames.ProcessNcpResult.Pass), anyDouble());
    }

    @Test
    void processJobResult_failures_allThree_populatesResults_and_emitsErrorMetrics() {
        String lldpMsg =
                "Failed: {\\\"message\\\":\\\"LLDP Failures: 1\\\",\\\"errors_object\\\":[{\\\"current_origin\\\":\\\"devX:Eth1/1:x:y:u1\\\",\\\"current_destination\\\":\\\"devY:Eth2/2:a:b:u2\\\",\\\"expected_destination\\\":\\\"devZ:Eth3/3:c:d:u3\\\"}]}";
        String opticsMsg =
                "Failed: {\\\"errors_object\\\":[{\\\"device\\\":\\\"devX\\\",\\\"intf_name\\\":\\\"Eth1/1\\\",\\\"input_power\\\":\\\"-5.0\\\",\\\"output_power\\\":\\\"1.2\\\",\\\"device_phys\\\":\\\"rack:U42\\\"}]}";
        String json =
                """
                        {
                          "testResults": {
                            "devX": {
                              "healthCheckReport": {
                                "testCases": [
                                  { "testCase": "test_lldp", "status": "FAILED", "message": "%s" },
                                  { "testCase": "test_optics", "status": "FAILED", "message": "%s" },
                                  { "testCase": "test_power", "status": "FAILED" }
                                ]
                              }
                            }
                          }
                        }
                        """
                        .formatted(lldpMsg, opticsMsg);

        NcpJobResultProcessor p = newProcessorWithJson(json);
        assertDoesNotThrow(() -> p.processJobResult(metricsScope));

        Map<String, Map<String, List<Map<String, String>>>> results = p.getDeviceResults();
        Map<String, List<Map<String, String>>> perDev = results.get("devX");
        assertNotNull(perDev);

        // LLDP Errors
        List<Map<String, String>> lldp = perDev.get("LLDP Errors");
        assertNotNull(lldp);
        assertEquals(1, lldp.size());
        Map<String, String> lldpEntry = lldp.get(0);
        assertEquals("devX", lldpEntry.get("Device A Name"));
        assertEquals("Eth1/1", lldpEntry.get("Device A Port"));
        assertEquals("MISMATCH", lldpEntry.get("LLDP Status"));

        // Optic Errors
        List<Map<String, String>> optics = perDev.get("Optic Errors");
        assertNotNull(optics);
        assertEquals(1, optics.size());
        Map<String, String> opticEntry = optics.get(0);
        assertEquals("devX", opticEntry.get("Device Name"));
        assertEquals("Eth1/1", opticEntry.get("Device Port"));
        assertEquals("1.2", opticEntry.get("Tx Power"));
        assertEquals("-5.0", opticEntry.get("Rx Power"));

        // Power Errors
        List<Map<String, String>> power = perDev.get("Power Errors");
        assertNotNull(power);
        assertEquals(1, power.size());
        assertEquals("devX", power.get(0).get("Device A Name"));

        // Metrics for each failure branch
        verify(metricsScope, atLeastOnce())
                .emit(eq(MetricNames.ProcessNcpResult.LldpError), anyDouble());
        verify(metricsScope, atLeastOnce())
                .emit(eq(MetricNames.ProcessNcpResult.OpticError), anyDouble());
        verify(metricsScope, atLeastOnce())
                .emit(eq(MetricNames.ProcessNcpResult.PsuError), anyDouble());
    }

    @Test
    void processJobResult_lldpBreakoutPort_preservedInOutput() {
        String lldpMsg =
                "Failed: {\\\"message\\\":\\\"LLDP Failures: 1\\\",\\\"errors_object\\\":[{\\\"current_origin\\\":\\\"devX:et-0/0/25:1:x:y:u1\\\",\\\"current_destination\\\":\\\"devY:et-0/0/26:1:a:b:u2\\\",\\\"expected_destination\\\":\\\"devZ:et-0/0/27:1:c:d:u3\\\"}]}";
        String json =
                """
                        {
                          "testResults": {
                            "devX": {
                              "healthCheckReport": {
                                "testCases": [
                                  { "testCase": "test_lldp", "status": "FAILED", "message": "%s" },
                                  { "testCase": "test_optics", "status": "PASSED" },
                                  { "testCase": "test_power", "status": "PASSED" }
                                ]
                              }
                            }
                          }
                        }
                        """
                        .formatted(lldpMsg);

        NcpJobResultProcessor p = newProcessorWithJson(json);
        p.processJobResult(metricsScope);

        Map<String, List<Map<String, String>>> perDev = p.getDeviceResults().get("devX");
        assertNotNull(perDev);
        List<Map<String, String>> lldp = perDev.get("LLDP Errors");
        assertNotNull(lldp);
        assertEquals(1, lldp.size());
        Map<String, String> lldpEntry = lldp.get(0);
        assertEquals("et-0/0/25:1", lldpEntry.get("Device A Port"));
        assertEquals("et-0/0/26:1", lldpEntry.get("Device B Port"));
        assertEquals("et-0/0/27:1", lldpEntry.get("Expected Device B Port"));
    }

    @Test
    void processJobResult_lldpMalformed_createsUnknown_and_emitsExtractorMetric() {
        // LLDP with malformed JSON after "Failed:" should create UNKNOWN entry via extractor
        String lldpMsg = "Failed: not_json";
        String json =
                """
                        {
                          "testResults": {
                            "devL": {
                              "healthCheckReport": {
                                "testCases": [
                                  { "testCase": "test_lldp", "status": "FAILED", "message": "%s" },
                                  { "testCase": "test_optics", "status": "PASSED" },
                                  { "testCase": "test_power", "status": "PASSED" }
                                ]
                              }
                            }
                          }
                        }
                        """
                        .formatted(lldpMsg);

        NcpJobResultProcessor p = newProcessorWithJson(json);
        assertDoesNotThrow(() -> p.processJobResult(metricsScope));

        Map<String, Map<String, List<Map<String, String>>>> results = p.getDeviceResults();
        Map<String, List<Map<String, String>>> perDev = results.get("devL");
        assertNotNull(perDev);

        List<Map<String, String>> lldp = perDev.get("LLDP Errors");
        assertNotNull(lldp);
        assertEquals(1, lldp.size());
        assertEquals("Unknown", lldp.get(0).get("LLDP Status"));

        // Extractor emits format unexpected metric
        verify(metricsScope, atLeastOnce())
                .emit(eq(MetricNames.ProcessNcpResult.LldpErrorFormatUnexpected), anyDouble());
    }

    @Test
    void processJobResult_opticsMalformed_throws_and_emitsExtractorMetric() {
        // Optics extractor throws RenderableException on malformed, which should bubble up
        String opticsMsg = "Failed: not_json";
        String json =
                """
                        {
                          "testResults": {
                            "devO": {
                              "healthCheckReport": {
                                "testCases": [
                                  { "testCase": "test_lldp", "status": "PASSED" },
                                  { "testCase": "test_optics", "status": "FAILED", "message": "%s" },
                                  { "testCase": "test_power", "status": "PASSED" }
                                ]
                              }
                            }
                          }
                        }
                        """
                        .formatted(opticsMsg);

        NcpJobResultProcessor p = newProcessorWithJson(json);

        RenderableException ex =
                assertThrows(RenderableException.class, () -> p.processJobResult(metricsScope));
        assertTrue(
                ex.getMessage().toLowerCase().contains("optic error in unexpected format"),
                "Exception should indicate optics unexpected format");

        // Extractor emits format unexpected metric
        verify(metricsScope, atLeastOnce())
                .emit(eq(MetricNames.ProcessNcpResult.OpticErrorFormatUnexpected), anyDouble());
    }

    @Test
    void processJobResult_emptyTestResults_noDevices_noMetricsOrDao() {
        String json =
                """
                        {
                          "testResults": { }
                        }
                        """;
        NcpJobResultProcessor p = newProcessorWithJson(json);
        p.processJobResult(metricsScope);

        assertTrue(p.getDeviceResults().isEmpty(), "No devices should be recorded");
        verifyNoInteractions(ncpJobDetailsDao);
        verify(metricsScope, never()).emit(any(Enum.class), anyDouble());
        verify(metricsScope, never()).emit(anyString(), anyDouble());
    }

    @Test
    void processJobResult_missingHealthCheckReport_skipsDevice_withoutMetrics() {
        String json =
                """
                        {
                          "testResults": {
                            "devX": { }
                          }
                        }
                        """;
        NcpJobResultProcessor p = newProcessorWithJson(json);
        p.processJobResult(metricsScope);

        assertFalse(p.getDeviceResults().containsKey("devX"), "Device should be skipped");
        verifyNoInteractions(ncpJobDetailsDao);
        verify(metricsScope, never()).emit(any(Enum.class), anyDouble());
        verify(metricsScope, never()).emit(anyString(), anyDouble());
    }

    @Test
    void processJobResult_testCasesNotArray_skipsDevice_withoutMetrics() {
        String json =
                """
                        {
                          "testResults": {
                            "devY": {
                              "healthCheckReport": {
                                "testCases": { "not": "an array" }
                              }
                            }
                          }
                        }
                        """;
        NcpJobResultProcessor p = newProcessorWithJson(json);
        p.processJobResult(metricsScope);

        assertFalse(p.getDeviceResults().containsKey("devY"), "Device should be skipped");
        verifyNoInteractions(ncpJobDetailsDao);
        verify(metricsScope, never()).emit(any(Enum.class), anyDouble());
        verify(metricsScope, never()).emit(anyString(), anyDouble());
    }

    @Test
    void processJobResult_emptyTestCasesArray_treatedAsPass_and_emitsPass() {
        String json =
                """
                        {
                          "testResults": {
                            "devE": {
                              "healthCheckReport": {
                                "testCases": []
                              }
                            }
                          }
                        }
                        """;
        NcpJobResultProcessor p = newProcessorWithJson(json);
        p.processJobResult(metricsScope);

        Map<String, Map<String, List<Map<String, String>>>> results = p.getDeviceResults();
        assertTrue(results.containsKey("devE"));
        assertTrue(
                results.get("devE").isEmpty(),
                "Empty test cases should result in empty map (pass)");
        verify(metricsScope, atLeastOnce())
                .emit(eq(MetricNames.ProcessNcpResult.Pass), anyDouble());
    }

    @Test
    void processJobResult_lldpFailureNonStandardMessage_addsEmptyLldpList_and_emitsLldpMetric() {
        String json =
                """
                        {
                          "testResults": {
                            "devNS": {
                              "healthCheckReport": {
                                "testCases": [
                                  { "testCase": "test_lldp", "status": "FAILED", "message": "LLDP failure without Failed prefix" },
                                  { "testCase": "test_optics", "status": "PASSED" },
                                  { "testCase": "test_power", "status": "PASSED" }
                                ]
                              }
                            }
                          }
                        }
                        """;
        NcpJobResultProcessor p = newProcessorWithJson(json);
        p.processJobResult(metricsScope);

        Map<String, List<Map<String, String>>> perDev = p.getDeviceResults().get("devNS");
        assertNotNull(perDev, "Device entry should exist");
        assertTrue(perDev.containsKey("LLDP Errors"), "LLDP Errors key should be created");
        assertEquals(
                0,
                perDev.get("LLDP Errors").size(),
                "LLDP list should be empty for non-standard message");

        verify(metricsScope, atLeastOnce())
                .emit(eq(MetricNames.ProcessNcpResult.LldpError), anyDouble());
        verify(metricsScope, never()).emit(eq(MetricNames.ProcessNcpResult.Pass), anyDouble());
    }

    @Test
    void processJobResult_lldpUnsupported_whenCryptoInName_setsUnsupportedStatus() {
        String lldpMsg =
                "Failed: {\\\"message\\\":\\\"LLDP Failures: 1\\\",\\\"errors_object\\\":[{\\\"current_origin\\\":\\\"cryptoDev:Eth1/1:x:y:U10\\\",\\\"current_destination\\\":\\\"devB:Eth2/2:a:b:U20\\\",\\\"expected_destination\\\":\\\"devZ:Eth3/3:c:d:U30\\\"}]}";
        String json =
                """
                        {
                          "testResults": {
                            "devC": {
                              "healthCheckReport": {
                                "testCases": [
                                  { "testCase": "test_lldp", "status": "FAILED", "message": "%s" },
                                  { "testCase": "test_optics", "status": "PASSED" },
                                  { "testCase": "test_power", "status": "PASSED" }
                                ]
                              }
                            }
                          }
                        }
                        """
                        .formatted(lldpMsg);

        NcpJobResultProcessor p = newProcessorWithJson(json);
        p.processJobResult(metricsScope);

        Map<String, List<Map<String, String>>> perDev = p.getDeviceResults().get("devC");
        assertNotNull(perDev);
        List<Map<String, String>> lldp = perDev.get("LLDP Errors");
        assertNotNull(lldp);
        assertEquals(1, lldp.size());
        assertEquals("UNSUPPORTED", lldp.get(0).get("LLDP Status"));

        verify(metricsScope, atLeastOnce())
                .emit(eq(MetricNames.ProcessNcpResult.LldpError), anyDouble());
    }

    @Test
    void processJobResult_lldpInterfaceDown_whenDestinationUnknown_setsInterfaceDownStatus() {
        String lldpMsg =
                "Failed: {\\\"message\\\":\\\"LLDP Failures: 1\\\",\\\"errors_object\\\":[{\\\"current_origin\\\":\\\"devA:Eth1/1:x:y:U10\\\",\\\"current_destination\\\":\\\"devB:Eth2/2\\\",\\\"expected_destination\\\":\\\"devZ:Eth3/3:c:d:U30\\\"}]}";
        String json =
                """
                        {
                          "testResults": {
                            "devID": {
                              "healthCheckReport": {
                                "testCases": [
                                  { "testCase": "test_lldp", "status": "FAILED", "message": "%s" },
                                  { "testCase": "test_optics", "status": "PASSED" },
                                  { "testCase": "test_power", "status": "PASSED" }
                                ]
                              }
                            }
                          }
                        }
                        """
                        .formatted(lldpMsg);

        NcpJobResultProcessor p = newProcessorWithJson(json);
        p.processJobResult(metricsScope);

        Map<String, List<Map<String, String>>> perDev = p.getDeviceResults().get("devID");
        assertNotNull(perDev);
        List<Map<String, String>> lldp = perDev.get("LLDP Errors");
        assertNotNull(lldp);
        assertEquals(1, lldp.size());
        assertEquals("INTERFACE_DOWN", lldp.get(0).get("LLDP Status"));

        verify(metricsScope, atLeastOnce())
                .emit(eq(MetricNames.ProcessNcpResult.LldpError), anyDouble());
    }

    @Test
    void processJobResult_opticsRawJsonEmptyArray_addsEmptyOpticsList_and_emitsOpticMetric() {
        String json =
                """
                        {
                          "testResults": {
                            "devORaw": {
                              "healthCheckReport": {
                                "testCases": [
                                  { "testCase": "test_lldp", "status": "PASSED" },
                                  { "testCase": "test_optics", "status": "FAILED", "message": "{\\"errors_object\\":[]}" },
                                  { "testCase": "test_power", "status": "PASSED" }
                                ]
                              }
                            }
                          }
                        }
                        """;

        NcpJobResultProcessor p = newProcessorWithJson(json);
        p.processJobResult(metricsScope);

        Map<String, List<Map<String, String>>> perDev = p.getDeviceResults().get("devORaw");
        assertNotNull(perDev);
        List<Map<String, String>> optics = perDev.get("Optic Errors");
        assertNotNull(optics, "Optic Errors key should exist");
        assertEquals(0, optics.size(), "Optic list should be empty when errors_object is empty");

        verify(metricsScope, atLeastOnce())
                .emit(eq(MetricNames.ProcessNcpResult.OpticError), anyDouble());
        verify(metricsScope, never()).emit(eq(MetricNames.ProcessNcpResult.Pass), anyDouble());
    }

    @Test
    void processJobResult_onlyPowerFailure_emitsPsuOnly_and_recordsPowerError() {
        String json =
                """
                        {
                          "testResults": {
                            "devP": {
                              "healthCheckReport": {
                                "testCases": [
                                  { "testCase": "test_lldp", "status": "PASSED" },
                                  { "testCase": "test_optics", "status": "PASSED" },
                                  { "testCase": "test_power", "status": "FAILED" }
                                ]
                              }
                            }
                          }
                        }
                        """;

        NcpJobResultProcessor p = newProcessorWithJson(json);
        p.processJobResult(metricsScope);

        Map<String, List<Map<String, String>>> perDev = p.getDeviceResults().get("devP");
        assertNotNull(perDev);
        List<Map<String, String>> power = perDev.get("Power Errors");
        assertNotNull(power);
        assertEquals(1, power.size());
        assertEquals("devP", power.get(0).get("Device A Name"));

        verify(metricsScope, atLeastOnce())
                .emit(eq(MetricNames.ProcessNcpResult.PsuError), anyDouble());
        verify(metricsScope, never()).emit(eq(MetricNames.ProcessNcpResult.LldpError), anyDouble());
        verify(metricsScope, never())
                .emit(eq(MetricNames.ProcessNcpResult.OpticError), anyDouble());
        verify(metricsScope, never()).emit(eq(MetricNames.ProcessNcpResult.Pass), anyDouble());
    }

    @Test
    void processJobResult_interfaceFailures_parsesPorts_and_emitsInterfaceMetric() {
        String json =
                """
                        {
                          "testResults": {
                            "devI": {
                              "healthCheckReport": {
                                "testCases": [
                                  { "testCase": "test_interfaces", "status": "FAILED", "message": "Failed: Device lhr10-c1-b2-t0-r102 interfaces are not enabled or up: ['Ethernet13/1', 'et-0/0/1']" }
                                ]
                              }
                            }
                          }
                        }
                        """;

        NcpJobResultProcessor p = newProcessorWithJson(json);
        assertDoesNotThrow(() -> p.processJobResult(metricsScope));

        Map<String, Map<String, List<Map<String, String>>>> results = p.getDeviceResults();
        Map<String, List<Map<String, String>>> perDev = results.get("devI");
        assertNotNull(perDev, "Device entry should exist");

        List<Map<String, String>> iface = perDev.get("Interface Errors");
        assertNotNull(iface, "Interface Errors table should exist");
        assertEquals(2, iface.size(), "Two interfaces should be captured");

        Map<String, String> eth11 =
                iface.stream()
                        .filter(r -> "Ethernet13/1".equals(r.get("Device Port")))
                        .findFirst()
                        .orElse(null);
        Map<String, String> et001 =
                iface.stream()
                        .filter(r -> "et-0/0/1".equals(r.get("Device Port")))
                        .findFirst()
                        .orElse(null);

        assertNotNull(eth11);
        assertEquals("devI", eth11.get("Device Name"));
        assertEquals("Interfaces are not enabled or up", eth11.get("Issue"));

        assertNotNull(et001);
        assertEquals("devI", et001.get("Device Name"));
        assertEquals("Interfaces are not enabled or up", et001.get("Issue"));

        verify(metricsScope, atLeastOnce())
                .emit(eq(MetricNames.ProcessNcpResult.InterfaceError), anyDouble());
        verify(metricsScope, never()).emit(eq(MetricNames.ProcessNcpResult.Pass), anyDouble());
    }

    @Test
    void processJobResult_fecBerFailures_parsesBlocks_and_emitsFecBerMetric() {
        // Build JSON via ObjectMapper to ensure proper escaping of quotes in the message string
        String fecBerMsg =
                "Failed: {'Ethernet1/1': {'rack': 'U12', 'pre_fec_ber': '1e-5', 'lock_status': True, "
                        + "'remote_device': 'R1', 'remote_interface': 'Eth2/1'}}"
                        + "{'et-0/0/1': {'rack': 'U13', 'device_name': 'OverrideName', "
                        + "'pre_fec_ber': '2e-5', 'lock_status': False, "
                        + "'remote_device': 'R2', 'remote_interface': 'Et2/2'}}";

        String json =
                """
                        {
                          "testResults": {
                            "devF": {
                              "healthCheckReport": {
                                "testCases": [
                                  { "testCase": "test_fec_ber_threshold", "status": "FAILED", "message": "%s" }
                                ]
                              }
                            }
                          }
                        }
                        """
                        .formatted(fecBerMsg);

        NcpJobResultProcessor p = newProcessorWithJson(json);
        assertDoesNotThrow(() -> p.processJobResult(metricsScope));

        Map<String, Map<String, List<Map<String, String>>>> results = p.getDeviceResults();
        Map<String, List<Map<String, String>>> perDev = results.get("devF");
        assertNotNull(perDev, "Device entry should exist");

        List<Map<String, String>> fec = perDev.get("FEC_BER Errors");
        assertNotNull(fec, "FEC_BER Errors table should exist");
        assertEquals(2, fec.size(), "Two FEC_BER rows expected");

        Map<String, String> rowEth =
                fec.stream()
                        .filter(r -> "Ethernet1/1".equals(r.get("Device Port")))
                        .findFirst()
                        .orElse(null);
        Map<String, String> rowEt =
                fec.stream()
                        .filter(r -> "et-0/0/1".equals(r.get("Device Port")))
                        .findFirst()
                        .orElse(null);

        assertNotNull(rowEth);
        assertEquals("U12", rowEth.get("Device Rack"));
        assertEquals(
                "devF", rowEth.get("Device Name"), "Fallback to deviceId when device_name missing");
        assertEquals("1e-5", rowEth.get("PRE_FEC_BER"));
        assertEquals("true", rowEth.get("Lock Status"));
        assertEquals("R1", rowEth.get("Remote Device"));
        assertEquals("Eth2/1", rowEth.get("Remote Interface"));

        assertNotNull(rowEt);
        assertEquals("U13", rowEt.get("Device Rack"));
        assertEquals("OverrideName", rowEt.get("Device Name"));
        assertEquals("2e-5", rowEt.get("PRE_FEC_BER"));
        assertEquals("false", rowEt.get("Lock Status"));
        assertEquals("R2", rowEt.get("Remote Device"));
        assertEquals("Et2/2", rowEt.get("Remote Interface"));

        verify(metricsScope, atLeastOnce())
                .emit(eq(MetricNames.ProcessNcpResult.FecBerError), anyDouble());
        verify(metricsScope, never())
                .emit(eq(MetricNames.ProcessNcpResult.FecBerErrorFormatUnexpected), anyDouble());
        verify(metricsScope, never()).emit(eq(MetricNames.ProcessNcpResult.Pass), anyDouble());
    }

    @Test
    void processJobResult_fecBerFailures_jsonStyleBlocks_parsesAndEmitsFecBerMetric() {
        String fecBerMsg =
                "Failed: The following 2 interfaces did not meet criteria (Duration >= 4h, PRE_FEC_BER < 1e-07, FEC-BIN-COUNT = 0): "
                        + "{\"swp20s3\": {\"device_name\": \"aga5-q2-p1-t0-r89\", \"fec_bin\": 0, \"pre_fec_ber\": 5e-13, \"rack\": \"3403\", \"remote_device\": \"aga5-c1-b12-t0-r19-compute4\", \"remote_interface\": \"slot1/port1-1\", \"warning\": \"UP_TIME=0.1 < 4h\"}} "
                        + "{\"swp40s0\": {\"device_name\": \"aga5-q2-p1-t0-r89\", \"fec_bin\": 2, \"pre_fec_ber\": 1e-10, \"rack\": \"3403\", \"remote_device\": \"aga5-c1-b12-t0-r23-compute5\", \"remote_interface\": \"slot1/port1-1\", \"warning\": \"FEC_BIN_9_COUNT=2 (expected 0)\"}}";

        String json =
                """
                        {
                          "testResults": {
                            "devFJson": {
                              "healthCheckReport": {
                                "testCases": [
                                  { "testCase": "test_fec_ber_threshold", "status": "FAILED", "message": "%s" }
                                ]
                              }
                            }
                          }
                        }
                        """
                        .formatted(escapeForJsonValue(fecBerMsg));

        NcpJobResultProcessor p = newProcessorWithJson(json);
        assertDoesNotThrow(() -> p.processJobResult(metricsScope));

        Map<String, Map<String, List<Map<String, String>>>> results = p.getDeviceResults();
        Map<String, List<Map<String, String>>> perDev = results.get("devFJson");
        assertNotNull(perDev, "Device entry should exist");

        List<Map<String, String>> fec = perDev.get("FEC_BER Errors");
        assertNotNull(fec, "FEC_BER Errors table should exist");
        assertEquals(2, fec.size(), "Two FEC_BER rows expected");

        Map<String, String> rowSwp20s3 =
                fec.stream()
                        .filter(r -> "swp20s3".equals(r.get("Device Port")))
                        .findFirst()
                        .orElse(null);
        assertNotNull(rowSwp20s3);
        assertEquals("3403", rowSwp20s3.get("Device Rack"));
        assertEquals("aga5-q2-p1-t0-r89", rowSwp20s3.get("Device Name"));
        assertEquals("5e-13", rowSwp20s3.get("PRE_FEC_BER"));
        assertEquals("Unknown", rowSwp20s3.get("Lock Status"));
        assertEquals("aga5-c1-b12-t0-r19-compute4", rowSwp20s3.get("Remote Device"));
        assertEquals("slot1/port1-1", rowSwp20s3.get("Remote Interface"));

        Map<String, String> rowSwp40s0 =
                fec.stream()
                        .filter(r -> "swp40s0".equals(r.get("Device Port")))
                        .findFirst()
                        .orElse(null);
        assertNotNull(rowSwp40s0);
        assertEquals("3403", rowSwp40s0.get("Device Rack"));
        assertEquals("aga5-q2-p1-t0-r89", rowSwp40s0.get("Device Name"));
        assertEquals("1e-10", rowSwp40s0.get("PRE_FEC_BER"));
        assertEquals("Unknown", rowSwp40s0.get("Lock Status"));
        assertEquals("aga5-c1-b12-t0-r23-compute5", rowSwp40s0.get("Remote Device"));
        assertEquals("slot1/port1-1", rowSwp40s0.get("Remote Interface"));

        verify(metricsScope, atLeastOnce())
                .emit(eq(MetricNames.ProcessNcpResult.FecBerError), anyDouble());
        verify(metricsScope, never())
                .emit(eq(MetricNames.ProcessNcpResult.FecBerErrorFormatUnexpected), anyDouble());
    }

    @Test
    void processJobResult_onlyFanFailure_emitsFanOnly_and_recordsFanError() {
        String fanMsg =
                "Failed: {\\\"message\\\":\\\"Fan issues found\\\",\\\"errors_object\\\":[{\\\"fan_name\\\":\\\"\\\",\\\"fan_slot\\\":3,\\\"status\\\":false}]}";
        String json =
                """
                        {
                          "testResults": {
                            "devFan": {
                              "healthCheckReport": {
                                "testCases": [
                                  { "testCase": "test_lldp", "status": "PASSED" },
                                  { "testCase": "test_optics", "status": "PASSED" },
                                  { "testCase": "test_power", "status": "PASSED" },
                                  { "testCase": "test_interfaces", "status": "PASSED" },
                                  { "testCase": "test_fec_ber_threshold", "status": "PASSED" },
                                  { "testCase": "test_fans", "status": "FAILED", "message": "%s" }
                                ]
                              }
                            }
                          }
                        }
                        """
                        .formatted(fanMsg);

        NcpJobResultProcessor p = newProcessorWithJson(json);
        p.processJobResult(metricsScope);

        Map<String, List<Map<String, String>>> perDev = p.getDeviceResults().get("devFan");
        assertNotNull(perDev);
        List<Map<String, String>> fanErrors = perDev.get("Fan Errors");
        assertNotNull(fanErrors);
        assertEquals(1, fanErrors.size());
        Map<String, String> row = fanErrors.get(0);
        assertEquals("devFan", row.get("Device Name"));
        assertEquals("", row.get("Fan Name"));
        assertEquals("3", row.get("Fan Slot"));
        assertEquals("false", row.get("Status"));

        verify(metricsScope, atLeastOnce())
                .emit(eq(MetricNames.ProcessNcpResult.FanError), anyDouble());
    }

    @Test
    void processJobResult_fanMalformedMessage_doesNotThrow_addsUnknown_and_emitsFormatMetric() {
        String fanMsg =
                "Failed: Unable to connect to devFanMalformed. Error: 500 Server Error: TypeError "
                        + "for url: https://device-access-service.svc.ad1.us-saltlake-2/v1/"
                        + "devices/devFanMalformed/environment: {'message': \\\"'<' not supported "
                        + "between instances of 'float' and 'str'\\\", 'name': "
                        + "'unhandled exception TypeError'}";
        String json =
                """
                        {
                          "testResults": {
                            "devFanMalformed": {
                              "healthCheckReport": {
                                "testCases": [
                                  { "testCase": "test_lldp", "status": "PASSED" },
                                  { "testCase": "test_optics", "status": "PASSED" },
                                  { "testCase": "test_power", "status": "PASSED" },
                                  { "testCase": "test_interfaces", "status": "PASSED" },
                                  { "testCase": "test_fec_ber_threshold", "status": "PASSED" },
                                  { "testCase": "test_fans", "status": "FAILED", "message": "%s" }
                                ]
                              }
                            }
                          }
                        }
                        """
                        .formatted(fanMsg);

        NcpJobResultProcessor p = newProcessorWithJson(json);
        assertDoesNotThrow(() -> p.processJobResult(metricsScope));

        Map<String, List<Map<String, String>>> perDev = p.getDeviceResults().get("devFanMalformed");
        assertNotNull(perDev);
        List<Map<String, String>> fanErrors = perDev.get("Fan Errors");
        assertNotNull(fanErrors);
        assertEquals(1, fanErrors.size());
        Map<String, String> row = fanErrors.get(0);
        assertEquals("devFanMalformed", row.get("Device Name"));
        assertEquals("Unknown", row.get("Fan Name"));
        assertEquals("Unknown", row.get("Fan Slot"));
        assertEquals("Unknown", row.get("Status"));

        verify(metricsScope, atLeastOnce())
                .emit(eq(MetricNames.ProcessNcpResult.FanError), anyDouble());
        verify(metricsScope, atLeastOnce())
                .emit(eq(MetricNames.ProcessNcpResult.FanErrorFormatUnexpected), anyDouble());
    }
}
