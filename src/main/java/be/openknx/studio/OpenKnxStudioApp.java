package be.openknx.studio;

import be.openknx.studio.knx.KnxConnectionService;
import be.openknx.studio.knx.KnxDiscoveryService;
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

        var discoverButton = new Button("Zoek KNX/IP");
        discoverButton.setDefaultButton(true);

        var connectButton = new Button("Test verbinding");

        var clearButton = new Button("Log wissen");

        var status = new Label("Klaar");

        log.setEditable(false);
        log.setWrapText(true);
        log.setPrefRowCount(20);
        log.setText("""
                Welkom bij OpenKNX Studio.

                1. Klik op 'Zoek KNX/IP'.
                2. Zoek in de resultaten naar je Weinzierl KNX IP Router 751.
                3. Vul het IP-adres in.
                4. Klik op 'Test verbinding'.

                Deze versie schrijft nog niets naar de KNX-bus.
                """);

        discoverButton.setOnAction(event -> {
            discoverButton.setDisable(true);
            connectButton.setDisable(true);
            status.setText("KNX/IP-apparaten zoeken...");
            append("\n--- Discovery gestart ---");

            CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return discoveryService.discover();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .whenComplete((results, error) -> Platform.runLater(() -> {
                        discoverButton.setDisable(false);
                        connectButton.setDisable(false);

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
            status.setText("Tunnelingverbinding testen...");
            append("\n--- Verbindingstest naar " + routerIp.getText().trim() + " ---");

            CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return connectionService.testConnection(routerIp.getText());
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .whenComplete((name, error) -> Platform.runLater(() -> {
                        discoverButton.setDisable(false);
                        connectButton.setDisable(false);

                        if (error != null) {
                            status.setText("Verbinding mislukt");
                            append("Fout: " + rootMessage(error));
                            return;
                        }

                        status.setText("Verbonden");
                        append("OK — KNXnet/IP tunneling werkt. Link: " + name);
                    }));
        });

        clearButton.setOnAction(event -> log.clear());

        var ipRow = new HBox(10,
                new Label("Router IP:"),
                routerIp,
                connectButton
        );
        ipRow.setFillHeight(true);

        var actionRow = new HBox(10, discoverButton, clearButton);

        var top = new VBox(6, title, subtitle);
        var controls = new VBox(12, ipRow, actionRow);

        var statusBar = new HBox(8, new Label("Status:"), status);
        statusBar.setStyle("-fx-padding: 8 0 0 0;");

        var root = new VBox(16, top, new Separator(), controls, log, statusBar);
        root.setPadding(new Insets(20));
        VBox.setVgrow(log, Priority.ALWAYS);

        var scene = new Scene(root, 820, 620);
        stage.setScene(scene);
        stage.setMinWidth(680);
        stage.setMinHeight(480);
        stage.show();
    }

    private void append(String text) {
        if (!log.getText().isEmpty() && !log.getText().endsWith("\n")) {
            log.appendText("\n");
        }
        log.appendText(text + "\n");
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
