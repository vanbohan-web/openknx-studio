package be.openknx.studio.knx;

import be.openknx.studio.knxprod.KnxMemorySample;
import be.openknx.studio.knxprod.KnxProductCandidate;
import io.calimero.IndividualAddress;
import io.calimero.KNXException;
import io.calimero.link.KNXNetworkLinkIP;
import io.calimero.link.medium.TPSettings;
import io.calimero.mgmt.Destination;
import io.calimero.mgmt.ManagementClient;
import io.calimero.mgmt.RemotePropertyServiceAdapter;

import java.net.InetSocketAddress;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class KnxDeepRecognitionService {

    public record Result(
            int startingCandidates,
            List<KnxProductCandidate> verifiedMatches,
            List<KnxProductCandidate> inconclusiveMatches,
            int memoryReads,
            int comparedSamples,
            List<String> diagnostics
    ) {
        public List<KnxProductCandidate> remainingCandidates() {
            var out = new ArrayList<KnxProductCandidate>();
            out.addAll(verifiedMatches);
            out.addAll(inconclusiveMatches);
            return List.copyOf(out);
        }

        public boolean conclusive() {
            return !verifiedMatches.isEmpty() && inconclusiveMatches.isEmpty();
        }
    }

    private record ReadKey(int address, int length) {}
    private record MemoryRead(byte[] bytes, boolean complete) {}

    public Result recognize(
            String host,
            KnxDeviceInfoService.BasicDeviceInfo device,
            List<KnxProductCandidate> candidates
    ) throws KNXException, InterruptedException {

        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Vul eerst het IP-adres van de KNX/IP-router in.");
        }
        if (device == null) {
            throw new IllegalArgumentException("Selecteer eerst een KNX-apparaat.");
        }
        if (candidates == null || candidates.isEmpty()) {
            return new Result(0, List.of(), List.of(), 0, 0, List.of("Geen kandidaten om diep te herkennen."));
        }

        var address = new IndividualAddress(device.address());
        var local = new InetSocketAddress(0);
        var remote = new InetSocketAddress(host.trim(), 3671);

        var verified = new ArrayList<KnxProductCandidate>();
        var inconclusive = new ArrayList<KnxProductCandidate>();
        var diagnostics = new ArrayList<String>();
        var cache = new HashMap<ReadKey, Optional<MemoryRead>>();

        int comparedSamples = 0;

        try (var link = KNXNetworkLinkIP.newTunnelingLink(local, remote, false, new TPSettings());
             var adapter = new RemotePropertyServiceAdapter(link, address, event -> {}, true)) {

            var management = adapter.managementClient();
            var destination = adapter.destination();

            try {
                int level = management.authorize(
                        destination,
                        new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff}
                );
                diagnostics.add("A_Authorize uitgevoerd met KNX free-access key; toegangsniveau " + level + ".");
            }
            catch (KNXException | RuntimeException e) {
                diagnostics.add("A_Authorize niet bevestigd: "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }

            for (var candidate : candidates) {
                boolean mismatch = false;
                int comparedForCandidate = 0;
                int strongMatchesForCandidate = 0;

                for (KnxMemorySample sample : candidate.codeSamples()) {
                    if (sample == null || sample.length() <= 0) {
                        continue;
                    }

                    var key = new ReadKey(sample.address(), sample.length());
                    Optional<MemoryRead> resident;

                    if (cache.containsKey(key)) {
                        resident = cache.get(key);
                    }
                    else {
                        try {
                            var read = readMemoryAdaptive(
                                    management,
                                    destination,
                                    sample.address(),
                                    sample.length()
                            );
                            resident = read.bytes().length == 0
                                    ? Optional.empty()
                                    : Optional.of(read);

                            if (!read.complete()) {
                                diagnostics.add(String.format(
                                        "Geheugen 0x%04X gedeeltelijk leesbaar: %d/%d bytes gebruikt voor fingerprint.",
                                        sample.address(),
                                        read.bytes().length,
                                        sample.length()
                                ));
                            }
                        }
                        catch (KNXException | RuntimeException e) {
                            resident = Optional.empty();
                            diagnostics.add(String.format(
                                    "Geheugen 0x%04X (%d bytes) niet leesbaar: %s",
                                    sample.address(),
                                    sample.length(),
                                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
                            ));
                        }
                        cache.put(key, resident);
                    }

                    if (resident.isEmpty()) {
                        continue;
                    }

                    comparedForCandidate++;
                    comparedSamples++;

                    var read = resident.get();
                    if (!matches(sample, read.bytes())) {
                        mismatch = true;
                        break;
                    }

                    if (read.complete() || read.bytes().length >= Math.min(8, sample.length())) {
                        strongMatchesForCandidate++;
                    }
                }

                if (!mismatch) {
                    if (comparedForCandidate > 0 && strongMatchesForCandidate > 0) {
                        verified.add(candidate);
                    }
                    else {
                        inconclusive.add(candidate);
                    }
                }
            }
        }

        return new Result(
                candidates.size(),
                List.copyOf(verified),
                List.copyOf(inconclusive),
                cache.size(),
                comparedSamples,
                diagnostics.stream().distinct().limit(20).toList()
        );
    }

    private static MemoryRead readMemoryAdaptive(
            ManagementClient management,
            Destination destination,
            int address,
            int length
    ) throws KNXException, InterruptedException {

        var out = new ByteArrayOutputStream(length);
        int offset = 0;
        KNXException lastError = null;

        while (offset < length) {
            int remaining = length - offset;
            int[] preferred = {12, 8, 4, 1};
            boolean readAny = false;

            for (int requested : preferred) {
                int chunk = Math.min(requested, remaining);

                // Avoid retrying the same size when remaining is already smaller.
                boolean duplicate = false;
                for (int earlier : preferred) {
                    if (earlier == requested) {
                        break;
                    }
                    if (Math.min(earlier, remaining) == chunk) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) {
                    continue;
                }

                try {
                    var bytes = management.readMemory(destination, address + offset, chunk);
                    if (bytes == null || bytes.length != chunk) {
                        lastError = new KNXException(String.format(
                                "onvolledige geheugenrespons op 0x%04X: verwacht %d bytes, kreeg %d",
                                address + offset,
                                chunk,
                                bytes == null ? 0 : bytes.length
                        ));
                        continue;
                    }

                    out.writeBytes(bytes);
                    offset += chunk;
                    readAny = true;
                    break;
                }
                catch (KNXException e) {
                    lastError = e;
                }
            }

            if (!readAny) {
                if (out.size() > 0) {
                    return new MemoryRead(out.toByteArray(), false);
                }
                if (lastError != null) {
                    throw lastError;
                }
                throw new KNXException(String.format(
                        "could not read memory from 0x%04X",
                        address + offset
                ));
            }
        }

        return new MemoryRead(out.toByteArray(), true);
    }

    private static boolean matches(KnxMemorySample sample, byte[] actual) {
        try {
            var expected = HexFormat.of().parseHex(sample.expectedHex());
            var mask = sample.maskHex() == null || sample.maskHex().isBlank()
                    ? fullMask(expected.length)
                    : HexFormat.of().parseHex(sample.maskHex());

            int length = Math.min(expected.length, actual.length);
            for (int i = 0; i < length; i++) {
                int m = i < mask.length ? mask[i] & 0xff : 0xff;
                if (m == 0xff && actual[i] != expected[i]) {
                    return false;
                }
            }
            return actual.length > 0;
        }
        catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] fullMask(int length) {
        var mask = new byte[length];
        java.util.Arrays.fill(mask, (byte) 0xff);
        return mask;
    }
}
