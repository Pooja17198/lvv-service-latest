package com.oracle.pic.networking.lvv.service.dependencies.storekeeper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.oracle.pic.commons.exceptions.server.RenderableException;
import com.oracle.pic.commons.metrics.MetricsScope;
import com.oracle.pic.networking.lvv.service.dependencies.metrics.MetricNames;
import com.oracle.pic.storekeeper.StoreKeeper;
import com.oracle.pic.storekeeper.model.RackLocationMap;
import com.oracle.pic.storekeeper.requests.ListRackLocationsMapRequest;
import com.oracle.pic.storekeeper.responses.ListRackLocationsMapResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for StoreKeeperHelper covering edge cases and maximizing coverage. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StoreKeeperHelperTest {

    @Mock StoreKeeper storeKeeper;
    @Mock MetricsScope metricsScope;

    StoreKeeperHelper helper;

    final String building = "B1";
    final String block = "BLK1";

    @BeforeEach
    void setUp() {
        helper = new StoreKeeperHelper(storeKeeper);
    }

    private RackLocationMap mockRack(
            String rackNumber,
            String state,
            String actualSerial,
            String fallbackSerial,
            String platform) {
        RackLocationMap rl = mock(RackLocationMap.class);
        when(rl.getRackNumber()).thenReturn(rackNumber);
        when(rl.getRackState()).thenReturn(state);
        when(rl.getActualRackSerial()).thenReturn(actualSerial);
        when(rl.getRackSerial()).thenReturn(fallbackSerial);
        when(rl.getPlatformName()).thenReturn(platform);
        return rl;
    }

    private ListRackLocationsMapResponse pageResponse(
            List<RackLocationMap> racks, String nextToken) {
        // Use deep stubs to avoid having to know the exact inner class type of
        // getListRackLocationsMap()
        ListRackLocationsMapResponse resp =
                mock(ListRackLocationsMapResponse.class, Answers.RETURNS_DEEP_STUBS);
        when(resp.getListRackLocationsMap().getRackLocations()).thenReturn(racks);
        when(resp.getListRackLocationsMap().getNextPageToken()).thenReturn(nextToken);
        return resp;
    }

    @Test
    void listRacks_success_filtersAllowedStates_andMapsFields_andSerialPriority() {
        // Allowed states: DELIVERED, RECEIVED, AVAILABLE
        RackLocationMap r1 = mockRack("R1", "DELIVERED", "AS1", "FS1", "PF1"); // uses actual
        RackLocationMap r2 = mockRack("R2", "RECEIVED", "", "FS2", "PF2"); // uses fallback
        RackLocationMap r3 = mockRack("R3", "AVAILABLE", null, null, "PF3"); // serial null
        // Excluded states
        RackLocationMap r4 = mockRack("R4", "INSTALLED", "AS4", "FS4", "PF4");
        RackLocationMap r5 = mockRack("R5", null, "AS5", "FS5", "PF5");

        ListRackLocationsMapResponse resp =
                pageResponse(Arrays.asList(r1, r2, r3, r4, r5), null /* no pagination */);

        when(storeKeeper.listRackLocationsMap(any(ListRackLocationsMapRequest.class)))
                .thenReturn(resp);

        List<Rack> result = helper.listRacks(block, building, metricsScope);

        // Only 3 allowed states pass the filter
        assertEquals(3, result.size());

        // Helper to find rack by rackLocation (which maps from RackLocationMap.getRackNumber())
        Rack rr1 =
                result.stream()
                        .filter(r -> "R1".equals(r.getRackLocation()))
                        .findFirst()
                        .orElseThrow();
        Rack rr2 =
                result.stream()
                        .filter(r -> "R2".equals(r.getRackLocation()))
                        .findFirst()
                        .orElseThrow();
        Rack rr3 =
                result.stream()
                        .filter(r -> "R3".equals(r.getRackLocation()))
                        .findFirst()
                        .orElseThrow();

        // Validate mapping
        assertEquals(building, rr1.getBuilding());
        assertEquals(block, rr1.getBlock());
        assertEquals("R1", rr1.getRackLocation());
        assertEquals("AS1", rr1.getRackSerial()); // actual serial preferred
        assertEquals("DELIVERED", rr1.getRackState());
        assertEquals("PF1", rr1.getPlatformName());

        assertEquals("FS2", rr2.getRackSerial()); // fallback serial used when actual is empty
        assertEquals("RECEIVED", rr2.getRackState());
        assertEquals("PF2", rr2.getPlatformName());

        assertNull(rr3.getRackSerial()); // both actual and fallback are null -> null
        assertEquals("IN-SERVICE", rr3.getRackState());
        assertEquals("PF3", rr3.getPlatformName());

        // On success, failure metric should not be emitted
        verify(metricsScope, never()).emit(any(MetricNames.FetchRacks.class), anyDouble());
        verify(storeKeeper, times(1)).listRackLocationsMap(any(ListRackLocationsMapRequest.class));
    }

    @Test
    void listRacks_pagination_accumulatesAcrossPages_untilNullToken() {
        RackLocationMap p1r1 = mockRack("P1-R1", "DELIVERED", "S1", null, "PLAT1");
        RackLocationMap p2r1 = mockRack("P2-R1", "AVAILABLE", "S2", null, "PLAT2");

        ListRackLocationsMapResponse resp1 = pageResponse(List.of(p1r1), "token-2");
        ListRackLocationsMapResponse resp2 = pageResponse(List.of(p2r1), null);

        when(storeKeeper.listRackLocationsMap(any(ListRackLocationsMapRequest.class)))
                .thenReturn(resp1) // first page
                .thenReturn(resp2); // second page

        List<Rack> result = helper.listRacks(block, building, metricsScope);

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(r -> "P1-R1".equals(r.getRackLocation())));
        assertTrue(result.stream().anyMatch(r -> "P2-R1".equals(r.getRackLocation())));

        // Ensure we fetched both pages
        verify(storeKeeper, times(2)).listRackLocationsMap(any(ListRackLocationsMapRequest.class));
        // No failure metric on success
        verify(metricsScope, never()).emit(any(MetricNames.FetchRacks.class), anyDouble());
    }

    @Test
    void listRacks_nullResponse_emitsMetric_andThrowsRenderableException() {
        when(storeKeeper.listRackLocationsMap(any(ListRackLocationsMapRequest.class)))
                .thenReturn(null);

        RenderableException ex =
                assertThrows(
                        RenderableException.class,
                        () -> helper.listRacks(block, building, metricsScope));
        assertNotNull(ex);
        assertTrue(ex.getMessage().contains(building));
        assertTrue(ex.getMessage().contains(block));
        assertFalse(ex.getMessage().contains("buildingName={}"));
        assertFalse(ex.getMessage().contains("blockName={}"));

        verify(metricsScope, times(2)).emit(eq(MetricNames.FetchRacks.FetchRacksFailed), eq(1.0));
        verify(storeKeeper, times(1)).listRackLocationsMap(any(ListRackLocationsMapRequest.class));
    }

    @Test
    void listRacks_nullInner_emitsMetric_andThrows() {
        ListRackLocationsMapResponse resp =
                mock(ListRackLocationsMapResponse.class, Answers.RETURNS_DEEP_STUBS);
        // Null inner map
        when(resp.getListRackLocationsMap()).thenReturn(null);
        when(storeKeeper.listRackLocationsMap(any(ListRackLocationsMapRequest.class)))
                .thenReturn(resp);

        assertThrows(
                RenderableException.class, () -> helper.listRacks(block, building, metricsScope));
        verify(metricsScope, times(2)).emit(eq(MetricNames.FetchRacks.FetchRacksFailed), eq(1.0));
    }

    @Test
    void listRacks_nullRackLocations_emitsMetric_andThrows() {
        ListRackLocationsMapResponse resp =
                mock(ListRackLocationsMapResponse.class, Answers.RETURNS_DEEP_STUBS);
        when(resp.getListRackLocationsMap().getRackLocations()).thenReturn(null);
        when(storeKeeper.listRackLocationsMap(any(ListRackLocationsMapRequest.class)))
                .thenReturn(resp);

        assertThrows(
                RenderableException.class, () -> helper.listRacks(block, building, metricsScope));
        verify(metricsScope, times(2)).emit(eq(MetricNames.FetchRacks.FetchRacksFailed), eq(1.0));
    }

    @Test
    void listRacks_clientThrows_emitsMetric_andThrowsRenderableException() {
        when(storeKeeper.listRackLocationsMap(any(ListRackLocationsMapRequest.class)))
                .thenThrow(new RuntimeException("backend failure"));

        RenderableException ex =
                assertThrows(
                        RenderableException.class,
                        () -> helper.listRacks(block, building, metricsScope));
        assertNotNull(ex);
        assertTrue(ex.getMessage().contains(building));
        assertTrue(ex.getMessage().contains(block));
        assertFalse(ex.getMessage().contains("buildingName={}"));
        assertFalse(ex.getMessage().contains("blockName={}"));

        verify(metricsScope, times(1)).emit(eq(MetricNames.FetchRacks.FetchRacksFailed), eq(1.0));
        verify(storeKeeper, times(1)).listRackLocationsMap(any(ListRackLocationsMapRequest.class));
    }

    @Test
    void listRacks_filtersOutNonAllowedStates() {
        // Include many states, only 3 should pass
        RackLocationMap a = mockRack("A", "DELIVERED", "S", null, "P");
        RackLocationMap b = mockRack("B", "RECEIVED", "S", null, "P");
        RackLocationMap c = mockRack("C", "AVAILABLE", "S", null, "P");
        RackLocationMap d = mockRack("D", "IN_TRANSIT", "S", null, "P");
        RackLocationMap e = mockRack("E", "INSTALLED", "S", null, "P");
        RackLocationMap f = mockRack("F", null, "S", null, "P");

        List<RackLocationMap> input = new ArrayList<>(Arrays.asList(a, b, c, d, e, f));

        ListRackLocationsMapResponse resp = pageResponse(input, null);
        when(storeKeeper.listRackLocationsMap(any(ListRackLocationsMapRequest.class)))
                .thenReturn(resp);

        List<Rack> result = helper.listRacks(block, building, metricsScope);

        assertEquals(3, result.size());
        assertTrue(result.stream().anyMatch(r -> "A".equals(r.getRackLocation())));
        assertTrue(result.stream().anyMatch(r -> "B".equals(r.getRackLocation())));
        assertTrue(result.stream().anyMatch(r -> "C".equals(r.getRackLocation())));

        verify(metricsScope, never()).emit(any(MetricNames.FetchRacks.class), anyDouble());
    }

    @Test
    void listRacks_emptyRackLocations_returnsEmpty_andNoMetric() {
        ListRackLocationsMapResponse resp = pageResponse(new ArrayList<>(), null);
        when(storeKeeper.listRackLocationsMap(any(ListRackLocationsMapRequest.class)))
                .thenReturn(resp);

        List<Rack> result = helper.listRacks(block, building, metricsScope);

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(storeKeeper, times(1)).listRackLocationsMap(any(ListRackLocationsMapRequest.class));
        verify(metricsScope, never()).emit(any(MetricNames.FetchRacks.class), anyDouble());
    }

    @Test
    void listRacks_nullElementInRackLocations_emitsMetric_andThrows() {
        // Include a null element in rack locations to trigger NPE inside processing -> caught and
        // wrapped
        List<RackLocationMap> withNull = new ArrayList<>(Arrays.asList((RackLocationMap) null));
        ListRackLocationsMapResponse resp = pageResponse(withNull, null);
        when(storeKeeper.listRackLocationsMap(any(ListRackLocationsMapRequest.class)))
                .thenReturn(resp);

        assertThrows(
                RenderableException.class, () -> helper.listRacks(block, building, metricsScope));
        verify(metricsScope, times(1)).emit(eq(MetricNames.FetchRacks.FetchRacksFailed), eq(1.0));
    }
}
