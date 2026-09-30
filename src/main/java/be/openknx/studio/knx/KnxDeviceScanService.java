package be.openknx.studio.knx;

import io.calimero.IndividualAddress;
import io.calimero.KNXException;
import io.calimero.link.KNXNetworkLinkIP;
import io.calimero.link.medium.TPSettings;
import io.calimero.mgmt.ManagementProcedures;
import io.calimero.mgmt.ManagementProceduresImpl;

import java.net.InetSocketAddress;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class KnxDeviceScanService {

    public List<String> scanLine(String host, int area, int line)
            throws KNXException, InterruptedException {

        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Vul eerst het IP-adres van de KNX/IP-router in.");
        }
        if (area < 0 || area > 15 || line < 0 || line > 15) {
            throw new IllegalArgumentException("Area en lijn moeten tussen 0 en 15 liggen.");
        }

        var local = new InetSocketAddress(0);
        var remote = new InetSocketAddress(host.trim(), 3671);

        Set<IndividualAddress> found = new TreeSet<>(
                Comparator.comparingInt(IndividualAddress::getRawAddress)
        );

        try (var link = KNXNetworkLinkIP.newTunnelingLink(local, remote, false, new TPSettings());
             ManagementProcedures management = new ManagementProceduresImpl(link)) {

            management.scanNetworkDevices(
                    area,
                    line,
                    found::add,
                    (address, descriptor) -> found.add(address)
            );
        }

        return found.stream()
                .map(IndividualAddress::toString)
                .toList();
    }
}
