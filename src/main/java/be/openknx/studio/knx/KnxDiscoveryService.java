package be.openknx.studio.knx;

import io.calimero.knxnetip.Discoverer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;

public final class KnxDiscoveryService {

    public List<String> discover() throws InterruptedException, ExecutionException {
        return Discoverer.udp(false)
                .timeout(Duration.ofSeconds(4))
                .search()
                .get()
                .stream()
                .map(result -> result.toString().replace(", ", System.lineSeparator() + "  "))
                .toList();
    }
}
