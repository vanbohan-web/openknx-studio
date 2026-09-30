package be.openknx.studio.knxprod;

import be.openknx.studio.knx.KnxDeviceInfoService;

import java.util.List;

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

        if (!hardwareType.isBlank() && !"NIETBESCHIKBAAR".equals(hardwareType)) {
            var exact = base.stream()
                    .filter(candidate -> !candidate.hardwareTypeMarker().isBlank())
                    .filter(candidate -> hardwareType.equalsIgnoreCase(normalizeHex(candidate.hardwareTypeMarker())))
                    .toList();
            if (!exact.isEmpty()) {
                return exact;
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
        var v = value.trim().toUpperCase();
        if (v.startsWith("MV-")) {
            v = v.substring(3);
        }
        if (v.startsWith("0X")) {
            v = v.substring(2);
        }
        return v;
    }

    private static String normalizeHex(String value) {
        if (value == null || value.equalsIgnoreCase("niet beschikbaar")) {
            return "";
        }
        return value.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
    }
}
