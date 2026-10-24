package br.com.deixa.teleprompter;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/index.html";
    private static final int REQ_WEB_PERM = 1, REQ_FILE = 2, REQ_SPEECH = 3, REQ_STORAGE = 4;

    private WebView web;
    private PermissionRequest pendingPerm;
    private ValueCallback<Uri[]> fileCb;
    private SpeechRecognizer recognizer;
    private boolean speechOn = false;
    private boolean muted = false;
    private String speechLang = "pt-BR";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, File> saves = new HashMap<>();
    private int saveSeq = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                ui.post(() -> handleWebPermission(request));
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("text/*");
                    startActivityForResult(Intent.createChooser(i, "Importar roteiro"), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "DeixaAndroid");
        if (savedInstanceState == null) web.loadUrl(START_URL);
        else web.restoreState(savedInstanceState);
        immersive();
    }

    /* ---------- Permissões da câmera e do microfone ---------- */
    private void handleWebPermission(PermissionRequest r) {
        pendingPerm = r;
        List<String> missing = new ArrayList<>();
        for (String res : r.getResources()) {
            String p = androidPerm(res);
            if (p != null && checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED && !missing.contains(p)) missing.add(p);
        }
        if (missing.isEmpty()) grantPending();
        else requestPermissions(missing.toArray(new String[0]), REQ_WEB_PERM);
    }

    private String androidPerm(String res) {
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) return Manifest.permission.CAMERA;
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) return Manifest.permission.RECORD_AUDIO;
        return null;
    }

    private void grantPending() {
        if (pendingPerm == null) return;
        List<String> ok = new ArrayList<>();
        for (String res : pendingPerm.getResources()) {
            String p = androidPerm(res);
            if (p != null && checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) ok.add(res);
        }
        if (ok.isEmpty()) pendingPerm.deny();
        else pendingPerm.grant(ok.toArray(new String[0]));
        pendingPerm = null;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_WEB_PERM) {
            grantPending();
        } else if (code == REQ_SPEECH) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startSpeechInternal();
            else js("window.__deixaSpeechErr&&window.__deixaSpeechErr('not-allowed')");
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_FILE && fileCb != null) {
            Uri[] out = null;
            if (result == RESULT_OK && data != null && data.getData() != null) out = new Uri[]{data.getData()};
            fileCb.onReceiveValue(out);
            fileCb = null;
        }
    }

    /* ---------- Reconhecimento de voz nativo ---------- */
    private void startSpeechInternal() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            js("window.__deixaSpeechErr&&window.__deixaSpeechErr('unavailable')");
            return;
        }
        speechOn = true;
        muteBeeps(true);
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle b) { }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float v) { }
                @Override public void onBufferReceived(byte[] b) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onEvent(int t, Bundle b) { }
                @Override public void onPartialResults(Bundle b) { sendSpeech(b); }
                @Override public void onResults(Bundle b) {
                    sendSpeech(b);
                    ui.postDelayed(MainActivity.this::listen, 60);
                }
                @Override public void onError(int err) {
                    if (!speechOn) return;
                    if (err == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        stopSpeechInternal();
                        js("window.__deixaSpeechErr&&window.__deixaSpeechErr('not-allowed')");
                    } else if (err == SpeechRecognizer.ERROR_AUDIO) {
                        stopSpeechInternal();
                        js("window.__deixaSpeechErr&&window.__deixaSpeechErr('audio-capture')");
                    } else {
                        if (err == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                            try { recognizer.cancel(); } catch (Exception ignored) { }
                        }
                        ui.postDelayed(MainActivity.this::listen, err == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 400 : 120);
                    }
                }
            });
        }
        listen();
    }

    private void listen() {
        if (!speechOn || recognizer == null) return;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000);
        i.putExtra("android.speech.extra.DICTATION_MODE", true);
        try { recognizer.startListening(i); } catch (Exception ignored) { }
    }

    private void sendSpeech(Bundle b) {
        if (!speechOn || b == null) return;
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (r == null || r.isEmpty() || r.get(0) == null) return;
        js("window.__deixaSpeech&&window.__deixaSpeech(" + JSONObject.quote(r.get(0)) + ")");
    }

    private void stopSpeechInternal() {
        speechOn = false;
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
        }
        ui.postDelayed(() -> { if (!speechOn) muteBeeps(false); }, 400);
    }

    /* O reconhecedor do Android toca um bipe a cada reinício; silenciamos para não sair na gravação. */
    private void muteBeeps(boolean mute) {
        if (muted == mute) return;
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            int dir = mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, dir, 0);
            am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, dir, 0);
            muted = mute;
        } catch (Exception ignored) { }
    }

    /* ---------- Ponte com a página ---------- */
    private class Bridge {
        @JavascriptInterface public boolean isApp() { return true; }

        @JavascriptInterface public void startSpeech(String lang) {
            ui.post(() -> {
                if (lang != null && !lang.isEmpty()) speechLang = lang;
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_SPEECH);
                } else startSpeechInternal();
            });
        }

        @JavascriptInterface public void stopSpeech() { ui.post(MainActivity.this::stopSpeechInternal); }

        @JavascriptInterface public String startSave(String name, String mime) {
            String id = "rec" + (++saveSeq);
            File f = new File(getCacheDir(), id + ".part");
            if (f.exists()) f.delete();
            saves.put(id, f);
            return id;
        }

        @JavascriptInterface public boolean appendSave(String id, String b64) {
            File f = saves.get(id);
            if (f == null) return false;
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(Base64.decode(b64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface public String finishSave(String id, String name, String mime) {
            File f = saves.get(id);
            if (f == null) return "err:missing";
            String type = (mime != null && mime.startsWith("video/")) ? mime.split(";")[0] : "video/mp4";
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentResolver cr = getContentResolver();
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                    cv.put(MediaStore.Video.Media.MIME_TYPE, type);
                    cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Deixa");
                    cv.put(MediaStore.Video.Media.IS_PENDING, 1);
                    Uri uri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
                    if (uri == null) return "err:insert";
                    try (OutputStream out = cr.openOutputStream(uri); InputStream in = new FileInputStream(f)) {
                        copy(in, out);
                    }
                    cv.clear();
                    cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                    cr.update(uri, cv, null, null);
                } else {
                    if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        ui.post(() -> requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE));
                        return "err:perm";
                    }
                    File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Deixa");
                    if (!dir.exists()) dir.mkdirs();
                    File dest = new File(dir, name);
                    try (OutputStream out = new FileOutputStream(dest); InputStream in = new FileInputStream(f)) {
                        copy(in, out);
                    }
                    MediaScannerConnection.scanFile(MainActivity.this, new String[]{dest.getAbsolutePath()}, new String[]{type}, null);
                }
                f.delete();
                saves.remove(id);
                return "ok";
            } catch (Exception e) {
                return "err:" + e.getClass().getSimpleName();
            }
        }

        @JavascriptInterface public void exitApp() { ui.post(MainActivity.this::finish); }
    }

    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[256 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }

    private void js(final String code) {
        ui.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    /* ---------- Teclas de volume como controle remoto ---------- */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                js("window.__deixaKey&&window.__deixaKey('" + (k == KeyEvent.KEYCODE_VOLUME_UP ? "toggle" : "back") + "')");
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("window.__deixaBack?window.__deixaBack():false", v -> {
            if (!"true".equals(v)) finish();
        });
    }

    /* ---------- Tela cheia ---------- */
    @SuppressWarnings("deprecation")
    private void immersive() {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (speechOn) {
            stopSpeechInternal();
            js("window.__deixaSpeechErr&&window.__deixaSpeechErr('paused')");
        }
    }

    @Override
    protected void onDestroy() {
        muteBeeps(false);
        if (recognizer != null) { try { recognizer.destroy(); } catch (Exception ignored) { } }
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
