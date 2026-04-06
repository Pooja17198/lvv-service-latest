package com.oracle.pic.networking.lvv.service.dependencies.storekeeper;

import com.google.inject.Inject;
import com.oracle.pic.commons.exceptions.server.ErrorCode;
import com.oracle.pic.commons.exceptions.server.RenderableException;
import com.oracle.pic.commons.metrics.MetricsScope;
import com.oracle.pic.networking.lvv.service.dependencies.metrics.MetricNames;
import com.oracle.pic.storekeeper.StoreKeeper;
import com.oracle.pic.storekeeper.model.RackLocationMap;
import com.oracle.pic.storekeeper.requests.ListRackLocationsMapRequest;
import com.oracle.pic.storekeeper.responses.ListRackLocationsMapResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ToString
public class StoreKeeperHelper {

    private final StoreKeeper storeKeeperClient;
    private static final Set<String> ALLOWED_RACK_STATES =
            Set.of("DELIVERED", "RECEIVED", "AVAILABLE");
    private static final int RACK_LIST_SIZE = 1000;
    private static final String EMPTY_RESPONSE_ERROR_TEMPLATE =
            "Empty response from StoreKeeper for list of racks in buildingName=%s blockName=%s";
    private static final String FETCH_FAILED_ERROR_TEMPLATE =
            "Fetching listRackLocationsMap from StoreKeeper failed for buildingName=%s blockName=%s";

    @Inject
    public StoreKeeperHelper(StoreKeeper storeKeeperClient) {
        this.storeKeeperClient = storeKeeperClient;
    }

    private String getRackSerial(RackLocationMap rack) {
        if (rack == null) {
            return null;
        }
        if (rack.getActualRackSerial() != null && !rack.getActualRackSerial().isEmpty()) {
            return rack.getActualRackSerial();
        }
        if (rack.getRackSerial() != null && !rack.getRackSerial().isEmpty()) {
            return rack.getRackSerial();
        }
        return null;
    }

    public String normalizedRackState(String rackState) {
        return "AVAILABLE".equals(rackState) ? "IN-SERVICE" : rackState;
    }

    public List<Rack> listRacks(String blockName, String buildingName, MetricsScope scope) {
        List<Rack> rackList = new ArrayList<>();

        String pageToken = null;

        log.info("Fetching racks in building {} block {}", buildingName, blockName);
        try {
            do {
                ListRackLocationsMapRequest listRacksRequest =
                        ListRackLocationsMapRequest.builder()
                                .buildingName(buildingName)
                                .numResults(RACK_LIST_SIZE)
                                .pageToken(pageToken)
                                .blockName(blockName)
                                .build();
                ListRackLocationsMapResponse rackResponse =
                        this.storeKeeperClient.listRackLocationsMap(listRacksRequest);
                if (rackResponse == null
                        || rackResponse.getListRackLocationsMap() == null
                        || rackResponse.getListRackLocationsMap().getRackLocations() == null) {
                    scope.emit(MetricNames.FetchRacks.FetchRacksFailed, 1.0);
                    throw new RenderableException(
                            ErrorCode.ExternalServerInvalidResponse,
                            formatStoreKeeperError(
                                    EMPTY_RESPONSE_ERROR_TEMPLATE, buildingName, blockName));
                }
                pageToken = rackResponse.getListRackLocationsMap().getNextPageToken();

                log.info("Page token {}", pageToken);
                for (RackLocationMap rl :
                        rackResponse.getListRackLocationsMap().getRackLocations()) {
                    log.info(
                            "{} {} state is {}",
                            rl.getRackNumber(),
                            getRackSerial(rl),
                            rl.getRackState());
                }

                rackList.addAll(
                        rackResponse.getListRackLocationsMap().getRackLocations().stream()
                                .filter(
                                        rackLocationMap ->
                                                rackLocationMap.getRackState() != null
                                                        && ALLOWED_RACK_STATES.contains(
                                                                rackLocationMap.getRackState()))
                                .map(
                                        rackLocationMap ->
                                                Rack.builder()
                                                        .building(buildingName)
                                                        .block(blockName)
                                                        .rackLocation(
                                                                rackLocationMap.getRackNumber())
                                                        .rackSerial(getRackSerial(rackLocationMap))
                                                        .rackState(
                                                                normalizedRackState(
                                                                        rackLocationMap
                                                                                .getRackState()))
                                                        .platformName(
                                                                rackLocationMap.getPlatformName())
                                                        .build())
                                .toList());

            } while (pageToken != null);

            log.info("Filtered Racks list {}", rackList);
        } catch (Exception e) {
            log.error(
                    "Fetching listRackLocationsMap from StoreKeeper failed for buildingName={} blockName={}",
                    buildingName,
                    blockName,
                    e);
            scope.emit(MetricNames.FetchRacks.FetchRacksFailed, 1.0);
            throw new RenderableException(
                    ErrorCode.ExternalServerInvalidResponse,
                    formatStoreKeeperError(FETCH_FAILED_ERROR_TEMPLATE, buildingName, blockName));
        }
        return rackList;
    }

    private String formatStoreKeeperError(String template, String buildingName, String blockName) {
        return String.format(
                template,
                safeForErrorMessage(buildingName),
                safeForErrorMessage(blockName));
    }

    private String safeForErrorMessage(String value) {
        if (value == null || value.isBlank()) {
            return "<unknown>";
        }
        return value;
    }
}
