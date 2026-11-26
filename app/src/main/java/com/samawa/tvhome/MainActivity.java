package com.samawa.tvhome;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shashti: a light, native Android TV home screen.
 * Everything is drawn with plain Android views (no WebView) so it stays fast on weak TV boxes.
 * The layout is designed on a 1280x720 grid and scaled to the real screen.
 */
public class MainActivity extends Activity {

    private static final int REQ_IMAGES = 7;
    private static final int MAX_IMAGES = 4;
    private static final int BG = 0xFF07080B;
    private static final int INK = 0xFFF5F6F8;
    private static final int ACCENT = 0xFFFF6B4A;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    private static final String[] PREFER = {
            "com.google.android.youtube.tv", "com.google.android.youtube", "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom", "com.spotify.tv.android", "com.android.chrome",
            "com.android.vending"};
    private static final String[] DAYS = {"الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت"};
    private static final String[] MONTHS = {"كانون الثاني", "شباط", "آذار", "نيسان", "أيار", "حزيران",
            "تموز", "آب", "أيلول", "تشرين الأول", "تشرين الثاني", "كانون الأول"};
    private static final int[] SCENES = {R.drawable.scene1, R.drawable.scene2, R.drawable.scene3, R.drawable.scene4};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;

    private float S = 1f;
    private int screenW = 1920;
    private float zoom = 0.9f;

    private FrameLayout root, stage, bgHolder, homeLayer, appsLayer, settingsLayer;
    private ImageView bgFront, bgBack;
    private View dim;
    private TextView timeTv, dateTv, weatherTv, chipTv, titleTv, subTv, focusNameTv, zoomTv;
    private LinearLayout heroText, dots, row, grid, opts;
    private TextView playBtn;
    private final TextView[] tabs = new TextView[3];
    private static final String[] TAB_KEYS = {"home", "apps", "settings"};

    private String screen = "home";
    private final List<App> apps = new ArrayList<>();
    private boolean gridDirty = true;
    private List<Bitmap> slides = new ArrayList<>();
    private boolean customBanner = false;
    private int slide = 0;

    private static class App {
        String pkg;
        String label;
        Drawable icon;
    }

    /* ======================= lifecycle ======================= */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("shashti", MODE_PRIVATE);
        zoom = prefs.getFloat("zoom", 0.9f);

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        S = Math.min(dm.widthPixels / 1280f, dm.heightPixels / 720f);

        buildUi();
        setContentView(root);
        hideBars();

        tick.run();
        loadWeather();
        loadSlides();
        loadApps();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        ui.removeCallbacks(rotate);
        ui.postDelayed(rotate, 5000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(rotate);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        ui.removeCallbacksAndMessages(null);
    }

