package com.deepseek.balance;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private WebView webView;
    private static final String PREFS_NAME = "deepseek_prefs";
    private static final String KEY_API = "saved_api_key";
    private static final String KEY_BATCH_KEYS = "saved_batch_keys";

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#1a1a2e"));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#1a1a2e"));
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setLoadWithOverviewMode(true);

        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
        });

        webView.loadUrl("file:///android_asset/index.html");

        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    public class AndroidBridge {
        private final Context context;
        private final SharedPreferences prefs;

        public AndroidBridge(Context context) {
            this.context = context;
            this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }

        @JavascriptInterface
        public String httpGet(String url, String apiKey) {
            return httpGetInternal(url, apiKey);
        }

        @JavascriptInterface
        public String batchQuery(String keysJson) {
            StringBuilder result = new StringBuilder("[");
            try {
                org.json.JSONArray keys = new org.json.JSONArray(keysJson);
                for (int i = 0; i < keys.length(); i++) {
                    if (i > 0) result.append(",");
                    String key = keys.getString(i).trim();
                    try {
                        String respBody = httpGetInternal("https://api.deepseek.com/user/balance", key);
                        org.json.JSONObject data = new org.json.JSONObject(respBody);
                        org.json.JSONArray infos = data.optJSONArray("balance_infos");
                        if (infos != null && infos.length() > 0) {
                            org.json.JSONObject info = infos.getJSONObject(0);
                            result.append("{");
                            result.append("\"key\":\"").append(escapeJson(key)).append("\",");
                            result.append("\"total\":").append(info.optDouble("total_balance", 0)).append(",");
                            result.append("\"grant\":").append(info.optDouble("granted_balance", 0)).append(",");
                            result.append("\"toppedUp\":").append(info.optDouble("topped_up_balance", 0)).append(",");
                            result.append("\"status\":\"").append(data.optBoolean("is_available", false) ? "正常" : "不可用").append("\"");
                            result.append("}");
                        } else {
                            result.append("{\"key\":\"").append(escapeJson(key)).append("\",\"error\":\"无法解析余额信息\"}");
                        }
                    } catch (Exception e) {
                        result.append("{\"key\":\"").append(escapeJson(key)).append("\",\"error\":\"").append(escapeJson(e.getMessage())).append("\"}");
                    }
                }
            } catch (Exception e) {
                return "[{\"error\":\"参数解析失败: " + escapeJson(e.getMessage()) + "\"}]";
            }
            result.append("]");
            return result.toString();
        }

        private String httpGetInternal(String url, String apiKey) {
            HttpURLConnection conn = null;
            try {
                URL requestUrl = new URL(url);
                conn = (HttpURLConnection) requestUrl.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setDoInput(true);
                int responseCode = conn.getResponseCode();
                InputStream is = (responseCode >= 200 && responseCode < 300)
                        ? conn.getInputStream() : conn.getErrorStream();
                String body = readStream(is);
                if (responseCode >= 400)
                    throw new IOException("HTTP " + responseCode + ": " + body);
                return body;
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        private String escapeJson(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        }

        @JavascriptInterface
        public void saveApiKey(String key) { prefs.edit().putString(KEY_API, key).apply(); }

        @JavascriptInterface
        public String getSavedApiKey() { return prefs.getString(KEY_API, ""); }

        @JavascriptInterface
        public void clearApiKey() { prefs.edit().remove(KEY_API).apply(); }

        @JavascriptInterface
        public void saveBatchKeys(String keysJson) { prefs.edit().putString(KEY_BATCH_KEYS, keysJson).apply(); }

        @JavascriptInterface
        public String getSavedBatchKeys() { return prefs.getString(KEY_BATCH_KEYS, ""); }

        @JavascriptInterface
        public void clearBatchKeys() { prefs.edit().remove(KEY_BATCH_KEYS).apply(); }

        private String readStream(InputStream is) throws IOException {
            BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return sb.toString();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}