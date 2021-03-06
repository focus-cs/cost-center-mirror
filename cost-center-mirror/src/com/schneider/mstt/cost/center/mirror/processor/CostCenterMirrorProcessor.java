package com.schneider.mstt.cost.center.mirror.processor;

import com.schneider.mstt.cost.center.mirror.enums.Action;
import com.schneider.mstt.cost.center.mirror.enums.ReturnCode;
import com.schneider.mstt.cost.center.mirror.exceptions.FileException;
import com.schneider.mstt.cost.center.mirror.file.CsvParser;
import com.schneider.mstt.cost.center.mirror.file.LogManager;
import com.schneider.mstt.cost.center.mirror.model.CostCenter;
import com.schneider.mstt.cost.center.mirror.service.SciformaService;
import com.sciforma.psnext.api.DataViewRow;
import com.sciforma.psnext.api.PSException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.util.StopWatch;

@Configuration
@PropertySource("${propertySource}")
public class CostCenterMirrorProcessor {

    private static final Logger LOG = Logger.getLogger(CostCenterMirrorProcessor.class);
    private static SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");

    @Value("${csv.requests.directory}")
    private String requestDirectory;
    @Value("${csv.results.directory}")
    private String resultsDirectory;
    @Value("${csv.logs.directory}")
    private String logsDirectory;

    @Autowired
    private CsvParser csvParser;
    @Autowired
    private SciformaService sciformaService;

    public ReturnCode process() {

        if (!validateProperties()) {
            LOG.error("Properties are not valid");
            return ReturnCode.MISSING_PROPERTIES;
        }

        File requests = new File(requestDirectory);
        if (!requests.exists()) {
            LOG.error("Missing request folder");
            return ReturnCode.MISSING_REQUEST_FOLDER;
        }

        File results = new File(resultsDirectory);
        if (!results.exists()) {
            results.mkdirs();
        }

        File logs = new File(logsDirectory);
        if (!logs.exists()) {
            logs.mkdirs();
        }
        
        StopWatch stopWatch = new StopWatch("cost-center-mirror");

        try (Stream<Path> walk = Files.walk(Paths.get(requestDirectory))) {

            List<Path> paths = walk
                    .filter(path -> path.toString().endsWith(".csv") && !path.toString().endsWith("_OK.csv") && !path.toString().endsWith("_KO.csv"))
                    .collect(Collectors.toList());

            if (paths.isEmpty()) {
                LOG.info("No CSV request file to be processed");
                return ReturnCode.SUCCESS;
            }

            LOG.info("Found " + paths.size() + " file(s) to process");

            stopWatch.start("Logging to Sciforma");
            if (sciformaService.createConnection()) {

                stopWatch.stop();

                stopWatch.start("Locking Global");
                if (sciformaService.openGlobal()) {

                    stopWatch.stop();

                    stopWatch.start("Retrieving dataview");
                    List<DataViewRow> costCenterMirrorManagementDataView = sciformaService.getDataView();
                    stopWatch.stop();

                    if (costCenterMirrorManagementDataView != null && !costCenterMirrorManagementDataView.isEmpty()) {

                        stopWatch.start("Updating/inserting " + paths.size() + " row(s)");
                        for (Path path : paths) {

                            LogManager logManager = LogManager.builder()
                                    .directory(logsDirectory)
                                    .filename(getFileNameWithoutExtension(path.toFile()))
                                    .build();

                            boolean status = false;

                            try {
                                LOG.info("Processing file " + path.toString());
                                List<CostCenter> costCenters = csvParser.parse(path);
                                LOG.info("Found " + costCenters.size() + " line(s)");

                                for (CostCenter costCenter : costCenters) {

                                    logManager.info("Action : " + costCenter.getAction());

                                    logManager.info("Looking for data row with key " + costCenter.getInternalCostCenterId() + " - " + costCenter.getSecondMirrorGccRe() + " - " + costCenter.getSecondMirrorGccId());
                                    Optional<DataViewRow> existingRow = findRow(costCenterMirrorManagementDataView, costCenter);

                                    if (costCenter.getAction().equals(Action.CREATION)) {

                                        if (existingRow.isPresent()) {
                                            logManager.error("Line rejected : cost center already exists");
                                            logManager.error("Error code : " + ReturnCode.FAILED_TO_CREATE_LINE.ordinal());
                                        } else {

                                            try {
                                                logManager.info("Inserting row");
                                                createRow(sciformaService.createDataViewRow(), costCenter);
                                                logManager.info("Row inserted");
                                                status = true;
                                            } catch (PSException ex) {
                                                LOG.error("Failed to create cost center", ex);
                                                logManager.error("Failed to create cost center : " + ex.getMessage());
                                            }

                                        }
                                    }

                                    if (costCenter.getAction().equals(Action.UPDATE)) {

                                        if (existingRow.isPresent()) {

                                            DataViewRow rowToUpdate = existingRow.get();
                                            try {
                                                logManager.info("Updating row");
                                                updateRow(rowToUpdate, costCenter);
                                                logManager.info("Row updated");
                                                status = true;
                                            } catch (PSException ex) {
                                                LOG.error("Failed to update cost center", ex);
                                                logManager.error("Failed to update cost center : " + ex.getMessage());
                                            }

                                        } else {
                                            logManager.error("Line rejected : cost center doesn't exists");
                                            logManager.error("Error code : " + ReturnCode.FAILED_TO_CREATE_LINE.ordinal());
                                        }

                                    }

                                }

                            } catch (FileException ex) {
                                logManager.error(ex.getMessage());
                                logManager.error("Failed to process file, the process will exit with code " + ReturnCode.FILE_ERROR.ordinal());
                            } finally {
                                moveFile(path, status);
                                logManager.saveLogFile(status);
                            }

                            LOG.info("File processed");

                        }
                        
                        stopWatch.stop();
                        
                    } else {
                        return ReturnCode.FAILED_TO_ACCESS_TABLE;
                    }

                } else {

                    if (stopWatch.isRunning()) {
                        stopWatch.stop();
                    }

                    paths.forEach((path) -> {
                        LogManager logManager = LogManager.builder()
                                .directory(logsDirectory)
                                .filename(getFileNameWithoutExtension(path.toFile()))
                                .build();

                        logManager.error("Failed to lock table");
                        logManager.error("Error code : " + ReturnCode.FAILED_TO_LOCK_TABLE.ordinal());

                        moveFile(path, false);
                        logManager.saveLogFile(false);
                    });

                    return ReturnCode.FAILED_TO_LOCK_TABLE;
                }

            } else {
                if (stopWatch.isRunning()) {
                    stopWatch.stop();
                }
                return ReturnCode.MSTT_ACCESS;
            }

        } catch (IOException ex) {
            LOG.error(ex);
        } finally {
            stopWatch.start("Save and close Global");
            sciformaService.saveAndCloseGlobal();
            stopWatch.stop();
            stopWatch.start("Logout from Sciforma");
            sciformaService.closeConnection();
            stopWatch.stop();
        }
        
        LOG.info(stopWatch.prettyPrint());

        return ReturnCode.SUCCESS;

    }

