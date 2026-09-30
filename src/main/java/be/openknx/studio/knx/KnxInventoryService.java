package be.openknx.studio.knx;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

public final class KnxInventoryService {

    private final KnxDeviceScanService scanService;
    private final KnxDeviceInfoService infoService;

    public KnxInventoryService(KnxDeviceScanService scanService, KnxDeviceInfoService infoService) {
        this.scanService = scanService;
        this.infoService = infoService;
    }

    public List<KnxDeviceInfoService.BasicDeviceInfo> scanAndIdentify(
            String host,
            int area,
            int line,
            BiConsumer<Integer, Integer> progress
    ) throws Exception {

        var addresses = scanService.scanLine(host, area, line);
        var result = new ArrayList<KnxDeviceInfoService.BasicDeviceInfo>();

        int total = addresses.size();
        int current = 0;

        for (var address : addresses) {
            current++;
            if (progress != null) {
                progress.accept(current, total);
            }

            try {
                result.add(infoService.read(host, address));
            }
            catch (Exception e) {
                result.add(new KnxDeviceInfoService.BasicDeviceInfo(
                        address,
                        "niet beschikbaar",
                        "onbekend / niet herkend",
                        "niet beschikbaar",
                        "niet beschikbaar",
                        "niet beschikbaar",
                        "niet beschikbaar",
                        "niet beschikbaar",
                        "niet beschikbaar",
                        "niet beschikbaar",
                        "niet beschikbaar"
                ));
            }
        }

        return List.copyOf(result);
    }
}
