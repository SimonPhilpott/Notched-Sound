package com.simon.tinnitusrelief;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Log;
import android.util.LruCache;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_CODE_OPEN_FOLDER = 1001;
    private static final int REQUEST_CODE_RECORD_AUDIO = 1002;

    private WebView webView;
    private MediaSession mediaSession;
    private String mediaMetadataSignature;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean resumeOnFocusGain = false;
    private boolean playbackDesired = false;
    private MusicIndexManager indexManager;
    private MusicIndexManager.ScanListener scanListener;
    private PermissionRequest pendingWebPermissionRequest;
    private boolean batteryPromptShownThisLaunch = false;
    private long lastBackPressMs = 0L;
    private static final long BACK_EXIT_WINDOW_MS = 2000L;
    private boolean isPlaybackActive = false;
    private boolean playbackServiceRunning = false;
    private String lastServiceSignature = "";
    private BroadcastReceiver mediaVolumeReceiver;
    private String lastNotificationTitle = "Notched Sound Therapy";
    private String lastNotificationSubtitle = "Playing in background";
    private final LruCache<String, byte[]> albumArtCache = new LruCache<String, byte[]>(2 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, byte[] value) {
            return value == null ? 0 : value.length;
        }
    };

    @SuppressLint({"SetJavaScriptEnabled"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        indexManager = MusicIndexManager.getInstance(this);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        boolean initiallyDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        applyNativeTheme(initiallyDark);
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

        // WebChromeClient with audio capture permission delegation
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    String[] resources = request.getResources();
                    boolean hasAudio = false;
                    for (String res : resources) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) {
                            hasAudio = true;
                            break;
                        }
                    }
                    if (hasAudio) {
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            request.grant(resources);
                        } else {
                            pendingWebPermissionRequest = request;
                            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_CODE_RECORD_AUDIO);
                        }
                    } else {
                        request.grant(resources);
                    }
                });
            }
        });

        // Stream audio from ContentResolver via custom host "tinnitus.local"
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                view.postDelayed(() -> maybePromptBatteryOptimization(false), 900);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri reqUri = request.getUrl();
                if (reqUri != null && "tinnitus.local".equalsIgnoreCase(reqUri.getHost())) {
                    if ("/album_art".equalsIgnoreCase(reqUri.getPath())) {
                        String docUriStr = reqUri.getQueryParameter("uri");
                        byte[] art = loadAlbumArtThumbnail(docUriStr, 112);
                        Map<String, String> headers = new HashMap<>();
                        headers.put("Access-Control-Allow-Origin", "*");
                        if (art == null) {
                            headers.put("Cache-Control", "no-store");
                            return new WebResourceResponse(
                                    "image/jpeg",
                                    null,
                                    404,
                                    "Not Found",
                                    headers,
                                    new ByteArrayInputStream(new byte[0])
                            );
                        }
                        headers.put("Cache-Control", "public, max-age=86400");
                        headers.put("Content-Length", String.valueOf(art.length));
                        return new WebResourceResponse(
                                "image/jpeg",
                                null,
                                200,
                                "OK",
                                headers,
                                new ByteArrayInputStream(art)
                        );
                    }
                    if ("/music_stream".equalsIgnoreCase(reqUri.getPath())) {
                        String docUriStr = reqUri.getQueryParameter("uri");
                        if (docUriStr != null) {
                            try {
                                Uri docUri = Uri.parse(docUriStr);
                                InputStream is = getContentResolver().openInputStream(docUri);
                                if (is == null) {
                                    throw new IllegalStateException("Content resolver returned no audio stream");
                                }
                                String mime = resolveAudioMimeType(docUri);
                                long contentLength = resolveContentLength(docUri);

                                Map<String, String> headers = new HashMap<>();
                                headers.put("Access-Control-Allow-Origin", "*");
                                headers.put("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
                                headers.put("Access-Control-Allow-Headers", "Range");
                                headers.put("Cache-Control", "no-store");
                                headers.put("Accept-Ranges", "none");
                                if (contentLength > 0) {
                                    headers.put("Content-Length", String.valueOf(contentLength));
                                }

                                return new WebResourceResponse(mime, null, 200, "OK", headers, is);
                            } catch (Exception e) {
                                Log.e(TAG, "Error streaming local music: " + docUriStr, e);
                            }
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }
        });

        // Background scan listener to broadcast progress to WebView
        scanListener = new MusicIndexManager.ScanListener() {
            @Override
            public void onProgress(String status, int count, boolean isScanning) {
                runOnUiThread(() -> {
                    if (webView != null) {
                        String safe = (status != null ? status : "").replace("'", "\\'");
                        webView.evaluateJavascript("if(typeof onMusicScanProgress==='function') onMusicScanProgress('" + safe + "', " + count + ", " + isScanning + ");", null);
                    }
                });
            }

            @Override
            public void onCompleted(String folderName, String json, int totalTracks, int totalArtists) {
                runOnUiThread(() -> {
                    if (webView != null) {
                        String safe = (folderName != null ? folderName : "").replace("'", "\\'");
                        webView.evaluateJavascript("if(typeof onMusicLibraryReady==='function') onMusicLibraryReady('" + safe + "', true);", null);
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    if (webView != null) {
                        String safe = (error != null ? error : "").replace("'", "\\'");
                        webView.evaluateJavascript("if(typeof onMusicScanError==='function') onMusicScanError('" + safe + "');", null);
                    }
                });
            }
        };
        indexManager.addListener(scanListener);

        // Native Android Bridge for JavaScript
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void log(String msg) {
                Log.d("WebTinnitus", msg != null ? msg : "");
            }

            @JavascriptInterface
            public void setTherapyActive(boolean active) {
                runOnUiThread(() -> {
                    playbackDesired = active;
                    if (active) {
                        if (requestAppAudioFocus()) {
                            isPlaybackActive = true;
                            resumeOnFocusGain = false;
                            updateWakeLock(true);
                        } else {
                            dispatchMediaCommand("focuspause", -1L);
                        }
                    } else {
                        isPlaybackActive = false;
                        resumeOnFocusGain = false;
                        updateWakeLock(false);
                        abandonAppAudioFocus();
                    }
                });
            }

            @JavascriptInterface
            public void updateMediaSession(boolean playing, boolean hasMedia, String title,
                                           String artist, String album,
                                           long durationMs, long positionMs) {
                runOnUiThread(() -> applyMediaSessionState(
                        playing, hasMedia, title, artist, album, durationMs, positionMs));
            }

            @JavascriptInterface
            public void selectMusicFolder() {
                runOnUiThread(() -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    startActivityForResult(intent, REQUEST_CODE_OPEN_FOLDER);
                });
            }

            @JavascriptInterface
            public void rescanMusicFolder() {
                runOnUiThread(() -> {
                    SharedPreferences sp = getSharedPreferences(MusicIndexManager.PREF_NAME, Context.MODE_PRIVATE);
                    String savedUri = sp.getString(MusicIndexManager.KEY_FOLDER_URI, null);
                    String savedName = sp.getString(MusicIndexManager.KEY_FOLDER_NAME, "Music Folder");
                    if (savedUri != null) {
                        indexManager.startScan(Uri.parse(savedUri), savedName);
                    } else {
                        selectMusicFolder();
                    }
                });
            }

            @JavascriptInterface
            public void checkSavedMusic() {
                runOnUiThread(() -> {
                    if (indexManager.isScanning()) {
                        String safeFolder = indexManager.getActiveFolderName().replace("'", "\\'");
                        String safeStatus = indexManager.getCurrentStatus().replace("'", "\\'");
                        int count = indexManager.getCurrentTrackCount();
                        webView.evaluateJavascript("if(typeof onMusicFolderSelected==='function') onMusicFolderSelected('" + safeFolder + "');", null);
                        webView.evaluateJavascript("if(typeof onMusicScanProgress==='function') onMusicScanProgress('" + safeStatus + "', " + count + ", true);", null);
                        return;
                    }

                    String json = indexManager.getCachedLibraryJson();
                    if (json != null && !json.equals("[]")) {
                        String safeFolder = indexManager.getActiveFolderName().replace("'", "\\'");
                        webView.evaluateJavascript("if(typeof onMusicLibraryReady==='function') onMusicLibraryReady('" + safeFolder + "', false);", null);
                    } else {
                        SharedPreferences sp = getSharedPreferences(MusicIndexManager.PREF_NAME, Context.MODE_PRIVATE);
                        String savedUri = sp.getString(MusicIndexManager.KEY_FOLDER_URI, null);
                        if (savedUri != null) {
                            indexManager.startScan(Uri.parse(savedUri), sp.getString(MusicIndexManager.KEY_FOLDER_NAME, "Music Folder"));
                        }
                    }
                });
            }

            @JavascriptInterface
            public String getMusicLibraryJson() {
                return indexManager.getCachedLibraryJson();
            }

            @JavascriptInterface
            public String getSavedFolderName() {
                return indexManager.getActiveFolderName();
            }

            @JavascriptInterface
            public boolean isBatteryOptimizationIgnored() {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            }

            @JavascriptInterface
            public void openBatteryOptimizationPrompt() {
                runOnUiThread(() -> maybePromptBatteryOptimization(true));
            }

            @JavascriptInterface
            public void setTheme(boolean dark) {
                runOnUiThread(() -> applyNativeTheme(dark));
            }

            @JavascriptInterface
            public boolean isScanInProgress() {
                return indexManager.isScanning();
            }

            @JavascriptInterface
            public int getIndexedTrackCount() {
                return indexManager.getCurrentTrackCount();
            }

            @JavascriptInterface
            public boolean hasMicrophonePermission() {
                return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            }

            @JavascriptInterface
            public void requestMicrophonePermission() {
                runOnUiThread(() -> {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_CODE_RECORD_AUDIO);
                    } else {
                        if (webView != null) {
                            webView.evaluateJavascript("if(typeof onMicrophonePermissionResult==='function') onMicrophonePermissionResult(true);", null);
                        }
                    }
                });
            }

            @JavascriptInterface
            public int getMediaVolume() {
                return getMediaVolumePercent();
            }

            @JavascriptInterface
            public void setMediaVolume(int percent) {
                setMediaVolumePercent(percent);
            }

            @JavascriptInterface
            public String getAppVersion() {
                return "1.0.7-pro-unlocked";
            }
        }, "AndroidBridge");

        setupAudioFocus();
        setupMediaSession();
        registerMediaVolumeReceiver();
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void setupAudioFocus() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;

        AudioManager.OnAudioFocusChangeListener listener = this::handleAudioFocusChange;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(listener)
                    .build();
        }
    }

    private boolean requestAppAudioFocus() {
        if (audioManager == null) return false;
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            result = audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            result = audioManager.requestAudioFocus(
                    this::handleAudioFocusChange,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
            );
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAppAudioFocus() {
        resumeOnFocusGain = false;
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(this::handleAudioFocusChange);
        }
    }

    private void handleAudioFocusChange(int focusChange) {
        runOnUiThread(() -> {
            Log.d(TAG, "Audio focus changed: " + focusChange + " isPlaybackActive=" + isPlaybackActive + " playbackDesired=" + playbackDesired);
            // Only process focus change events for ambient sound therapy.
            // HTML5 media elements in WebView handle their own audio focus autonomously.
            if (!playbackDesired) {
                return;
            }
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    // Permanent focus loss: user started another media app (Spotify, YouTube, etc.)
                    // Strictly STOP / PAUSE playback, abandon focus, and NEVER auto-resume!
                    resumeOnFocusGain = false;
                    isPlaybackActive = false;
                    playbackDesired = false;
                    updateWakeLock(false);
                    if (mediaSession != null) {
                        mediaSession.setActive(false);
                        mediaSession.setPlaybackState(buildPlaybackState(false, false, 0L));
                    }
                    abandonAppAudioFocus();
                    dispatchMediaCommand("focuspause", -1L);
                    break;

                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    // Transient loss: phone call, navigation prompt, or speech assistant
                    // Pause playback temporarily, but allow resumption when the call/prompt ends
                    if (isPlaybackActive) {
                        resumeOnFocusGain = true;
                    }
                    isPlaybackActive = false;
                    updateWakeLock(false);
                    if (mediaSession != null) {
                        mediaSession.setPlaybackState(buildPlaybackState(false, true, 0L));
                    }
                    dispatchMediaCommand("focuspause", -1L);
                    break;

                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    // Short notification or navigation chime - pause without fighting
                    if (isPlaybackActive) {
                        resumeOnFocusGain = true;
                    }
                    isPlaybackActive = false;
                    updateWakeLock(false);
                    dispatchMediaCommand("focuspause", -1L);
                    break;

                case AudioManager.AUDIOFOCUS_GAIN:
                    // Regained focus after transient call ended
                    if (resumeOnFocusGain) {
                        resumeOnFocusGain = false;
                        if (requestAppAudioFocus()) {
                            isPlaybackActive = true;
                            playbackDesired = true;
                            updateWakeLock(true);
                            if (mediaSession != null) {
                                mediaSession.setActive(true);
                                mediaSession.setPlaybackState(buildPlaybackState(true, true, 0L));
                            }
                            dispatchMediaCommand("play", -1L);
                        }
                    }
                    break;
            }
        });
    }

    private void updateWakeLock(boolean active) {
        isPlaybackActive = active;
        syncPlaybackService(active, lastNotificationTitle, lastNotificationSubtitle);
    }

    private void syncPlaybackService(boolean active, String title, String subtitle) {
        String safeTitle = cleanMediaText(title, "Notched Sound Therapy");
        String safeSubtitle = cleanMediaText(subtitle, "Playing in background");
        String signature = safeTitle + "\u0000" + safeSubtitle;

        if (active) {
            if (!playbackServiceRunning) {
                playbackServiceRunning = true;
                lastServiceSignature = signature;
                TherapyPlaybackService.startPlaybackService(this, safeTitle, safeSubtitle);
            } else if (!signature.equals(lastServiceSignature)) {
                lastServiceSignature = signature;
                TherapyPlaybackService.updatePlaybackService(this, safeTitle, safeSubtitle);
            }
        } else if (playbackServiceRunning) {
            playbackServiceRunning = false;
            lastServiceSignature = "";
            TherapyPlaybackService.stopPlaybackService(this);
        }
    }

    private void setupMediaSession() {
        mediaSession = new MediaSession(this, "NotchedSoundMediaSession");
        mediaSession.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                        | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        );

        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent sessionActivity = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        mediaSession.setSessionActivity(sessionActivity);

        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                isPlaybackActive = true;
                resumeOnFocusGain = false;
                updateWakeLock(true);
                dispatchMediaCommand("play", -1L);
            }

            @Override
            public void onPause() {
                isPlaybackActive = false;
                resumeOnFocusGain = false;
                updateWakeLock(false);
                abandonAppAudioFocus();
                dispatchMediaCommand("pause", -1L);
            }

            @Override
            public void onStop() {
                isPlaybackActive = false;
                resumeOnFocusGain = false;
                updateWakeLock(false);
                abandonAppAudioFocus();
                dispatchMediaCommand("stop", -1L);
            }

            @Override
            public void onSkipToNext() {
                dispatchMediaCommand("next", -1L);
            }

            @Override
            public void onSkipToPrevious() {
                dispatchMediaCommand("previous", -1L);
            }

            @Override
            public void onSeekTo(long positionMs) {
                dispatchMediaCommand("seek", Math.max(0L, positionMs));
            }

            @Override
            public void onFastForward() {
                dispatchMediaCommand("forward", -1L);
            }

            @Override
            public void onRewind() {
                dispatchMediaCommand("rewind", -1L);
            }
        });
        mediaSession.setPlaybackState(buildPlaybackState(false, false, 0L));
    }

    private void dispatchMediaCommand(String command, long positionMs) {
        runOnUiThread(() -> {
            if (webView == null) return;
            if ("play".equals(command) || "next".equals(command) || "previous".equals(command)) {
                webView.onResume();
                webView.resumeTimers();
            }
            String script = "if(typeof onNativeMediaCommand==='function')"
                    + " onNativeMediaCommand('" + command + "', " + positionMs + ");";
            webView.evaluateJavascript(script, null);
        });
    }

    private void applyMediaSessionState(boolean playing, boolean hasMedia, String title,
                                        String artist, String album,
                                        long durationMs, long positionMs) {
        if (mediaSession == null) return;

        boolean activeSession = hasMedia && (playing || isPlaybackActive || playbackDesired);
        if (mediaSession.isActive() != activeSession) {
            mediaSession.setActive(activeSession);
        }

        if (!activeSession) {
            mediaSession.setPlaybackState(buildPlaybackState(false, false, 0L));
            isPlaybackActive = false;
            syncPlaybackService(false, lastNotificationTitle, lastNotificationSubtitle);
            return;
        }

        String safeTitle = cleanMediaText(title, "Notched Sound");
        String safeArtist = cleanMediaText(artist, "Notched Sound");
        String safeAlbum = cleanMediaText(album, "Notched Sound");
        long safeDuration = Math.max(0L, durationMs);
        lastNotificationTitle = safeTitle;
        lastNotificationSubtitle = safeArtist;

        isPlaybackActive = playing;
        // If music is actively playing in WebView, ensure any native focus request from Java
        // is abandoned so it does not compete with WebView's internal Chromium media player.
        if (playing && !playbackDesired) {
            abandonAppAudioFocus();
        }
        syncPlaybackService(playing || playbackDesired, safeTitle, safeArtist);

        String signature = safeTitle + "\u0000" + safeArtist + "\u0000" + safeAlbum
                + "\u0000" + safeDuration;

        if (!signature.equals(mediaMetadataSignature)) {
            mediaMetadataSignature = signature;
            MediaMetadata metadata = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, safeTitle)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, safeArtist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, safeAlbum)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, safeDuration)
                    .build();
            mediaSession.setMetadata(metadata);
        }

        mediaSession.setPlaybackState(buildPlaybackState(playing, true, positionMs));
    }

    private PlaybackState buildPlaybackState(boolean playing, boolean hasMedia, long positionMs) {
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP;
        if (hasMedia) {
            actions |= PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackState.ACTION_SEEK_TO
                    | PlaybackState.ACTION_FAST_FORWARD
                    | PlaybackState.ACTION_REWIND;
        }

        int state = !hasMedia
                ? PlaybackState.STATE_NONE
                : (playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED);
        return new PlaybackState.Builder()
                .setActions(actions)
                .setState(
                        state,
                        Math.max(0L, positionMs),
                        playing ? 1.0f : 0.0f,
                        SystemClock.elapsedRealtime()
                )
                .build();
    }

    private String cleanMediaText(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private void applyNativeTheme(boolean dark) {
        int background = dark ? 0xFF0A0E1A : 0xFFF1EFE9;
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        if (webView != null) {
            webView.setBackgroundColor(background);
        }

        int flags = getWindow().getDecorView().getSystemUiVisibility();
        flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (!dark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void maybePromptBatteryOptimization(boolean force) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            if (force) {
                Toast.makeText(this, "Battery optimisation is already disabled for this app.", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (!force && batteryPromptShownThisLaunch) return;
        batteryPromptShownThisLaunch = true;

        new AlertDialog.Builder(this)
                .setTitle("Keep sound therapy running")
                .setMessage("Android battery optimisation can close or pause the app when the screen is off.\n\n"
                        + "Allow Notched Sound to run without battery restrictions so long therapy sessions and notched music keep playing?")
                .setPositiveButton("Allow", (dialog, which) -> requestBatteryOptimizationExemption())
                .setNeutralButton("Settings", (dialog, which) -> openBatteryOptimizationSettings())
                .setNegativeButton("Not now", null)
                .show();
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Intent intent = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Direct battery optimisation request unavailable", e);
            openBatteryOptimizationSettings();
        }
    }

    private void openBatteryOptimizationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception e) {
            Log.w(TAG, "Battery optimisation settings unavailable", e);
            Toast.makeText(this, "Open Android Settings > Apps > Notched Sound > Battery.", Toast.LENGTH_LONG).show();
        }
    }

    private static final byte[] EMPTY_ART = new byte[0];

    private byte[] loadAlbumArtThumbnail(String docUriStr, int targetPx) {
        if (docUriStr == null || docUriStr.trim().isEmpty()) return null;

        int size = Math.max(48, Math.min(192, targetPx));
        String cacheKey = docUriStr + "|" + size;
        byte[] cached = albumArtCache.get(cacheKey);
        if (cached != null) {
            return cached.length == 0 ? null : cached;
        }

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap decoded = null;
        Bitmap square = null;
        Bitmap scaled = null;
        try {
            retriever.setDataSource(this, Uri.parse(docUriStr));
            byte[] embedded = retriever.getEmbeddedPicture();
            if (embedded == null || embedded.length == 0) {
                albumArtCache.put(cacheKey, EMPTY_ART);
                return null;
            }

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(embedded, 0, embedded.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                albumArtCache.put(cacheKey, EMPTY_ART);
                return null;
            }

            int sampleSize = 1;
            int largestSide = Math.max(bounds.outWidth, bounds.outHeight);
            while (largestSide / (sampleSize * 2) >= size * 2) {
                sampleSize *= 2;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            decoded = BitmapFactory.decodeByteArray(embedded, 0, embedded.length, options);
            if (decoded == null) {
                albumArtCache.put(cacheKey, EMPTY_ART);
                return null;
            }

            int side = Math.min(decoded.getWidth(), decoded.getHeight());
            int left = (decoded.getWidth() - side) / 2;
            int top = (decoded.getHeight() - side) / 2;
            square = Bitmap.createBitmap(decoded, left, top, side, side);
            scaled = side == size ? square : Bitmap.createScaledBitmap(square, size, size, true);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 84, output);
            byte[] result = output.toByteArray();
            if (result.length == 0) {
                albumArtCache.put(cacheKey, EMPTY_ART);
                return null;
            }

            albumArtCache.put(cacheKey, result);
            return result;
        } catch (Exception e) {
            Log.w(TAG, "Could not load album artwork for " + docUriStr, e);
            albumArtCache.put(cacheKey, EMPTY_ART);
            return null;
        } finally {
            if (scaled != null && scaled != square && scaled != decoded) scaled.recycle();
            if (decoded != null && decoded != square && decoded != scaled) decoded.recycle();
            if (square != null && square != scaled) square.recycle();
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private String resolveAudioMimeType(Uri uri) {
        String mime = getContentResolver().getType(uri);
        if (mime != null && mime.startsWith("audio/")) {
            return mime;
        }

        String name = uri.getLastPathSegment();
        if (name != null && name.contains(".")) {
            String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
            String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (guessed != null && guessed.startsWith("audio/")) {
                return guessed;
            }
        }
        return "audio/mpeg";
    }

    private long resolveContentLength(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.SIZE},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    return cursor.getLong(sizeIndex);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not resolve audio content length", e);
        }
        return -1;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!isPlaybackActive && webView != null) {
            webView.onPause();
            webView.pauseTimers();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isPlaybackActive && webView != null) {
            webView.onPause();
            webView.pauseTimers();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
            int volPct = getMediaVolumePercent();
            webView.evaluateJavascript("if(typeof onNativeMediaVolumeChanged==='function') onNativeMediaVolumeChanged(" + volPct + ");", null);
        }
        // When returning to app from background or another app, immediately sync progress state
        if (indexManager != null && webView != null) {
            webView.evaluateJavascript("if(typeof syncBatteryProtectionUI==='function') syncBatteryProtectionUI();", null);
            String folderName = indexManager.getActiveFolderName().replace("'", "\\'");
            webView.evaluateJavascript("if(typeof onMusicFolderSelected==='function') onMusicFolderSelected('" + folderName + "');", null);

            if (indexManager.isScanning()) {
                String safe = indexManager.getCurrentStatus().replace("'", "\\'");
                int count = indexManager.getCurrentTrackCount();
                webView.evaluateJavascript("if(typeof onMusicScanProgress==='function') onMusicScanProgress('" + safe + "', " + count + ", true);", null);
            } else {
                String json = indexManager.getCachedLibraryJson();
                if (json != null && !json.equals("[]")) {
                    webView.evaluateJavascript("if(typeof onMusicLibraryReady==='function') onMusicLibraryReady('" + folderName + "', false);", null);
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_OPEN_FOLDER && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(
                            treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
                } catch (Exception e) {
                    Log.w(TAG, "Failed to take persistable permission", e);
                }

                String folderName = treeUri.getLastPathSegment();
                if (folderName != null && folderName.contains(":")) {
                    folderName = folderName.substring(folderName.indexOf(':') + 1);
                }
                if (folderName == null || folderName.isEmpty()) folderName = "Music Folder";

                final String fName = folderName;
                runOnUiThread(() -> {
                    if (webView != null) {
                        String safe = fName.replace("'", "\\'");
                        webView.evaluateJavascript("if(typeof onMusicFolderSelected==='function') onMusicFolderSelected('" + safe + "');", null);
                    }
                });

                // Start persistent background indexing task
                indexManager.startScan(treeUri, folderName);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_RECORD_AUDIO) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (pendingWebPermissionRequest != null) {
                if (granted) {
                    pendingWebPermissionRequest.grant(pendingWebPermissionRequest.getResources());
                } else {
                    pendingWebPermissionRequest.deny();
                }
                pendingWebPermissionRequest = null;
            }
            if (webView != null) {
                webView.evaluateJavascript("if(typeof onMicrophonePermissionResult==='function') onMicrophonePermissionResult(" + granted + ");", null);
            }
        }
    }

    private int getMediaVolumePercent() {
        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        if (audioManager == null) return 80;
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (max <= 0) return 80;
        return Math.max(0, Math.min(100, Math.round((cur * 100f) / max)));
    }

    private void setMediaVolumePercent(int percent) {
        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        if (audioManager == null) return;
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (max <= 0) return;
        int clamped = Math.max(0, Math.min(100, percent));
        int target = Math.round((clamped * max) / 100f);
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (target != current) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        }
    }

    private void registerMediaVolumeReceiver() {
        if (mediaVolumeReceiver != null) return;
        mediaVolumeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                int streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
                if (streamType == -1 || streamType == AudioManager.STREAM_MUSIC) {
                    int volPct = getMediaVolumePercent();
                    runOnUiThread(() -> {
                        if (webView != null) {
                            webView.evaluateJavascript("if(typeof onNativeMediaVolumeChanged==='function') onNativeMediaVolumeChanged(" + volPct + ");", null);
                        }
                    });
                }
            }
        };
        IntentFilter filter = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaVolumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mediaVolumeReceiver, filter);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript(
                "(function(){ return typeof handleAndroidBackPress==='function' ? handleAndroidBackPress() : false; })()",
                value -> {
                    if ("true".equals(value)) {
                        return;
                    }
                    handleMainScreenBackPress();
                }
            );
            return;
        }
        handleMainScreenBackPress();
    }

    private void handleMainScreenBackPress() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastBackPressMs <= BACK_EXIT_WINDOW_MS) {
            finish();
            return;
        }
        lastBackPressMs = now;
        Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaVolumeReceiver != null) {
            try {
                unregisterReceiver(mediaVolumeReceiver);
            } catch (Exception ignored) {
            }
            mediaVolumeReceiver = null;
        }
        abandonAppAudioFocus();
        playbackServiceRunning = false;
        TherapyPlaybackService.stopPlaybackService(this);
        if (indexManager != null && scanListener != null) {
            indexManager.removeListener(scanListener);
        }
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        if (webView != null) {
            webView.destroy();
        }
    }
}
