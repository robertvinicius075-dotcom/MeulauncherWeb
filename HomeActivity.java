package com.meulauncher.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Launcher que mostra o arquivo assets/index.html numa WebView.
 * A página fala com o Android por um objeto "Android" com só 3 funções:
 * requestApps(), launch(pacote) e openSettings(tipo).
 */
public class HomeActivity extends Activity {
    private static final String PAGE = "file:///android_asset/index.html";
    private static final int REQ_FILE = 7;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private volatile boolean loadingApps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildWebView();
    }

    @SuppressWarnings({"deprecation", "SetJavaScriptEnabled"})
    private void buildWebView() {
        web = new WebView(this);
        web.setBackgroundColor(0xFF0D0E11);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // o vídeo de fundo toca sozinho
        s.setAllowFileAccess(false);                   // não afeta file:///android_asset
        s.setAllowFileAccessFromFileURLs(false);
        // A página é só a sua (local). Isto deixa o fetch() baixar listas M3U de qualquer servidor.
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setSupportZoom(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // nunca sai da sua página
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (web != null) {
                            web.destroy();
                        }
                        buildWebView();
                    }
                });
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public Bitmap getDefaultVideoPoster() {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }

            // O seu HTML usa alert() para avisar "canais importados". Sem isto a WebView engole o aviso.
            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(HomeActivity.this)
                        .setMessage(message)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                result.confirm();
                            }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(DialogInterface d) {
                                result.cancel();
                            }
                        })
                        .show();
                return true;
            }

            // Botão "Arquivo .m3u" do seu HTML.
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = cb;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(Intent.createChooser(i, "Escolher lista .m3u"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    cb.onReceiveValue(null);
                    toast("Esta TV não tem um seletor de arquivos");
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.requestFocus();
        web.loadUrl(PAGE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && fileCallback != null) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null && data.getData() != null) {
                r = new Uri[]{data.getData()};
            }
            fileCallback.onReceiveValue(r);
            fileCallback = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadApps(); // atualiza se você instalou ou removeu algum app
    }

    @Override
    protected void onPause() {
        js("window.__stop&&window.__stop()"); // o canal não continua tocando em segundo plano
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        js("window.__home&&window.__home()"); // botão Home com o launcher já aberto
    }

    @Override
    public void onBackPressed() {
        // Um launcher nunca fecha: a página decide o que o Voltar faz.
        js("window.__back&&window.__back()");
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private void js(final String code) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (web != null) {
                    web.evaluateJavascript(code, null);
                }
            }
        });
    }

    private void toast(final String msg) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(HomeActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ------------------------------------------------------------------ apps instalados

    private String lastSig = "";

    private void loadApps() {
        if (loadingApps) {
            return;
        }
        loadingApps = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    PackageManager pm = getPackageManager();
                    Set<String> seen = new HashSet<>();
                    List<ResolveInfo> found = new ArrayList<>();
                    String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
                    StringBuilder sig = new StringBuilder();
                    for (String cat : cats) {
                        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
                        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                            if (ri.activityInfo == null) {
                                continue;
                            }
                            String p = ri.activityInfo.packageName;
                            if (p.equals(getPackageName()) || !seen.add(p)) {
                                continue;
                            }
                            found.add(ri);
                            sig.append(p).append(';');
                        }
                    }
                    if (sig.toString().equals(lastSig)) {
                        return;
                    }
                    JSONArray arr = new JSONArray();
                    for (ResolveInfo ri : found) {
                        JSONObject o = new JSONObject();
                        o.put("p", ri.activityInfo.packageName);
                        CharSequence l = ri.loadLabel(pm);
                        o.put("l", l == null ? ri.activityInfo.packageName : l.toString());
                        o.put("i", iconBase64(ri.loadIcon(pm)));
                        arr.put(o);
                    }
                    lastSig = sig.toString();
                    String b64 = Base64.encodeToString(arr.toString().getBytes("UTF-8"), Base64.NO_WRAP);
                    js("window.onApps&&window.onApps('" + b64 + "')");
                } catch (Exception e) {
                    toast("Não foi possível listar os aplicativos");
                } finally {
                    loadingApps = false;
                }
            }
        }, "apps-loader").start();
    }

    private static String iconBase64(Drawable d) {
        int size = 96;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        b.recycle();
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    // ------------------------------------------------------------------ ponte para a página

    private final class Bridge {
        @JavascriptInterface
        public void requestApps() {
            lastSig = ""; // a página (re)carregou: manda a lista de novo
            loadApps();
        }

        @JavascriptInterface
        public void launch(final String pkg) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        PackageManager pm = getPackageManager();
                        ResolveInfo ri = pm.resolveActivity(new Intent(Intent.ACTION_MAIN)
                                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg), 0);
                        if (ri == null) {
                            ri = pm.resolveActivity(new Intent(Intent.ACTION_MAIN)
                                    .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg), 0);
                        }
                        if (ri == null || ri.activityInfo == null) {
                            toast("Não foi possível abrir este aplicativo");
                            return;
                        }
                        Intent i = new Intent(Intent.ACTION_MAIN);
                        i.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                        startActivity(i);
                    } catch (Exception e) {
                        toast("Não foi possível abrir este aplicativo");
                    }
                }
            });
        }

        @JavascriptInterface
        public void openSettings(final String kind) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        String action = "wifi".equals(kind) ? Settings.ACTION_WIFI_SETTINGS : Settings.ACTION_SETTINGS;
                        startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e) {
                        toast("Esta TV não permite abrir essa tela");
                    }
                }
            });
        }
    }
}
