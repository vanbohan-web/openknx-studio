package be.openknx.studio.knxprod;

public record KnxProductCandidate(
        String manufacturerRef,
        String hardwareRef,
        String hardwareName,
        String originalManufacturerRef,
        String orderNumber,
        String productText,
        String applicationRef,
        String applicationName,
        String maskVersion,
        String hardwareTypeMarker,
        int applicationNumber,
        int applicationVersion
) {
    public String displayName() {
        var order = orderNumber == null || orderNumber.isBlank() ? "zonder ordernr." : orderNumber;
        var text = productText == null || productText.isBlank() ? hardwareName : productText;
        return order + " — " + text;
    }
}
