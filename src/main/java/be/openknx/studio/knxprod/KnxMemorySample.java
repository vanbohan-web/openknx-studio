package be.openknx.studio.knxprod;

public record KnxMemorySample(
        int address,
        String segmentId,
        String expectedHex,
        String maskHex
) {
    public int length() {
        return expectedHex == null ? 0 : expectedHex.length() / 2;
    }
}
