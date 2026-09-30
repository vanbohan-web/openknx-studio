package be.openknx.studio.knxprod;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class KnxProdImportService {

    private record ApplicationInfo(
            String ref,
            String name,
            String maskVersion,
            String hardwareTypeMarker,
            int applicationNumber,
            int applicationVersion,
            List<KnxMemorySample> codeSamples
    ) {}

    private record HardwareInfo(
            String manufacturerRef,
            String hardwareRef,
            String hardwareName,
            String originalManufacturerRef,
            List<ProductInfo> products,
            List<String> applicationRefs
    ) {}

    private record ProductInfo(String orderNumber, String text) {}

    public List<KnxProductCandidate> importFiles(List<File> files) throws Exception {
        var all = new ArrayList<KnxProductCandidate>();
        for (var file : files) {
            var name = file.getName().toLowerCase();
            if (name.endsWith(".knxprod")) {
                all.addAll(importFile(file));
            }
            else if (name.endsWith(".zip")) {
                all.addAll(importBundleZip(file));
            }
        }
        return List.copyOf(all);
    }

    private List<KnxProductCandidate> importBundleZip(File bundle) throws Exception {
        var all = new ArrayList<KnxProductCandidate>();

        try (var zip = new ZipFile(bundle)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".knxprod")) {
                    continue;
                }

                var temp = java.nio.file.Files.createTempFile("openknx-", ".knxprod");
                try (var in = zip.getInputStream(entry)) {
                    java.nio.file.Files.copy(
                            in,
                            temp,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    );
                    all.addAll(importFile(temp.toFile()));
                }
                finally {
                    java.nio.file.Files.deleteIfExists(temp);
                }
            }
        }

        return List.copyOf(all);
    }

    public List<KnxProductCandidate> importFile(File file) throws Exception {
        var apps = new HashMap<String, ApplicationInfo>();
        var hardware = new ArrayList<HardwareInfo>();

        try (var zip = new ZipFile(file)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".xml")) {
                    continue;
                }

                try (InputStream in = zip.getInputStream(entry)) {
                    parseXml(in, apps, hardware);
                }
                catch (Exception ignored) {
                    // Some baggage XML files are not useful for matching.
                }
            }
        }

        var out = new ArrayList<KnxProductCandidate>();
        for (var hw : hardware) {
            for (var product : hw.products()) {
                if (hw.applicationRefs().isEmpty()) {
                    out.add(new KnxProductCandidate(
                            hw.manufacturerRef(),
                            hw.hardwareRef(),
                            hw.hardwareName(),
                            hw.originalManufacturerRef(),
                            product.orderNumber(),
                            product.text(),
                            "",
                            "",
                            "",
                            "",
                            -1,
                            -1,
                            List.of()
                    ));
                    continue;
                }

                for (var appRef : hw.applicationRefs()) {
                    var app = apps.get(appRef);
                    out.add(new KnxProductCandidate(
                            hw.manufacturerRef(),
                            hw.hardwareRef(),
                            hw.hardwareName(),
                            hw.originalManufacturerRef(),
                            product.orderNumber(),
                            product.text(),
                            appRef,
                            app != null ? app.name() : "",
                            app != null ? app.maskVersion() : "",
                            app != null ? app.hardwareTypeMarker() : "",
                            app != null ? app.applicationNumber() : -1,
                            app != null ? app.applicationVersion() : -1,
                            app != null ? app.codeSamples() : List.of()
                    ));
                }
            }
        }

        return List.copyOf(out);
    }

    private static void parseXml(
            InputStream in,
            Map<String, ApplicationInfo> apps,
            List<HardwareInfo> hardware
    ) throws Exception {

        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);

        var doc = factory.newDocumentBuilder().parse(in);

        var appNodes = doc.getElementsByTagNameNS("*", "ApplicationProgram");
        for (int i = 0; i < appNodes.getLength(); i++) {
            var element = (Element) appNodes.item(i);
            var id = attr(element, "Id");
            if (id.isBlank()) {
                continue;
            }

            var marker = findHardwareTypeMarker(element);

            apps.put(id, new ApplicationInfo(
                    id,
                    firstNonBlank(attr(element, "Name"), attr(element, "Text")),
                    normalizeMask(attr(element, "MaskVersion")),
                    marker,
                    parseInt(attr(element, "ApplicationNumber")),
                    parseInt(attr(element, "ApplicationVersion")),
                    findCodeSamples(element)
            ));
        }

        var hwNodes = doc.getElementsByTagNameNS("*", "Hardware");
        for (int i = 0; i < hwNodes.getLength(); i++) {
            var element = (Element) hwNodes.item(i);
            var manufacturerRef = manufacturerRef(element);
            var hardwareRef = attr(element, "Id");
            var hwName = firstNonBlank(attr(element, "Name"), attr(element, "SerialNumber"));
            var originalManufacturerRef = attr(element, "OriginalManufacturer");

            var products = new ArrayList<ProductInfo>();
            var productNodes = element.getElementsByTagNameNS("*", "Product");
            for (int p = 0; p < productNodes.getLength(); p++) {
                var product = (Element) productNodes.item(p);
                products.add(new ProductInfo(
                        firstNonBlank(attr(product, "OrderNumber"), attr(product, "Id")),
                        firstNonBlank(attr(product, "Text"), attr(product, "Name"))
                ));
            }

            if (products.isEmpty()) {
                products.add(new ProductInfo("", hwName));
            }

            var refs = new ArrayList<String>();
            var refNodes = element.getElementsByTagNameNS("*", "ApplicationProgramRef");
            for (int r = 0; r < refNodes.getLength(); r++) {
                var ref = attr((Element) refNodes.item(r), "RefId");
                if (!ref.isBlank() && !refs.contains(ref)) {
                    refs.add(ref);
                }
            }

            hardware.add(new HardwareInfo(
                    manufacturerRef,
                    hardwareRef,
                    hwName,
                    originalManufacturerRef,
                    List.copyOf(products),
                    List.copyOf(refs)
            ));
        }
    }

    private static List<KnxMemorySample> findCodeSamples(Element application) {
        final int maxSampleBytes = 32;
        final int maxSamples = 8;

        var parameterSegments = new HashSet<String>();
        var memoryNodes = application.getElementsByTagNameNS("*", "Memory");
        for (int i = 0; i < memoryNodes.getLength(); i++) {
            var memory = (Element) memoryNodes.item(i);
            var ref = attr(memory, "CodeSegment");
            if (!ref.isBlank()) {
                parameterSegments.add(ref);
            }
        }

        var samples = new ArrayList<KnxMemorySample>();
        var segmentNodes = application.getElementsByTagNameNS("*", "AbsoluteSegment");

        for (int i = 0; i < segmentNodes.getLength() && samples.size() < maxSamples; i++) {
            var segment = (Element) segmentNodes.item(i);
            var id = attr(segment, "Id");
            int address = parseInt(attr(segment, "Address"));

            if (id.isBlank() || address < 0 || parameterSegments.contains(id)) {
                continue;
            }

            // System-7 group/association tables are project data, not application code.
            if (address == 0x4000 || address == 0x4201) {
                continue;
            }

            var segFlagsText = attr(segment, "SegFlags");
            if (!segFlagsText.isBlank() && parseInt(segFlagsText) == 0) {
                // Runtime-writable segment: unsuitable as a stable fingerprint.
                continue;
            }

            var data = decodePayload(firstChildText(segment, "Data"));
            if (data.length < 4) {
                continue;
            }

            int declaredSize = parseInt(attr(segment, "Size"));
            int length = Math.min(maxSampleBytes, data.length);
            if (declaredSize > 0) {
                length = Math.min(length, declaredSize);
            }
            if (length < 4) {
                continue;
            }

            var maskData = decodePayload(firstChildText(segment, "Mask"));
            var expected = java.util.Arrays.copyOf(data, length);
            var mask = new byte[length];

            if (maskData.length >= length) {
                System.arraycopy(maskData, 0, mask, 0, length);
            }
            else {
                java.util.Arrays.fill(mask, (byte) 0xff);
            }

            int fixed = 0;
            for (byte b : mask) {
                if ((b & 0xff) == 0xff) {
                    fixed++;
                }
            }
            if (fixed < 4) {
                continue;
            }

            samples.add(new KnxMemorySample(
                    address,
                    id,
                    HexFormat.of().withUpperCase().formatHex(expected),
                    HexFormat.of().withUpperCase().formatHex(mask)
            ));
        }

        return List.copyOf(samples);
    }

    private static byte[] decodePayload(String value) {
        if (value == null || value.isBlank()) {
            return new byte[0];
        }

        var compact = value.replaceAll("\\s+", "");
        try {
            return Base64.getDecoder().decode(compact);
        }
        catch (IllegalArgumentException ignored) {
            if (compact.matches("(?i)[0-9a-f]+") && compact.length() % 2 == 0) {
                try {
                    return HexFormat.of().parseHex(compact);
                }
                catch (IllegalArgumentException ignoredHex) {
                }
            }
            return new byte[0];
        }
    }

    private static String firstChildText(Element parent, String localName) {
        var nodes = parent.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) {
            return "";
        }

        var text = nodes.item(0).getTextContent();
        return text == null ? "" : text.trim();
    }

    private static String findHardwareTypeMarker(Element application) {
        var nodes = application.getElementsByTagNameNS("*", "LdCtrlCompareProp");
        for (int i = 0; i < nodes.getLength(); i++) {
            var element = (Element) nodes.item(i);
            if ("0".equals(attr(element, "ObjIdx")) && "78".equals(attr(element, "PropId"))) {
                return normalizeHex(attr(element, "InlineData"));
            }
        }
        return "";
    }

    private static String manufacturerRef(Element element) {
        Node node = element;
        while (node != null) {
            if (node instanceof Element current && "Manufacturer".equals(current.getLocalName())) {
                return attr(current, "RefId");
            }
            node = node.getParentNode();
        }

        var id = attr(element, "Id");
        int idx = id.indexOf("_H-");
        return idx > 0 ? id.substring(0, idx) : "";
    }

    private static String normalizeMask(String value) {
        if (value == null) {
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
        return value == null ? "" : value.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
    }

    private static int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }

        var v = value.trim();
        try {
            if (v.startsWith("0x") || v.startsWith("0X")) {
                return Integer.parseInt(v.substring(2), 16);
            }
            return Integer.parseInt(v);
        }
        catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String attr(Element element, String name) {
        return element.hasAttribute(name) ? element.getAttribute(name).trim() : "";
    }

    private static String firstNonBlank(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
