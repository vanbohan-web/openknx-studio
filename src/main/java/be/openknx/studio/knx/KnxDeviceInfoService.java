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

    private static final int APPLICATION_PROGRAM_OBJECT_TYPE = 3;

    public record BasicDeviceInfo(
            String address,
            String deviceDescriptor,
            String systemType,
            String manufacturerId,
            String manufacturerName,
            String serialNumber,
            String hardwareType,
            String orderInfo,
            String programVersion,
            int programManufacturerId,
            int programApplicationNumber,
            int programApplicationVersion,
            String programmingMode,
            String maxApduLength
    ) {}

    private record ProgramIdentity(
            byte[] raw,
            int manufacturerId,
            int applicationNumber,
            int applicationVersion
    ) {
        static ProgramIdentity unavailable() {
            return new ProgramIdentity(null, -1, -1, -1);
        }
    }

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

            var manufacturer = readProperty(properties, 0, PropertyAccess.PID.MANUFACTURER_ID);
            var serial = readProperty(properties, 0, PropertyAccess.PID.SERIAL_NUMBER);
            var hardwareType = readProperty(properties, 0, 78);
            var orderInfo = readProperty(properties, 0, PropertyAccess.PID.ORDER_INFO);
            var program = readProgramIdentity(properties);
            var progMode = readProperty(properties, 0, PropertyAccess.PID.PROGMODE);
            var maxApdu = readProperty(properties, 0, PropertyAccess.PID.MAX_APDULENGTH);

            return new BasicDeviceInfo(
                    device.toString(),
                    descriptor,
                    systemType(descriptor),
                    formatManufacturer(manufacturer),
                    manufacturerName(manufacturer),
                    formatHex(serial),
                    formatHex(hardwareType),
                    formatTextOrHex(orderInfo),
                    formatProgramVersion(program.raw()),
                    program.manufacturerId(),
                    program.applicationNumber(),
                    program.applicationVersion(),
                    formatProgrammingMode(progMode),
                    formatUnsigned(maxApdu)
            );
        }
    }

    private static ProgramIdentity readProgramIdentity(PropertyClient client) throws InterruptedException {
        byte[] data = readApplicationProgramObject(client);

        if (data == null || data.length == 0) {
            // Older masks sometimes expose it directly on object 0.
            data = readProperty(client, 0, PropertyAccess.PID.PROGRAM_VERSION);
        }

        if (data == null || data.length != 5) {
            return ProgramIdentity.unavailable();
        }

        int manufacturer = ((data[0] & 0xff) << 8) | (data[1] & 0xff);
        int application = ((data[2] & 0xff) << 8) | (data[3] & 0xff);
        int version = data[4] & 0xff;

        return new ProgramIdentity(data, manufacturer, application, version);
    }

    private static byte[] readApplicationProgramObject(PropertyClient client) throws InterruptedException {
        // Preferred path: use the Device Object's interface-object list.
        try {
            var countData = client.getProperty(0, PropertyAccess.PID.IO_LIST, 0, 1);
            int objectCount = (int) unsigned(countData);

            if (objectCount > 0 && objectCount <= 100) {
                var ioList = client.getProperty(0, PropertyAccess.PID.IO_LIST, 1, objectCount);

                if (ioList != null && ioList.length >= objectCount * 2) {
                    for (int objectIndex = 0; objectIndex < objectCount; objectIndex++) {
                        int offset = objectIndex * 2;
                        int objectType = ((ioList[offset] & 0xff) << 8) | (ioList[offset + 1] & 0xff);

                        if (objectType == APPLICATION_PROGRAM_OBJECT_TYPE) {
                            var program = readProperty(
                                    client,
                                    objectIndex,
                                    PropertyAccess.PID.PROGRAM_VERSION
                            );
                            if (program != null && program.length > 0) {
                                return program;
                            }
                        }
                    }
                }
            }
        }
        catch (KNXException | RuntimeException ignored) {
            // Older System-7 devices do not always expose IO_LIST in a way
            // that a generic property client can enumerate. Fall through.
        }

        // Calimero DeviceInfo uses the same fallback idea: enumerate object
        // indices and read PID_OBJECT_TYPE until the object server stops
        // answering. This matters for older JUNG 0705 devices.
        int consecutiveMisses = 0;
        for (int objectIndex = 0; objectIndex < 32 && consecutiveMisses < 4; objectIndex++) {
            var typeData = readProperty(client, objectIndex, PropertyAccess.PID.OBJECT_TYPE);

            if (typeData == null || typeData.length == 0) {
                consecutiveMisses++;
                continue;
            }

            consecutiveMisses = 0;
            int objectType = (int) unsigned(typeData);

            if (objectType == APPLICATION_PROGRAM_OBJECT_TYPE) {
                var program = readProperty(
                        client,
                        objectIndex,
                        PropertyAccess.PID.PROGRAM_VERSION
                );
                if (program != null && program.length > 0) {
                    return program;
                }
            }
        }

        return null;
    }

    private static byte[] readProperty(PropertyClient client, int objectIndex, int pid)
            throws InterruptedException {
        try {
            return client.getProperty(objectIndex, pid, 1, 1);
        }
        catch (KNXException | RuntimeException e) {
            return null;
        }
    }

    private static String systemType(String descriptor) {
        return switch (descriptor) {
            case "0x0700", "0x0701", "0x0705" -> "System 7";
            case "0x0010", "0x0011", "0x0012" -> "System 1";
            case "0x0020", "0x0021", "0x0025" -> "System 2";
            case "0x07B0", "0x17B0", "0x27B0", "0x57B0" -> "System B";
            default -> "onbekend / niet herkend";
        };
    }

    private static String manufacturerName(byte[] data) {
        if (data == null || data.length == 0) {
            return "niet beschikbaar";
        }
        return switch ((int) unsigned(data)) {
            case 1 -> "Siemens";
            case 2 -> "ABB";
            case 4 -> "Albrecht Jung";
            case 5 -> "BTicino";
            case 6 -> "Berker";
            case 7 -> "Busch-Jaeger Elektro";
            case 11 -> "Legrand";
            case 12 -> "Merten";
            case 61 -> "WAGO";
            case 72 -> "Theben";
            case 113 -> "Zennio";
            case 128 -> "ESYLUX";
            case 131 -> "MDT technologies";
            default -> "onbekend (ID " + unsigned(data) + ")";
        };
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

    private static String formatTextOrHex(byte[] data) {
        if (data == null || data.length == 0) {
            return "niet beschikbaar";
        }

        boolean allFF = true;
        for (byte b : data) {
            if ((b & 0xff) != 0xff) {
                allFF = false;
                break;
            }
        }
        if (allFF) {
            return "niet ondersteund";
        }

        int printableEnd = 0;
        while (printableEnd < data.length) {
            int ch = data[printableEnd] & 0xff;
            if (ch < 32 || ch > 126) {
                break;
            }
            printableEnd++;
        }

        if (printableEnd >= 4) {
            return new String(data, 0, printableEnd, java.nio.charset.StandardCharsets.US_ASCII);
        }

        return formatHex(data);
    }

    private static String formatProgramVersion(byte[] data) {
        if (data == null || data.length == 0) {
            return "niet beschikbaar";
        }
        if (data.length == 5) {
            int manufacturer = ((data[0] & 0xff) << 8) | (data[1] & 0xff);
            int application = ((data[2] & 0xff) << 8) | (data[3] & 0xff);
            int version = data[4] & 0xff;
            int major = version >> 4;
            int minor = version & 0x0f;
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
        if (data == null) {
            return 0;
        }

        long value = 0;
        for (byte b : data) {
            value = (value << 8) | (b & 0xff);
        }
        return value;
    }
}
