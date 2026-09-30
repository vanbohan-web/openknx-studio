package be.openknx.studio.knx;

import io.calimero.IndividualAddress;
import io.calimero.KNXException;
import io.calimero.link.KNXNetworkLinkIP;
import io.calimero.link.medium.TPSettings;
import io.calimero.mgmt.PropertyAccess;
import io.calimero.mgmt.PropertyClient;
import io.calimero.mgmt.RemotePropertyServiceAdapter;

import java.net.InetSocketAddress;
import java.util.HexFormat;

public final class KnxDeviceInfoService {

    public record BasicDeviceInfo(
            String address,
            String deviceDescriptor,
            String manufacturerId,
            String serialNumber,
            String programVersion,
            String programmingMode,
            String maxApduLength
    ) {}

    public BasicDeviceInfo read(String host, String individualAddress)
            throws KNXException, InterruptedException {

        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Vul eerst het IP-adres van de KNX/IP-router in.");
        }
        if (individualAddress == null || individualAddress.isBlank()) {
            throw new IllegalArgumentException("Vul een KNX fysiek adres in, bijvoorbeeld 1.1.15.");
        }

        var device = new IndividualAddress(individualAddress.trim());
        var local = new InetSocketAddress(0);
        var remote = new InetSocketAddress(host.trim(), 3671);

        try (var link = KNXNetworkLinkIP.newTunnelingLink(local, remote, false, new TPSettings());
             var adapter = new RemotePropertyServiceAdapter(link, device, event -> {}, true)) {

            var management = adapter.managementClient();
            var destination = adapter.destination();
            var properties = new PropertyClient(adapter);

            String descriptor = "niet beschikbaar";
            try {
                descriptor = "0x" + HexFormat.of().withUpperCase()
                        .formatHex(management.readDeviceDesc(destination, 0));
            }
            catch (KNXException ignored) {
            }

            var manufacturer = readProperty(properties, PropertyAccess.PID.MANUFACTURER_ID);
            var serial = readProperty(properties, PropertyAccess.PID.SERIAL_NUMBER);
            var program = readProperty(properties, PropertyAccess.PID.PROGRAM_VERSION);
            var progMode = readProperty(properties, PropertyAccess.PID.PROGMODE);
            var maxApdu = readProperty(properties, PropertyAccess.PID.MAX_APDULENGTH);

            return new BasicDeviceInfo(
                    device.toString(),
                    descriptor,
                    formatManufacturer(manufacturer),
                    formatHex(serial),
                    formatProgramVersion(program),
                    formatProgrammingMode(progMode),
                    formatUnsigned(maxApdu)
            );
        }
    }

    private static byte[] readProperty(PropertyClient client, int pid) throws InterruptedException {
        try {
            return client.getProperty(0, pid, 1, 1);
        }
        catch (KNXException | RuntimeException e) {
            return null;
        }
    }

    private static String formatManufacturer(byte[] data) {
        if (data == null || data.length == 0) {
            return "niet beschikbaar";
        }
        long value = unsigned(data);
        return value + " (0x" + Long.toHexString(value).toUpperCase() + ")";
    }

    private static String formatHex(byte[] data) {
        return data == null || data.length == 0
                ? "niet beschikbaar"
                : HexFormat.of().withUpperCase().formatHex(data);
    }

    private static String formatProgramVersion(byte[] data) {
        if (data == null || data.length == 0) {
            return "niet beschikbaar";
        }
        if (data.length == 5) {
            int manufacturer = ((data[0] & 0xff) << 8) | (data[1] & 0xff);
            int application = ((data[2] & 0xff) << 8) | (data[3] & 0xff);
            int major = (data[4] & 0xff) >> 4;
            int minor = data[4] & 0x0f;
            return "fabrikant " + manufacturer
                    + ", applicatie 0x" + String.format("%04X", application)
                    + ", v" + major + "." + minor;
        }
        return formatHex(data);
    }

    private static String formatProgrammingMode(byte[] data) {
        if (data == null || data.length == 0) {
            return "niet beschikbaar";
        }
        return (data[0] & 0x01) != 0 ? "AAN" : "uit";
    }

    private static String formatUnsigned(byte[] data) {
        return data == null || data.length == 0
                ? "niet beschikbaar"
                : Long.toString(unsigned(data));
    }

    private static long unsigned(byte[] data) {
        long value = 0;
        for (byte b : data) {
            value = (value << 8) | (b & 0xff);
        }
        return value;
    }
}
