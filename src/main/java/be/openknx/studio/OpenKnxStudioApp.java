package be.openknx.studio;

import be.openknx.studio.knx.KnxConnectionService;
import be.openknx.studio.knx.KnxDeviceInfoService;
import be.openknx.studio.knx.KnxDeviceScanService;
import be.openknx.studio.knx.KnxDiscoveryService;
import be.openknx.studio.knx.KnxGroupMonitorService;
import be.openknx.studio.knx.KnxInventoryService;
import be.openknx.studio.knxprod.KnxProdImportService;
import be.openknx.studio.knxprod.KnxProductCandidate;
import be.openknx.studio.knxprod.KnxProductMatcher;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class OpenKnxStudioApp extends Application {

    private final KnxDiscoveryService discoveryService = new KnxDiscoveryService();
    private final KnxConnectionService connectionService = new KnxConnectionService();
    private final KnxGroupMonitorService monitorService = new KnxGroupMonitorService();
    private final KnxDeviceScanService deviceScanService = new KnxDeviceScanService();
    private final KnxDeviceInfoService deviceInfoService = new KnxDeviceInfoService();
    private final KnxInventoryService inventoryService =
            new KnxInventoryService(deviceScanService, deviceInfoService);

    private final KnxProdImportService knxProdImportService = new KnxProdImportService();
    private final KnxProductMatcher productMatcher = new KnxProductMatcher();

    private final TextArea log = new TextArea();
    private final TextField routerIp = new TextField();
    private final TableView<KnxDeviceInfoService.BasicDeviceInfo> deviceTable = new TableView<>();

    private List<KnxProductCandidate> productCatalog = List.of();

    @Override
    public void start(Stage stage) {
        stage.setTitle("OpenKNX Studio");

        var title = new Label("OpenKNX Studio");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");

        var subtitle = new Label("Eenvoudige KNX-configuratie • v0.2");
        subtitle.setStyle("-fx-text-fill: #666666;");

        routerIp.setPromptText("bv. 192.168.1.50");
        routerIp.setPrefColumnCount(18);

        var areaField = new Spinner<Integer>(0, 15, 1);
        areaField.setEditable(true);
        areaField.setPrefWidth(75);

        var lineField = new Spinner<Integer>(0, 15, 1);
        lineField.setEditable(true);
        lineField.setPrefWidth(75);

        var discoverButton = new Button("Zoek KNX/IP");
        discoverButton.setDefaultButton(true);

        var connectButton = new Button("Test verbinding");
        var scanButton = new Button("Scan adressen");
        var inventoryButton = new Button("Scan + herken apparaten");

        var deviceAddressField = new TextField();
        deviceAddressField.setPromptText("bv. 1.1.15");
        deviceAddressField.setPrefColumnCount(10);
        var deviceInfoButton = new Button("Lees apparaatinfo");

        var importKnxProdButton = new Button("Importeer productdatabase");
        var clearCatalogButton = new Button("Wis productdatabase");
        clearCatalogButton.setDisable(true);
        var catalogStatus = new Label("Geen productdatabase geladen");

        var startMonitorButton = new Button("Start busmonitor");
        var stopMonitorButton = new Button("Stop busmonitor");
        stopMonitorButton.setDisable(true);

        var clearButton = new Button("Log wissen");
        var status = new Label("Klaar");

        configureDeviceTable(deviceAddressField);

        log.setEditable(false);
        log.setWrapText(false);
        log.setPrefRowCount(14);
        log.setStyle("-fx-font-family: 'Consolas';");

        log.setText("""
                Welkom bij OpenKNX Studio.

                • Zoek eerst je KNX/IP-router en test de verbinding.
                • 'Scan + herken apparaten' maakt automatisch een inventaris.
                • 'Importeer .knxprod' leest officiële KNX-productbestanden in.
                • OpenKNX Studio probeert daarna fabrikant, maskversie en System-7 hardware-ID te koppelen.

                Alle busfuncties in deze versie zijn read-only.
                """);

        discoverButton.setOnAction(event -> {
            setBusy(true, discoverButton, connectButton, scanButton, inventoryButton, deviceInfoButton, startMonitorButton);
            status.setText("KNX/IP-apparaten zoeken...");
            append("\n--- Discovery gestart ---");

            CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return discoveryService.discover();
                        }
                        catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .whenComplete((results, error) -> Platform.runLater(() -> {
                        setBusy(false, discoverButton, connectButton, scanButton, inventoryButton, deviceInfoButton, startMonitorButton);
                        startMonitorButton.setDisable(monitorService.isRunning());

                        if (error != null) {
                            status.setText("Zoeken mislukt");
                            append("Fout tijdens discovery: " + rootMessage(error));
                            return;
                        }

                        if (results.isEmpty()) {
                            status.setText("Geen KNX/IP-apparaten gevonden");
                            append("Geen KNX/IP-apparaten gevonden. Controleer netwerk en firewall.");
                            return;
                        }

                        status.setText(results.size() + " KNX/IP-apparaat/apparaten gevonden");
                        for (int i = 0; i < results.size(); i++) {
                            append("\nResultaat " + (i + 1) + ":\n" + results.get(i));
                        }
                    }));
        });

        connectButton.setOnAction(event -> {
            setBusy(true, discoverButton, connectButton, scanButton, inventoryButton, deviceInfoButton, startMonitorButton);
            status.setText("Tunnelingverbinding testen...");
            append("\n--- Verbindingstest naar " + routerIp.getText().trim() + " ---");

            CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return connectionService.testConnection(routerIp.getText());
                        }
                        catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .whenComplete((name, error) -> Platform.runLater(() -> {
                        setBusy(false, discoverButton, connectButton, scanButton, inventoryButton, deviceInfoButton, startMonitorButton);
                        startMonitorButton.setDisable(monitorService.isRunning());

                        if (error != null) {
                            status.setText("Verbinding mislukt");
                            append("Fout: " + rootMessage(error));
                            return;
                        }

                        status.setText("Verbonden");
                        append("OK — KNXnet/IP tunneling werkt. Link: " + name);
                    }));
        });

        scanButton.setOnAction(event -> {
            var area = areaField.getValue();
            var line = lineField.getValue();

            scanButton.setDisable(true);
            inventoryButton.setDisable(true);
            status.setText("KNX-lijn " + area + "." + line + " scannen...");
            append("\n--- Adressenscan " + area + "." + line + ".[0..255] ---");

            CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return deviceScanService.scanLine(routerIp.getText(), area, line);
                        }
                        catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .whenComplete((devices, error) -> Platform.runLater(() -> {
                        scanButton.setDisable(false);
                        inventoryButton.setDisable(false);

                        if (error != null) {
                            status.setText("Apparatenscan mislukt");
                            append("Fout tijdens scan: " + rootMessage(error));
                            return;
                        }

                        status.setText(devices.size() + " KNX-apparaat/apparaten gevonden op " + area + "." + line);

                        if (devices.isEmpty()) {
                            append("Geen reagerende KNX-apparaten gevonden.");
                            return;
                        }

                        append("Gevonden fysieke adressen:");
                        devices.forEach(address -> append("  • " + address));
                    }));
        });

        inventoryButton.setOnAction(event -> {
            var area = areaField.getValue();
            var line = lineField.getValue();

            setBusy(true, scanButton, inventoryButton, deviceInfoButton, startMonitorButton);
            deviceTable.getItems().clear();
            status.setText("Inventaris starten...");
            append("\n--- Automatische inventaris " + area + "." + line + " ---");

            CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return inventoryService.scanAndIdentify(
                                    routerIp.getText(),
                                    area,
                                    line,
                                    (currentDevice, totalDevices) -> Platform.runLater(() ->
                                            status.setText("Apparaat " + currentDevice + " van " + totalDevices + " uitlezen...")
                                    )
                            );
                        }
                        catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .whenComplete((devices, error) -> Platform.runLater(() -> {
                        setBusy(false, scanButton, inventoryButton, deviceInfoButton, startMonitorButton);
                        startMonitorButton.setDisable(monitorService.isRunning());

                        if (error != null) {
                            status.setText("Inventaris mislukt");
                            append("Fout tijdens inventaris: " + rootMessage(error));
                            return;
                        }

                        deviceTable.setItems(FXCollections.observableArrayList(devices));
                        deviceTable.refresh();
                        status.setText(devices.size() + " apparaten geïnventariseerd op " + area + "." + line);
                        append("Inventaris klaar: " + devices.size() + " apparaten.");
                    }));
        });

        deviceInfoButton.setOnAction(event -> {
            deviceInfoButton.setDisable(true);
            status.setText("Apparaatinformatie lezen...");
            append("\n--- Apparaatinfo " + deviceAddressField.getText().trim() + " ---");

            CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return deviceInfoService.read(routerIp.getText(), deviceAddressField.getText());
                        }
                        catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .whenComplete((info, error) -> Platform.runLater(() -> {
                        deviceInfoButton.setDisable(false);

                        if (error != null) {
                            status.setText("Apparaatinfo lezen mislukt");
                            append("Fout: " + rootMessage(error));
                            return;
                        }

                        status.setText("Apparaatinfo gelezen van " + info.address());
                        appendDeviceInfo(info);
                    }));
        });

        importKnxProdButton.setOnAction(event -> {
            var chooser = new FileChooser();
            chooser.setTitle("KNX productbestanden importeren");
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("KNX productbestanden (*.knxprod, *.zip)", "*.knxprod", "*.zip"),
                    new FileChooser.ExtensionFilter("KNXPROD", "*.knxprod"),
                    new FileChooser.ExtensionFilter("ZIP-bundels", "*.zip")
            );

            var files = chooser.showOpenMultipleDialog(stage);
            if (files == null || files.isEmpty()) {
                return;
            }

            importKnxProdButton.setDisable(true);
            catalogStatus.setText("Productbestanden inlezen...");
            status.setText(".knxprod importeren...");
            append("\n--- KNXPROD import ---");

            CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return knxProdImportService.importFiles(files);
                        }
                        catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .whenComplete((catalog, error) -> Platform.runLater(() -> {
                        importKnxProdButton.setDisable(false);

                        if (error != null) {
                            catalogStatus.setText("Import mislukt");
                            status.setText(".knxprod import mislukt");
                            append("Fout bij KNXPROD import: " + rootMessage(error));
                            return;
                        }

                        var merged = new java.util.LinkedHashSet<KnxProductCandidate>(productCatalog);
                        merged.addAll(catalog);
                        productCatalog = List.copyOf(merged);

                        var products = productCatalog.stream()
                                .map(KnxProductCandidate::displayName)
                                .distinct()
                                .count();

                        catalogStatus.setText(products + " product(en) / " + productCatalog.size() + " koppelingen geladen");
                        clearCatalogButton.setDisable(productCatalog.isEmpty());
                        status.setText("KNX-productdatabase geladen");
                        append("KNX-productdatabase klaar: " + products + " unieke producten, " + productCatalog.size() + " product/app-koppelingen totaal.");
                        deviceTable.refresh();
                    }));
        });

        clearCatalogButton.setOnAction(event -> {
            productCatalog = List.of();
            catalogStatus.setText("Geen productdatabase geladen");
            clearCatalogButton.setDisable(true);
            deviceTable.refresh();
            status.setText("Productdatabase gewist");
            append("Productdatabase uit OpenKNX Studio verwijderd.");
        });

        startMonitorButton.setOnAction(event -> {
            discoverButton.setDisable(true);
            connectButton.setDisable(true);
            startMonitorButton.setDisable(true);
            status.setText("Busmonitor starten...");
            append("\n--- Busmonitor ---");

            CompletableFuture
                    .runAsync(() -> {
                        try {
                            monitorService.start(
                                    routerIp.getText(),
                                    line -> Platform.runLater(() -> append(line))
                            );
                        }
                        catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .whenComplete((unused, error) -> Platform.runLater(() -> {
                        discoverButton.setDisable(false);
                        connectButton.setDisable(false);

                        if (error != null) {
                            startMonitorButton.setDisable(false);
                            stopMonitorButton.setDisable(true);
                            status.setText("Busmonitor kon niet starten");
                            append("Fout: " + rootMessage(error));
                            return;
                        }

                        startMonitorButton.setDisable(true);
                        stopMonitorButton.setDisable(false);
                        status.setText("Busmonitor actief");
                    }));
        });

        stopMonitorButton.setOnAction(event -> {
            monitorService.stop();
            startMonitorButton.setDisable(false);
            stopMonitorButton.setDisable(true);
            status.setText("Busmonitor gestopt");
            append("Busmonitor gestopt.");
        });

        clearButton.setOnAction(event -> log.clear());

        var ipRow = new HBox(10,
                new Label("Router IP:"),
                routerIp,
                connectButton
        );

        var scanRow = new HBox(10,
                new Label("KNX area:"),
                areaField,
                new Label("Lijn:"),
                lineField,
                scanButton,
                inventoryButton
        );

        var deviceInfoRow = new HBox(10,
                new Label("Fysiek adres:"),
                deviceAddressField,
                deviceInfoButton
        );

        var productRow = new HBox(10,
                importKnxProdButton,
                clearCatalogButton,
                catalogStatus
        );

        var actionRow = new HBox(10,
                discoverButton,
                startMonitorButton,
                stopMonitorButton,
                clearButton
        );

        var top = new VBox(6, title, subtitle);
        var controls = new VBox(12, ipRow, scanRow, deviceInfoRow, productRow, actionRow);

        var tabs = new TabPane();
        var devicesTab = new Tab("Apparaten", deviceTable);
        devicesTab.setClosable(false);
        var logTab = new Tab("Log", log);
        logTab.setClosable(false);
        tabs.getTabs().addAll(devicesTab, logTab);

        var statusBar = new HBox(8, new Label("Status:"), status);
        statusBar.setStyle("-fx-padding: 8 0 0 0;");

        var root = new VBox(16, top, new Separator(), controls, tabs, statusBar);
        root.setPadding(new Insets(20));
        VBox.setVgrow(tabs, Priority.ALWAYS);

        var scene = new Scene(root, 1320, 800);
        stage.setScene(scene);
        stage.setMinWidth(980);
        stage.setMinHeight(650);
        stage.setOnCloseRequest(event -> monitorService.stop());
        stage.show();
    }

    private void configureDeviceTable(TextField deviceAddressField) {
        var address = new TableColumn<KnxDeviceInfoService.BasicDeviceInfo, String>("Adres");
        address.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().address()));
        address.setPrefWidth(80);

        var manufacturer = new TableColumn<KnxDeviceInfoService.BasicDeviceInfo, String>("Fabrikant");
        manufacturer.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().manufacturerName()));
        manufacturer.setPrefWidth(150);

        var mask = new TableColumn<KnxDeviceInfoService.BasicDeviceInfo, String>("Mask");
        mask.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().deviceDescriptor()));
        mask.setPrefWidth(85);

        var system = new TableColumn<KnxDeviceInfoService.BasicDeviceInfo, String>("Systeem");
        system.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().systemType()));
        system.setPrefWidth(100);

        var serial = new TableColumn<KnxDeviceInfoService.BasicDeviceInfo, String>("Serienummer");
        serial.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().serialNumber()));
        serial.setPrefWidth(145);

        var hardware = new TableColumn<KnxDeviceInfoService.BasicDeviceInfo, String>("Hardware-ID");
        hardware.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().hardwareType()));
        hardware.setPrefWidth(175);

        var orderInfo = new TableColumn<KnxDeviceInfoService.BasicDeviceInfo, String>("Order info");
        orderInfo.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().orderInfo()));
        orderInfo.setPrefWidth(130);

        var product = new TableColumn<KnxDeviceInfoService.BasicDeviceInfo, String>("Productmatch");
        product.setCellValueFactory(data ->
                new SimpleStringProperty(productMatcher.summarize(data.getValue(), productCatalog)));
        product.setPrefWidth(260);

        var program = new TableColumn<KnxDeviceInfoService.BasicDeviceInfo, String>("Applicatie");
        program.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().programVersion()));
        program.setPrefWidth(170);

        deviceTable.getColumns().addAll(
                address, manufacturer, mask, system, serial, hardware, orderInfo, product, program
        );
        deviceTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        deviceTable.setPlaceholder(new Label("Nog geen inventaris gemaakt."));

        deviceTable.setRowFactory(table -> {
            var row = new TableRow<KnxDeviceInfoService.BasicDeviceInfo>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    deviceAddressField.setText(row.getItem().address());
                }
            });
            return row;
        });
    }

    private void appendDeviceInfo(KnxDeviceInfoService.BasicDeviceInfo info) {
        append("Fysiek adres:       " + info.address());
        append("Device descriptor:  " + info.deviceDescriptor());
        append("Systeemtype:         " + info.systemType());
        append("Manufacturer ID:    " + info.manufacturerId());
        append("Fabrikant:          " + info.manufacturerName());
        append("Serienummer:        " + info.serialNumber());
        append("Hardware-ID:        " + info.hardwareType());
        append("Order info:         " + info.orderInfo());
        append("Productmatch:       " + productMatcher.summarize(info, productCatalog));
        append("Program version:    " + info.programVersion());
        append("Programmeerstand:   " + info.programmingMode());
        append("Max. APDU-lengte:   " + info.maxApduLength());
    }

    private void append(String text) {
        if (!log.getText().isEmpty() && !log.getText().endsWith("\n")) {
            log.appendText("\n");
        }
        log.appendText(text + "\n");
        log.positionCaret(log.getLength());
    }

    private static void setBusy(boolean busy, Button... buttons) {
        for (var button : buttons) {
            button.setDisable(busy);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.toString();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
