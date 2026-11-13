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

    private Store store;
    private LinearLayout root;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(32));
        sv.addView(root);
        setContentView(sv);
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
        TextView t = text(title.toUpperCase(Locale.US), 13, 0xFF90CAF9, true);
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
            COMMUNITY = "community", FLIGHTS = "flights", FLIGHT_DATE = "flightDate";
    private String screen = HOME;
    private String openCommunity = null;
    private String openFlightDate = null;

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
        if (COMMUNITY.equals(screen) && (openCommunity == null || !store.communityNames().contains(openCommunity))) screen = COMMUNITIES;
        if (FLIGHT_DATE.equals(screen) && (openFlightDate == null || !store.flightDates().contains(openFlightDate))) screen = FLIGHTS;
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
            default:
                screen = HOME;
                renderHome();
        }
        if (!tickViews.isEmpty()) ticker.post(tick);
    }

    private void backButton(String label, String to) {
        root.addView(button("←  " + label, 0xFF333333, v -> go(to)), new LinearLayout.LayoutParams(-2, -2));
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
        root.addView(text("Version " + myVersionName() + "  (build " + myBuild() + ")", 12, 0xFF888888, false));

        if (!hasOverlay() || !hasFiles()) {
            root.addView(text("⚠  Some permissions are missing — open Settings.", 14, 0xFFFFB74D, true));
        }
        if (BubbleService.running) {
            root.addView(text("● 360 bubble on: " + store.active() + "  ·  " + prettyDate(store.activeDate()), 14, 0xFF81C784, true));
        }

        tile("⚙  Settings", "Pano folder, save location, updates", 0xFF333333, v -> go(SETTINGS));
        int nc = store.communityNames().size();
        tile("◎  360s", nc + (nc == 1 ? " community" : " communities") + " · lot bubble", 0xFF1565C0, v -> go(COMMUNITIES));

        Object[] run = store.runningSession();
        String sub = run == null ? "Time at each place" : null;
        LinearLayout fl = tile("✈  Flight Log", sub, 0xFF2A2A2A, v -> go(FLIGHTS));
        if (run != null) {
            JSONObject e = (JSONObject) run[1];
            TextView t = text("", 14, 0xFFFFCDD2, true);
            fl.addView(t);
            tickViews.add(new Object[]{t, e.optLong("start"), "● Timer running — " + e.optString("place") + "  "});
            // slow red pulse so you remember to stop the time
            GradientDrawable bg = box(0xFF2A2A2A, 0);
            fl.setBackground(bg);
            pulse = ValueAnimator.ofObject(new ArgbEvaluator(), 0xFF2A2A2A, 0xFF8E1B1B);
            pulse.setDuration(1600);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.addUpdateListener(an -> bg.setColor((Integer) an.getAnimatedValue()));
            pulse.start();
        }
    }

    // ------------------------------------------------------------------ settings

    private void renderSettings() {
        backButton("Home", HOME);
        title("Settings");

        section("Permissions");
        LinearLayout r1 = row();
        r1.addView(text((hasOverlay() ? "✅" : "❌") + "  Show bubble over DJI Fly", 15, Color.WHITE, false), new LinearLayout.LayoutParams(0, -2, 1));
        if (!hasOverlay()) r1.addView(button("Allow", 0xFF1565C0, v -> askOverlay()));
        root.addView(r1);
        LinearLayout r2 = row();
        r2.addView(text((hasFiles() ? "✅" : "❌") + "  Read & save photos", 15, Color.WHITE, false), new LinearLayout.LayoutParams(0, -2, 1));
        if (!hasFiles()) r2.addView(button("Allow", 0xFF1565C0, v -> askFiles()));
        root.addView(r2);

        section("Where DJI Fly saves 360s");
        List<String> folders = store.watchFolders();
        if (folders.isEmpty()) {
            root.addView(text("Not set. Take one sphere pano in DJI Fly first, then tap Find.", 14, 0xFFFFB74D, false));
        } else {
            for (String f : folders) root.addView(text("📁  " + f, 14, Color.WHITE, false));
        }
        addButtons(button("Find my pano folder", 0xFF1565C0, v -> scanForFolders()),
                button("Type path", 0xFF333333, v -> typeFolder()),
                button("Clear", 0xFF333333, v -> {
                    store.setWatchFolders(new ArrayList<>());
                    render();
                }));

        section("Save 360s to");
        boolean sd = Store.onSdCard();
        addButtons(button((sd ? "○" : "◉") + "  Controller", sd ? 0xFF333333 : 0xFF1565C0, v -> {
                    store.setSaveRoot(null);
                    toast("Saving to controller storage");
                    render();
                }),
                button((sd ? "◉" : "○") + "  microSD card", sd ? 0xFF1565C0 : 0xFF333333, v -> {
                    String r = Store.findSdRoot(this);
                    if (r == null) {
                        toast("No microSD card found (or it can't be written to)");
                        return;
                    }
                    store.setSaveRoot(r);
                    render();
                    if (r.contains("/Android/data/")) {
                        new AlertDialog.Builder(this)
                                .setTitle("Saving to microSD")
                                .setMessage("The controller only lets Lot Shots save inside its own folder on the card:\n\n"
                                        + r + "\n\nOn your Mac, open the card and go to Android → data → com.lotshots.drone → files → LotShots.\n\n"
                                        + "Important: copy your 360s off before uninstalling Lot Shots — Android deletes this folder on uninstall. (Updating from the GitHub link is fine.)")
                                .setPositiveButton("OK", null).show();
                    } else {
                        toast("Saving to microSD: " + r);
                    }
                }));
        root.addView(text("Folder: " + Store.outputRoot().getAbsolutePath() + "/<Community>/<Date>/", 13, 0xFFBBBBBB, false));
        root.addView(text("Only 360 spheres are copied. DJI Fly's originals are never changed.", 12, 0xFF888888, false));
        if (store.log().length() > 0) addButtons(button("Export 360 log CSV", 0xFF333333, v -> exportCsv()));

        section("Send to Mac");
        String[] mac = store.mac();
        root.addView(text(mac == null ? "No Mac chosen yet." : "\uD83D\uDCBB  " + mac[2] + "   (" + mac[0] + ")", 15, mac == null ? 0xFFFFB74D : Color.WHITE, false));
        root.addView(text("Needs the free LocalSend app open on your Mac, on the same Wi-Fi.", 12, 0xFF888888, false));
        addButtons(button("Find my Mac", 0xFF1565C0, v -> findMac(null)),
                button("Type address", 0xFF333333, v -> typeMacIp(null)));

        section("App");
        root.addView(text("Version " + myVersionName() + "  (build " + myBuild() + ")", 14, Color.WHITE, false));
        addButtons(button("↻  Check for update", 0xFF1565C0, v -> checkForUpdate()));
    }

    // ------------------------------------------------------------------ 360s: communities

    private void renderCommunities() {
        backButton("Home", HOME);
        title("360s");

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
        if (names.isEmpty()) root.addView(text("No communities yet. Add one and paste its lots.", 14, 0xFFBBBBBB, false));
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
            card.setBackground(box(on ? 0xFF0D3C78 : 0xFF2A2A2A, on ? 0xFF64B5F6 : 0));
            card.addView(text(name, 18, Color.WHITE, true));
            int nd = store.dates(name).size();
            card.addView(text(store.lots(name).size() + " lots  ·  " + nd + (nd == 1 ? " date" : " dates"), 13, 0xFFBBBBBB, false));
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
        addButtons(button("+ Add community", 0xFF2E7D32, v -> editCommunity(null)));
        if (!names.isEmpty()) root.addView(text("Long-press a community to edit its lots or delete it.", 12, 0xFF888888, false));
    }

    // ------------------------------------------------------------------ flight log

    private void renderFlightLog() {
        backButton("Home", HOME);
        title("Flight Log");

        TextView add = button("+  Add date", 0xFF2E7D32, v -> {
            String d = Store.today();
            if (!store.addFlightDate(d)) toast("Today is already in the Flight Log");
            render();
        });
        add.setTextSize(18);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, dp(56));
        alp.setMargins(0, dp(8), 0, dp(8));
        root.addView(add, alp);

        List<String> dates = store.flightDates();
        if (dates.isEmpty()) root.addView(text("No dates yet. Tap + Add date when you start your day.", 14, 0xFFBBBBBB, false));
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
            t.setOrientation(LinearLayout.VERTICAL);
            t.setPadding(dp(16), dp(12), dp(16), dp(12));
            t.setBackground(box(running ? 0xFF4A1414 : 0xFF2A2A2A, running ? 0xFFFF5252 : 0));
            t.addView(text(prettyDate(d), 18, Color.WHITE, true));
            t.addView(text(ss.isEmpty() ? "No places yet" : places.toString(), 13, 0xFFBBBBBB, false));
            if (running) {
                TextView tv = text("", 13, 0xFFFFCDD2, true);
                t.addView(tv);
                tickViews.add(new Object[]{tv, ((JSONObject) run[1]).optLong("start"), "● Timer running  "});
            }
            t.setOnClickListener(v -> {
                openFlightDate = d;
                go(FLIGHT_DATE);
                if (store.sessions(d).isEmpty()) addPlace(d);
            });
            t.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("Delete " + prettyDate(d) + "?")
                        .setMessage("Removes this date's places and times from the Flight Log.")
                        .setPositiveButton("Delete", (dd, w) -> {
                            store.deleteFlightDate(d);
                            render();
                        })
                        .setNegativeButton("Cancel", null).show();
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(4), 0, dp(4));
            root.addView(t, lp);
        }
        if (!dates.isEmpty()) root.addView(text("Long-press a date to delete it.", 12, 0xFF888888, false));
    }

    private void renderFlightDate(String date) {
        backButton("Flight Log", FLIGHTS);
        title(prettyDate(date));

        TextView add = button("+  Community or location", 0xFF2E7D32, v -> addPlace(date));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, dp(52));
        alp.setMargins(0, dp(8), 0, dp(8));
        root.addView(add, alp);

        List<JSONObject> ss = store.sessions(date);
        if (ss.isEmpty()) root.addView(text("Add the community or location you're flying at.", 14, 0xFFBBBBBB, false));
        for (int i = ss.size() - 1; i >= 0; i--) {
            JSONObject e = ss.get(i);
            long id = e.optLong("id"), start = e.optLong("start"), stop = e.optLong("stop");
            boolean running = start > 0 && stop == 0;

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(12), dp(16), dp(12));
            card.setBackground(box(0xFF2A2A2A, running ? 0xFFFF5252 : 0));
            card.addView(text(e.optString("place"), 19, Color.WHITE, true));
            card.addView(text("community".equals(e.optString("type")) ? "Community" : "Location", 12, 0xFF888888, false));

            if (start > 0) {
                String times = "Started " + clock(start) + (stop > 0 ? "   →   Stopped " + clock(stop) + "   (" + duration(stop - start) + ")" : "");
                TextView tt = text(times, 14, 0xFFDDDDDD, false);
                tt.setPadding(0, dp(6), 0, 0);
                card.addView(tt);
            }

            TextView btn;
            if (start == 0) {
                btn = button("▶  Start time", 0xFF2E7D32, v -> {
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
                btn = button("▶  Start again here", 0xFF333333, v -> {
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
            String[] mac = store.mac();
            TextView exp = button("\u21EA  Export data to " + (mac == null ? "Mac" : mac[2]), 0xFF1565C0, v -> exportFlightDate(date, true));
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, dp(52));
            elp.setMargins(0, dp(18), 0, 0);
            root.addView(exp, elp);
            addButtons(button("Share\u2026", 0xFF333333, v -> exportFlightDate(date, false)));
            root.addView(text("Sends the CSV to LocalSend on your Mac (same Wi-Fi). A copy is also saved to " + flightLogDir().getAbsolutePath(), 12, 0xFF888888, false));
        }
    }

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

            if (toMac) {
                toast(backupNote);
                sendToMac(name, sb.toString().getBytes("UTF-8"));
                return;
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
            startActivity(Intent.createChooser(send, "Send Flight Log " + date));
        } catch (Exception ex) {
            toast("Export failed: " + ex.getMessage());
        }
    }

    /** "Community or location?" then pick a community, or type a location name. */
    private void addPlace(String date) {
        new AlertDialog.Builder(this)
                .setTitle("Where are you flying?")
                .setItems(new String[]{"Community", "Location"}, (d, which) -> {
                    if (which == 0) {
                        List<String> names = store.communityNames();
                        if (names.isEmpty()) {
                            toast("No communities yet — add them under 360s, or choose Location");
                            return;
                        }
                        new AlertDialog.Builder(this)
                                .setTitle("Community")
                                .setItems(names.toArray(new String[0]), (d2, w) -> {
                                    store.addSession(date, "community", names.get(w));
                                    render();
                                })
                                .setNegativeButton("Cancel", null).show();
                    } else {
                        EditText name = new EditText(this);
                        name.setHint("Location name");
                        name.setSingleLine(true);
                        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
                        LinearLayout boxL = new LinearLayout(this);
                        boxL.setPadding(dp(20), dp(8), dp(20), 0);
                        boxL.addView(name, new LinearLayout.LayoutParams(-1, -2));
                        new AlertDialog.Builder(this)
                                .setTitle("Location")
                                .setView(boxL)
                                .setPositiveButton("Add", (d2, w) -> {
                                    String n = name.getText().toString().trim();
                                    if (n.isEmpty()) {
                                        toast("Type a location name");
                                        return;
                                    }
                                    store.addSession(date, "location", n);
                                    render();
                                })
                                .setNegativeButton("Cancel", null).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void renderCommunity(String name) {
        backButton("360s", COMMUNITIES);

        TextView title = text(name, 26, Color.WHITE, true);
        title.setPadding(0, dp(12), 0, 0);
        root.addView(title);
        LinearLayout info = row();
        info.addView(text(store.lots(name).size() + " lots", 14, 0xFFBBBBBB, false), new LinearLayout.LayoutParams(0, -2, 1));
        info.addView(button("Edit lots", 0xFF333333, v -> editCommunity(name)));
        root.addView(info);

        TextView newDate = button("+  New date", 0xFF2E7D32, v -> {
            String d = store.createDate(name);
            if (d.isEmpty()) {
                toast("Couldn't create the folder — allow photo access first");
                return;
            }
            selectDate(name, d);
        });
        newDate.setTextSize(18);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, dp(56));
        nlp.setMargins(0, dp(14), 0, dp(4));
        root.addView(newDate, nlp);

        section("Dates");
        List<String> dates = store.dates(name);
        if (dates.isEmpty()) root.addView(text("No dates yet. Tap + New date before you fly.", 14, 0xFFBBBBBB, false));
        boolean thisActive = name.equals(store.active());
        for (String d : dates) {
            boolean on = thisActive && d.equals(store.activeDate());
            LinearLayout tab = new LinearLayout(this);
            tab.setGravity(Gravity.CENTER_VERTICAL);
            tab.setPadding(dp(14), dp(10), dp(8), dp(10));
            tab.setBackground(box(on ? 0xFF0D3C78 : 0xFF2A2A2A, on ? 0xFF64B5F6 : 0));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.addView(text(prettyDate(d), 17, Color.WHITE, true));
            int n = store.count360s(name, d);
            labels.addView(text(n + (n == 1 ? " 360" : " 360s") + (on ? "   ● Shooting here" : ""), 13, on ? 0xFF81C784 : 0xFFBBBBBB, on));
            tab.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            TextView del = button("🗑", 0xFF5D1F1F, v -> confirmDeleteDate(name, d));
            tab.addView(del);
            tab.setOnClickListener(v -> selectDate(name, d));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(4), 0, dp(4));
            root.addView(tab, lp);
        }

        // --- selected date: next lot, start, shots
        if (thisActive && !store.activeDate().isEmpty() && dates.contains(store.activeDate())) {
            String date = store.activeDate();
            section("Shooting " + prettyDate(date));
            LinearLayout r = row();
            String cur = store.currentLot();
            r.addView(text("Set: " + (cur.isEmpty() ? "Lot __" : "Lot " + cur), 20, Color.WHITE, true), new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(button("Change", 0xFF333333, v -> pickLot()));
            root.addView(r);

            TextView go = BubbleService.running
                    ? button("■  Stop bubble", 0xFFC62828, v -> {
                        stopService(new Intent(this, BubbleService.class));
                        root.postDelayed(this::render, 300);
                    })
                    : button("▶  Start bubble", 0xFF2E7D32, v -> startBubble());
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, dp(56));
            glp.setMargins(0, dp(8), 0, 0);
            root.addView(go, glp);
            if (BubbleService.running) root.addView(text("Bubble is on. Switch to DJI Fly — it stays on top.", 13, 0xFF81C784, false));

            List<JSONObject> shots = store.entries(name, date);
            section(shots.size() + " lots shot this date");
            SimpleDateFormat fmt = new SimpleDateFormat("h:mm a", Locale.US);
            for (int i = shots.size() - 1; i >= 0; i--) {
                JSONObject e = shots.get(i);
                TextView t = text("Lot " + e.optString("lot") + "  ·  " + fmt.format(new Date(e.optLong("time")))
                        + "\n" + new File(e.optString("dest")).getName(), 13, Color.WHITE, false);
                t.setPadding(0, dp(5), 0, dp(5));
                root.addView(t);
            }
        } else if (!dates.isEmpty()) {
            root.addView(text("Tap a date to shoot into it.", 13, 0xFF888888, false));
        }
    }

    private void selectDate(String community, String date) {
        boolean changed = !community.equals(store.active()) || !date.equals(store.activeDate());
        store.setActive(community);
        store.setActiveDate(date);
        if (changed) store.setCurrentLot("");
        restartBubbleIfRunning();
        render();
    }

    private void confirmDeleteDate(String community, String date) {
        int n = store.count360s(community, date);
        new AlertDialog.Builder(this)
                .setTitle("Delete " + prettyDate(date) + "?")
                .setMessage("Removes this folder and its " + n + (n == 1 ? " 360" : " 360s") + " from the controller.\n\n"
                        + "DJI Fly's originals and the drone's SD card are not affected.")
                .setPositiveButton("Delete", (d, w) -> {
                    if (BubbleService.running && community.equals(store.active()) && date.equals(store.activeDate())) {
                        stopService(new Intent(this, BubbleService.class));
                    }
                    if (!store.deleteDate(community, date)) toast("Some files couldn't be deleted");
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
        if (store.watchFolders().isEmpty()) missing.add("set the pano folder (home screen)");
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
                    if (n.isEmpty() || parsed.isEmpty()) {
                        toast("Need a name and at least one lot");
                        return;
                    }
                    if (existing != null && !existing.equalsIgnoreCase(n)) store.deleteCommunity(existing);
                    String replaced = store.putCommunity(n, parsed);
                    if (existing == null && replaced != null) toast("Updated existing community " + replaced);
                    if (store.active().equalsIgnoreCase(n)) {
                        store.setActive(n);
                        if (!parsed.contains(store.currentLot())) store.setCurrentLot("");
                    }
                    toast(n + ": " + parsed.size() + " lots");
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

    // ------------------------------------------------------------------ send to Mac

    private LocalSendClient lsClient() {
        return new LocalSendClient("Lot Shots (RC Pro)", store.deviceFingerprint());
    }

    /** Scans the Wi-Fi for LocalSend, lets you pick your Mac, then runs `then` (may be null). */
    private void findMac(Runnable then) {
        if (LocalSendClient.localIps().isEmpty()) {
            toast("Connect the controller to Wi-Fi first");
            return;
        }
        AlertDialog wait = new AlertDialog.Builder(this)
                .setMessage("Looking for your Mac\u2026\n\nMake sure LocalSend is open on the Mac.")
                .setCancelable(false).show();
        new Thread(() -> {
            android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            android.net.wifi.WifiManager.MulticastLock lock = null;
            try {
                lock = wifi.createMulticastLock("lotshots-localsend");
                lock.setReferenceCounted(false);
                lock.acquire();
            } catch (Exception ignored) {
            }
            List<LocalSendClient.Device> found = lsClient().scan();
            if (lock != null) try {
                lock.release();
            } catch (Exception ignored) {
            }
            runOnUiThread(() -> {
                wait.dismiss();
                if (found.isEmpty()) {
                    new AlertDialog.Builder(this)
                            .setTitle("No Mac found")
                            .setMessage("This controller's address: " + TextUtils.join(", ", LocalSendClient.localIps()) + "\n\n"
                                    + "Check that:\n\u2022 LocalSend is open on your Mac (Receive tab)\n\u2022 The Mac and controller are on the same Wi-Fi\n"
                                    + "\u2022 Mac: System Settings \u2192 Privacy & Security \u2192 Local Network \u2192 LocalSend is on\n"
                                    + "\u2022 Mac: System Settings \u2192 Network \u2192 Firewall isn't blocking LocalSend\n\n"
                                    + "Or type the Mac's address (Mac: System Settings \u2192 Wi-Fi \u2192 Details \u2192 IP address).")
                            .setPositiveButton("OK", null)
                            .setNeutralButton("Type Mac's address", (d, w) -> typeMacIp(then))
                            .show();
                    return;
                }
                String[] labels = new String[found.size()];
                for (int i = 0; i < found.size(); i++) labels[i] = found.get(i).alias + "   (" + found.get(i).ip + ")";
                new AlertDialog.Builder(this)
                        .setTitle("Choose your Mac")
                        .setItems(labels, (d, w) -> {
                            store.setMac(found.get(w));
                            toast("Will send to " + found.get(w).alias);
                            render();
                            if (then != null) then.run();
                        })
                        .setNegativeButton("Cancel", null).show();
            });
        }).start();
    }

    private void typeMacIp(Runnable then) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint("e.g. 192.168.1.42");
        e.setInputType(InputType.TYPE_CLASS_PHONE);
        LinearLayout boxL = new LinearLayout(this);
        boxL.setPadding(dp(20), dp(8), dp(20), 0);
        boxL.addView(e, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("Mac's IP address")
                .setView(boxL)
                .setPositiveButton("Connect", (d, w) -> {
                    String ip = e.getText().toString().trim();
                    if (ip.isEmpty()) return;
                    AlertDialog wait = new AlertDialog.Builder(this).setMessage("Checking " + ip + "\u2026").setCancelable(false).show();
                    new Thread(() -> {
                        LocalSendClient.Device dev = lsClient().probe(ip);
                        runOnUiThread(() -> {
                            wait.dismiss();
                            if (dev == null) {
                                new AlertDialog.Builder(this)
                                        .setTitle("LocalSend not found at " + ip)
                                        .setMessage("Make sure LocalSend is open on the Mac and the address is right. "
                                                + "If it still fails, the Mac's firewall or the Wi-Fi may be blocking devices from talking to each other.")
                                        .setPositiveButton("OK", null).show();
                                return;
                            }
                            store.setMac(dev);
                            toast("Will send to " + dev.alias);
                            render();
                            if (then != null) then.run();
                        });
                    }).start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendToMac(String fileName, byte[] data) {
        String[] mac = store.mac();
        if (mac == null) {
            findMac(() -> sendToMac(fileName, data));
            return;
        }
        AlertDialog wait = new AlertDialog.Builder(this)
                .setMessage("Sending to " + mac[2] + "\u2026\n\nIf the Mac asks, click Accept.")
                .setCancelable(false).show();
        new Thread(() -> {
            LocalSendClient c = lsClient();
            LocalSendClient.Device target = c.probe(mac[0]);
            if (target == null || (!mac[3].isEmpty() && !mac[3].equals(target.fingerprint))) {
                // The Mac's address may have changed: look for it by its ID.
                target = null;
                for (LocalSendClient.Device d : c.scan()) {
                    if (d.fingerprint.equals(mac[3])) target = d;
                }
                if (target != null) store.setMac(target);
            }
            final String err = target == null
                    ? "Couldn't find " + mac[2] + ". Is LocalSend open on the Mac, on the same Wi-Fi?"
                    : c.send(target, fileName, "text/csv", data);
            final String who = target == null ? mac[2] : target.alias;
            runOnUiThread(() -> {
                wait.dismiss();
                if (err == null) {
                    toast("\u2713 Sent " + fileName + " to " + who);
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle("Not sent")
                            .setMessage(err)
                            .setPositiveButton("OK", null)
                            .setNeutralButton("Choose Mac again", (d, w) -> findMac(() -> sendToMac(fileName, data)))
                            .show();
                }
            });
        }).start();
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
