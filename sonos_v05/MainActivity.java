package be.vantique.mijnmuzieksonos;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.media.MediaMetadataRetriever;
import android.content.ClipData;
import android.app.AlertDialog;
import android.provider.DocumentsContract;
import android.widget.LinearLayout.LayoutParams;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;
import android.content.ActivityNotFoundException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_MEDIA = 50;
    private static final int REQUEST_OPEN_FILES = 71;
    private static final int REQUEST_OPEN_FOLDER = 72;
    private static final String SAVED_FOLDERS = "sonos_saved_folders";
    private static final String SAVED_PLAYLISTS = "sonos_saved_playlists";
    private final Map<String, LinkedHashSet<String>> playlists = new LinkedHashMap<>();
    private String activePlaylist = "";
    private EditText filterBox;
    private static final String SAVED_URIS = "manually_picked_audio_uris";
    private long nextExternalId = 4_000_000_000_000L;
    private static final int TEXT = Color.rgb(20, 32, 55);
    private static final int MUTED = Color.rgb(91, 105, 129);
    private static final int BLUE = Color.rgb(40, 82, 185);
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final List<Song> allSongs = new ArrayList<>();
    private final List<Song> pickedSongs = new ArrayList<>();
    private final List<Song> shownSongs = new ArrayList<>();
    private final List<SonosDevice> speakers = new ArrayList<>();
    private ArrayAdapter<Song> songAdapter;
    private ArrayAdapter<SonosDevice> deviceAdapter;
    private TextView status;
    private TextView playingLabel;
    private TextView libraryLabel;
    private Spinner deviceSpinner;
    private EditText manualIp;
    private Button scanButton;
    private Song currentlyPlaying;
    private Song pendingSong;
    private MusicServerService musicService;
    private boolean bound;
    private boolean startedByUs;
    private SeekBar positionBar;
    private TextView positionLabel;
    private int latestPositionSec = -1;
    private int latestDurationSec = -1;
    private boolean scrubbing = false;
    private boolean playbackActive = false;
    private boolean autoNext = true;
    private boolean queuePlayback = false;
    private final List<Song> currentQueue = new ArrayList<>();
    private static final int MAX_QUEUE_TRACKS = 150;
    private long measuredAtElapsedMs = SystemClock.elapsedRealtime();
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            redrawProgressClock();
            positionHandler.postDelayed(this, 500);
        }
    };
    private final Handler positionHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean positionRequestInFlight = new AtomicBoolean(false);
    private boolean visible = false;
    private final Runnable positionPoll = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            SonosDevice speaker = currentSpeakerWithoutErrors();
            if (speaker != null && currentlyPlaying != null && positionRequestInFlight.compareAndSet(false, true)) {
                background.execute(() -> {
                    try {
                        SonosApi.PositionInfo info = SonosApi.getPosition(speaker.ip);
                        runOnUiThread(() -> {
                            if (queuePlayback && info.trackNumber > 0 && info.trackNumber <= currentQueue.size()) {
                                Song actual = currentQueue.get(info.trackNumber - 1);
                                if (currentlyPlaying != actual) {
                                    currentlyPlaying = actual;
                                    latestPositionSec = -1;
                                    latestDurationSec = -1;
                                    SonosDevice selected = currentSpeakerWithoutErrors();
                                    playingLabel.setText("▶  " + actual.title +
                                        (selected == null ? "" : "  →  " + selected.name));
                                }
                            }
                            showPosition(info.elapsedSeconds, info.durationSeconds);
                        });
                    } catch (Exception ignored) { /* Position is optional while switching tracks. */ }
                    finally { positionRequestInFlight.set(false); }
                });
            }
            positionHandler.postDelayed(this, 3500);
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            bound = true;
            musicService = ((MusicServerService.LocalBinder) binder).getService();
            if (!musicService.isReady()) {
                status.setText("Muziekserver niet beschikbaar: " + musicService.getStartupError());
                return;
            }
            status.setText("Muziekdeling actief op je telefoon");
            musicService.reloadSongs();
            if (pendingSong != null) {
                Song song = pendingSong;
                pendingSong = null;
                sendTrack(song);
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            musicService = null;
            status.setText("Verbinding met muziekserver verbroken");
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        autoNext = getPreferences(MODE_PRIVATE).getBoolean("auto_next", true);
        restorePlaylists();
        restorePickedFiles();
        restoreFolders();
        if (hasAudioPermission()) reloadLibrary();
        else requestAudioPermission();
        restoreSpeakerOrScan();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.setBackgroundColor(Color.rgb(246, 248, 252));
        setContentView(root);

        TextView title = label("Mijn Muziek → Sonos", 24, TEXT, true);
        root.addView(title);
        TextView subtitle = label("Speel MP3, FLAC en andere bestanden op je wifi-speakers", 12, MUTED, false);
        root.addView(subtitle);
        status = label("Klaar om te verbinden", 12, BLUE, false);
        margins(status, 3, 0, 12);
        root.addView(status);

        TextView roomTitle = label("SONOS-KAMER", 12, MUTED, true);
        root.addView(roomTitle);
        LinearLayout roomRow = row();
        deviceSpinner = new Spinner(this);
        speakers.add(new SonosDevice("Tik op 'Zoek Sonos'", "", ""));
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, speakers);
        deviceSpinner.setAdapter(deviceAdapter);
        roomRow.addView(deviceSpinner, new LinearLayout.LayoutParams(0, dp(50), 1f));
        scanButton = button("Zoek Sonos");
        roomRow.addView(scanButton);
        scanButton.setOnClickListener(v -> searchSpeakers());
        root.addView(roomRow);

        LinearLayout ipRow = row();
        manualIp = new EditText(this);
        manualIp.setSingleLine(true);
        manualIp.setTextSize(13);
        manualIp.setHint("Of vul Sonos-IP in: 192.168.1.20");
        manualIp.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        ipRow.addView(manualIp, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button addIp = button("Gebruik IP");
        ipRow.addView(addIp);
        addIp.setOnClickListener(v -> addManualIp());
        root.addView(ipRow);
        TextView hint = label("IP automatisch zoeken (ook als multicast niet werkt).", 11, MUTED, false);
        root.addView(hint);

        libraryLabel = label("MUZIEK OP MIJN GSM", 12, MUTED, true);
        margins(libraryLabel, 10, 4, 6);
        root.addView(libraryLabel);
        Button openFileButton = button("📁 Selecteer losse muziekbestanden");
        openFileButton.setTextSize(13);
        openFileButton.setOnClickListener(v -> openAudioPicker());
        root.addView(openFileButton);
        TextView chooseHint = label("Kies ook meerdere nummers. Android toont je eigen mappen en beschikbare opslaglocaties.", 11, MUTED, false);
        root.addView(chooseHint);
        LinearLayout foldersAndLists = row();
        Button addFolder = button("+ Hele map");
        Button viewLists = button("Afspeellijsten");
        foldersAndLists.addView(addFolder, new LinearLayout.LayoutParams(0, dp(45), 1f));
        foldersAndLists.addView(viewLists, new LinearLayout.LayoutParams(0, dp(45), 1f));
        root.addView(foldersAndLists);
        addFolder.setOnClickListener(v -> openFolderPicker());
        viewLists.setOnClickListener(v -> choosePlaylist());
        LinearLayout playlistRow = row();
        Button newList = button("+ Nieuwe lijst");
        Button addToList = button("+ Nummers aan lijst");
        playlistRow.addView(newList, new LinearLayout.LayoutParams(0, dp(43), 1f));
        playlistRow.addView(addToList, new LinearLayout.LayoutParams(0, dp(43), 1f));
        root.addView(playlistRow);
        newList.setOnClickListener(v -> createPlaylist());
        addToList.setOnClickListener(v -> addSongsToPlaylist());
        EditText filter = new EditText(this);
        filter.setSingleLine(true);
        filter.setTextSize(15);
        filter.setHint("Zoek titel of artiest");
        filterBox = filter;
        root.addView(filter, new LinearLayout.LayoutParams(-1, dp(48)));
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) {
                filterSongs(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        ListView list = new ListView(this);
        list.setDividerHeight(dp(1));
        list.setBackground(round(Color.WHITE, 14));
        songAdapter = new ArrayAdapter<Song>(this, android.R.layout.simple_list_item_1, shownSongs) {
            @Override public View getView(int position, View view, ViewGroup parent) {
                TextView text = (TextView) super.getView(position, view, parent);
                text.setTextSize(14);
                text.setTextColor(TEXT);
                text.setMinHeight(dp(52));
                text.setGravity(Gravity.CENTER_VERTICAL);
                text.setPadding(dp(12), dp(6), dp(12), dp(6));
                return text;
            }
        };
        list.setAdapter(songAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> playSong(shownSongs.get(position)));
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        CheckBox autoBox = new CheckBox(this);
        autoBox.setText("▶ Automatisch volgende nummer uit de lijst afspelen");
        autoBox.setTextColor(TEXT);
        autoBox.setTextSize(12);
        autoBox.setChecked(getPreferences(MODE_PRIVATE).getBoolean("auto_next", true));
        autoBox.setOnCheckedChangeListener((b, enabled) -> {
            autoNext = enabled;
            getPreferences(MODE_PRIVATE).edit().putBoolean("auto_next", enabled).apply();
            status.setText(enabled ? "Volgende nummers worden automatisch afgespeeld." :
                "Automatisch doorgaan uitgeschakeld voor nieuwe afspeelbeurten.");
        });
        root.addView(autoBox);

        playingLabel = label("Tik op een liedje om af te spelen", 13, TEXT, true);
        margins(playingLabel, 10, 0, 6);
        root.addView(playingLabel);
        LinearLayout controls = row();
        Button prev = button("◀◀");
        Button play = button("▶");
        Button pause = button("Ⅱ");
        Button stop = button("■");
        Button next = button("▶▶");
        for (Button b : new Button[] {prev, play, pause, stop, next}) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(45), 1f);
            p.setMargins(dp(2), 0, dp(2), 0);
            controls.addView(b,p);
        }
        prev.setOnClickListener(v -> changeSong(-1));
        next.setOnClickListener(v -> changeSong(+1));
        play.setOnClickListener(v -> control("Afspelen", SonosApi::play));
        pause.setOnClickListener(v -> control("Pauze", SonosApi::pause));
        stop.setOnClickListener(v -> control("Stop", SonosApi::stop));
        root.addView(controls);

        LinearLayout timeline = row();
        positionLabel = label("00:00 / --:--", 12, MUTED, false);
        timeline.addView(positionLabel, new LinearLayout.LayoutParams(dp(105), dp(38)));
        positionBar = new SeekBar(this);
        positionBar.setMax(1000);
        positionBar.setProgress(0);
        positionBar.setEnabled(false);
        timeline.addView(positionBar, new LinearLayout.LayoutParams(0, dp(44), 1f));
        root.addView(timeline);
        positionBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && latestDurationSec > 0) {
                    int target = Math.round((value / 1000f) * latestDurationSec);
                    positionLabel.setText(timeText(target) + " / " + timeText(latestDurationSec));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { scrubbing = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                scrubbing = false;
                if (latestDurationSec > 0) {
                    seekTo(Math.round((bar.getProgress() / 1000f) * latestDurationSec));
                }
            }
        });
        LinearLayout skipRow = row();
        Button back10 = button("↶ 10 seconden");
        Button forward10 = button("10 seconden ↷");
        LinearLayout.LayoutParams skipParam1 = new LinearLayout.LayoutParams(0, dp(42), 1f);
        LinearLayout.LayoutParams skipParam2 = new LinearLayout.LayoutParams(0, dp(42), 1f);
        skipRow.addView(back10, skipParam1);
        skipRow.addView(forward10, skipParam2);
        back10.setOnClickListener(v -> skipSeconds(-10));
        forward10.setOnClickListener(v -> skipSeconds(+10));
        root.addView(skipRow);

        LinearLayout volumeRow = row();
        TextView vol = label("Volume", 12, MUTED, false);
        volumeRow.addView(vol);
        SeekBar volume = new SeekBar(this);
        volume.setMax(100);
        volume.setProgress(25);
        volumeRow.addView(volume,new LinearLayout.LayoutParams(0, dp(42), 1f));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                final int value = bar.getProgress();
                control("Volume " + value + "%", ip -> SonosApi.volume(ip, value));
            }
        });
        root.addView(volumeRow);
        Button stopShare = button("Stop muziekdeling op mijn gsm");
        stopShare.setTextSize(12);
        stopShare.setOnClickListener(v -> stopSharing());
        root.addView(stopShare);
    }

    /** Reconnect automatically to the last successful room, without opening Sonos. */
    private void restoreSpeakerOrScan() {
        String last = getPreferences(MODE_PRIVATE).getString("preferred_sonos_ip", "");
        if (last.isEmpty()) { searchSpeakers(); return; }
        status.setText("Vorige Sonos-kamer verbinden …");
        background.execute(() -> {
            SonosDevice device = SonosDiscovery.inspect(last);
            runOnUiThread(() -> {
                if (device == null) { searchSpeakers(); return; }
                speakers.clear();
                speakers.add(device);
                deviceAdapter.notifyDataSetChanged();
                status.setText("Verbonden met " + device.name + " · " + device.ip);
            });
        });
    }

    private void saveSpeaker(String ip) {
        getPreferences(MODE_PRIVATE).edit().putString("preferred_sonos_ip", ip).apply();
    }

    private SonosDevice currentSpeakerWithoutErrors() {
        Object selected = deviceSpinner == null ? null : deviceSpinner.getSelectedItem();
        if (!(selected instanceof SonosDevice)) return null;
        SonosDevice device = (SonosDevice) selected;
        return device.ip.isEmpty() ? null : device;
    }

    private void showPosition(int position, int duration) {
        if (scrubbing) return;
        if (position >= 0) {
            latestPositionSec = position;
            measuredAtElapsedMs = SystemClock.elapsedRealtime();
        }
        if (duration > 0) latestDurationSec = duration;
        redrawProgressClock();
    }

    /** Smooth clock between real Sonos position checks; also works if Sonos returns no timing. */
    private int displayedSeconds() {
        if (latestPositionSec < 0) return 0;
        long delta = playbackActive ? Math.max(0L, SystemClock.elapsedRealtime() - measuredAtElapsedMs) / 1000L : 0;
        long estimated = latestPositionSec + delta;
        if (latestDurationSec > 0) estimated = Math.min(estimated, latestDurationSec);
        return (int) Math.min(Integer.MAX_VALUE, estimated);
    }

    private void redrawProgressClock() {
        if (scrubbing || positionBar == null) return;
        int now = displayedSeconds();
        positionLabel.setText(timeText(now) + " / " +
                (latestDurationSec > 0 ? timeText(latestDurationSec) : "--:--"));
        boolean enabled = currentlyPlaying != null && latestDurationSec > 0;
        positionBar.setEnabled(enabled);
        if (enabled) positionBar.setProgress(Math.min(1000,
                Math.round(1000f * now / latestDurationSec)));
    }

    private static String timeText(int seconds) {
        if (seconds < 0) return "--:--";
        int mins = seconds / 60;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", mins, seconds % 60);
    }

    private void seekTo(int seconds) {
        SonosDevice speaker = selectedSpeaker();
        if (speaker == null) return;
        int target = Math.max(0, latestDurationSec > 0 ? Math.min(latestDurationSec - 1, seconds) : seconds);
        background.execute(() -> {
            try {
                SonosApi.seek(speaker.ip, target);
                runOnUiThread(() -> {
                    showPosition(target, latestDurationSec);
                    status.setText("Doorgespoeld naar " + timeText(target));
                });
            } catch (Exception e) { runOnUiThread(() -> error("Doorschuiven mislukt: " + e.getMessage())); }
        });
    }

    private void skipSeconds(int delta) {
        if (currentlyPlaying == null) { error("Kies eerst een liedje."); return; }
        // Use the visible position instead of depending on GetPositionInfo on every click.
        // Some Sonos groups do not return position quickly, while Seek itself works.
        int target = Math.max(0, displayedSeconds() + delta);
        if (latestDurationSec > 0) target = Math.min(target, Math.max(0, latestDurationSec - 1));
        seekTo(target);
    }

    private void searchSpeakers() {
        scanButton.setEnabled(false);
        status.setText("Sonos-kamers zoeken via wifi …");
        background.execute(() -> {
            try {
                List<SonosDevice> found = SonosDiscovery.discover(this);
                runOnUiThread(() -> {
                    scanButton.setEnabled(true);
                    String currentIp = currentSpeakerWithoutErrors() == null ? "" : currentSpeakerWithoutErrors().ip;
                    if (!found.isEmpty()) {
                        speakers.clear();
                        speakers.addAll(found);
                        int selected = 0;
                        for (int i = 0; i < speakers.size(); i++) {
                            if (speakers.get(i).ip.equals(currentIp)) selected = i;
                        }
                        deviceAdapter.notifyDataSetChanged();
                        deviceSpinner.setSelection(selected);
                        status.setText(found.size() + " afspeelbare Sonos-kamer(s) gevonden");
                    } else if (currentIp.isEmpty()) {
                        speakers.clear();
                        speakers.add(new SonosDevice("Geen Sonos gevonden", "", ""));
                        deviceAdapter.notifyDataSetChanged();
                        status.setText("Geen speler gevonden. Vul eventueel het IP in.");
                    } else {
                        status.setText("Scannen leverde niets op. Bestaande kamer blijft geselecteerd.");
                    }
                });
            } catch (Exception e) { runOnUiThread(() -> {
                scanButton.setEnabled(true);
                error("Zoeken mislukt: " + e.getMessage());
            }); }
        });
    }

    private void addManualIp() {
        String value = manualIp.getText().toString().trim();
        try {
            // No hostname or URL input: only an IPv4 address on the local network.
            if (!value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new Exception();
            InetAddress ip = InetAddress.getByName(value);
            if (!(ip.isSiteLocalAddress() || ip.isLinkLocalAddress())) throw new Exception();
            SonosDevice speaker = new SonosDevice("Handmatig ingestelde Sonos", value, "");
            speakers.add(speaker);
            deviceAdapter.notifyDataSetChanged();
            deviceSpinner.setSelection(speakers.size() - 1);
            status.setText("IP toegevoegd. Kies een nummer.");
            saveSpeaker(value);
        } catch (Exception ignored) { error("Vul een geldig lokaal IPv4-adres in, bijvoorbeeld 192.168.1.20"); }
    }

    private SonosDevice selectedSpeaker() {
        Object selected = deviceSpinner.getSelectedItem();
        if (!(selected instanceof SonosDevice) || ((SonosDevice)selected).ip.isEmpty()) {
            error("Zoek eerst je Sonos-kamer of vul het IP-adres in.");
            return null;
        }
        return (SonosDevice) selected;
    }

    private boolean hasAudioPermission() {
        String p = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }
    private void requestAudioPermission() {
        String p = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        requestPermissions(new String[] {p}, REQUEST_MEDIA);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request,permissions,grants);
        if (request == REQUEST_MEDIA && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) reloadLibrary();
        else if (request == REQUEST_MEDIA) error("Toegang tot muziek is nodig. Geef de app muziektoegang in de Android-instellingen.");
    }

    private void openAudioPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*"); // Lets users reach files even in providers with imperfect MIME typing.
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, REQUEST_OPEN_FILES); }
        catch (ActivityNotFoundException e) { error("Er is geen Android-bestandskiezer beschikbaar."); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQUEST_OPEN_FOLDER) {
            Uri tree = data.getData();
            if (tree != null) {
                try { getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (Exception e) { error("Deze opslaglocatie bewaart geen toegang na herstart."); }
                Set<String> existing = new LinkedHashSet<>(getPreferences(MODE_PRIVATE).getStringSet(SAVED_FOLDERS, new LinkedHashSet<>()));
                existing.add(tree.toString());
                getPreferences(MODE_PRIVATE).edit().putStringSet(SAVED_FOLDERS, existing).apply();
                importFolder(tree);
            }
            return;
        }
        if (requestCode != REQUEST_OPEN_FILES) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getData() != null) uris.add(data.getData());
        ClipData clip = data.getClipData();
        if (clip != null) for (int i=0; i<clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        int ok = 0;
        Set<String> saved = new LinkedHashSet<>(getPreferences(MODE_PRIVATE).getStringSet(SAVED_URIS, new LinkedHashSet<>()));
        for (Uri uri : uris) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) { /* Session grant still works if provider is nonpersistable. */ }
            Song picked = songFromDocument(uri);
            if (picked == null) continue;
            boolean already = false;
            for (Song other : pickedSongs) if (other.uri.equals(uri)) { already = true; break; }
            if (already) continue;
            pickedSongs.add(picked);
            allSongs.add(picked);
            saved.add(uri.toString());
            ok++;
        }
        getPreferences(MODE_PRIVATE).edit().putStringSet(SAVED_URIS, saved).apply();
        filterSongs("");
        filterSongs(filterBox == null ? "" : filterBox.getText().toString());
        if (ok > 0) status.setText(ok + " muziekbestand(en) toegevoegd uit je gekozen map. Tik op het nummer om af te spelen.");
        else error("Geen bruikbare audiobestanden geselecteerd. Kies MP3, FLAC, WAV, M4A of AAC.");
    }

    /** A system document URI is readable without broad storage permissions. */
    private Song songFromDocument(Uri uri) {
        String name = "Audio";
        long size = -1;
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int b = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0 && !c.isNull(n)) name = c.getString(n);
                if (b >= 0 && !c.isNull(b)) size = c.getLong(b);
            }
        } catch (Exception ignored) { }
        String mime = getContentResolver().getType(uri);
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0) ext = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        switch (ext) {
            case "mp3": mime = "audio/mpeg"; break;
            case "flac": mime = "audio/flac"; break;
            case "wav": mime = "audio/wav"; break;
            case "m4a": mime = "audio/mp4"; break;
            case "aac": mime = "audio/aac"; break;
            case "ogg": mime = "audio/ogg"; break;
            case "opus": mime = "audio/opus"; break;
        }
        if (mime == null || (!mime.startsWith("audio/") && !"application/ogg".equals(mime))) return null;
        long durationMs = -1;
        try {
            MediaMetadataRetriever metadata = new MediaMetadataRetriever();
            try {
                metadata.setDataSource(this, uri);
                String duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (duration != null) durationMs = Long.parseLong(duration);
            } finally { metadata.release(); }
        } catch (Exception ignored) { }
        if (size <= 0) {
            try (android.os.ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r")) {
                if (fd != null) size = fd.getStatSize();
            } catch (Exception ignored) { }
        }
        if (size <= 0) return null;
        return new Song(nextExternalId++, name, "", mime, uri, size, durationMs);
    }

    private void restorePickedFiles() {
        Set<String> urls = getPreferences(MODE_PRIVATE).getStringSet(SAVED_URIS, new LinkedHashSet<>());
        Set<String> valid = new LinkedHashSet<>();
        for (String text : urls) {
            try {
                Uri uri = Uri.parse(text);
                Song item = songFromDocument(uri);
                if (item == null) continue;
                pickedSongs.add(item);
                allSongs.add(item);
                valid.add(text);
            } catch (Exception ignored) { }
        }
        if (valid.size() != urls.size()) getPreferences(MODE_PRIVATE).edit().putStringSet(SAVED_URIS, valid).apply();
        filterSongs("");
        filterSongs(filterBox == null ? "" : filterBox.getText().toString());
    }


    /** Android's system folder picker grants access without requesting broad filesystem permissions. */
    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, REQUEST_OPEN_FOLDER); }
        catch (ActivityNotFoundException e) { error("Geen geschikte Android-mappenbrowser gevonden."); }
    }

    private void restoreFolders() {
        Set<String> roots = getPreferences(MODE_PRIVATE).getStringSet(SAVED_FOLDERS, new LinkedHashSet<>());
        for (String root : roots) importFolder(Uri.parse(root));
    }

    private void importFolder(Uri tree) {
        status.setText("Muziek in map en submappen zoeken …");
        background.execute(() -> {
            List<Song> discovered = new ArrayList<>();
            try {
                List<Uri> files = FolderScanner.listAudio(getContentResolver(), tree, 2500);
                for (Uri file : files) {
                    Song song = songFromDocument(file);
                    if (song != null) discovered.add(song);
                }
                runOnUiThread(() -> {
                    Set<String> known = new LinkedHashSet<>();
                    for (Song song : allSongs) known.add(song.uri.toString());
                    int added = 0;
                    for (Song song : discovered) {
                        if (known.add(song.uri.toString())) {
                            pickedSongs.add(song);
                            allSongs.add(song);
                            added++;
                        }
                    }
                    filterSongs(filterBox == null ? "" : filterBox.getText().toString());
                    status.setText(added + " nieuwe nummers uit map toegevoegd (ook submappen).");
                });
            } catch (Exception ex) {
                runOnUiThread(() -> error("Map kon niet gelezen worden: " + ex.getMessage()));
            }
        });
    }

    private void restorePlaylists() {
        try {
            String raw = getPreferences(MODE_PRIVATE).getString(SAVED_PLAYLISTS, "{}");
            JSONObject root = new JSONObject(raw);
            java.util.Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                JSONArray array = root.optJSONArray(name);
                LinkedHashSet<String> tracks = new LinkedHashSet<>();
                if (array != null) for (int i = 0; i < array.length(); i++) tracks.add(array.optString(i));
                playlists.put(name, tracks);
            }
        } catch (Exception ignored) { }
    }

    private void savePlaylists() {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, LinkedHashSet<String>> e : playlists.entrySet()) {
                JSONArray songs = new JSONArray();
                for (String value : e.getValue()) songs.put(value);
                root.put(e.getKey(), songs);
            }
            getPreferences(MODE_PRIVATE).edit().putString(SAVED_PLAYLISTS, root.toString()).apply();
        } catch (Exception e) { error("Afspeellijsten opslaan mislukt."); }
    }

    private void choosePlaylist() {
        List<String> names = new ArrayList<>();
        names.add("Alle muziek");
        names.addAll(playlists.keySet());
        names.add("Lijst verwijderen…");
        new AlertDialog.Builder(this).setTitle("Kies een afspeellijst")
            .setItems(names.toArray(new String[0]), (dialog, choice) -> {
                if (choice == names.size() - 1) { deletePlaylist(); return; }
                activePlaylist = choice == 0 ? "" : names.get(choice);
                filterSongs(filterBox.getText().toString());
            }).show();
    }

    private void createPlaylist() {
        EditText nameBox = new EditText(this);
        nameBox.setSingleLine(true);
        nameBox.setHint("Bijvoorbeeld: Feestmuziek");
        new AlertDialog.Builder(this).setTitle("Nieuwe afspeellijst")
            .setView(nameBox).setNegativeButton("Annuleren", null)
            .setPositiveButton("Maken", (dialog, which) -> {
                String name = nameBox.getText().toString().trim();
                if (name.isEmpty() || name.length() > 70) { error("Geef een geldige naam op."); return; }
                if (playlists.containsKey(name)) { error("Deze afspeellijst bestaat al."); return; }
                playlists.put(name, new LinkedHashSet<>());
                activePlaylist = name;
                savePlaylists();
                filterSongs("");
                filterBox.setText("");
                status.setText("Afspeellijst '" + name + "' aangemaakt. Voeg nummers toe.");
            }).show();
    }

    private void addSongsToPlaylist() {
        if (playlists.isEmpty()) { createPlaylist(); return; }
        List<String> names = new ArrayList<>(playlists.keySet());
        String target = playlists.containsKey(activePlaylist) ? activePlaylist : null;
        if (target == null) {
            new AlertDialog.Builder(this).setTitle("Kies de afspeellijst")
                .setItems(names.toArray(new String[0]), (d, index) -> selectTracksForPlaylist(names.get(index)))
                .show();
        } else selectTracksForPlaylist(target);
    }

    private void selectTracksForPlaylist(String name) {
        if (allSongs.isEmpty()) { error("Voeg eerst muziekbestanden of een map toe."); return; }
        // Limit the dialog for responsiveness on large phone libraries.
        int count = Math.min(500, allSongs.size());
        CharSequence[] titles = new CharSequence[count];
        boolean[] checked = new boolean[count];
        LinkedHashSet<String> set = playlists.get(name);
        for (int i=0;i<count;i++) {
            Song track = allSongs.get(i);
            titles[i] = track.toString();
            checked[i] = set.contains(track.uri.toString());
        }
        new AlertDialog.Builder(this).setTitle("Nummers voor '" + name + "'")
            .setMultiChoiceItems(titles, checked, (dialog, index, isChecked) -> checked[index] = isChecked)
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("Bewaren", (d, which) -> {
                for (int i=0;i<count;i++) {
                    String uri = allSongs.get(i).uri.toString();
                    if (checked[i]) set.add(uri); else set.remove(uri);
                }
                savePlaylists();
                activePlaylist = name;
                filterSongs(filterBox.getText().toString());
                status.setText("Afspeellijst '" + name + "' bijgewerkt (" + set.size() + " nummers).");
            }).show();
    }

    private void deletePlaylist() {
        if (playlists.isEmpty()) return;
        List<String> names = new ArrayList<>(playlists.keySet());
        new AlertDialog.Builder(this).setTitle("Verwijder afspeellijst")
            .setItems(names.toArray(new String[0]), (d, index) -> {
                String name = names.get(index);
                new AlertDialog.Builder(this).setMessage("'" + name + "' verwijderen? Muziekbestanden blijven bewaard.")
                    .setNegativeButton("Annuleren", null)
                    .setPositiveButton("Verwijderen", (d2, which) -> {
                        playlists.remove(name);
                        if (activePlaylist.equals(name)) activePlaylist = "";
                        savePlaylists(); filterSongs(filterBox.getText().toString());
                    }).show();
            }).show();
    }

    private void reloadLibrary() {
        background.execute(() -> {
            try {
                List<Song> tracks = MusicLibrary.load(getContentResolver());
                runOnUiThread(() -> {
                    allSongs.clear();
                    allSongs.addAll(tracks);
                    allSongs.addAll(pickedSongs);
                    filterSongs("");
                    filterSongs(filterBox == null ? "" : filterBox.getText().toString());
                    if (tracks.isEmpty()) status.setText("Geen muziek gevonden. Kopieer eerst MP3-bestanden naar je gsm.");
                });
            } catch (Exception e) { runOnUiThread(() -> error("Muziek lezen mislukt: " + e.getMessage())); }
        });
    }
    private void filterSongs(String search) {
        String q = search.toLowerCase(java.util.Locale.ROOT).trim();
        shownSongs.clear();
        Set<String> allowed = playlists.get(activePlaylist);
        List<Song> ordering = new ArrayList<>();
        if (allowed == null) ordering.addAll(allSongs);
        else {
            for (String uri : allowed) {
                for (Song song : allSongs) {
                    if (song.uri.toString().equals(uri)) { ordering.add(song); break; }
                }
            }
        }
        for (Song song : ordering) {
            if (song.title.toLowerCase(java.util.Locale.ROOT).contains(q) ||
                (song.artist != null && song.artist.toLowerCase(java.util.Locale.ROOT).contains(q))) shownSongs.add(song);
        }
        if (songAdapter != null) songAdapter.notifyDataSetChanged();
        if (libraryLabel != null) {
            String suffix = activePlaylist.isEmpty() ? "ALLE MUZIEK" : "AFSPEELLIJST: " + activePlaylist;
            libraryLabel.setText(suffix + " · " + shownSongs.size() + " nummers");
        }
    }

    private void playSong(Song song) {
        if (selectedSpeaker() == null) return;
        currentQueue.clear();
        int start = shownSongs.indexOf(song);
        if (start < 0) start = 0;
        if (autoNext) {
            for (int i = start; i < shownSongs.size() && currentQueue.size() < MAX_QUEUE_TRACKS; i++)
                currentQueue.add(shownSongs.get(i));
        }
        if (currentQueue.isEmpty()) currentQueue.add(song);
        queuePlayback = autoNext && currentQueue.size() > 1;
        if (!pickedSongs.contains(song) && !hasAudioPermission()) { requestAudioPermission(); return; }
        if (musicService == null || !musicService.isReady()) {
            pendingSong = song;
            status.setText("Lokale muziekdeling wordt gestart …");
            Intent intent = new Intent(this, MusicServerService.class);
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
                else startService(intent);
                startedByUs = true;
                if (!bound) bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
            } catch (Exception e) { error("Muziekserver kon niet starten: " + e.getMessage()); }
            return;
        }
        sendTrack(song);
    }

    private void sendTrack(Song song) {
        SonosDevice speaker = selectedSpeaker();
        if (speaker == null) return;
        currentlyPlaying = song;
        latestDurationSec = song.durationMs > 0 ? (int) (song.durationMs / 1000) : -1;
        latestPositionSec = 0;
        playbackActive = false;
        showPosition(0, latestDurationSec);
        playingLabel.setText("▶  " + song.title + "  →  " + speaker.name);
        status.setText("Nummer naar Sonos sturen …");
        background.execute(() -> {
            try {
                List<Song> batch = new ArrayList<>(currentQueue);
                for (Song item : batch) musicService.registerSong(item);
                String url = musicService.urlFor(song, speaker.ip);
                boolean useQueue = queuePlayback && speaker.uid.startsWith("uuid:RINCON_");
                if (useQueue) SonosApi.startQueue(speaker.ip, speaker.uid, url, song);
                else SonosApi.playTrack(speaker.ip, url, song);
                runOnUiThread(() -> {
                    saveSpeaker(speaker.ip);
                    playbackActive = true;
                    measuredAtElapsedMs = SystemClock.elapsedRealtime();
                    status.setText(useQueue ? "Sonos-wachtrij: 1 / " + batch.size() + " (wordt aangevuld)" :
                        "Speelt via Sonos · houd je telefoon op wifi");
                });
                if (useQueue) {
                    int added = 1;
                    for (int i = 1; i < batch.size(); i++) {
                        Song next = batch.get(i);
                        try {
                            SonosApi.addToQueue(speaker.ip, musicService.urlFor(next, speaker.ip), next);
                            added++;
                        } catch (Exception ex) {
                            final String title = next.title;
                            runOnUiThread(() -> status.setText("Wachtrij: nummer overgeslagen: " + title));
                        }
                    }
                    final int finalAdded = added;
                    runOnUiThread(() -> status.setText("Automatisch doorspelen · " + finalAdded +
                        " nummers in Sonos-wachtrij · houd wifi en muziekdeling aan"));
                }
            } catch (Exception e) { runOnUiThread(() -> error("Afspelen mislukt: " + e.getMessage())); }
        });
    }

    private void changeSong(int delta) {
        if (shownSongs.isEmpty()) { error("Er staan geen nummers in deze lijst."); return; }
        int index = currentlyPlaying == null ? -1 : shownSongs.indexOf(currentlyPlaying);
        int next = Math.max(0, Math.min(shownSongs.size() - 1, index + delta));
        playSong(shownSongs.get(next));
    }

    private interface SonosCommand { void run(String ip) throws Exception; }
    private void control(String label, SonosCommand command) {
        SonosDevice speaker = selectedSpeaker();
        if (speaker == null) return;
        background.execute(() -> {
            try {
                command.run(speaker.ip);
                runOnUiThread(() -> {
                    latestPositionSec = displayedSeconds();
                    measuredAtElapsedMs = SystemClock.elapsedRealtime();
                    if ("Afspelen".equals(label)) playbackActive = true;
                    if ("Pauze".equals(label)) playbackActive = false;
                    if ("Stop".equals(label)) { playbackActive = false; latestPositionSec = 0; }
                    redrawProgressClock();
                    status.setText(label + " → " + speaker.name);
                });
            } catch (Exception e) { runOnUiThread(() -> error(label + " mislukt: " + e.getMessage())); }
        });
    }

    private void stopSharing() {
        pendingSong = null;
        playbackActive = false;
        queuePlayback = false;
        currentQueue.clear();
        currentlyPlaying = null;
        if (bound) {
            unbindService(serviceConnection);
            bound = false;
        }
        musicService = null;
        stopService(new Intent(this, MusicServerService.class));
        startedByUs = false;
        status.setText("Muziekdeling gestopt. Sonos kan bestanden niet langer ophalen.");
        playingLabel.setText("Tik op een liedje om te beginnen");
    }

    private static GradientDrawable round(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusDp * 3f);
        return drawable;
    }
    private int dp(float size) { return (int)(size * getResources().getDisplayMetrics().density + .5f); }
    private TextView label(String content, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(content); view.setTextSize(sp); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }
    private Button button(String content) {
        Button b = new Button(this);
        b.setText(content);
        b.setTextSize(12);
        b.setAllCaps(false);
        return b;
    }
    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }
    private void margins(View view, int top, int left, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(left),dp(top),0,dp(bottom));
        view.setLayoutParams(p);
    }
    private void error(String message) {
        status.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
    @Override protected void onResume() {
        super.onResume();
        visible = true;
        positionHandler.removeCallbacks(positionPoll);
        positionHandler.removeCallbacks(clockTick);
        positionHandler.postDelayed(positionPoll, 1400);
        positionHandler.post(clockTick);
    }
    @Override protected void onPause() {
        visible = false;
        positionHandler.removeCallbacks(positionPoll);
        positionHandler.removeCallbacks(clockTick);
        super.onPause();    }
    @Override public void onDestroy() {
        if (bound) unbindService(serviceConnection);
        background.shutdownNow();
        super.onDestroy();
    }
}