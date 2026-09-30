package be.openknx.studio.knx;

import io.calimero.KNXException;
import io.calimero.knxnetip.KNXnetIPConnection;
import io.calimero.link.KNXNetworkLinkIP;
import io.calimero.link.medium.TPSettings;

import java.net.InetSocketAddress;

public final class KnxConnectionService {

    public String testConnection(String host) throws KNXException, InterruptedException {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Vul eerst het IP-adres van de KNX/IP-router in.");
        }

        var local = new InetSocketAddress(0);
        var server = new InetSocketAddress(host.trim(), KNXnetIPConnection.DEFAULT_PORT);

        try (var link = KNXNetworkLinkIP.newTunnelingLink(
                local,
                server,
                false,
                new TPSettings())) {
            return link.getName();
        }
    }
}
