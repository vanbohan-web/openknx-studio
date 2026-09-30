package be.openknx.studio.knx;

import io.calimero.DetachEvent;
import io.calimero.KNXException;
import io.calimero.link.KNXNetworkLink;
import io.calimero.link.KNXNetworkLinkIP;
import io.calimero.link.medium.TPSettings;
import io.calimero.process.ProcessCommunicator;
import io.calimero.process.ProcessCommunicatorImpl;
import io.calimero.process.ProcessEvent;
import io.calimero.process.ProcessListener;

import java.net.InetSocketAddress;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Consumer;

public final class KnxGroupMonitorService implements AutoCloseable {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private KNXNetworkLink link;
    private ProcessCommunicator communicator;

    public synchronized void start(String host, Consumer<String> onEvent)
            throws KNXException, InterruptedException {

        Objects.requireNonNull(onEvent, "onEvent");

        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Vul eerst het IP-adres van de KNX/IP-router in.");
        }
        if (isRunning()) {
            throw new IllegalStateException("De busmonitor draait al.");
        }

        var local = new InetSocketAddress(0);
        var remote = new InetSocketAddress(host.trim(), 3671);

        KNXNetworkLink newLink = null;
        ProcessCommunicator newCommunicator = null;

        try {
            newLink = KNXNetworkLinkIP.newTunnelingLink(local, remote, false, new TPSettings());
            newCommunicator = new ProcessCommunicatorImpl(newLink);

            var listener = new ProcessListener() {
                @Override
                public void groupWrite(ProcessEvent event) {
                    onEvent.accept(format("WRITE", event));
                }

                @Override
                public void groupReadRequest(ProcessEvent event) {
                    onEvent.accept(format("READ?", event));
                }

                @Override
                public void groupReadResponse(ProcessEvent event) {
                    onEvent.accept(format("RESPONSE", event));
                }

                @Override
                public void detached(DetachEvent event) {
                    onEvent.accept(timestamp() + " monitor losgekoppeld");
                }
            };

            newCommunicator.addProcessListener(listener);

            link = newLink;
            communicator = newCommunicator;
            onEvent.accept(timestamp() + " busmonitor gestart via " + host.trim());
        }
        catch (KNXException | InterruptedException | RuntimeException e) {
            if (newCommunicator != null) {
                try {
                    newCommunicator.close();
                }
                catch (RuntimeException ignored) {
                }
            }
            if (newLink != null) {
                try {
                    newLink.close();
                }
                catch (RuntimeException ignored) {
                }
            }
            throw e;
        }
    }

    public synchronized boolean isRunning() {
        return link != null && link.isOpen();
    }

    public synchronized void stop() {
        var currentCommunicator = communicator;
        var currentLink = link;
        communicator = null;
        link = null;

        if (currentCommunicator != null) {
            try {
                currentCommunicator.close();
            }
            catch (RuntimeException ignored) {
            }
        }

        if (currentLink != null) {
            try {
                currentLink.close();
            }
            catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    private static String format(String service, ProcessEvent event) {
        return timestamp()
                + " "
                + event.getSourceAddr()
                + " -> "
                + event.getDestination()
                + "  "
                + service
                + "  ASDU="
                + HexFormat.of().formatHex(event.getASDU());
    }

    private static String timestamp() {
        return LocalTime.now().format(TIME);
    }
}
