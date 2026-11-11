package com.simon.tinnitusrelief;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MusicIndexManager {
    private static final String TAG = "MusicIndexManager";
    public static final String PREF_NAME = "tinnitus_prefs";
    public static final String KEY_FOLDER_URI = "music_folder_uri";
    public static final String KEY_FOLDER_NAME = "music_folder_name";
    public static final String CACHE_FILE_NAME = "music_library.json";

    private static MusicIndexManager instance;
    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile boolean isScanning = false;
    private volatile int currentTrackCount = 0;
    private volatile String currentStatus = "";
    private volatile String activeFolderName = "";
    private volatile String cachedLibraryJson = null;

    private PowerManager.WakeLock wakeLock;

    public interface ScanListener {
        void onProgress(String status, int count, boolean isScanning);
        void onCompleted(String folderName, String json, int totalTracks, int totalArtists);
        void onError(String error);
    }

    private final List<ScanListener> listeners = new CopyOnWriteArrayList<>();

    public static synchronized MusicIndexManager getInstance(Context context) {
        if (instance == null) {
            instance = new MusicIndexManager(context.getApplicationContext());
        }
        return instance;
    }

    private MusicIndexManager(Context context) {
        this.appContext = context;
        SharedPreferences sp = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.activeFolderName = sp.getString(KEY_FOLDER_NAME, "");
        loadCacheFromDisk();
    }

    public synchronized void addListener(ScanListener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
            // Immediately sync current state with new listener
            if (isScanning) {
                l.onProgress(currentStatus, currentTrackCount, true);
            } else if (cachedLibraryJson != null && !cachedLibraryJson.equals("[]")) {
                l.onCompleted(activeFolderName, cachedLibraryJson, currentTrackCount, 0);
            }
        }
    }

    public synchronized void removeListener(ScanListener l) {
        listeners.remove(l);
    }

    public boolean isScanning() {
        return isScanning;
    }

    public int getCurrentTrackCount() {
        return currentTrackCount;
    }

    public String getCurrentStatus() {
        return currentStatus;
    }

    public String getActiveFolderName() {
        return activeFolderName;
    }

    public String getCachedLibraryJson() {
        if (cachedLibraryJson == null) {
            loadCacheFromDisk();
        }
        return cachedLibraryJson != null ? cachedLibraryJson : "[]";
    }

    public synchronized void startScan(Uri treeUri, String folderName) {
        this.activeFolderName = folderName != null ? folderName : "Music Folder";

        // Persist selection immediately
        SharedPreferences sp = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit()
                .putString(KEY_FOLDER_URI, treeUri.toString())
                .putString(KEY_FOLDER_NAME, activeFolderName)
                .apply();

        if (isScanning) {
            Log.d(TAG, "Scan already in progress for folder: " + activeFolderName);
            dispatchProgress(currentStatus, currentTrackCount, true);
            return;
        }

        isScanning = true;
        currentTrackCount = 0;
        currentStatus = "Scanning " + activeFolderName + "...";
        dispatchProgress(currentStatus, 0, true);

        // Start Foreground Service so Android does not kill process when switching apps
        try {
            Intent serviceIntent = new Intent(appContext, MusicIndexService.class);
            serviceIntent.putExtra(MusicIndexService.EXTRA_FOLDER_NAME, activeFolderName);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(serviceIntent);
            } else {
                appContext.startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not start MusicIndexService as foreground service", e);
        }

        acquireWakeLock();

        executor.execute(() -> {
            List<TrackInfo> trackList = new ArrayList<>();
            try {
                String treeDocId = DocumentsContract.getTreeDocumentId(treeUri);
                Uri rootChildrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId);
                crawlDocuments(treeUri, rootChildrenUri, trackList);
            } catch (Exception e) {
                Log.e(TAG, "Error crawling documents tree", e);
            }

            // Organize by Artist -> Album -> Tracks (Poweramp tagging model)
            Map<String, Map<String, List<TrackInfo>>> artistMap = new LinkedHashMap<>();
            for (TrackInfo t : trackList) {
                String artist = t.artist.trim();
                String album = t.album.trim();
                artistMap.computeIfAbsent(artist, k -> new LinkedHashMap<>())
                        .computeIfAbsent(album, k -> new ArrayList<>())
                        .add(t);
            }

            // Convert to JSON
            JSONArray artistsArr = new JSONArray();
            try {
                List<String> sortedArtists = new ArrayList<>(artistMap.keySet());
                Collections.sort(sortedArtists, String.CASE_INSENSITIVE_ORDER);

                for (String art : sortedArtists) {
                    JSONObject artObj = new JSONObject();
                    artObj.put("artist", art);

                    Map<String, List<TrackInfo>> albumMap = artistMap.get(art);
                    JSONArray albumsArr = new JSONArray();

                    List<String> sortedAlbums = new ArrayList<>(albumMap.keySet());
                    Collections.sort(sortedAlbums, String.CASE_INSENSITIVE_ORDER);

                    for (String alb : sortedAlbums) {
                        JSONObject albObj = new JSONObject();
                        albObj.put("album", alb);

                        List<TrackInfo> tracks = albumMap.get(alb);
                        Collections.sort(tracks, Comparator.comparingInt(a -> a.trackNumber));

                        JSONArray tracksArr = new JSONArray();
                        for (TrackInfo trk : tracks) {
                            JSONObject trkObj = new JSONObject();
                            trkObj.put("id", trk.uriStr);
                            trkObj.put("title", trk.title);
                            trkObj.put("artist", trk.artist);
                            trkObj.put("album", trk.album);
                            trkObj.put("track", trk.trackNumber);
                            trkObj.put("duration", trk.durationSeconds);
                            tracksArr.put(trkObj);
                        }
                        albObj.put("tracks", tracksArr);
                        albumsArr.put(albObj);
                    }
                    artObj.put("albums", albumsArr);
                    artistsArr.put(artObj);
                }

                String jsonResult = artistsArr.toString();
                cachedLibraryJson = jsonResult;
                currentTrackCount = trackList.size();

                // Save to internal storage cache
                File cacheFile = new File(appContext.getFilesDir(), CACHE_FILE_NAME);
                FileOutputStream fos = new FileOutputStream(cacheFile);
                fos.write(jsonResult.getBytes(StandardCharsets.UTF_8));
                fos.close();

                isScanning = false;
                currentStatus = "Indexed " + trackList.size() + " tracks across " + artistMap.size() + " artists.";

                dispatchProgress(currentStatus, trackList.size(), false);
                dispatchCompleted(activeFolderName, jsonResult, trackList.size(), artistMap.size());

            } catch (Exception e) {
                isScanning = false;
                Log.e(TAG, "Error serializing music library", e);
                currentStatus = "Error scanning folder: " + e.getMessage();
                dispatchError(currentStatus);
            } finally {
                releaseWakeLock();
                // Stop service
                try {
                    Intent stopIntent = new Intent(appContext, MusicIndexService.class);
                    appContext.stopService(stopIntent);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void crawlDocuments(Uri treeUri, Uri dirChildrenUri, List<TrackInfo> outTracks) {
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };

        Cursor cursor = null;
        try {
            cursor = appContext.getContentResolver().query(dirChildrenUri, projection, null, null, null);
            if (cursor == null) return;

            while (cursor.moveToNext()) {
                String docId = cursor.getString(0);
                String displayName = cursor.getString(1);
                String mimeType = cursor.getString(2);

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    Uri subDirUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
                    crawlDocuments(treeUri, subDirUri, outTracks);
                } else if (isAudioFile(displayName, mimeType)) {
                    Uri fileDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                    TrackInfo ti = readAudioTags(fileDocUri, displayName);
                    if (ti != null) {
                        outTracks.add(ti);
                        currentTrackCount = outTracks.size();
                        if (outTracks.size() % 5 == 0) {
                            currentStatus = "Indexing tags: " + outTracks.size() + " tracks...";
                            dispatchProgress(currentStatus, outTracks.size(), true);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error in crawlDocuments", e);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private boolean isAudioFile(String name, String mime) {
        if (mime != null && mime.startsWith("audio/")) return true;
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".m4a")
                || lower.endsWith(".ogg") || lower.endsWith(".wav") || lower.endsWith(".aac")
                || lower.endsWith(".opus") || lower.endsWith(".wma");
    }

    private TrackInfo readAudioTags(Uri docUri, String displayName) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        ParcelFileDescriptor pfd = null;
        String fallbackTitle = displayName;
        int dot = fallbackTitle.lastIndexOf('.');
        if (dot > 0) fallbackTitle = fallbackTitle.substring(0, dot);

        try {
            pfd = appContext.getContentResolver().openFileDescriptor(docUri, "r");
            if (pfd == null) {
                return new TrackInfo(docUri.toString(), fallbackTitle, "Unknown Artist", "Unknown Album", 0, 0);
            }

            mmr.setDataSource(pfd.getFileDescriptor());

            String artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (artist == null || artist.trim().isEmpty()) {
                artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
            }
            if (artist == null || artist.trim().isEmpty()) {
                artist = "Unknown Artist";
            }

            String album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            if (album == null || album.trim().isEmpty()) {
                album = "Unknown Album";
            }

            String title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            if (title == null || title.trim().isEmpty()) {
                title = fallbackTitle;
            }

            int trackNumber = 0;
            String trackStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER);
            if (trackStr != null) {
                try {
                    int slash = trackStr.indexOf('/');
                    if (slash > 0) trackStr = trackStr.substring(0, slash);
                    trackNumber = Integer.parseInt(trackStr.trim());
                } catch (Exception ignored) {
                }
            }

            int durationSec = 0;
            String durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durStr != null) {
                try {
                    durationSec = (int) (Long.parseLong(durStr) / 1000);
                } catch (Exception ignored) {
                }
            }

            return new TrackInfo(docUri.toString(), title, artist, album, trackNumber, durationSec);
        } catch (Exception e) {
            return new TrackInfo(docUri.toString(), fallbackTitle, "Unknown Artist", "Unknown Album", 0, 0);
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void loadCacheFromDisk() {
        try {
            File cacheFile = new File(appContext.getFilesDir(), CACHE_FILE_NAME);
            if (cacheFile.exists()) {
                FileInputStream fis = new FileInputStream(cacheFile);
                byte[] bytes = new byte[(int) cacheFile.length()];
                int read = fis.read(bytes);
                fis.close();
                if (read > 0) {
                    cachedLibraryJson = new String(bytes, StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading cached music library from disk", e);
        }
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TinnitusRelief:MusicIndexWakeLock");
                wakeLock.setReferenceCounted(false);
            }
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(30 * 60 * 1000L); // Max 30 minutes safe scan duration
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void dispatchProgress(String status, int count, boolean scanning) {
        for (ScanListener l : listeners) {
            try {
                l.onProgress(status, count, scanning);
            } catch (Exception e) {
                Log.w(TAG, "Error dispatching progress", e);
            }
        }
    }

    private void dispatchCompleted(String folder, String json, int tracks, int artists) {
        for (ScanListener l : listeners) {
            try {
                l.onCompleted(folder, json, tracks, artists);
            } catch (Exception e) {
                Log.w(TAG, "Error dispatching completed", e);
            }
        }
    }

    private void dispatchError(String err) {
        for (ScanListener l : listeners) {
            try {
                l.onError(err);
            } catch (Exception e) {
                Log.w(TAG, "Error dispatching error", e);
            }
        }
    }

    public static class TrackInfo {
        final String uriStr;
        final String title;
        final String artist;
        final String album;
        final int trackNumber;
        final int durationSeconds;

        TrackInfo(String uriStr, String title, String artist, String album, int trackNumber, int durationSeconds) {
            this.uriStr = uriStr;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.trackNumber = trackNumber;
            this.durationSeconds = durationSeconds;
        }
    }
}

