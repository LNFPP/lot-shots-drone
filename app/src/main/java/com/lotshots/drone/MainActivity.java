package com.lotshots.drone;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {

    // Purple palette
    static final int C_BG = 0xFF140E1F, C_HEADER = 0xFF201634, C_CARD = 0xFF2A1F3D, C_CARD_ON = 0xFF4A2F7A,
            C_ON_STROKE = 0xFFB388FF, C_PRIMARY = 0xFF7E57C2, C_SECONDARY = 0xFF3B2D55, C_ACCENT = 0xFF9C6ADE,
            C_SECTION = 0xFFC9A7FF, C_MUTED = 0xFFB9ACD0, C_DANGER = 0xFF7A1F3D;

    private Store store;
    private LinearLayout root;
    private LinearLayout head;   // fixed header (doesn't scroll)

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setBackgroundColor(C_BG);
        head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(20), dp(12), dp(20), dp(12));
        head.setBackgroundColor(C_HEADER);
        head.setElevation(dp(4));
        head.setVisibility(View.GONE);
        frame.addView(head, new LinearLayout.LayoutParams(-1, -2));
        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(32));
        sv.addView(root);
        frame.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(frame);
        getWindow().getDecorView().setBackgroundColor(C_BG);
    }

    @Override
    protected void onResume() {
        super.onResume();
        BubbleService.onChange = this::render;
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        BubbleService.onChange = null;
        stopAnimations();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        render();
    }

    // ------------------------------------------------------------------ permissions

    private boolean hasOverlay() {
        return Settings.canDrawOverlays(this);
    }

    private boolean hasFiles() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void askOverlay() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("Overlay setting not available")
                    .setMessage("This controller hides the \"Display over other apps\" setting. Connect it to your Mac with USB and run:\n\n"
                            + "adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow")
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private void askFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    // ------------------------------------------------------------------ UI helpers

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void section(String title) {
        TextView t = text(title.toUpperCase(Locale.US), 13, C_SECTION, true);
        t.setPadding(0, dp(22), 0, dp(6));
        root.addView(t);
    }

    private TextView button(String label, int color, View.OnClickListener click) {
        TextView t = text(label, 15, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(8));
        t.setBackground(g);
        t.setOnClickListener(click);
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(4), 0, dp(4));
        return r;
    }

    private void addButtons(TextView... buttons) {
        LinearLayout r = row();
        for (TextView b : buttons) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, dp(10), 0);
            r.addView(b, lp);
        }
        root.addView(r);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ screen

    // ------------------------------------------------------------------ navigation

    private static final String HOME = "home", SETTINGS = "settings", COMMUNITIES = "communities",
            COMMUNITY = "community", FLIGHTS = "flights", FLIGHT_DATE = "flightDate", WHAT_CHANGED = "whatChanged",
            DATE360 = "date360";
    private String screen = HOME;
    private String openCommunity = null;
    private String openFlightDate = null;
    private String openDate360 = null;

    private ValueAnimator pulse;
    private final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());
    private final List<Object[]> tickViews = new ArrayList<>(); // {TextView, startMs, prefix}
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            for (Object[] t : tickViews) ((TextView) t[0]).setText(t[2] + elapsed((Long) t[1]));
            if (!tickViews.isEmpty()) ticker.postDelayed(this, 1000);
        }
    };

    private void go(String to) {
        screen = to;
        render();
    }

    @Override
    public void onBackPressed() {
        switch (screen) {
            case COMMUNITY:
                go(COMMUNITIES);
                break;
            case FLIGHT_DATE:
                go(FLIGHTS);
                break;
            case DATE360:
                go(COMMUNITY);
                break;
            case WHAT_CHANGED:
                go(SETTINGS);
                break;
            case HOME:
                super.onBackPressed();
                break;
            default:
                go(HOME);
        }
    }

    private void stopAnimations() {
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        tickViews.clear();
        ticker.removeCallbacks(tick);
    }

    private void render() {
        stopAnimations();
        root.removeAllViews();
        head.removeAllViews();
        head.setVisibility(View.GONE);
        if (COMMUNITY.equals(screen) && (openCommunity == null || !store.communityNames().contains(openCommunity))) screen = COMMUNITIES;
        if (FLIGHT_DATE.equals(screen) && (openFlightDate == null || !store.flightDates().contains(openFlightDate))) screen = FLIGHTS;
        if (DATE360.equals(screen) && (openCommunity == null || openDate360 == null
                || !store.communityNames().contains(openCommunity) || !store.dates(openCommunity).contains(openDate360))) screen = COMMUNITY;
        if (COMMUNITY.equals(screen) && (openCommunity == null || !store.communityNames().contains(openCommunity))) screen = COMMUNITIES;
        switch (screen) {
            case SETTINGS:
                renderSettings();
                break;
            case COMMUNITIES:
                renderCommunities();
                break;
            case COMMUNITY:
                renderCommunity(openCommunity);
                break;
            case FLIGHTS:
                renderFlightLog();
                break;
            case FLIGHT_DATE:
                renderFlightDate(openFlightDate);
                break;
            case WHAT_CHANGED:
                renderWhatChanged();
                break;
            case DATE360:
                renderDate360(openCommunity, openDate360);
                break;
            default:
                screen = HOME;
                renderHome();
        }
        if (!tickViews.isEmpty()) ticker.post(tick);
    }

    /** Fixed header: back link, big title (+ smaller line under it), a button on the right, and a row of buttons at the bottom. */
    private void header(String backLabel, String backTo, String titleText, String sub, View right, View... bottom) {
        head.setVisibility(View.VISIBLE);
        if (backTo != null) {
            TextView b = button("\u2190  " + backLabel, C_SECONDARY, v -> go(backTo));
            b.setTextSize(13);
            b.setPadding(dp(12), dp(6), dp(12), dp(6));
            head.addView(b, new LinearLayout.LayoutParams(-2, -2));
        }
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(8), 0, 0);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(titleText, 24, Color.WHITE, true);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(t);
        if (sub != null) titles.addView(text(sub, 14, C_MUTED, false));
        r.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        if (right != null) {
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
            rl.setMargins(dp(10), 0, 0, 0);
            r.addView(right, rl);
        }
        head.addView(r);
        if (bottom.length > 0) {
            LinearLayout bl = new LinearLayout(this);
            bl.setPadding(0, dp(10), 0, 0);
            for (int i = 0; i < bottom.length; i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1);
                if (i < bottom.length - 1) lp.setMargins(0, 0, dp(6), 0);
                bl.addView(bottom[i], lp);
            }
            head.addView(bl);
        }
    }

    private boolean setupDone() {
        return hasOverlay() && hasFiles();
    }

    private void backButton(String label, String to) {
        root.addView(button("←  " + label, C_SECONDARY, v -> go(to)), new LinearLayout.LayoutParams(-2, -2));
    }

    private void title(String t) {
        TextView tv = text(t, 26, Color.WHITE, true);
        tv.setPadding(0, dp(12), 0, dp(4));
        root.addView(tv);
    }

    private static String prettyDate(String folder) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(folder);
            return new SimpleDateFormat("EEE, MMM d, yyyy", Locale.US).format(d);
        } catch (Exception e) {
            return folder;
        }
    }

    private static String clock(long ms) {
        return new SimpleDateFormat("h:mm a", Locale.US).format(new Date(ms));
    }

    private static String elapsed(long startMs) {
        long sec = Math.max(0, (System.currentTimeMillis() - startMs) / 1000);
        return String.format(Locale.US, "%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
    }

    private static String duration(long ms) {
        long min = Math.max(0, ms / 60000);
        return min >= 60 ? (min / 60) + " hr " + (min % 60) + " min" : min + " min";
    }

    private GradientDrawable box(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(12));
        if (stroke != 0) g.setStroke(dp(2), stroke);
        return g;
    }

    /** A big full-width tile button with a title and a subtitle. */
    private LinearLayout tile(String titleText, String sub, int fill, View.OnClickListener click) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(18), dp(16), dp(18), dp(16));
        t.setBackground(box(fill, 0));
        t.addView(text(titleText, 20, Color.WHITE, true));
        if (sub != null) t.addView(text(sub, 13, 0xFFCCCCCC, false));
        t.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(6), 0, dp(6));
        root.addView(t, lp);
        return t;
    }

    // ------------------------------------------------------------------ home

    private void renderHome() {
        root.addView(text("Lot Shots — Drone", 26, Color.WHITE, true));
        root.addView(text("Version " + myVersionName() + "  (build " + myBuild() + ")", 12, C_MUTED, false));
        if (BubbleService.running) {
            root.addView(text("● 360 bubble on: " + store.active() + "  ·  " + prettyDate(store.activeDate()), 14, 0xFF81C784, true));
        }

        int nc = store.communityNames().size();
        tile("◎  360s", nc + (nc == 1 ? " place" : " places") + " · lot bubble", C_PRIMARY, v -> go(COMMUNITIES));

        Object[] run = store.runningSession();
        LinearLayout fl = tile("✈  Flight Log", run == null ? "Time at each place" : null, C_PRIMARY, v -> go(FLIGHTS));
        if (run != null) {
            JSONObject e = (JSONObject) run[1];
            TextView t = text("", 14, 0xFFFFCDD2, true);
            fl.addView(t);
            tickViews.add(new Object[]{t, e.optLong("start"), "● Timer running — " + e.optString("place") + "  "});
            // slow red pulse so you remember to stop the time
            GradientDrawable bg = box(C_PRIMARY, 0);
            fl.setBackground(bg);
            pulse = ValueAnimator.ofObject(new ArgbEvaluator(), C_PRIMARY, 0xFF9E1B3A);
            pulse.setDuration(1600);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.addUpdateListener(an -> bg.setColor((Integer) an.getAnimatedValue()));
            pulse.start();
        }

        LinearLayout st = tile("⚙  Settings", "Permissions, updates, tests", C_PRIMARY, v -> go(SETTINGS));
        if (!setupDone()) st.addView(text("⚠  Do this first", 15, 0xFFFFD54F, true));
    }

    // ------------------------------------------------------------------ settings

    private void renderSettings() {
        header("Home", HOME, "Settings", setupDone() ? "All set" : "Finish the steps marked \u274C", null);

        section("Permissions");
        LinearLayout r1 = row();
        r1.addView(text((hasOverlay() ? "✅" : "❌") + "  Show bubble over DJI Fly", 15, Color.WHITE, false), new LinearLayout.LayoutParams(0, -2, 1));
        if (!hasOverlay()) r1.addView(button("Allow", C_PRIMARY, v -> askOverlay()));
        root.addView(r1);
        LinearLayout r2 = row();
        r2.addView(text((hasFiles() ? "✅" : "❌") + "  Read & save photos", 15, Color.WHITE, false), new LinearLayout.LayoutParams(0, -2, 1));
        if (!hasFiles()) r2.addView(button("Allow", C_PRIMARY, v -> askFiles()));
        root.addView(r2);

        section("Test: What changed?");
        root.addView(text("Finds where DJI Fly keeps photo previews on the controller and what's in them.", 13, C_MUTED, false));
        if (WhatChanged.before == null) {
            root.addView(text("1. Tap Start   2. In DJI Fly take one regular photo, then one 360 (wait for it to finish)   3. Come back and tap Stop", 12, 0xFF888888, false));
            addButtons(button("▶  Start test", 0xFF2E7D32, v -> startWhatChanged()));
        } else {
            root.addView(text("● Test running since " + clock(WhatChanged.startedAt) + " — take one regular photo and one 360 in DJI Fly, then tap Stop.", 13, 0xFFFFCDD2, true));
            addButtons(button("■  Stop & show results", 0xFFC62828, v -> stopWhatChanged()),
                    button("Cancel", C_SECONDARY, v -> {
                        WhatChanged.before = null;
                        render();
                    }));
        }
        addButtons(button("Show DJI preview folder", C_PRIMARY, v -> {
            AlertDialog wait = new AlertDialog.Builder(this).setMessage("Reading DJI Fly's previews\u2026").setCancelable(false).show();
            new Thread(() -> {
                String r = WhatChanged.previewFolder();
                runOnUiThread(() -> {
                    wait.dismiss();
                    WhatChanged.lastReport = r;
                    go(WHAT_CHANGED);
                });
            }).start();
        }));
        if (WhatChanged.lastReport != null) addButtons(button("Show last results", C_SECONDARY, v -> go(WHAT_CHANGED)));

        section("App");
        root.addView(text("Version " + myVersionName() + "  (build " + myBuild() + ")", 14, Color.WHITE, false));
        addButtons(button("↻  Check for update", C_PRIMARY, v -> checkForUpdate()));
    }

    // ------------------------------------------------------------------ 360s: communities

    private void renderCommunities() {
        TextView addC = button("+ Add", C_ACCENT, v -> editCommunity(null));
        header("Home", HOME, "360s", "Communities & places", addC);

        if (BubbleService.running) {
            root.addView(text("● Bubble on: " + store.active() + "  ·  " + prettyDate(store.activeDate()), 15, 0xFF81C784, true));
            TextView stop = button("■  Stop bubble", 0xFFC62828, v -> {
                stopService(new Intent(this, BubbleService.class));
                root.postDelayed(this::render, 300);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
            lp.setMargins(0, dp(6), 0, 0);
            root.addView(stop, lp);
        }

        section("Communities");
        List<String> names = store.communityNames();
        if (names.isEmpty()) root.addView(text("No communities yet. Add one and paste its lots.", 14, C_MUTED, false));
        final int perRow = 2;
        LinearLayout row = null;
        for (int i = 0; i < names.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                root.addView(row);
            }
            String name = names.get(i);
            boolean on = name.equals(store.active()) && !store.activeDate().isEmpty();
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(14), dp(14), dp(14));
            card.setBackground(box(on ? C_CARD_ON : C_CARD, on ? C_ON_STROKE : 0));
            card.addView(text(name, 18, Color.WHITE, true));
            int nd = store.dates(name).size();
            card.addView(text(store.lots(name).size() + " lots  ·  " + nd + (nd == 1 ? " date" : " dates"), 13, C_MUTED, false));
            if (on) card.addView(text("● Shooting " + store.activeDate(), 12, 0xFF81C784, true));
            card.setOnClickListener(v -> {
                openCommunity = name;
                go(COMMUNITY);
            });
            card.setOnLongClickListener(v -> {
                communityMenu(name);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            row.addView(card, lp);
        }
        if (row != null && names.size() % perRow != 0) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        if (!names.isEmpty()) root.addView(text("Long-press a community to edit its lots or delete it.", 12, 0xFF888888, false));
    }

    // ------------------------------------------------------------------ flight log

    private void renderFlightLog() {
        TextView add = button("+ Add date", C_ACCENT, v -> {
            String d = Store.today();
            if (!store.addFlightDate(d)) toast("Today is already in the Flight Log");
            render();
        });
        add.setTextSize(14);
        header("Home", HOME, "Flight Log", null, add);

        List<String> dates = store.flightDates();
        if (dates.isEmpty()) root.addView(text("No dates yet. Tap + Add date when you start your day.", 14, C_MUTED, false));
        Object[] run = store.runningSession();
        for (String d : dates) {
            List<JSONObject> ss = store.sessions(d);
            boolean running = run != null && d.equals(run[0]);
            StringBuilder places = new StringBuilder();
            for (JSONObject e : ss) {
                if (places.length() > 0) places.append(", ");
                places.append(e.optString("place"));
            }
            LinearLayout t = new LinearLayout(this);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(dp(16), dp(12), dp(8), dp(12));
            boolean today = d.equals(Store.today());
            t.setBackground(box(running ? 0xFF4A1414 : C_CARD, today ? 0xFF4CAF50 : (running ? 0xFFFF5252 : 0)));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.addView(text(prettyDate(d) + (today ? "   \u25CF Today" : ""), 18, Color.WHITE, true));
            labels.addView(text(ss.isEmpty() ? "No places yet" : places.toString(), 13, C_MUTED, false));
            if (running) {
                TextView tv = text("", 13, 0xFFFFCDD2, true);
                labels.addView(tv);
                tickViews.add(new Object[]{tv, ((JSONObject) run[1]).optLong("start"), "\u25CF Timer running  "});
            }
            t.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            t.addView(button("\uD83D\uDDD1", C_DANGER, v -> confirmDeleteFlightDate(d)));
            t.setOnClickListener(v -> {
                openFlightDate = d;
                go(FLIGHT_DATE);
                if (store.sessions(d).isEmpty()) addPlace(d);
            });
            t.setOnLongClickListener(v -> {
                confirmDeleteFlightDate(d);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(4), 0, dp(4));
            root.addView(t, lp);
        }
    }

    private void confirmDeleteFlightDate(String d) {
        boolean running = false;
        Object[] run = store.runningSession();
        if (run != null && d.equals(run[0])) running = true;
        new AlertDialog.Builder(this)
                .setTitle("Delete " + prettyDate(d) + "?")
                .setMessage("Removes this date's places and times from the Flight Log."
                        + (running ? "\n\nA timer is still running on this date \u2014 it will be deleted too." : "")
                        + "\n\nAlready-exported CSVs aren't affected.")
                .setPositiveButton("Delete", (dd, w) -> {
                    store.deleteFlightDate(d);
                    render();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void renderFlightDate(String date) {
        TextView exp = button("\u21EA Export data", C_PRIMARY, v -> exportFlightDate(date, true));
        exp.setTextSize(14);
        header("Flight Log", FLIGHTS, prettyDate(date), null, exp,
                button("+  Community or location", C_ACCENT, v -> addPlace(date)));

        List<JSONObject> ss = store.sessions(date);
        if (ss.isEmpty()) root.addView(text("Add the community or location you're flying at.", 14, C_MUTED, false));
        for (int i = ss.size() - 1; i >= 0; i--) {
            JSONObject e = ss.get(i);
            long id = e.optLong("id"), start = e.optLong("start"), stop = e.optLong("stop");
            boolean running = start > 0 && stop == 0;

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(12), dp(16), dp(12));
            card.setBackground(box(C_CARD, running ? 0xFFFF5252 : 0));
            card.addView(text(e.optString("place"), 19, Color.WHITE, true));
            card.addView(text("community".equals(e.optString("type")) ? "Community" : "Location", 12, 0xFF888888, false));

            if (start > 0) {
                String times = "Started " + clock(start) + (stop > 0 ? "   →   Stopped " + clock(stop) + "   (" + duration(stop - start) + ")" : "");
                TextView tt = text(times, 14, 0xFFDDDDDD, false);
                tt.setPadding(0, dp(6), 0, 0);
                card.addView(tt);
            }

            TextView del = button("\uD83D\uDDD1  Delete location", C_DANGER, v -> new AlertDialog.Builder(this)
                    .setMessage("Delete " + e.optString("place") + " from " + prettyDate(date) + "?" + (running ? "\n\nIts timer is still running." : ""))
                    .setPositiveButton("Delete", (d2, w) -> {
                        store.deleteSession(date, id);
                        render();
                    })
                    .setNegativeButton("Cancel", null).show());
            del.setTextSize(13);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(40));
            dlp.setMargins(0, dp(10), 0, 0);
            card.addView(del, dlp);

            TextView btn;
            if (start == 0) {
                btn = button("\u25B6  Start time", 0xFF2E7D32, v -> {
                    store.setSessionTime(date, id, "start", System.currentTimeMillis());
                    render();
                });
            } else if (running) {
                btn = button("", 0xFFC62828, v -> {
                    store.setSessionTime(date, id, "stop", System.currentTimeMillis());
                    render();
                });
                tickViews.add(new Object[]{btn, start, "■  Stop time   "});
            } else {
                btn = button("▶  Start again here", C_SECONDARY, v -> {
                    store.addSession(date, e.optString("type"), e.optString("place"));
                    List<JSONObject> now = store.sessions(date);
                    store.setSessionTime(date, now.get(now.size() - 1).optLong("id"), "start", System.currentTimeMillis());
                    render();
                });
            }
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(52));
            blp.setMargins(0, dp(10), 0, 0);
            card.addView(btn, blp);

            card.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("Remove " + e.optString("place") + "?")
                        .setItems(new String[]{"Clear times (keep place)", "Remove this place"}, (d, which) -> {
                            if (which == 0) {
                                store.setSessionTime(date, id, "start", 0);
                                store.setSessionTime(date, id, "stop", 0);
                            } else {
                                store.deleteSession(date, id);
                            }
                            render();
                        })
                        .setNegativeButton("Cancel", null).show();
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(5), 0, dp(5));
            root.addView(card, lp);
        }
        if (!ss.isEmpty()) {
            root.addView(text("Long-press a place to clear its times or remove it.", 12, 0xFF888888, false));
            addButtons(button("Other apps\u2026", C_SECONDARY, v -> exportFlightDate(date, false)));
            root.addView(text("Export data opens LocalSend with the CSV ready \u2014 tap your Mac. A copy is also saved to " + flightLogDir().getAbsolutePath(), 12, 0xFF888888, false));
        }
    }

    private static final String LOCALSEND_PKG = "org.localsend.localsend_app";

    private File flightLogDir() {
        return new File(Store.outputRoot(), "FlightLogs");
    }

    /** One CSV per date: Date, Type, Place, Start, Stop, Duration. Times include the time zone. */
    private void exportFlightDate(String date, boolean toMac) {
        try {
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
            StringBuilder sb = new StringBuilder("Date,Type,Place,Start,Stop,Duration (min)\n");
            int rows = 0;
            for (JSONObject e : store.sessions(date)) {
                long start = e.optLong("start"), stop = e.optLong("stop");
                if (start == 0) continue;
                sb.append(date).append(',')
                        .append("community".equals(e.optString("type")) ? "Community" : "Location").append(',')
                        .append(csv(e.optString("place"))).append(',')
                        .append(iso.format(new Date(start))).append(',')
                        .append(stop > 0 ? iso.format(new Date(stop)) : "").append(',')
                        .append(stop > 0 ? String.valueOf(Math.round((stop - start) / 60000.0)) : "")
                        .append('\n');
                rows++;
            }
            if (rows == 0) {
                toast("Nothing to export yet \u2014 start a time first");
                return;
            }
            String name = "FlightLog_" + date + ".csv";

            // backup copy next to the 360s (controller or microSD)
            String backupNote;
            try {
                File dir = flightLogDir();
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("no folder");
                try (FileWriter w = new FileWriter(new File(dir, name))) {
                    w.write(sb.toString());
                }
                backupNote = "Saved a copy to " + dir.getAbsolutePath();
            } catch (Exception ex) {
                backupNote = "Couldn't save the backup copy (check photo access)";
            }

            // file for the share menu
            File shared = new File(ShareProvider.shareDir(this), name);
            try (FileWriter w = new FileWriter(shared)) {
                w.write(sb.toString());
            }
            android.net.Uri uri = ShareProvider.uriFor(shared);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/csv");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "Flight Log " + date);
            send.setClipData(android.content.ClipData.newRawUri(name, uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            toast(backupNote);
            if (toMac) {
                // Straight into LocalSend with the file loaded; you just tap your Mac.
                Intent ls = new Intent(send).setPackage(LOCALSEND_PKG);
                try {
                    startActivity(ls);
                    return;
                } catch (android.content.ActivityNotFoundException ex) {
                    toast("LocalSend isn't installed \u2014 showing other apps");
                }
            }
            startActivity(Intent.createChooser(send, "Send Flight Log " + date));
        } catch (Exception ex) {
            toast("Export failed: " + ex.getMessage());
        }
    }

    /** Two choices: Community or Location. */
    private void addPlace(String date) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(4));
        final AlertDialog[] dlg = new AlertDialog[1];
        TextView c = button("◎  Community", C_PRIMARY, v -> {
            dlg[0].dismiss();
            pickPlace(date, true);
        });
        TextView l = button("📍  Location", C_PRIMARY, v -> {
            dlg[0].dismiss();
            pickPlace(date, false);
        });
        c.setTextSize(18);
        l.setTextSize(18);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(60));
        lp.setMargins(0, dp(4), 0, dp(8));
        box.addView(c, lp);
        box.addView(l, new LinearLayout.LayoutParams(-1, dp(60)));
        dlg[0] = new AlertDialog.Builder(this).setTitle("Where are you flying?").setView(box).setNegativeButton("Cancel", null).show();
    }

    /** "+ Add …" on top, then a scrolling list of existing communities (or locations). */
    private void pickPlace(String date, boolean community) {
        String kind = community ? "community" : "location";
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        final AlertDialog[] dlg = new AlertDialog[1];

        TextView add = button("+  Add " + kind, C_ACCENT, v -> {
            EditText name = new EditText(this);
            name.setHint(community ? "Community name" : "Location name");
            name.setSingleLine(true);
            name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            LinearLayout nb = new LinearLayout(this);
            nb.setPadding(dp(20), dp(8), dp(20), 0);
            nb.addView(name, new LinearLayout.LayoutParams(-1, -2));
            new AlertDialog.Builder(this)
                    .setTitle("New " + kind)
                    .setView(nb)
                    .setPositiveButton("Add", (d, w) -> {
                        String n = name.getText().toString().trim();
                        if (n.isEmpty()) {
                            toast("Type a name");
                            return;
                        }
                        // Already exists (as a community or a location)? Use that one.
                        for (String x : store.communityNames()) if (x.equalsIgnoreCase(n)) {
                            useSession(date, "community", x, dlg[0]);
                            return;
                        }
                        for (String x : store.locationNames()) if (x.equalsIgnoreCase(n)) {
                            useSession(date, "location", x, dlg[0]);
                            return;
                        }
                        if (community) store.putCommunity(n, new ArrayList<>()); // also shows in 360s
                        else store.addLocation(n);
                        useSession(date, kind, n, dlg[0]);
                    })
                    .setNegativeButton("Cancel", null).show();
        });
        add.setTextSize(16);
        box.addView(add, new LinearLayout.LayoutParams(-1, dp(52)));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        List<String> names = community ? store.communityNames() : store.locationNames();
        if (names.isEmpty()) list.addView(text("None yet — tap + Add " + kind + ".", 14, C_MUTED, false));
        for (String n : names) {
            TextView t = button(n, C_SECONDARY, v -> useSession(date, kind, n, dlg[0]));
            t.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(3), 0, dp(3));
            list.addView(t, lp);
        }
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(Math.min(320, Math.max(60, names.size() * 52))));
        slp.setMargins(0, dp(10), 0, 0);
        box.addView(sv, slp);

        dlg[0] = new AlertDialog.Builder(this)
                .setTitle(community ? "Community" : "Location")
                .setView(box)
                .setNegativeButton("Back", (d, w) -> addPlace(date))
                .show();
    }

    private void useSession(String date, String kind, String place, AlertDialog toClose) {
        store.addSession(date, kind, place);
        if (toClose != null) toClose.dismiss();
        render();
    }

    /** Opens DJI Fly's album if DJI Fly offers a public album screen; otherwise opens DJI Fly. */
    private void openDjiAlbum() {
        String pkg = "dji.go.v5";
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
            if (pi.activities != null) {
                for (android.content.pm.ActivityInfo a : pi.activities) {
                    String n = a.name.toLowerCase(Locale.US);
                    if (a.exported && (n.contains("album") || n.contains("playback") || n.contains("gallery"))) {
                        try {
                            startActivity(new Intent().setClassName(pkg, a.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            toast("Scroll past today's 360s, then come back and tap Match 360s");
                            return;
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) {
            startActivity(launch);
            toast("In DJI Fly, tap the album button and scroll past today's 360s");
        } else {
            toast("DJI Fly isn't installed");
        }
    }

    private void renderCommunity(String name) {
        TextView nd = button("+ New date", C_ACCENT, v -> {
            String d = store.createDate(name);
            if (d.isEmpty()) {
                toast("Couldn't create the date — allow photo access first (Settings)");
                return;
            }
            openDate360 = d;
            selectDate(name, d);
            go(DATE360);
        });
        nd.setTextSize(14);
        int nl = store.lots(name).size();
        header("360s", COMMUNITIES, name, nl + (nl == 1 ? " lot" : " lots"), nd);

        List<String> dates = store.dates(name);
        if (dates.isEmpty()) root.addView(text("No dates yet. Tap + New date before you fly.", 14, C_MUTED, false));
        boolean thisActive = name.equals(store.active());
        final int perRow = 2;
        LinearLayout row = null;
        for (int i = 0; i < dates.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                root.addView(row);
            }
            String d = dates.get(i);
            boolean on = thisActive && d.equals(store.activeDate());
            LinearLayout sq = new LinearLayout(this);
            sq.setOrientation(LinearLayout.VERTICAL);
            sq.setGravity(Gravity.CENTER_VERTICAL);
            sq.setPadding(dp(14), dp(12), dp(14), dp(12));
            sq.setBackground(box(on ? C_CARD_ON : C_CARD, on ? C_ON_STROKE : 0));
            String[] pd = prettyDate(d).split(", ");
            sq.addView(text(pd.length == 3 ? pd[0] + ", " + pd[1] : d, 18, Color.WHITE, true));
            sq.addView(text(pd.length == 3 ? pd[2] : "", 13, C_MUTED, false));
            int n = store.loggedLots(name, d).size();
            TextView c = text(n + (n == 1 ? " lot logged" : " lots logged"), 14, Color.WHITE, false);
            c.setPadding(0, dp(8), 0, 0);
            sq.addView(c);
            if (on) sq.addView(text("● Shooting here", 12, 0xFF81C784, true));
            sq.setOnClickListener(v -> {
                selectDate(name, d);
                openDate360 = d;
                go(DATE360);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(128), 1);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            row.addView(sq, lp);
        }
        if (row != null && dates.size() % perRow != 0) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        addButtons(button("Edit lots", C_SECONDARY, v -> editCommunity(name)));
    }

    /** One 360s date: header with bubble, Match / Send / Delete; lots newest first. */
    private void renderDate360(String community, String date) {
        boolean active = community.equals(store.active()) && date.equals(store.activeDate());
        TextView bubbleBtn = BubbleService.running && active
                ? button("■ Stop bubble", 0xFFC62828, v -> {
                    stopService(new Intent(this, BubbleService.class));
                    root.postDelayed(this::render, 300);
                })
                : button("▶ Start bubble", 0xFF2E7D32, v -> {
                    if (!active && !selectDate(community, date)) return;
                    startBubble();
                });
        bubbleBtn.setTextSize(14);
        header(community, COMMUNITY, prettyDate(date), community, bubbleBtn,
                button("Match 360s", C_PRIMARY, v -> showMatch(community, date)),
                button("⇪ Send to Mac", C_PRIMARY, v -> exportLotCsv(community, date)),
                button("🗑 Delete", C_DANGER, v -> confirmDeleteDate(community, date)));

        TextView albumTip = text("\uD83D\uDCF7  Open DJI Fly preview album and scroll the day  \u203A", 15, 0xFFFFD54F, true);
        albumTip.setPadding(dp(12), dp(10), dp(12), dp(10));
        albumTip.setBackground(box(0xFF3A3018, 0xFFFFD54F));
        albumTip.setOnClickListener(v -> openDjiAlbum());
        LinearLayout.LayoutParams atl = new LinearLayout.LayoutParams(-1, -2);
        atl.setMargins(0, 0, 0, dp(10));
        root.addView(albumTip, atl);

        if (!active) {
            root.addView(text("The bubble is set to " + (store.active().isEmpty() ? "nothing" : store.active() + " · " + prettyDate(store.activeDate())) + ".", 13, 0xFFFFD54F, false));
            addButtons(button("Use this date for the bubble", C_SECONDARY, v -> {
                if (selectDate(community, date)) render();
            }));
        } else if (BubbleService.running) {
            root.addView(text("Bubble is on. Switch to DJI Fly — it stays on top.", 13, 0xFF81C784, false));
        }

        List<JSONObject> ws = store.windows(community, date);
        if (ws.isEmpty()) root.addView(text("No lots yet. Set each lot with the bubble right before its 360.", 14, C_MUTED, false));
        SimpleDateFormat fmt = new SimpleDateFormat("h:mm:ss a", Locale.US);
        long openId = store.openWindowId();
        for (int i = ws.size() - 1; i >= 0; i--) {
            JSONObject e = ws.get(i);
            long id = e.optLong("id"), fin = e.optLong("finish");
            boolean waiting = fin == 0;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(12), dp(16), dp(12));
            card.setBackground(box(C_CARD, waiting ? 0xFFFF5252 : 0));
            card.addView(text("Lot " + e.optString("lot"), 20, Color.WHITE, true));
            card.addView(text("Set " + fmt.format(new Date(e.optLong("set")))
                    + (waiting ? "   →   waiting for Finished stitch" : "   →   Finished " + fmt.format(new Date(fin))), 13, waiting ? 0xFFFF8A80 : C_MUTED, false));
            if (waiting && id == openId) {
                String lot = e.optString("lot");
                LinearLayout br = new LinearLayout(this);
                br.setPadding(0, dp(10), 0, 0);
                TextView yes = button("✓ Finished stitch", 0xFF2E7D32, v -> {
                    store.finishWindow(id, System.currentTimeMillis());
                    store.setOpenWindowId(0);
                    store.setCurrentLot("");
                    restartBubbleIfRunning();
                    toast("✓ Lot " + lot + " logged");
                    render();
                });
                TextView cancel = button("Cancel lot", C_DANGER, v -> new AlertDialog.Builder(this)
                        .setMessage("Cancel Lot " + lot + "? Its set time is removed.")
                        .setPositiveButton("Yes", (d2, w) -> {
                            store.cancelWindow(id);
                            store.setOpenWindowId(0);
                            store.setCurrentLot("");
                            restartBubbleIfRunning();
                            render();
                        })
                        .setNegativeButton("No", null).show());
                LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, dp(46), 1);
                l1.setMargins(0, 0, dp(6), 0);
                br.addView(yes, l1);
                br.addView(cancel, new LinearLayout.LayoutParams(0, dp(46), 1));
                card.addView(br);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(4), 0, dp(4));
            root.addView(card, lp);
        }
    }

    private interface MatchDone {
        void done(LotMatcher.Result r);
    }

    private void runMatch(String community, String date, MatchDone then) {
        if (!hasFiles()) {
            toast("Allow photo access first (Settings → Permissions)");
            return;
        }
        AlertDialog wait = new AlertDialog.Builder(this).setMessage("Matching 360s to lots…").setCancelable(false).show();
        new Thread(() -> {
            LotMatcher.Result r = LotMatcher.match(store, community, date);
            runOnUiThread(() -> {
                wait.dismiss();
                then.done(r);
            });
        }).start();
    }

    private String matchSummary(LotMatcher.Result r) {
        SimpleDateFormat fmt = new SimpleDateFormat("h:mm:ss a", Locale.US);
        StringBuilder sb = new StringBuilder();
        if (!r.folderReadable) sb.append("⚠ Can't read DJI Fly's preview folder.\n\n");
        int matched = 0;
        for (LotMatcher.Row row : r.rows) if (!row.panos.isEmpty()) matched++;
        sb.append(matched).append(" of ").append(r.rows.size()).append(" lots matched to a 360\n\n");
        for (LotMatcher.Row row : r.rows) {
            String lot = row.window.optString("lot");
            if (row.panos.isEmpty()) {
                sb.append("✗ Lot ").append(lot).append(" — no 360 found\n");
            } else {
                for (LotMatcher.Pano p : row.panos) {
                    sb.append("✓ Lot ").append(lot).append(" → ").append(p.droneFile.replace(".JPG", ""))
                            .append("  (started ").append(fmt.format(new Date(p.started))).append(")\n");
                }
                if (row.panos.size() > 1) sb.append("   ⚠ ").append(row.panos.size()).append(" 360s in this lot\n");
            }
        }
        if (!r.unmatched.isEmpty()) {
            sb.append("\n360s not in any lot window:\n");
            for (LotMatcher.Pano p : r.unmatched) sb.append("  ").append(p.droneFile.replace(".JPG", "")).append("  (").append(fmt.format(new Date(p.started))).append(")\n");
        }
        if (r.pano360sThatDay == 0) sb.append("\nNo 360 previews found for this date. Open DJI Fly's album (scroll past today's 360s) and try again.");
        return sb.toString();
    }

    private void showMatch(String community, String date) {
        runMatch(community, date, r -> new AlertDialog.Builder(this)
                .setTitle(community + " · " + prettyDate(date))
                .setMessage(matchSummary(r))
                .setPositiveButton("OK", null)
                .setNeutralButton("Send CSV to Mac", (d, w) -> exportLotCsv(community, date))
                .show());
    }

    /** Matches, saves the CSV next to the date, and opens LocalSend with it. */
    private void exportLotCsv(String community, String date) {
        runMatch(community, date, r -> {
            String csv = LotMatcher.csv(community, r);
            String name = Names.clean(community) + "_360s_" + date + ".csv";
            try {
                File dir = Store.dateDir(community, date);
                dir.mkdirs();
                try (FileWriter w = new FileWriter(new File(dir, name))) {
                    w.write(csv);
                }
            } catch (Exception ignored) {
            }
            try {
                File f = new File(ShareProvider.shareDir(this), name);
                try (FileWriter w = new FileWriter(f)) {
                    w.write(csv);
                }
                android.net.Uri uri = ShareProvider.uriFor(f);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/csv");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.putExtra(Intent.EXTRA_SUBJECT, community + " 360s " + date);
                send.setClipData(android.content.ClipData.newRawUri(name, uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                int matched = 0;
                for (LotMatcher.Row row : r.rows) if (!row.panos.isEmpty()) matched++;
                toast(matched + " of " + r.rows.size() + " lots matched");
                try {
                    startActivity(new Intent(send).setPackage(LOCALSEND_PKG));
                } catch (android.content.ActivityNotFoundException ex) {
                    startActivity(Intent.createChooser(send, "Send " + name));
                }
            } catch (Exception e) {
                toast("Export failed: " + e.getMessage());
            }
        });
    }

    private boolean selectDate(String community, String date) {
        boolean changed = !community.equals(store.active()) || !date.equals(store.activeDate());
        if (changed && store.openWindowId() != 0) {
            toast("Finish or cancel Lot " + store.currentLot() + " first");
            return false;
        }
        store.setActive(community);
        store.setActiveDate(date);
        if (changed) store.setCurrentLot("");
        restartBubbleIfRunning();
        render();
        return true;
    }

    private void confirmDeleteDate(String community, String date) {
        int n = store.windows(community, date).size();
        new AlertDialog.Builder(this)
                .setTitle("Delete " + prettyDate(date) + "?")
                .setMessage("Removes this date and its " + n + (n == 1 ? " lot" : " lots") + " (set and finished times) from Lot Shots.\n\n"
                        + "Photos on the drone and CSVs already sent to your Mac are not affected.")
                .setPositiveButton("Delete", (d, w) -> {
                    if (BubbleService.running && community.equals(store.active()) && date.equals(store.activeDate())) {
                        stopService(new Intent(this, BubbleService.class));
                    }
                    if (!store.deleteDate(community, date)) toast("Some files couldn't be deleted");
                    if (DATE360.equals(screen)) screen = COMMUNITY;
                    root.postDelayed(this::render, 300);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------ actions

    private void startBubble() {
        List<String> missing = new ArrayList<>();
        if (!hasOverlay()) missing.add("allow the bubble (Setup)");
        if (!hasFiles()) missing.add("allow photo access (Setup)");
        if (store.active().isEmpty() || store.activeDate().isEmpty()) missing.add("open a community and pick a date");
        if (!missing.isEmpty()) {
            toast("First: " + TextUtils.join(", ", missing));
            return;
        }
        Intent i = new Intent(this, BubbleService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        root.postDelayed(this::render, 400);
    }

    private void restartBubbleIfRunning() {
        if (BubbleService.running) {
            // poke the service so the bubble label refreshes
            startService(new Intent(this, BubbleService.class));
        }
    }

    private void communityMenu(String name) {
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(new String[]{"Edit lots", "Delete community"}, (d, which) -> {
                    if (which == 0) editCommunity(name);
                    else new AlertDialog.Builder(this)
                            .setMessage("Delete " + name + "? The shot log and photos are kept.")
                            .setPositiveButton("Delete", (d2, w) -> {
                                store.deleteCommunity(name);
                                render();
                            })
                            .setNegativeButton("Cancel", null).show();
                })
                .show();
    }

    private void editCommunity(String existing) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText name = new EditText(this);
        name.setHint("Community name (e.g. Miralon)");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText lots = new EditText(this);
        lots.setHint("Paste lots: one per line, or ranges like 2-17");
        lots.setMinLines(6);
        lots.setGravity(Gravity.TOP);
        lots.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        if (existing != null) {
            name.setText(existing);
            lots.setText(TextUtils.join("\n", store.lots(existing)));
        }
        box.addView(name);
        box.addView(lots);

        new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Add community" : "Edit " + existing)
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String n = name.getText().toString().trim();
                    List<String> parsed = Names.parseLots(lots.getText().toString());
                    if (n.isEmpty()) {
                        toast("Type a name");
                        return;
                    }
                    if (existing != null && !existing.equalsIgnoreCase(n)) store.deleteCommunity(existing);
                    String replaced = store.putCommunity(n, parsed);
                    if (existing == null && replaced != null) toast("Updated existing community " + replaced);
                    if (store.active().equalsIgnoreCase(n)) {
                        store.setActive(n);
                        if (store.openWindowId() == 0 && !parsed.contains(store.currentLot())) store.setCurrentLot("");
                    }
                    toast(n + ": " + parsed.size() + (parsed.size() == 1 ? " lot" : " lots"));
                    restartBubbleIfRunning();
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pickLot() {
        String c = store.active();
        List<String> lots = store.lots(c);
        Set<String> shot = store.shotLots(c, store.activeDate());
        String[] labels = new String[lots.size()];
        for (int i = 0; i < lots.size(); i++) labels[i] = "Lot " + lots.get(i) + (shot.contains(lots.get(i)) ? "   ✓" : "");
        new AlertDialog.Builder(this)
                .setTitle(c)
                .setItems(labels, (d, which) -> {
                    store.setCurrentLot(lots.get(which));
                    restartBubbleIfRunning();
                    render();
                })
                .show();
    }

    private void typeFolder() {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/");
        new AlertDialog.Builder(this)
                .setTitle("Folder to watch")
                .setView(e)
                .setPositiveButton("Add", (d, w) -> addFolder(e.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addFolder(String path) {
        if (path.isEmpty()) return;
        File f = new File(path);
        if (!f.isDirectory()) {
            toast("Folder not found: " + path);
            return;
        }
        List<String> list = store.watchFolders();
        if (!list.contains(f.getAbsolutePath())) list.add(f.getAbsolutePath());
        store.setWatchFolders(list);
        render();
    }

    /** Looks through the controller's storage (and microSD) for folders that contain 2:1 panoramas. */
    private void scanForFolders() {
        if (!hasFiles()) {
            toast("Allow photo access first (step 1)");
            return;
        }
        AlertDialog wait = new AlertDialog.Builder(this).setMessage("Looking for 360 panoramas…").setCancelable(false).show();
        new Thread(() -> {
            Map<String, int[]> found = new LinkedHashMap<>();
            List<File> roots = new ArrayList<>();
            roots.add(Environment.getExternalStorageDirectory());
            File[] ext = getExternalFilesDirs(null);
            if (ext != null) for (File d : ext) {
                if (d == null) continue;
                String p = d.getAbsolutePath();
                int i = p.indexOf("/Android/data");
                if (i > 0) {
                    File r = new File(p.substring(0, i));
                    if (!roots.contains(r)) roots.add(r);
                }
            }
            int[] budget = {6000};
            String skip = Store.outputRoot().getAbsolutePath();
            for (File r : roots) walk(r, 0, found, budget, skip);
            runOnUiThread(() -> {
                wait.dismiss();
                showScanResults(found);
            });
        }).start();
    }

    private void walk(File dir, int depth, Map<String, int[]> found, int[] budget, String skip) {
        if (depth > 7 || budget[0] <= 0 || dir.getAbsolutePath().startsWith(skip)) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (budget[0] <= 0) return;
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".")) walk(f, depth + 1, found, budget, skip);
            } else if (Names.isJpeg(f)) {
                budget[0]--;
                if (Names.is360(f)) {
                    int[] c = found.get(dir.getAbsolutePath());
                    if (c == null) found.put(dir.getAbsolutePath(), c = new int[]{0});
                    c[0]++;
                }
            }
        }
    }

    private void showScanResults(Map<String, int[]> found) {
        if (found.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("No 360s found yet")
                    .setMessage("Take one sphere panorama in DJI Fly and let it finish stitching, then tap Find again.\n\n"
                            + "If it still isn't found, DJI Fly may be keeping panos in its private folder. Use \"Type path\" if you know where they are.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        List<String> paths = new ArrayList<>(found.keySet());
        String[] labels = new String[paths.size()];
        for (int i = 0; i < paths.size(); i++) labels[i] = paths.get(i) + "\n(" + found.get(paths.get(i))[0] + " panoramas)";
        new AlertDialog.Builder(this)
                .setTitle("Folders with 360s — tap to watch")
                .setItems(labels, (d, which) -> addFolder(paths.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void exportCsv() {
        try {
            File dir = Store.exportDir();
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("can't create " + dir);
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
            File out = new File(dir, "lotshots_log_" + stamp + ".csv");
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            JSONArray log = store.log();
            try (FileWriter w = new FileWriter(out)) {
                w.write("Community,Date Folder,Lot,Captured,File,Original\n");
                for (int i = 0; i < log.length(); i++) {
                    JSONObject e = log.optJSONObject(i);
                    if (e == null) continue;
                    w.write(csv(e.optString("community")) + "," + csv(e.optString("date")) + "," + csv(e.optString("lot")) + ","
                            + fmt.format(new Date(e.optLong("time"))) + ","
                            + csv(new File(e.optString("dest")).getName()) + ","
                            + csv(new File(e.optString("src")).getName()) + "\n");
                }
            }
            toast("Saved " + out.getAbsolutePath());
        } catch (Exception ex) {
            toast("Export failed: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ "What changed?" test

    private void startWhatChanged() {
        if (!hasFiles()) {
            toast("Allow photo access first (Permissions)");
            return;
        }
        AlertDialog wait = new AlertDialog.Builder(this).setMessage("Taking a snapshot of the controller's files…").setCancelable(false).show();
        new Thread(() -> {
            java.util.Map<String, long[]> snap = WhatChanged.snapshot(WhatChanged.roots(this));
            runOnUiThread(() -> {
                wait.dismiss();
                WhatChanged.before = snap;
                WhatChanged.startedAt = System.currentTimeMillis();
                new AlertDialog.Builder(this)
                        .setTitle("Test started")
                        .setMessage("Checked " + snap.size() + " files.\n\nNow switch to DJI Fly and take:\n1. One regular photo\n2. One 360 — wait until it finishes\n\nThen come back to Lot Shots → Settings → Stop & show results.")
                        .setPositiveButton("OK", null).show();
                render();
            });
        }).start();
    }

    private void stopWhatChanged() {
        AlertDialog wait = new AlertDialog.Builder(this).setMessage("Looking for what changed…").setCancelable(false).show();
        new Thread(() -> {
            java.util.Map<String, long[]> after = WhatChanged.snapshot(WhatChanged.roots(this));
            String report = WhatChanged.report(this, after);
            String saved;
            try {
                File dir = new File(Environment.getExternalStorageDirectory(), "Documents/LotShots");
                dir.mkdirs();
                File out = new File(dir, "WhatChanged_" + new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(new Date()) + ".txt");
                try (FileWriter w = new FileWriter(out)) {
                    w.write(report);
                }
                saved = out.getAbsolutePath();
            } catch (Exception e) {
                saved = null;
            }
            final String savedPath = saved;
            runOnUiThread(() -> {
                wait.dismiss();
                WhatChanged.lastReport = report;
                WhatChanged.before = null;
                if (savedPath != null) toast("Saved " + savedPath);
                go(WHAT_CHANGED);
            });
        }).start();
    }

    private void renderWhatChanged() {
        backButton("Settings", SETTINGS);
        title("What changed?");
        addButtons(button("⇪  Send report to Mac", C_PRIMARY, v -> sendReport()));
        String r = WhatChanged.lastReport == null ? "No results yet." : WhatChanged.lastReport;
        TextView t = text(r, 12, 0xFFDDDDDD, false);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setPadding(0, dp(10), 0, 0);
        root.addView(t);
    }

    private void sendReport() {
        if (WhatChanged.lastReport == null) return;
        try {
            String name = "WhatChanged_" + new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(new Date()) + ".txt";
            File f = new File(ShareProvider.shareDir(this), name);
            try (FileWriter w = new FileWriter(f)) {
                w.write(WhatChanged.lastReport);
            }
            android.net.Uri uri = ShareProvider.uriFor(f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.setClipData(android.content.ClipData.newRawUri(name, uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(new Intent(send).setPackage(LOCALSEND_PKG));
            } catch (android.content.ActivityNotFoundException ex) {
                startActivity(Intent.createChooser(send, "Send report"));
            }
        } catch (Exception e) {
            toast("Couldn't send: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ updates

    private static final String RELEASE_API = "https://api.github.com/repos/LNFPP/lot-shots-drone/releases/latest";
    private static final String APK_URL = "https://github.com/LNFPP/lot-shots-drone/releases/latest/download/LotShots.apk";

    private long myBuild() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private String myVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void checkForUpdate() {
        AlertDialog wait = new AlertDialog.Builder(this).setMessage("Checking for updates\u2026").setCancelable(false).show();
        new Thread(() -> {
            long latest = -1;
            String error = null;
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(RELEASE_API).openConnection();
                c.setRequestProperty("User-Agent", "LotShots-Drone");
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                int code = c.getResponseCode();
                if (code != 200) throw new Exception("GitHub replied " + code);
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                String body = new JSONObject(sb.toString()).optString("body", "");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("build=(\\d+)").matcher(body);
                if (m.find()) latest = Long.parseLong(m.group(1));
                else throw new Exception("couldn't read the latest build number");
            } catch (Exception ex) {
                error = ex.getMessage();
            }
            final long latestBuild = latest;
            final String err = error;
            runOnUiThread(() -> {
                wait.dismiss();
                if (err != null) {
                    new AlertDialog.Builder(this)
                            .setTitle("Couldn't check for updates")
                            .setMessage("Make sure the controller is on Wi-Fi and try again.\n\n(" + err + ")")
                            .setPositiveButton("OK", null).show();
                } else if (latestBuild <= myBuild()) {
                    new AlertDialog.Builder(this)
                            .setTitle("No update available")
                            .setMessage("You have the latest version (build " + myBuild() + ").")
                            .setPositiveButton("OK", null).show();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle("Update available")
                            .setMessage("Build " + latestBuild + " is available (you have build " + myBuild() + ").\n\n"
                                    + "Update now? The download opens in your browser \u2014 tap it when it finishes, then Install. "
                                    + "Your communities and dates are kept.")
                            .setPositiveButton("Yes, update", (d, w) -> {
                                try {
                                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL)));
                                } catch (Exception ex) {
                                    toast("No browser found to open the download");
                                }
                            })
                            .setNegativeButton("Not now", null).show();
                }
            });
        }).start();
    }

    private static String csv(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
