package com.samawa.tvhome;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_IMAGES = 7;
    private static final int MAX_IMAGES = 4;

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        web = new WebView(this);
        web.setBackgroundColor(0xFF07080B);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "TV");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
        web.requestFocus();
        hideBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        js("window.refreshApps && window.refreshApps()");
    }

    @Override
    public void onBackPressed() {
        // A home screen never closes itself: Back only returns to the main tab.
        js("window.tvBack && window.tvBack()");
    }

    private boolean longFired = false;

    /** OK and Menu are handled here so they work the same on every remote. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (ok) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    longFired = false;
                } else if (!longFired && e.getRepeatCount() >= 8) {
                    longFired = true;
                    js("window.tvLong && window.tvLong()");
                }
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                if (!longFired) js("window.tvOk && window.tvOk()");
                longFired = false;
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_MENU) {
            if (e.getAction() == KeyEvent.ACTION_UP) js("window.tvLong && window.tvLong()");
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void js(final String code) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript(code, null);
            }
        });
    }

    private void toast(String msg) {
        js("window.toast && window.toast(" + JSONObject.quote(msg) + ")");
    }

    /* ---------------- installed apps ---------------- */

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> found = new LinkedHashMap<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Intent phone = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (Intent i : Arrays.asList(tv, phone)) {
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || found.containsKey(pkg)) continue;
                found.put(pkg, ri);
            }
        }
        List<JSONObject> list = new ArrayList<>();
        for (Map.Entry<String, ResolveInfo> e : found.entrySet()) {
            try {
                JSONObject o = new JSONObject();
                o.put("pkg", e.getKey());
                o.put("label", e.getValue().loadLabel(pm).toString());
                o.put("icon", iconData(e.getValue().loadIcon(pm)));
                list.add(o);
            } catch (Exception ignored) { }
        }
        Collections.sort(list, (a, b) -> a.optString("label").compareToIgnoreCase(b.optString("label")));
        JSONArray arr = new JSONArray();
        for (JSONObject o : list) arr.put(o);
        js("window.onApps(" + JSONObject.quote(arr.toString()) + ")");
    }

    private String iconData(Drawable d) {
        int size = 96;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        b.recycle();
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private void launchApp(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = null;
        if (Build.VERSION.SDK_INT >= 21) i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) { toast("ما كدرت أفتح هذا التطبيق"); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception e) { toast("ما كدرت أفتح هذا التطبيق"); }
    }

    /* ---------------- banner images from USB ---------------- */

    private File bannerDir() {
        File d = new File(getFilesDir(), "banner");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private String bannerJson() {
        JSONArray arr = new JSONArray();
        File[] files = bannerDir().listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) arr.put("file://" + f.getAbsolutePath() + "?t=" + f.lastModified());
        }
        return arr.toString();
    }

    private String imagePermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void startImport() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(imagePermission()) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{imagePermission()}, REQ_IMAGES);
            return;
        }
        bg.execute(this::doImport);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_IMAGES) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            bg.execute(this::doImport);
        } else {
            toast("لازم توافق على صلاحية الصور حتى أكدر أقرا الفلاشة");
        }
    }

    private List<File> findBannerImages() {
        List<File> roots = new ArrayList<>();
        File storage = new File("/storage");
        File[] vols = storage.listFiles();
        if (vols != null) roots.addAll(Arrays.asList(vols));
        roots.add(Environment.getExternalStorageDirectory());
        File mnt = new File("/mnt/media_rw");
        File[] mvols = mnt.listFiles();
        if (mvols != null) roots.addAll(Arrays.asList(mvols));

        for (File root : roots) {
            File[] kids = root.listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                if (!k.isDirectory() || !k.getName().equalsIgnoreCase("banner")) continue;
                File[] imgs = k.listFiles(f -> {
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    return f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg")
                            || n.endsWith(".png") || n.endsWith(".webp"));
                });
                if (imgs != null && imgs.length > 0) {
                    Arrays.sort(imgs);
                    List<File> out = new ArrayList<>(Arrays.asList(imgs));
                    return out.subList(0, Math.min(MAX_IMAGES, out.size()));
                }
            }
        }
        return new ArrayList<>();
    }

    private void doImport() {
        List<File> imgs = findBannerImages();
        if (imgs.isEmpty()) {
            toast("ما لقيت مجلد Banner بيه صور. تأكد من الفلاشة واسم المجلد");
            return;
        }
        File dir = bannerDir();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();

        int ok = 0;
        for (int i = 0; i < imgs.size(); i++) {
            if (saveScaled(imgs.get(i), new File(dir, (i + 1) + ".jpg"))) ok++;
        }
        final int count = ok;
        js("window.onBanner(" + JSONObject.quote(bannerJson()) + ")");
        toast(count > 0 ? "تم استيراد " + count + " صور للبنر، تكدر تشيل الفلاشة"
                        : "الصور ما انقرت، جرّب صور JPG");
    }

    /** Copies an image, shrinking it to at most 1920 px wide so the home screen stays fast. */
    private boolean saveScaled(File src, File dst) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(src.getAbsolutePath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 1920) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeFile(src.getAbsolutePath(), o);
            if (b == null) return false;
            if (b.getWidth() > 1920) {
                int h = Math.round(b.getHeight() * (1920f / b.getWidth()));
                Bitmap s = Bitmap.createScaledBitmap(b, 1920, h, true);
                b.recycle();
                b = s;
            }
            FileOutputStream out = new FileOutputStream(dst);
            b.compress(Bitmap.CompressFormat.JPEG, 88, out);
            out.close();
            b.recycle();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /* ---------------- bridge used by index.html ---------------- */

    private class Bridge {
        @JavascriptInterface
        public void getApps() { bg.execute(MainActivity.this::loadApps); }

        @JavascriptInterface
        public void launch(final String pkg) { ui.post(() -> launchApp(pkg)); }

        @JavascriptInterface
        public String getBanner() { return bannerJson(); }

        @JavascriptInterface
        public void importBanner() { ui.post(MainActivity.this::startImport); }

        @JavascriptInterface
        public void clearBanner() {
            File[] old = bannerDir().listFiles();
            if (old != null) for (File f : old) f.delete();
        }

        @JavascriptInterface
        public void openHomeSettings() {
            ui.post(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
                } catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                    catch (Exception ignored) { }
                }
            });
        }

        @JavascriptInterface
        public void openSettings() {
            ui.post(() -> {
                try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                catch (Exception ignored) { }
            });
        }
    }
}
