package be.openknx.studio.knxprod;

import be.openknx.studio.knx.KnxDeviceInfoService;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class KnxProductMatcher {

    public List<KnxProductCandidate> match(
            KnxDeviceInfoService.BasicDeviceInfo device,
            List<KnxProductCandidate> catalog
    ) {
        var manufacturerRef = manufacturerRef(device.manufacturerId());
        var mask = normalizeMask(device.deviceDescriptor());
        var hardwareType = normalizeHex(device.hardwareType());

        var base = catalog.stream()
                .filter(candidate -> manufacturerRef.equalsIgnoreCase(candidate.manufacturerRef()))
                .filter(candidate -> mask.isBlank()
                        || candidate.maskVersion().isBlank()
                        || mask.equalsIgnoreCase(normalizeMask(candidate.maskVersion())))
                .toList();

        if (base.isEmpty()) {
            return base;
        }

        if (!hardwareType.isBlank()) {
            var exactHardware = base.stream()
                    .filter(candidate -> !candidate.hardwareTypeMarker().isBlank())
                    .filter(candidate -> hardwareType.equalsIgnoreCase(normalizeHex(candidate.hardwareTypeMarker())))
                    .toList();

            if (!exactHardware.isEmpty()) {
                base = exactHardware;
            }
        }

        var orderKeys = deviceOrderKeys(device.orderInfo());
        if (!orderKeys.isEmpty()) {
            var orderMatches = base.stream()
                    .filter(candidate -> candidateMatchesAnyOrderKey(candidate, orderKeys))
                    .toList();

            if (!orderMatches.isEmpty()) {
                base = orderMatches;
            }
        }

        return base;
    }

    public String summarize(
            KnxDeviceInfoService.BasicDeviceInfo device,
            List<KnxProductCandidate> catalog
    ) {
        if (catalog == null || catalog.isEmpty()) {
            return "geen .knxprod geladen";
        }

        var matches = match(device, catalog);
        if (matches.isEmpty()) {
            return "geen match";
        }

        var distinct = matches.stream()
                .map(KnxProductCandidate::displayName)
                .distinct()
                .toList();

        if (distinct.size() == 1) {
            return distinct.getFirst();
        }

        return distinct.size() + " kandidaten";
    }

    private static boolean candidateMatchesAnyOrderKey(
            KnxProductCandidate candidate,
            Set<String> keys
    ) {
        var haystack = normalizeText(
                safe(candidate.orderNumber())
                        + safe(candidate.productText())
                        + safe(candidate.hardwareName())
                        + safe(candidate.applicationName())
        );

        for (var key : keys) {
            if (key.length() >= 5 && haystack.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> deviceOrderKeys(String value) {
        var result = new LinkedHashSet<String>();

        var readable = readableOrderInfo(value);
        var normalized = normalizeText(readable);
        if (normalized.length() < 5) {
            return result;
        }

        result.add(normalized);

        // MDT legacy product info often reports e.g. PP360D1 while the
        // catalogue order number is SCN-P360D1.01.
        if (normalized.startsWith("PP") && normalized.length() > 2) {
            result.add(normalized.substring(1));
        }

        if (normalized.startsWith("SCN") && normalized.length() > 3) {
            result.add(normalized.substring(3));
        }

        return result;
    }

    private static String readableOrderInfo(String value) {
        if (value == null || value.isBlank()
                || value.toLowerCase(Locale.ROOT).startsWith("niet")) {
            return "";
        }

        var compact = value.replaceAll("\\s+", "");
        if (compact.matches("(?i)[0-9a-f]{8,}")) {
            try {
                var bytes = java.util.HexFormat.of().parseHex(compact);
                int end = 0;
                while (end < bytes.length) {
                    int ch = bytes[end] & 0xff;
                    if (ch < 32 || ch > 126) {
                        break;
                    }
                    end++;
                }
                if (end >= 4) {
                    return new String(bytes, 0, end, StandardCharsets.US_ASCII);
                }
            }
            catch (IllegalArgumentException ignored) {
            }
        }

        return value;
    }

    private static String manufacturerRef(String formattedId) {
        try {
            var token = formattedId == null ? "" : formattedId.trim().split("\\s+")[0];
            int id = Integer.parseInt(token);
            return String.format("M-%04X", id);
        }
        catch (RuntimeException e) {
            return "";
        }
    }

    private static String normalizeMask(String value) {
        if (value == null || value.equalsIgnoreCase("niet beschikbaar")) {
            return "";
        }
        var v = value.trim().toUpperCase(Locale.ROOT);
        if (v.startsWith("MV-")) {
            v = v.substring(3);
        }
        if (v.startsWith("0X")) {
            v = v.substring(2);
        }
        return v;
    }

    private static String normalizeHex(String value) {
        if (value == null || value.toLowerCase(Locale.ROOT).startsWith("niet")) {
            return "";
        }
        return value.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
    }

    private static String normalizeText(String value) {
        return value == null
                ? ""
                : value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