    private Optional<DataViewRow> findRow(List<DataViewRow> dataViewRows, CostCenter costCenter) {

        for (DataViewRow dataViewRow : dataViewRows) {

            try {

                if (dataViewRow.getStringField("Internal cost center ID").equals(costCenter.getInternalCostCenterId())
                        && dataViewRow.getStringField("Secondary mirror global cost center ID").equals(costCenter.getSecondMirrorGccId())
                        && dataViewRow.getStringField("Secondary mirror global cost center RE").equals(costCenter.getSecondMirrorGccRe())) {

                    return Optional.of(dataViewRow);

                }

            } catch (PSException ex) {
                LOG.error("Failed to read data view row", ex);
            }
        }

        return Optional.empty();
    }

    private boolean createRow(DataViewRow rowToUpdate, CostCenter costCenter) throws PSException {
        rowToUpdate.setStringField("Internal cost center ID", costCenter.getInternalCostCenterId());
        rowToUpdate.setStringField("Secondary mirror global cost center ID", costCenter.getSecondMirrorGccId());
        rowToUpdate.setStringField("Secondary mirror global cost center RE", costCenter.getSecondMirrorGccRe());
        rowToUpdate.setStringField("Last update by", costCenter.getLastUpdateBy());
        rowToUpdate.setDateField("Last update date", costCenter.getLastUpdate());
        rowToUpdate.setStringField("Status", costCenter.getStatus().toString().toLowerCase());
        return true;
    }

    private boolean updateRow(DataViewRow rowToUpdate, CostCenter costCenter) throws PSException {
        rowToUpdate.setStringField("Last update by", costCenter.getLastUpdateBy());
        rowToUpdate.setDateField("Last update date", costCenter.getLastUpdate());
        rowToUpdate.setStringField("Status", costCenter.getStatus().toString().toLowerCase());
        return true;
    }

    private boolean validateProperties() {
        LOG.info("Validate properties");
        if (requestDirectory == null || requestDirectory.isEmpty()) {
            LOG.error("Missing property csv.requests.directory");
            return false;
        }
        if (resultsDirectory == null || resultsDirectory.isEmpty()) {
            LOG.error("Missing property csv.results.directory");
            return false;
        }
        if (logsDirectory == null || logsDirectory.isEmpty()) {
            LOG.error("Missing property csv.logs.directory");
            return false;
        }
        LOG.info("Properties are valid");
        return true;
    }

    private Path moveFile(Path path, boolean status) {

        String filename = new StringBuilder()
                .append(getFileNameWithoutExtension(path.toFile()))
                .append("_")
                .append(sdf.format(new Date()))
                .append("_")
                .append(status ? "OK" : "KO")
                .append(".csv")
                .toString();

        try {
            Files.move(path, Paths.get(resultsDirectory + File.separator + filename), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            LOG.error(ex);
        }

        return null;
    }

    private static String getFileNameWithoutExtension(File file) {
        String fileName = "";

        try {
            if (file != null && file.exists()) {
                String name = file.getName();
                fileName = name.replaceFirst("[.][^.]+$", "");
            }
        } catch (Exception e) {
            LOG.error(e);
        }

        return fileName;

    }

}
