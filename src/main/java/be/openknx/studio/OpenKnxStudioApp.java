package be.openknx.studio;

import be.openknx.studio.knx.KnxConnectionService;
import be.openknx.studio.knx.KnxDeviceInfoService;
import be.openknx.studio.knx.KnxDeviceScanService;
import be.openknx.studio.knx.KnxDiscoveryService;
import be.openknx.studio.knx.KnxGroupMonitorService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.util.concurrent.CompletableFuture;

public final class OpenKnxStudioApp extends Application {

    private final KnxDiscoveryService discoveryService = new KnxDiscoveryService();
    private final KnxConnectionService connectionService = new KnxConnectionService();
    private final KnxGroupMonitorService monitorService = new KnxGroupMonitorService();
    private final KnxDeviceScanService deviceScanService = new KnxDeviceScanService();
    private final KnxDeviceInfoService deviceInfoService = new KnxDeviceInfoService();

    private final TextArea log = new TextArea();
    private final TextField routerIp = new TextField();

    @Override
    public void start(Stage stage) {
        stage.setTitle("OpenKNX Studio");

        var title = new Label("OpenKNX Studio");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");

        var subtitle = new Label("Eenvoudige KNX-configuratie • v0.1");
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
        var scanButton = new Button("Scan apparaten");

        var deviceAddressField = new TextField();
        deviceAddressField.setPromptText("bv. 1.1.15");
        deviceAddressField.setPrefColumnCount(10);
        var deviceInfoButton = new Button("Lees apparaatinfo");

        var startMonitorButton = new Button("Start busmonitor");
        var stopMonitorButton = new Button("Stop busmonitor");
        stopMonitorButton.setDisable(true);

        var clearButton = new Button("Log wissen");
        var status = new Label("Klaar");

        log.setEditable(false);
        log.setWrapText(false);
        log.setPrefRowCount(20);
        log.setStyle("-fx-font-family: 'Consolas';");

        log.setText("""
                Welkom bij OpenKNX Studio.

                1. Klik op 'Zoek KNX/IP'.
                2. Vul het IP-adres van je Weinzierl KNX IP Router 751 in.
                3. Klik op 'Test verbinding'.
                4. Gebruik 'Start busmonitor' om groepstelegrammen te bekijken.
                5. Gebruik 'Scan apparaten' om fysieke KNX-adressen op een lijn te zoeken.

                De scan leest alleen welke adressen reageren. Er worden geen adressen of parameters gewijzigd.
                """);

        discoverButton.setOnAction(event -> {
            discoverButton.setDisable(true);
            connectButton.setDisable(true);
            scanButton.setDisable(true);
            startMonitorButton.setDisable(true);
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
                        discoverButton.setDisable(false);
                        connectButton.setDisable(false);
                        scanButton.setDisable(false);
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
            discoverButton.setDisable(true);
            connectButton.setDisable(true);
            scanButton.setDisable(true);
            startMonitorButton.setDisable(true);
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
                        discoverButton.setDisable(false);
                        connectButton.setDisable(false);
                        scanButton.setDisable(false);
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
            status.setText("KNX-lijn " + area + "." + line + " scannen...");
            append("\n--- Apparaten scan " + area + "." + line + ".[0..255] ---");

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
                        append("Fysiek adres:       " + info.address());
                        append("Device descriptor:  " + info.deviceDescriptor());
                        append("Systeemtype:         " + info.systemType());
                        append("Manufacturer ID:    " + info.manufacturerId());
                        append("Fabrikant:          " + info.manufacturerName());
                        append("Serienummer:        " + info.serialNumber());
                        append("Program version:    " + info.programVersion());
                        append("Programmeerstand:   " + info.programmingMode());
                        append("Max. APDU-lengte:   " + info.maxApduLength());
                    }));
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
                scanButton
        );

        var deviceInfoRow = new HBox(10,
                new Label("Fysiek adres:"),
                deviceAddressField,
                deviceInfoButton
        );

        var actionRow = new HBox(10,
                discoverButton,
                startMonitorButton,
                stopMonitorButton,
                clearButton
        );

        var top = new VBox(6, title, subtitle);
        var controls = new VBox(12, ipRow, scanRow, deviceInfoRow, actionRow);

        var statusBar = new HBox(8, new Label("Status:"), status);
        statusBar.setStyle("-fx-padding: 8 0 0 0;");

        var root = new VBox(16, top, new Separator(), controls, log, statusBar);
        root.setPadding(new Insets(20));
        VBox.setVgrow(log, Priority.ALWAYS);

        var scene = new Scene(root, 940, 700);
        stage.setScene(scene);
        stage.setMinWidth(760);
        stage.setMinHeight(560);
        stage.setOnCloseRequest(event -> monitorService.stop());
        stage.show();
    }

    private void append(String text) {
        if (!log.getText().isEmpty() && !log.getText().endsWith("\n")) {
            log.appendText("\n");
        }
        log.appendText(text + "\n");
        log.positionCaret(log.getLength());
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
