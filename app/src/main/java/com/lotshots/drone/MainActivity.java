package com.lotshots.drone;

import android.Manifest;
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

    /** null = home screen (community boxes). Otherwise the open community's page. */
    private String openCommunity = null;

    @Override
    public void onBackPressed() {
        if (openCommunity != null) {
            openCommunity = null;
            render();
        } else {
            super.onBackPressed();
        }
    }

    private void render() {
        root.removeAllViews();
        if (openCommunity != null && store.communityNames().contains(openCommunity)) renderCommunity(openCommunity);
        else {
            openCommunity = null;
            renderHome();
        }
    }

    private static String prettyDate(String folder) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(folder);
            return new SimpleDateFormat("EEE, MMM d, yyyy", Locale.US).format(d);
        } catch (Exception e) {
            return folder;
        }
    }

    private GradientDrawable box(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(12));
        if (stroke != 0) g.setStroke(dp(2), stroke);
        return g;
    }

    private void renderHome() {
        LinearLayout top = row();
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("Lot Shots \u2014 Drone", 24, Color.WHITE, true));
        titles.addView(text("Version " + myVersionName() + "  (build " + myBuild() + ")", 12, 0xFF888888, false));
        top.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(button("\u21BB  Check for update", 0xFF333333, v -> checkForUpdate()));
        root.addView(top);

        // --- setup (only shown until everything is allowed)
        if (!hasOverlay() || !hasFiles()) {
            section("Setup");
            LinearLayout r1 = row();
            r1.addView(text((hasOverlay() ? "✅" : "❌") + "  Show bubble over DJI Fly", 15, Color.WHITE, false), new LinearLayout.LayoutParams(0, -2, 1));
            if (!hasOverlay()) r1.addView(button("Allow", 0xFF1565C0, v -> askOverlay()));
            root.addView(r1);
            LinearLayout r2 = row();
            r2.addView(text((hasFiles() ? "✅" : "❌") + "  Read & save photos", 15, Color.WHITE, false), new LinearLayout.LayoutParams(0, -2, 1));
            if (!hasFiles()) r2.addView(button("Allow", 0xFF1565C0, v -> askFiles()));
            root.addView(r2);
        }

        // --- now shooting
        if (BubbleService.running) {
            section("Now shooting");
            root.addView(text(store.active() + "  ·  " + prettyDate(store.activeDate()), 17, Color.WHITE, true));
            TextView stop = button("■  Stop bubble", 0xFFC62828, v -> {
                stopService(new Intent(this, BubbleService.class));
                root.postDelayed(this::render, 300);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
            lp.setMargins(0, dp(6), 0, 0);
            root.addView(stop, lp);
        }

        // --- community boxes
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
                render();
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

        // --- folder
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

        // --- options
        section("Options");
        CheckBox only = new CheckBox(this);
        only.setText("Only count 360 spheres (2:1 images), ignore regular photos");
        only.setTextColor(Color.WHITE);
        only.setChecked(store.only360());
        only.setOnCheckedChangeListener((b, c) -> store.setOnly360(c));
        root.addView(only);
        section("Save 360s to");
        boolean sd = Store.onSdCard();
        addButtons(button((sd ? "\u25CB" : "\u25C9") + "  Controller", sd ? 0xFF333333 : 0xFF1565C0, v -> {
                    store.setSaveRoot(null);
                    toast("Saving to controller storage");
                    render();
                }),
                button((sd ? "\u25C9" : "\u25CB") + "  microSD card", sd ? 0xFF1565C0 : 0xFF333333, v -> {
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
                                        + r + "\n\nOn your Mac, open the card and go to Android \u2192 data \u2192 com.lotshots.drone \u2192 files \u2192 LotShots.\n\n"
                                        + "Important: copy your 360s off before uninstalling Lot Shots \u2014 Android deletes this folder on uninstall. (Updating from the GitHub link is fine.)")
                                .setPositiveButton("OK", null).show();
                    } else {
                        toast("Saving to microSD: " + r);
                    }
                }));
        root.addView(text("Folder: " + Store.outputRoot().getAbsolutePath() + "/<Community>/<Date>/", 13, 0xFFBBBBBB, false));
        root.addView(text("DJI Fly's originals are never changed. Switching location starts fresh date folders there.", 12, 0xFF888888, false));
        if (store.log().length() > 0) addButtons(button("Export log CSV", 0xFF333333, v -> exportCsv()));
    }

    private void renderCommunity(String name) {
        root.addView(button("←  Communities", 0xFF333333, v -> {
            openCommunity = null;
            render();
        }), new LinearLayout.LayoutParams(-2, -2));

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
            r.addView(text("Next: " + (cur.isEmpty() ? "Lot __" : "Lot " + cur), 20, Color.WHITE, true), new LinearLayout.LayoutParams(0, -2, 1));
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
        List<String> lots = store.lots(community);
        if (changed || store.currentLot().isEmpty()) {
            store.setCurrentLot(lots.isEmpty() ? "" : store.nextUnshot(community, date, lots.get(lots.size() - 1)));
        }
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
                        if (!parsed.contains(store.currentLot()))
                            store.setCurrentLot(store.nextUnshot(n, store.activeDate(), parsed.get(parsed.size() - 1)));
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