    @Override
    public void onBackPressed() {
        if (!"home".equals(screen)) showScreen("home");
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof String) toggleFav((String) f.getTag());
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) { loadApps(); }
    };

    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /* ======================= small helpers ======================= */

    private int px(float v) { return Math.round(v * S); }

    private static String ar(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(c >= '0' && c <= '9' ? (char) ('٠' + (c - '0')) : c);
        return b.toString();
    }

    private GradientDrawable round(int fill, float radius, int stroke, float strokeW) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(px(radius));
        if (strokeW > 0) g.setStroke(px(strokeW), stroke);
        return g;
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setIncludeFontPadding(false);
        return t;
    }

    private LinearLayout box(boolean horizontal) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        return l;
    }

    private FrameLayout layer() {
        FrameLayout f = new FrameLayout(this);
        f.setClipChildren(false);
        f.setClipToPadding(false);
        return f;
    }

    /** Places a view at a design position. side = distance from the right or left edge. */
    private FrameLayout.LayoutParams at(int w, int h, int gravity, float side, float top) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        if ((gravity & Gravity.RIGHT) == Gravity.RIGHT) p.rightMargin = px(side);
        else p.leftMargin = px(side);
        p.topMargin = px(top);
        return p;
    }

    private LinearLayout.LayoutParams ll(int w, int h, float start, float top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMarginStart(px(start));
        p.topMargin = px(top);
        return p;
    }

    private View gradient(GradientDrawable.Orientation o, int[] colors) {
        View v = new View(this);
        v.setBackground(new GradientDrawable(o, colors));
        return v;
    }

    private void toast(final String msg) {
        ui.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    /* ======================= building the screen ======================= */

    private void buildUi() {
        root = layer();
        root.setBackgroundColor(BG);

        bgHolder = new FrameLayout(this);
        bgBack = bgImage();
        bgFront = bgImage();
        bgHolder.addView(bgBack, new FrameLayout.LayoutParams(MATCH, MATCH));
        bgHolder.addView(bgFront, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(bgHolder, new FrameLayout.LayoutParams(MATCH, MATCH));

        root.addView(gradient(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xFF07080B, 0xB307080B, 0x1A07080B}), new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(gradient(GradientDrawable.Orientation.RIGHT_LEFT,
                new int[]{0xD907080B, 0x4D07080B, 0x0007080B}), new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(gradient(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x8C07080B, 0x0007080B, 0x0007080B}), new FrameLayout.LayoutParams(MATCH, MATCH));

        dim = new View(this);
        dim.setBackgroundColor(0xEB07080B);
        dim.setVisibility(View.GONE);
        root.addView(dim, new FrameLayout.LayoutParams(MATCH, MATCH));

        stage = layer();
        stage.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        stage.setScaleX(zoom);
        stage.setScaleY(zoom);
        root.addView(stage, new FrameLayout.LayoutParams(px(1280), px(720), Gravity.CENTER));

        buildHeader();
        buildHome();
        buildApps();
        buildSettings();
        styleTabs();
    }

    private ImageView bgImage() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        return v;
    }

    private void buildHeader() {
        LinearLayout clock = box(true);
        clock.setGravity(Gravity.BOTTOM);
        timeTv = text("", 40, INK, false);
        timeTv.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        dateTv = text("", 16, 0xB3F5F6F8, false);
        clock.addView(timeTv);
        clock.addView(dateTv, ll(WRAP, WRAP, 14, 0));
        stage.addView(clock, at(WRAP, WRAP, Gravity.TOP | Gravity.RIGHT, 56, 30));

        LinearLayout bar = box(true);
        bar.setPadding(px(6), px(6), px(6), px(6));
        bar.setBackground(round(0x14FFFFFF, 30, 0x1AFFFFFF, 1));
        String[] names = {"الرئيسية", "التطبيقات", "الإعدادات"};
        for (int i = 0; i < 3; i++) {
            final String key = TAB_KEYS[i];
            TextView t = text(names[i], 16, INK, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(px(21), 0, px(21), 0);
            t.setFocusable(true);
            t.setOnClickListener(v -> showScreen(key));
            t.setOnFocusChangeListener((v, has) -> styleTabs());
            tabs[i] = t;
            bar.addView(t, ll(WRAP, px(44), i == 0 ? 0 : 6, 0));
        }
        stage.addView(bar, at(WRAP, WRAP, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 24));

        weatherTv = text("", 16, 0xD9F5F6F8, false);
        GradientDrawable sun = new GradientDrawable();
        sun.setShape(GradientDrawable.OVAL);
        sun.setColor(0xFFFFC36B);
        sun.setSize(px(12), px(12));
        weatherTv.setCompoundDrawablesRelativeWithIntrinsicBounds(sun, null, null, null);
        weatherTv.setCompoundDrawablePadding(px(8));
        weatherTv.setVisibility(View.INVISIBLE);
        stage.addView(weatherTv, at(WRAP, WRAP, Gravity.TOP | Gravity.LEFT, 56, 40));
    }

    private void styleTabs() {
        for (int i = 0; i < 3; i++) {
            TextView t = tabs[i];
            boolean cur = TAB_KEYS[i].equals(screen);
            boolean foc = t.isFocused();
            t.setBackground(round(cur ? INK : 0x00000000, 22, ACCENT, foc ? 3 : 0));
            t.setTextColor(cur ? BG : 0xCCF5F6F8);
            t.setTypeface(cur ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private TextView button(String label, final boolean main) {
        final TextView b = text(label, 17, main ? BG : INK, main);
        b.setGravity(Gravity.CENTER);
        b.setPadding(px(28), 0, px(28), 0);
        b.setFocusable(true);
        final Drawable normal = main ? round(INK, 26, 0, 0) : round(0x1AFFFFFF, 26, 0x2EFFFFFF, 1);
        final Drawable on = main ? round(INK, 26, ACCENT, 3) : round(0x33FFFFFF, 26, ACCENT, 3);
        b.setBackground(normal);
        b.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? on : normal);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(140).start();
        });
        return b;
    }

    private void buildHome() {
        homeLayer = layer();
        stage.addView(homeLayer, new FrameLayout.LayoutParams(MATCH, MATCH));

        heroText = box(false);
        heroText.setGravity(Gravity.START);
        chipTv = text("", 14, 0xFFFF9A80, true);
        chipTv.setPadding(px(14), px(6), px(14), px(6));
        titleTv = text("", 46, INK, true);
        subTv = text("", 18, 0xB8F5F6F8, false);
        heroText.addView(chipTv, ll(WRAP, WRAP, 0, 0));
        heroText.addView(titleTv, ll(WRAP, WRAP, 0, 14));
        heroText.addView(subTv, ll(WRAP, WRAP, 0, 12));
        homeLayer.addView(heroText, at(px(700), WRAP, Gravity.TOP | Gravity.RIGHT, 56, 150));

        LinearLayout actions = box(true);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        playBtn = button("تشغيل", true);
        TextView all = button("كل التطبيقات", false);
        playBtn.setOnClickListener(v -> playLast());
        all.setOnClickListener(v -> showScreen("apps"));
        dots = box(true);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(playBtn, ll(WRAP, px(52), 0, 0));
        actions.addView(all, ll(WRAP, px(52), 12, 0));
        actions.addView(dots, ll(WRAP, WRAP, 30, 0));
        homeLayer.addView(actions, at(WRAP, WRAP, Gravity.TOP | Gravity.RIGHT, 56, 340));

        homeLayer.addView(text("تطبيقاتك", 20, INK, true), at(WRAP, WRAP, Gravity.TOP | Gravity.RIGHT, 56, 446));
        focusNameTv = text("", 15, 0x8CF5F6F8, false);
        homeLayer.addView(focusNameTv, at(WRAP, WRAP, Gravity.TOP | Gravity.LEFT, 56, 450));

        row = box(true);
        row.setGravity(Gravity.CENTER_VERTICAL);
        homeLayer.addView(row, at(WRAP, px(156), Gravity.TOP | Gravity.RIGHT, 62, 478));
    }

    private void buildApps() {
        appsLayer = layer();
        appsLayer.setVisibility(View.GONE);
        stage.addView(appsLayer, new FrameLayout.LayoutParams(MATCH, MATCH));
        appsLayer.addView(text("كل التطبيقات", 30, INK, true), at(WRAP, WRAP, Gravity.TOP | Gravity.RIGHT, 56, 110));
        appsLayer.addView(text("OK للفتح · OK مطوّلاً أو زر القائمة حتى تضيف التطبيق للرئيسية أو تشيله",
                16, 0x99F5F6F8, false), at(WRAP, WRAP, Gravity.TOP | Gravity.RIGHT, 56, 154));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        sv.setSmoothScrollingEnabled(true);
        grid = box(false);
        grid.setPadding(px(10), px(18), px(10), px(40));
        sv.addView(grid, new FrameLayout.LayoutParams(MATCH, WRAP));
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(MATCH, MATCH);
        p.topMargin = px(186);
        p.leftMargin = px(40);
        p.rightMargin = px(40);
        p.bottomMargin = px(10);
        appsLayer.addView(sv, p);
    }

    private void buildSettings() {
        settingsLayer = layer();
        settingsLayer.setVisibility(View.GONE);
        stage.addView(settingsLayer, new FrameLayout.LayoutParams(MATCH, MATCH));
        settingsLayer.addView(text("الإعدادات", 30, INK, true), at(WRAP, WRAP, Gravity.TOP | Gravity.RIGHT, 56, 110));
        settingsLayer.addView(text("لتغيير صور البنر: سوّي مجلد اسمه Banner بالفلاشة، حط بيه لحد ٤ صور، وبعدين اضغط استيراد",
                16, 0x99F5F6F8, false), at(WRAP, WRAP, Gravity.TOP | Gravity.RIGHT, 56, 154));

        opts = box(false);
        settingsLayer.addView(opts, at(px(780), WRAP, Gravity.TOP | Gravity.RIGHT, 56, 196));
        option(0xFFFF9A80, "استيراد صور البنر من الفلاشة", "ياخذ أول ٤ صور من مجلد Banner وينسخها للتطبيق", this::startImport);
        option(0xFF8FE3F0, "رجّع الصور الافتراضية", "يمسح صورك ويرجّع المشاهد الأصلية", () -> {
            clearBanner();
            loadSlides();
            toast("رجعت الصور الافتراضية");
        });
        zoomTv = option(0xFFFFC36B, "", "إذا الأطراف مكصوصة بالتلفاز، اضغط OK حتى تصغر", this::cycleZoom);
        updateZoomLabel();
        option(0xFFC79BFF, "خلّيه الواجهة الرئيسية", "يفتح إعداد التطبيق الرئيسي بالتلفاز", () -> {
            try { startActivity(new Intent(Settings.ACTION_HOME_SETTINGS)); }
            catch (Exception e) { openTvSettings(); }
        });
        option(0xFFB9C2D3, "إعدادات التلفاز", "الشبكة، الصوت، الشاشة وغيرها", this::openTvSettings);
    }

    private TextView option(int color, String title, String sub, final Runnable action) {
        LinearLayout o = box(true);
        o.setGravity(Gravity.CENTER_VERTICAL);
        o.setPadding(px(22), px(16), px(22), px(16));
        o.setFocusable(true);
        final Drawable normal = round(0x0DFFFFFF, 20, 0x1AFFFFFF, 1);
        final Drawable on = round(0x1FFFFFFF, 20, ACCENT, 3);
        o.setBackground(normal);
        o.setOnFocusChangeListener((v, has) -> v.setBackground(has ? on : normal));
        o.setOnClickListener(v -> action.run());

        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        dot.setBackground(d);
        o.addView(dot, ll(px(22), px(22), 0, 0));

        LinearLayout texts = box(false);
        TextView t = text(title, 19, INK, true);
        texts.addView(t);
        texts.addView(text(sub, 14, 0x99F5F6F8, false), ll(WRAP, WRAP, 0, 6));
        o.addView(texts, ll(WRAP, WRAP, 18, 0));
        opts.addView(o, ll(MATCH, WRAP, 0, opts.getChildCount() == 0 ? 0 : 14));
        return t;
    }

    /* ======================= tiles ======================= */

    private View allIcon() {
        LinearLayout g = box(false);
        for (int r = 0; r < 3; r++) {
            LinearLayout line = box(true);
            for (int c = 0; c < 3; c++) {
                View d = new View(this);
                GradientDrawable o = new GradientDrawable();
                o.setShape(GradientDrawable.OVAL);
                o.setColor(0xFFE2E5EA);
                d.setBackground(o);
                line.addView(d, ll(px(8), px(8), c == 0 ? 0 : 7, 0));
            }
            g.addView(line, ll(WRAP, WRAP, 0, r == 0 ? 0 : 7));
        }
        return g;
    }

    /** A square tile. Focus makes it grow with a white ring. */
    private FrameLayout tile(Drawable icon, float size, boolean isAll) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(isAll ? round(0x0FFFFFFF, 26, 0x24FFFFFF, 1) : round(0xFF1A1D24, 26, 0x14FFFFFF, 1));
        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            f.addView(iv, new FrameLayout.LayoutParams(px(size * 0.55f), px(size * 0.55f), Gravity.CENTER));
        } else {
            f.addView(allIcon(), new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        }
        f.setAlpha(0.82f);
        return f;
    }

    private void focusFx(View focusable, final View anim, final boolean isAll, final String name, final TextView label) {
        final Drawable normal = isAll ? round(0x0FFFFFFF, 26, 0x24FFFFFF, 1) : round(0xFF1A1D24, 26, 0x14FFFFFF, 1);
        final Drawable on = round(isAll ? 0x1FFFFFFF : 0xFF252A33, 26, INK, 3);
        focusable.setOnFocusChangeListener((v, has) -> {
            anim.setBackground(has ? on : normal);
            anim.setAlpha(has ? 1f : 0.82f);
            anim.animate().scaleX(has ? 1.14f : 1f).scaleY(has ? 1.14f : 1f)
                    .translationY(has ? -px(6) : 0).setDuration(140).start();
            if (label != null) label.setTextColor(has ? INK : 0xB3F5F6F8);
            if (has && name != null && focusNameTv != null && "home".equals(screen)) focusNameTv.setText(name);
        });
    }

    private void renderRow() {
        int keep = -1;
        View cur = getCurrentFocus();
        if (cur != null && cur.getParent() == row) keep = row.indexOfChild(cur);
        row.removeAllViews();
        List<App> f = favs();
        for (int i = 0; i < f.size(); i++) {
            final App a = f.get(i);
            FrameLayout t = tile(a.icon, 116, false);
            t.setFocusable(true);
            t.setTag(a.pkg);
            focusFx(t, t, false, a.label, null);
            t.setOnClickListener(v -> launch(a));
            t.setOnLongClickListener(v -> { toggleFav(a.pkg); return true; });
            row.addView(t, ll(px(116), px(116), i == 0 ? 0 : 22, 0));
        }
        FrameLayout all = tile(null, 116, true);
        all.setFocusable(true);
        focusFx(all, all, true, "كل التطبيقات", null);
        all.setOnClickListener(v -> showScreen("apps"));
        row.addView(all, ll(px(116), px(116), f.isEmpty() ? 0 : 22, 0));
        if (keep >= 0) row.getChildAt(Math.min(keep, row.getChildCount() - 1)).requestFocus();
    }

    private void renderGrid() {
        gridDirty = false;
        grid.removeAllViews();
        List<String> fp = favPkgs();
        LinearLayout line = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % 7 == 0) {
                line = box(true);
                grid.addView(line, ll(WRAP, WRAP, 0, i == 0 ? 0 : 26));
            }
            final App a = apps.get(i);
            LinearLayout card = box(false);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setFocusable(true);
            card.setTag(a.pkg);
            FrameLayout t = tile(a.icon, 108, false);
            if (fp.contains(a.pkg)) t.setBackground(round(0xFF1A1D24, 26, 0x99FF6B4A, 2));
            card.addView(t, new LinearLayout.LayoutParams(px(108), px(108)));
            TextView lab = text(a.label, 15, 0xB3F5F6F8, false);
            lab.setSingleLine(true);
            lab.setEllipsize(TextUtils.TruncateAt.END);
            lab.setGravity(Gravity.CENTER);
            card.addView(lab, ll(px(150), WRAP, 0, 12));
            focusFx(card, t, false, a.label, lab);
            card.setOnClickListener(v -> launch(a));
            card.setOnLongClickListener(v -> { toggleFav(a.pkg); return true; });
            line.addView(card, ll(px(150), WRAP, (i % 7) == 0 ? 0 : 14, 0));
        }
    }

    /* ======================= screens ======================= */

    private void showScreen(String name) {
        screen = name;
        homeLayer.setVisibility("home".equals(name) ? View.VISIBLE : View.GONE);
        appsLayer.setVisibility("apps".equals(name) ? View.VISIBLE : View.GONE);
        settingsLayer.setVisibility("settings".equals(name) ? View.VISIBLE : View.GONE);
        dim.setVisibility("home".equals(name) ? View.GONE : View.VISIBLE);
        if ("apps".equals(name) && gridDirty) renderGrid();
        styleTabs();
        View target;
        if ("home".equals(name)) target = row.getChildCount() > 0 ? row.getChildAt(0) : playBtn;
        else if ("apps".equals(name)) target = firstCard();
        else target = opts.getChildAt(0);
        if (target == null) target = tabs[Arrays.asList(TAB_KEYS).indexOf(name)];
        final View t = target;
        ui.post(t::requestFocus);
    }

    private View firstCard() {
        if (grid.getChildCount() == 0) return null;
        ViewGroup line = (ViewGroup) grid.getChildAt(0);
        return line.getChildCount() > 0 ? line.getChildAt(0) : null;
    }

    /* ======================= clock & weather ======================= */

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Calendar c = Calendar.getInstance();
            int h = c.get(Calendar.HOUR);
            if (h == 0) h = 12;
            timeTv.setText(ar(String.format(Locale.US, "%d:%02d", h, c.get(Calendar.MINUTE))));
            dateTv.setText(DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + ar(String.valueOf(c.get(Calendar.DAY_OF_MONTH)))
                    + " " + MONTHS[c.get(Calendar.MONTH)]);
            ui.postDelayed(this, 10000);
        }
    };

    private void loadWeather() {
        new Thread(() -> {
            try {
                URL u = new URL("https://api.open-meteo.com/v1/forecast?latitude=31.31&longitude=45.28&current=temperature_2m");
                HttpURLConnection con = (HttpURLConnection) u.openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(8000);
                BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                double t = new JSONObject(sb.toString()).getJSONObject("current").getDouble("temperature_2m");
                final String s = ar(String.valueOf(Math.round(t))) + "° السماوة";
                ui.post(() -> { weatherTv.setText(s); weatherTv.setVisibility(View.VISIBLE); });
            } catch (Exception ignored) { }
        }).start();
        ui.postDelayed(this::loadWeather, 30 * 60 * 1000);
    }

    /* ======================= banner slides ======================= */

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            if (slides.size() > 1) {
                slide = (slide + 1) % slides.size();
                showSlide();
            }
            ui.postDelayed(this, 5000);
        }
    };

    private Bitmap decode(File f, int resId) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        if (f != null) BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        else BitmapFactory.decodeResource(getResources(), resId, o);
        int sample = 1;
        int target = Math.min(1920, Math.max(960, screenW));
        while (o.outWidth / (sample * 2) >= target) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return f != null ? BitmapFactory.decodeFile(f.getAbsolutePath(), o)
                : BitmapFactory.decodeResource(getResources(), resId, o);
    }

    private void loadSlides() {
        bg.execute(() -> {
            final List<Bitmap> list = new ArrayList<>();
            File[] files = bannerFiles();
            final boolean custom = files.length > 0;
            if (custom) {
                for (File f : files) { Bitmap b = decode(f, 0); if (b != null) list.add(b); }
            }
            if (list.isEmpty()) {
                for (int id : SCENES) { Bitmap b = decode(null, id); if (b != null) list.add(b); }
            }
            ui.post(() -> {
                slides = list;
                customBanner = custom && !list.isEmpty();
                slide = 0;
                dots.removeAllViews();
                for (int i = 0; i < slides.size(); i++) {
                    View d = new View(MainActivity.this);
                    dots.addView(d, ll(px(6), px(6), i == 0 ? 0 : 7, 0));
                }
                showSlide();
                ui.removeCallbacks(rotate);
                ui.postDelayed(rotate, 5000);
            });
        });
    }

    private void showSlide() {
        if (slides.isEmpty()) return;
        final ImageView next = bgBack;
        next.animate().cancel();
        next.setImageBitmap(slides.get(slide % slides.size()));
        next.setAlpha(0f);
        next.setScaleX(1f);
        next.setScaleY(1f);
        next.bringToFront();
        ObjectAnimator fade = ObjectAnimator.ofFloat(next, "alpha", 0f, 1f).setDuration(1000);
        ObjectAnimator sx = ObjectAnimator.ofFloat(next, "scaleX", 1f, 1.08f).setDuration(6000);
        ObjectAnimator sy = ObjectAnimator.ofFloat(next, "scaleY", 1f, 1.08f).setDuration(6000);
        sx.setInterpolator(new LinearInterpolator());
        sy.setInterpolator(new LinearInterpolator());
        AnimatorSet set = new AnimatorSet();
        set.playTogether(fade, sx, sy);
        set.start();
        bgBack = bgFront;
        bgFront = next;

        for (int i = 0; i < dots.getChildCount(); i++) {
            View d = dots.getChildAt(i);
            boolean on = i == slide;
            d.setBackground(round(on ? INK : 0x59FFFFFF, 3, 0, 0));
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) d.getLayoutParams();
            p.width = px(on ? 30 : 6);
            d.setLayoutParams(p);
        }

        final String[] cap = caption(slide % 4);
        heroText.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            boolean cool = "cool".equals(cap[3]);
            chipTv.setText(cap[0]);
            chipTv.setTextColor(cool ? 0xFF8FE3F0 : 0xFFFF9A80);
            chipTv.setBackground(round(cool ? 0x295ED6CF : 0x2EFF6B4A, 14, 0, 0));
            titleTv.setText(cap[1]);
            subTv.setText(cap[2]);
            heroText.setTranslationY(px(14));
            heroText.animate().alpha(1f).translationY(0).setDuration(380).start();
        }).start();
    }

    private String[] caption(int i) {
        String lastLabel = prefs.getString("last_label", null);
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        switch (i) {
            case 0: return new String[]{"سهرة الليلة", "أفلام ومسلسلات بدقة عالية", "كل تطبيقات المشاهدة بمكان واحد", ""};
            case 1: return new String[]{hour < 12 ? "صباح الخير" : "مساء الخير", "شنو تريد تشوف اليوم؟",
                    lastLabel != null ? "آخر تطبيق فتحته: " + lastLabel : "اختار تطبيق من تحت", ""};
            case 2: return new String[]{"وقت الراحة", "موسيقى هادئة للبيت", "شغّلها بضغطة وحدة من الريموت", "cool"};
            default: return customBanner
                    ? new String[]{"خلفياتك", "صورك على الشاشة", "تكدر تغيّرها من الإعدادات", "cool"}
                    : new String[]{"خلفياتك", "حط صورك بالبنر", "الإعدادات ← استيراد صور البنر", "cool"};
        }
    }

    /* ======================= apps & favourites ======================= */

    private void loadApps() {
        bg.execute(() -> {
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
            final List<App> list = new ArrayList<>();
            for (Map.Entry<String, ResolveInfo> e : found.entrySet()) {
                try {
                    App a = new App();
                    a.pkg = e.getKey();
                    a.label = e.getValue().loadLabel(pm).toString();
                    a.icon = e.getValue().loadIcon(pm);
                    list.add(a);
                } catch (Exception ignored) { }
            }
            Collections.sort(list, (x, y) -> x.label.compareToIgnoreCase(y.label));
            ui.post(() -> {
                apps.clear();
                apps.addAll(list);
                renderRow();
                gridDirty = true;
                if ("apps".equals(screen)) renderGrid();
                View f = getCurrentFocus();
                if (f == null || f == root) showScreen(screen);
            });
        });
    }

    private List<String> favPkgs() {
        String saved = prefs.getString("favs", null);
        List<String> out = new ArrayList<>();
        if (saved != null) {
            for (String p : saved.split(",")) if (!p.isEmpty()) out.add(p);
        } else {
            List<App> sorted = new ArrayList<>(apps);
            final List<String> pref = Arrays.asList(PREFER);
            Collections.sort(sorted, (x, y) -> rank(pref, x.pkg) - rank(pref, y.pkg));
            for (int i = 0; i < Math.min(7, sorted.size()); i++) out.add(sorted.get(i).pkg);
        }
        return out;
    }

    private static int rank(List<String> pref, String pkg) {
        int i = pref.indexOf(pkg);
        return i < 0 ? 99 : i;
    }

    private List<App> favs() {
        List<App> out = new ArrayList<>();
        for (String p : favPkgs()) {
            App a = find(p);
            if (a != null && out.size() < 7) out.add(a);
        }
        return out;
    }

    private App find(String pkg) {
        for (App a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    private void toggleFav(String pkg) {
        App app = find(pkg);
        if (app == null) return;
        List<String> f = new ArrayList<>();
        for (App a : favs()) f.add(a.pkg);
        if (f.contains(pkg)) {
            f.remove(pkg);
            toast("انشال " + app.label + " من الرئيسية");
        } else {
            if (f.size() >= 7) { toast("الرئيسية بيها ٧ تطبيقات، شيل واحد أول"); return; }
            f.add(pkg);
            toast("انضاف " + app.label + " للرئيسية");
        }
        prefs.edit().putString("favs", TextUtils.join(",", f)).apply();
        renderRow();
        if ("apps".equals(screen)) {
            View cur = getCurrentFocus();
            Object tag = cur != null ? cur.getTag() : null;
            renderGrid();
            if (tag != null) {
                View again = grid.findViewWithTag(tag);
                if (again != null) again.requestFocus();
            }
        } else {
            gridDirty = true;
        }
    }

    private void launch(App a) {
        prefs.edit().putString("last_pkg", a.pkg).putString("last_label", a.label).apply();
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(a.pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(a.pkg);
        if (i == null) { toast("ما كدرت أفتح هذا التطبيق"); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception e) { toast("ما كدرت أفتح هذا التطبيق"); }
    }

    private void playLast() {
        App a = find(prefs.getString("last_pkg", ""));
        if (a == null) {
            List<App> f = favs();
            if (!f.isEmpty()) a = f.get(0);
        }
        if (a != null) launch(a); else showScreen("apps");
    }

    /* ======================= settings actions ======================= */

    private void cycleZoom() {
        float[] zs = {0.8f, 0.85f, 0.9f, 0.95f, 1f};
        int idx = 0;
        for (int i = 0; i < zs.length; i++) if (Math.abs(zs[i] - zoom) < 0.01f) idx = i;
        zoom = zs[(idx + 1) % zs.length];
        prefs.edit().putFloat("zoom", zoom).apply();
        stage.setScaleX(zoom);
        stage.setScaleY(zoom);
        updateZoomLabel();
    }

    private void updateZoomLabel() {
        if (zoomTv != null) zoomTv.setText("حجم الواجهة: " + ar(String.valueOf(Math.round(zoom * 100))) + "٪");
    }

    private void openTvSettings() {
        try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) { }
    }

    /* ======================= banner images from USB ======================= */

    private File bannerDir() {
        File d = new File(getFilesDir(), "banner");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private File[] bannerFiles() {
        File[] files = bannerDir().listFiles();
        if (files == null) return new File[0];
        Arrays.sort(files);
        return files;
    }

    private void clearBanner() {
        for (File f : bannerFiles()) f.delete();
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
        toast("جاري البحث عن مجلد Banner…");
        bg.execute(this::doImport);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_IMAGES) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            toast("جاري البحث عن مجلد Banner…");
            bg.execute(this::doImport);
        } else {
            toast("لازم توافق على صلاحية الصور حتى أكدر أقرا الفلاشة");
        }
    }

    private List<File> findBannerImages() {
        List<File> roots = new ArrayList<>();
        File[] vols = new File("/storage").listFiles();
        if (vols != null) roots.addAll(Arrays.asList(vols));
        roots.add(Environment.getExternalStorageDirectory());
        File[] mvols = new File("/mnt/media_rw").listFiles();
        if (mvols != null) roots.addAll(Arrays.asList(mvols));
        File[] usb = new File("/mnt/usb").listFiles();
        if (usb != null) roots.addAll(Arrays.asList(usb));

        for (File r : roots) {
            File[] kids = r.listFiles();
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
        clearBanner();
        File dir = bannerDir();
        int ok = 0;
        for (int i = 0; i < imgs.size(); i++) {
            if (saveScaled(imgs.get(i), new File(dir, (i + 1) + ".jpg"))) ok++;
        }
        toast(ok > 0 ? "تم استيراد " + ok + " صور للبنر، تكدر تشيل الفلاشة" : "الصور ما انقرت، جرّب صور JPG");
        loadSlides();
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
}
