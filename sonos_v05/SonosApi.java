package be.vantique.mijnmuzieksonos;

import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

public final class SonosApi {
    private static final String AV = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String RENDER = "urn:schemas-upnp-org:service:RenderingControl:1";
    private SonosApi() {}

    public static void playTrack(String ip, String url, Song song) throws Exception {
        // A Sonos renderer fetches the content from our phone's HTTP server itself.
        String durationAttribute = "";
        if (song.durationMs > 0) {
            long time = song.durationMs / 1000;
            String clock = String.format(java.util.Locale.US, "%02d:%02d:%02d",
                time / 3600, (time % 3600) / 60, time % 60);
            durationAttribute = " duration=\"" + clock + "\"";
        }
        String didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"1\" parentID=\"0\" restricted=\"1\">" +
            "<dc:title>" + xml(song.title) + "</dc:title>" +
            "<dc:creator>" + xml(song.artist == null ? "" : song.artist) + "</dc:creator>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<res protocolInfo=\"http-get:*:" + xml(song.mimeType) + ":*\"" + durationAttribute + ">" + xml(url) + "</res>" +
            "</item></DIDL-Lite>";
        send(ip, AV, "AVTransport", "SetAVTransportURI",
            "<InstanceID>0</InstanceID>" +
            "<CurrentURI>" + xml(url) + "</CurrentURI>" +
            "<CurrentURIMetaData>" + xml(didl) + "</CurrentURIMetaData>");
        play(ip);
    }

    /** Sonos queue: the player itself proceeds to the next song, even if the UI is closed. */
    public static void startQueue(String ip, String uid, String url, Song song) throws Exception {
        if (uid == null || !uid.startsWith("uuid:RINCON_"))
            throw new IllegalArgumentException("Sonos toestel-ID ontbreekt; gebruik opnieuw Zoek Sonos");
        send(ip, AV, "AVTransport", "RemoveAllTracksFromQueue", "<InstanceID>0</InstanceID>");
        addToQueue(ip, url, song);
        String queueUri = "x-rincon-queue:" + uid.substring("uuid:".length()) + "#0";
        send(ip, AV, "AVTransport", "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>" + xml(queueUri) +
            "</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>");
        send(ip, AV, "AVTransport", "Seek",
            "<InstanceID>0</InstanceID><Unit>TRACK_NR</Unit><Target>1</Target>");
        play(ip);
    }

    public static void addToQueue(String ip, String url, Song song) throws Exception {
        send(ip, AV, "AVTransport", "AddURIToQueue",
            "<InstanceID>0</InstanceID>" +
            "<EnqueuedURI>" + xml(url) + "</EnqueuedURI>" +
            "<EnqueuedURIMetaData>" + xml(metadata(song, url)) + "</EnqueuedURIMetaData>" +
            "<DesiredFirstTrackNumberEnqueued>0</DesiredFirstTrackNumberEnqueued>" +
            "<EnqueueAsNext>0</EnqueueAsNext>");
    }

    public static void nextTrack(String ip) throws Exception {
        send(ip, AV, "AVTransport", "Next", "<InstanceID>0</InstanceID>");
    }
    public static void previousTrack(String ip) throws Exception {
        send(ip, AV, "AVTransport", "Previous", "<InstanceID>0</InstanceID>");
    }

