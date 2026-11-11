package com.simon.tinnitusrelief;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_CODE_OPEN_FOLDER = 1001;
    private static final int REQUEST_CODE_RECORD_AUDIO = 1002;

    private WebView webView;
    private PowerManager.WakeLock therapyWakeLock;
    private MusicIndexManager indexManager;
    private MusicIndexManager.ScanListener scanListener;
    private PermissionRequest pendingWebPermissionRequest;

    @SuppressLint({"SetJavaScriptEnabled"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen awake while active
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            therapyWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "TinnitusRelief:TherapyAudioWakeLock"
            );
            therapyWakeLock.setReferenceCounted(false);
        }

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

        webView.setBackgroundColor(0xFF090A0F);
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
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri reqUri = request.getUrl();
                if (reqUri != null && "tinnitus.local".equalsIgnoreCase(reqUri.getHost())) {
                    if ("/music_stream".equalsIgnoreCase(reqUri.getPath())) {
                        String docUriStr = reqUri.getQueryParameter("uri");
                        if (docUriStr != null) {
                            try {
                                Uri docUri = Uri.parse(docUriStr);
                                InputStream is = getContentResolver().openInputStream(docUri);
                                String mime = getContentResolver().getType(docUri);
                                if (mime == null) mime = "audio/mpeg";

                                Map<String, String> headers = new HashMap<>();
                                headers.put("Access-Control-Allow-Origin", "*");
                                headers.put("Accept-Ranges", "bytes");

                                return new WebResourceResponse(mime, "UTF-8", 200, "OK", headers, is);
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
                    if (therapyWakeLock != null) {
                        if (active && !therapyWakeLock.isHeld()) {
                            therapyWakeLock.acquire(12 * 60 * 60 * 1000L); // Max 12 hours safety timeout
                        } else if (!active && therapyWakeLock.isHeld()) {
                            therapyWakeLock.release();
                        }
                    }
                });
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
            public String getAppVersion() {
                return "1.0.7-pro-unlocked";
            }
        }, "AndroidBridge");

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // When returning to app from background or another app, immediately sync progress state
        if (indexManager != null && webView != null) {
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (indexManager != null && scanListener != null) {
            indexManager.removeListener(scanListener);
        }
        if (therapyWakeLock != null && therapyWakeLock.isHeld()) {
            therapyWakeLock.release();
        }
        if (webView != null) {
            webView.destroy();
        }
    }
}
