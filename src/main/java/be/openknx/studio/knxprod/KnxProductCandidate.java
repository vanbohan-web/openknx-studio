package be.openknx.studio.knxprod;

public record KnxProductCandidate(
        String manufacturerRef,
        String hardwareName,
        String orderNumber,
        String productText,
        String applicationRef,
        String applicationName,
        String maskVersion,
        String hardwareTypeMarker
) {
    public String displayName() {
        var order = orderNumber == null || orderNumber.isBlank() ? "zonder ordernr." : orderNumber;
        var text = productText == null || productText.isBlank() ? hardwareName : productText;
        return order + " — " + text;
    }
}