    private static String metadata(Song song, String url) {
        String durationAttribute = "";
        if (song.durationMs > 0) {
            long t = song.durationMs / 1000;
            durationAttribute = " duration=\"" + String.format(java.util.Locale.US,
                "%02d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60) + "\"";
        }
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"1\" parentID=\"0\" restricted=\"1\">" +
            "<dc:title>" + xml(song.title) + "</dc:title>" +
            "<dc:creator>" + xml(song.artist == null ? "" : song.artist) + "</dc:creator>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<res protocolInfo=\"http-get:*:" + xml(song.mimeType) + ":*\"" +
            durationAttribute + ">" + xml(url) + "</res></item></DIDL-Lite>";
    }

    public static void play(String ip) throws Exception {
        send(ip, AV, "AVTransport", "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
    }

    public static void pause(String ip) throws Exception {
        send(ip, AV, "AVTransport", "Pause", "<InstanceID>0</InstanceID>");
    }

    public static void stop(String ip) throws Exception {
        send(ip, AV, "AVTransport", "Stop", "<InstanceID>0</InstanceID>");
    }

    public static void volume(String ip, int volume) throws Exception {
        int safe = Math.max(0, Math.min(100, volume));
        send(ip, RENDER, "RenderingControl", "SetVolume",
            "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>" + safe + "</DesiredVolume>");
    }

    /** Time information returned by the actual Sonos player, not a phone-side stopwatch. */
    public static final class PositionInfo {
        public final int elapsedSeconds;
        public final int durationSeconds;
        public final int trackNumber;
        PositionInfo(int elapsedSeconds, int durationSeconds, int trackNumber) {
            this.elapsedSeconds = elapsedSeconds;
            this.durationSeconds = durationSeconds;
            this.trackNumber = trackNumber;
        }
    }

    public static PositionInfo getPosition(String ip) throws Exception {
        String response = sendResponse(ip, AV, "AVTransport", "GetPositionInfo",
            "<InstanceID>0</InstanceID>");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(
            response.getBytes(StandardCharsets.UTF_8)));
        int trackNumber = -1;
        try { trackNumber = Integer.parseInt(readTag(doc, "Track")); }
        catch (NumberFormatException ignored) { }
        return new PositionInfo(parseTime(readTag(doc, "RelTime")),
            parseTime(readTag(doc, "TrackDuration")), trackNumber);
    }

    public static void seek(String ip, int seconds) throws Exception {
        int safe = Math.max(0, seconds);
        String hms = String.format(java.util.Locale.US, "%02d:%02d:%02d",
            safe / 3600, (safe % 3600) / 60, safe % 60);
        send(ip, AV, "AVTransport", "Seek",
            "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>" + hms + "</Target>");
    }

    private static String readTag(Document document, String tag) {
        NodeList nodes = document.getElementsByTagName(tag);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent().trim() : "";
    }

    private static int parseTime(String time) {
        if (time == null || time.isEmpty() || time.equals("NOT_IMPLEMENTED")) return -1;
        String[] fields = time.split(":");
        if (fields.length != 3) return -1;
        try {
            int hours = Integer.parseInt(fields[0]);
            int minutes = Integer.parseInt(fields[1]);
            int seconds = Integer.parseInt(fields[2].split("\\.")[0]);
            return hours * 3600 + minutes * 60 + seconds;
        } catch (NumberFormatException ignored) { return -1; }
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static void send(String ip, String service, String path, String action, String fields) throws Exception {
        sendResponse(ip, service, path, action, fields);
    }

    private static String sendResponse(String ip, String service, String path, String action, String fields) throws Exception {
        String soap = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:" + action + " xmlns:u=\"" + service + "\">" + fields +
            "</u:" + action + "></s:Body></s:Envelope>";
        HttpURLConnection conn = (HttpURLConnection) new URL(
            "http://" + ip + ":1400/MediaRenderer/" + path + "/Control").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(4500);
        conn.setReadTimeout(4500);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        conn.setRequestProperty("SOAPACTION", "\"" + service + "#" + action + "\"");
        byte[] bytes = soap.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            String details = "";
            InputStream error = conn.getErrorStream();
            if (error != null) {
                try (InputStream in = error) {
                    byte[] data = new byte[4096];
                    int n = in.read(data);
                    if (n > 0) details = new String(data, 0, n, StandardCharsets.UTF_8);
                }
            }
            throw new java.io.IOException("Sonos-fout HTTP " + code + ": " + details.replaceAll("<[^>]+>", " ").trim());
        }
        String response;
        try (InputStream in = conn.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] b = new byte[2048];
            int n;
            while ((n = in.read(b)) != -1 && buffer.size() < 400_000) buffer.write(b, 0, n);
            response = buffer.toString("UTF-8");
        } finally { conn.disconnect(); }
        return response;
    }
}