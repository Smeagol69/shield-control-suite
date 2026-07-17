package dev.roesler.marquee;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;

/** Fullscreen WebView shell hosting the Marquee web UI, with a JS bridge that
 *  launches the provider apps (Netflix/Disney+/etc.) the user picks. */
public class MainActivity extends Activity {

    // provider packages the UI may ask about / launch (kept in sync with tmdb.js)
    private static final String[] PROVIDERS = {
        "com.netflix.ninja", "com.disney.disneyplus", "com.amazon.amazonvideo.livingroom",
        "com.wbd.stream", "com.hulu.livingroomplus", "com.apple.atve.androidtv.appletv",
        "com.cbs.ott", "com.peacocktv.peacockandroid", "com.crunchyroll.crunchyroid",
        "tv.fubo.mobile", "com.paramount.android.pplus"
    };

    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);              // localStorage for the TMDB key
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.setWebViewClient(new WebViewClient());  // keep navigation inside the WebView
        web.setBackgroundColor(0xFF0C0E11);
        web.addJavascriptInterface(new Bridge(), "Android");

        setContentView(web);
        hideSystemUi();
        web.loadUrl("file:///android_asset/www/index.html");
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    /** Hardware Back: let the web app close a detail / return home; exit only at the top. */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript(
            "(window.marqueeBack && window.marqueeBack()) ? 'h' : 'x'",
            value -> { if (value != null && value.contains("x")) finish(); });
    }

    private class Bridge {
        @JavascriptInterface
        public boolean openPackage(String pkg) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) return false;
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public boolean isInstalled(String pkg) {
            try { getPackageManager().getPackageInfo(pkg, 0); return true; }
            catch (PackageManager.NameNotFoundException e) { return false; }
        }

        /** JSON array of the provider packages actually installed (fast client-side filtering). */
        @JavascriptInterface
        public String installedPackages() {
            JSONArray arr = new JSONArray();
            for (String p : PROVIDERS) if (isInstalled(p)) arr.put(p);
            return arr.toString();
        }

        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }
    }
}
