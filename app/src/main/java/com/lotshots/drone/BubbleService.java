package com.lotshots.drone;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
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
import java.util.Set;

/**
 * Floating lot-picker bubble that sits on top of DJI Fly, plus a folder watcher that
 * names each new stitched 360 after the selected lot.
 */
public class BubbleService extends Service {

    public static final String ACTION_STOP = "com.lotshots.drone.STOP";
    public static volatile boolean running = false;
    /** Set by MainActivity so its screen refreshes when a shot is logged. Called on the main thread. */
    public static volatile Runnable onChange;

    private static final long POLL_MS = 2500;
    private static final int BLUE = 0xE61565C0;
    private static final int GREEN = 0xF243A047;

    private Store store;
    private WindowManager wm;
    private Handler main;
    private HandlerThread watchThread;
    private Handler watch;

    private static final int BUBBLE_DP = 64;
    private static final int WINDOW_DP = 80;
    private static final ArgbEvaluator ARGB = new ArgbEvaluator();

    private FrameLayout bubbleFrame;
    private TextView bubble;
    private GradientDrawable bubbleBg;
    private int fillColor = BLUE;
    private boolean watching = false;
    private float strobeT = 0f;
    private ValueAnimator strobe;
    private Animator fillAnim;
    private WindowManager.LayoutParams bubbleParams;
    private View panel;

    // watcher state (only touched on the watch thread)
    private final Map<String, Long> known = new HashMap<>();
    private final Map<String, Long> pending = new HashMap<>();
    private String baselineSig = null;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        store = new Store(this);
        main = new Handler(Looper.getMainLooper());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForeground(1, buildNotification());
        makeBubble();
        watchThread = new HandlerThread("lotshots-watch");
        watchThread.start();
        watch = new Handler(watchThread.getLooper());
        running = true;
        watch.post(this::poll);
        notifyChange();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        updateBubble();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        closePanel();
        if (strobe != null) strobe.cancel();
        if (fillAnim != null) fillAnim.cancel();
        if (bubbleFrame != null) {
            try {
                wm.removeView(bubbleFrame);
            } catch (Exception ignored) {
            }
        }
        if (watchThread != null) watchThread.quitSafely();
        notifyChange();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("lotshots", "Lot Shots", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, BubbleService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "lotshots")
                : new Notification.Builder(this);
        return b.setContentTitle("Lot Shots is watching for 360s")
                .setContentText(store.active().isEmpty() ? "No community selected" : store.active() + " \u00B7 " + store.activeDate())
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stopPi).build())
                .setOngoing(true)
                .build();
    }

    private void notifyChange() {
        Runnable r = onChange;
        if (r != null) main.post(r);
    }

    private void toast(String msg) {
        main.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    // ------------------------------------------------------------------ bubble

    private void makeBubble() {
        // The window is a bit bigger than the bubble so the "pop" animation has room and isn't clipped.
        bubbleFrame = new FrameLayout(this);
        bubble = new TextView(this);
        bubble.setGravity(Gravity.CENTER);
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(13);
        bubble.setTypeface(Typeface.DEFAULT_BOLD);
        bubbleBg = new GradientDrawable();
        bubbleBg.setShape(GradientDrawable.OVAL);
        bubble.setBackground(bubbleBg);
        applyBubbleStyle();
        bubbleFrame.addView(bubble, new FrameLayout.LayoutParams(dp(BUBBLE_DP), dp(BUBBLE_DP), Gravity.CENTER));

        bubbleParams = new WindowManager.LayoutParams(dp(WINDOW_DP), dp(WINDOW_DP), overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = dp(4);
        bubbleParams.y = dp(100);

        bubble.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY;
            float touchX, touchY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = bubbleParams.x;
                        startY = bubbleParams.y;
                        touchX = e.getRawX();
                        touchY = e.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - touchX);
                        int dy = (int) (e.getRawY() - touchY);
                        if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) moved = true;
                        if (moved) {
                            bubbleParams.x = startX + dx;
                            bubbleParams.y = startY + dy;
                            wm.updateViewLayout(bubbleFrame, bubbleParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) togglePanel();
                        return true;
                }
                return false;
            }
        });

        wm.addView(bubbleFrame, bubbleParams);
        updateBubble();
    }

    // ---- bubble look: blue fill, red outline that gently pulses while a lot is set (= watching)

    private void applyBubbleStyle() {
        if (bubbleBg == null) return;
        bubbleBg.setColor(fillColor);
        if (watching) {
            int stroke = (Integer) ARGB.evaluate(strobeT, 0x55FF5252, 0xFFFF1744);
            bubbleBg.setStroke(dp(3), stroke);
        } else {
            bubbleBg.setStroke(dp(2), Color.WHITE);
        }
    }

    private void setWatching(boolean w) {
        if (w == watching) return;
        watching = w;
        if (strobe != null) {
            strobe.cancel();
            strobe = null;
        }
        if (w) {
            strobe = ValueAnimator.ofFloat(0f, 1f);
            strobe.setDuration(1100);
            strobe.setRepeatCount(ValueAnimator.INFINITE);
            strobe.setRepeatMode(ValueAnimator.REVERSE);
            strobe.setInterpolator(new AccelerateDecelerateInterpolator());
            strobe.addUpdateListener(an -> {
                strobeT = (float) an.getAnimatedValue();
                applyBubbleStyle();
            });
            strobe.start();
        }
        applyBubbleStyle();
    }

    private GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(dp(2), Color.WHITE);
        return g;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void updateBubble() {
        main.post(() -> {
            if (bubble == null) return;
            String lot = store.currentLot();
            bubble.setText("Lot\n" + (lot.isEmpty() ? "__" : lot));
            setWatching(!lot.isEmpty());
        });
    }

    /** 360 copied: fill turns green, a small "pop", then the green fades back to blue. */
    private void flashBubble() {
        main.post(() -> {
            if (bubble == null) return;
            if (fillAnim != null) fillAnim.cancel();

            ValueAnimator toGreen = ValueAnimator.ofObject(ARGB, fillColor, GREEN);
            toGreen.setDuration(180);
            ValueAnimator toBlue = ValueAnimator.ofObject(ARGB, GREEN, BLUE);
            toBlue.setStartDelay(900);
            toBlue.setDuration(1400);
            toBlue.setInterpolator(new AccelerateDecelerateInterpolator());
            ValueAnimator.AnimatorUpdateListener l = an -> {
                fillColor = (Integer) an.getAnimatedValue();
                applyBubbleStyle();
            };
            toGreen.addUpdateListener(l);
            toBlue.addUpdateListener(l);
            AnimatorSet set = new AnimatorSet();
            set.playSequentially(toGreen, toBlue);
            fillAnim = set;
            set.start();

            bubble.animate().cancel();
            bubble.setScaleX(1f);
            bubble.setScaleY(1f);
            bubble.animate().scaleX(1.15f).scaleY(1.15f).setDuration(170)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> bubble.animate().scaleX(1f).scaleY(1f).setDuration(320)
                            .setInterpolator(new AccelerateDecelerateInterpolator()).start())
                    .start();
        });
    }

    // ------------------------------------------------------------------ lot panel

    private void togglePanel() {
        if (panel != null) closePanel();
        else openPanel();
    }

    private void closePanel() {
        if (panel != null) {
            try {
                wm.removeView(panel);
            } catch (Exception ignored) {
            }
            panel = null;
        }
    }

    private TextView chip(String text, int color, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(4), dp(10), dp(4), dp(10));
        t.setBackground(rounded(color, 8));
        t.setOnClickListener(click);
        return t;
    }

    private void openPanel() {
        String community = store.active();
        String current = store.currentLot();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(10));
        root.setBackground(rounded(0xF2202124, 14));

        // header
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(community.isEmpty() ? "No community" : community + "  \u00B7  " + store.activeDate());
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = chip("✕", 0xFF444444, v -> closePanel());
        header.addView(close, new LinearLayout.LayoutParams(dp(44), -2));
        root.addView(header);

        if (community.isEmpty()) {
            TextView msg = new TextView(this);
            msg.setText("Open Lot Shots and pick a community first.");
            msg.setTextColor(0xFFCCCCCC);
            msg.setPadding(0, dp(12), 0, dp(12));
            root.addView(msg);
        } else {
            final StringBuilder typed = new StringBuilder();
            final Set<String> shot = store.shotLots(community, store.activeDate());
            final List<String> lots = store.lots(community);

            // big "Lot __" readout
            final TextView display = new TextView(this);
            display.setTextColor(Color.WHITE);
            display.setTextSize(30);
            display.setTypeface(Typeface.DEFAULT_BOLD);
            display.setGravity(Gravity.CENTER);
            display.setBackground(rounded(0xFF111111, 8));
            display.setPadding(dp(6), dp(8), dp(6), dp(8));
            final TextView status = new TextView(this);
            status.setTextColor(0xFFAAAAAA);
            status.setTextSize(12);
            status.setGravity(Gravity.CENTER);
            status.setPadding(0, dp(4), 0, dp(4));

            final Runnable refresh = () -> {
                String t = typed.toString();
                display.setText("Lot " + (t.isEmpty() ? "__" : t));
                if (t.isEmpty()) {
                    status.setText(current.isEmpty() ? "Type the lot number" : "Now: Lot " + current);
                    status.setTextColor(0xFFAAAAAA);
                } else if (shot.contains(t)) {
                    status.setText("\u2713 Already has a 360");
                    status.setTextColor(0xFF81C784);
                } else if (!lots.isEmpty() && !lots.contains(t)) {
                    status.setText("Not in " + community + "'s list");
                    status.setTextColor(0xFFFFB74D);
                } else {
                    status.setText("");
                }
            };
            refresh.run();

            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.HORIZONTAL);
            body.setPadding(0, dp(6), 0, 0);

            // left: number pad
            LinearLayout pad = new LinearLayout(this);
            pad.setOrientation(LinearLayout.VERTICAL);
            String[][] keys = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"\u232B", "0", "C"}};
            for (String[] rowKeys : keys) {
                LinearLayout r = new LinearLayout(this);
                for (String k : rowKeys) {
                    int color = Character.isDigit(k.charAt(0)) ? 0xFF3C3C3C : 0xFF555555;
                    TextView key = chip(k, color, v -> {
                        if (k.equals("C")) typed.setLength(0);
                        else if (k.equals("\u232B")) {
                            if (typed.length() > 0) typed.setLength(typed.length() - 1);
                        } else if (typed.length() < 4 && !(typed.length() == 0 && k.equals("0"))) {
                            typed.append(k);
                        }
                        refresh.run();
                    });
                    key.setTextSize(20);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1);
                    lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                    r.addView(key, lp);
                }
                pad.addView(r);
            }
            body.addView(pad, new LinearLayout.LayoutParams(0, -2, 1.2f));

            // right: readout + actions
            LinearLayout side = new LinearLayout(this);
            side.setOrientation(LinearLayout.VERTICAL);
            side.setPadding(dp(6), dp(3), 0, 0);
            side.addView(display, new LinearLayout.LayoutParams(-1, -2));
            side.addView(status, new LinearLayout.LayoutParams(-1, -2));

            TextView set = chip("\u2713  Set lot", 0xFF1565C0, v -> {
                String t = typed.toString();
                if (t.isEmpty()) {
                    toast("Type a lot number first");
                    return;
                }
                store.setCurrentLot(t);
                updateBubble();
                closePanel();
                notifyChange();
            });
            set.setTextSize(18);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(56));
            sp.setMargins(0, dp(4), 0, dp(6));
            side.addView(set, sp);

            TextView retag = chip("\u21BA Re-tag last 360", 0xFF6A1B9A, v -> {
                String t = typed.toString();
                if (!t.isEmpty()) store.setCurrentLot(t);
                closePanel();
                retagLast();
            });
            retag.setTextSize(13);
            side.addView(retag, new LinearLayout.LayoutParams(-1, dp(44)));
            body.addView(side, new LinearLayout.LayoutParams(0, -2, 1f));

            root.addView(body);
        }

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int w = Math.min(dp(400), screenW - dp(16));

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        int x = bubbleParams.x + dp(WINDOW_DP);
        if (x + w > screenW) x = Math.max(0, bubbleParams.x - w - dp(8));
        p.x = x;
        p.y = Math.max(0, Math.min(bubbleParams.y, screenH - dp(300)));

        panel = root;
        wm.addView(panel, p);
    }

    // ------------------------------------------------------------------ folder watcher (watch thread)

    private List<File> candidates() {
        List<File> out = new ArrayList<>();
        String outRoot = Store.outputRoot().getAbsolutePath();
        for (String path : store.watchFolders()) collect(new File(path), 0, outRoot, out);
        return out;
    }

    private void collect(File dir, int depth, String skip, List<File> out) {
        if (depth > 2 || dir.getAbsolutePath().startsWith(skip)) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".")) collect(f, depth + 1, skip, out);
            } else if (Names.isJpeg(f)) {
                out.add(f);
            }
        }
    }

    private void poll() {
        if (!running) return;
        try {
            String sig = TextUtils.join("|", store.watchFolders());
            if (!sig.equals(baselineSig)) {
                // New or changed folder list: everything already there is "old" — only new files count.
                known.clear();
                pending.clear();
                for (File f : candidates()) known.put(f.getAbsolutePath(), f.length());
                baselineSig = sig;
            } else {
                for (File f : candidates()) {
                    String p = f.getAbsolutePath();
                    if (known.containsKey(p)) continue;
                    if (Names.isOurs(f.getName())) {
                        known.put(p, f.length());
                        continue;
                    }
                    long size = f.length();
                    Long prev = pending.get(p);
                    boolean settled = prev != null && prev == size && size > 0
                            && System.currentTimeMillis() - f.lastModified() > 2000;
                    if (settled) {
                        pending.remove(p);
                        known.put(p, size);
                        handleNew(f);
                    } else {
                        pending.put(p, size);
                    }
                }
            }
        } catch (Exception e) {
            // keep watching even if one pass fails
        }
        if (running) watch.postDelayed(this::poll, POLL_MS);
    }

    private void handleNew(File src) {
        if (store.only360() && !Names.is360(src)) return;

        String community = store.active();
        if (community.isEmpty()) {
            toast("New 360 found, but no community is selected in Lot Shots");
            return;
        }
        String date = store.activeDate();
        if (date.isEmpty()) {
            toast("New 360 found, but no date folder is selected in Lot Shots");
            return;
        }
        String lot = store.currentLot();
        boolean assigned = !lot.isEmpty();
        if (!assigned) lot = "UNASSIGNED";

        long captured = src.lastModified();
        File dest = save(src, community, date, lot, captured);
        if (dest == null) {
            toast("Lot Shots couldn't save " + src.getName() + " — check file access");
            return;
        }
        known.put(dest.getAbsolutePath(), dest.length());
        MediaScannerConnection.scanFile(this, new String[]{dest.getAbsolutePath(), src.getAbsolutePath()}, null, null);

        JSONObject e = new JSONObject();
        try {
            e.put("community", community);
            e.put("date", date);
            e.put("lot", lot);
            e.put("time", captured);
            e.put("src", src.getAbsolutePath());
            e.put("dest", dest.getAbsolutePath());
        } catch (JSONException ignored) {
        }
        store.addLog(e);

        String msg;
        if (assigned) {
            String next = store.nextUnshot(community, date, lot);
            store.setCurrentLot(next);
            msg = "✓ Lot " + lot + " saved" + (next.isEmpty() ? " — all lots done!" : "  → next: Lot " + next);
        } else {
            msg = "360 saved as UNASSIGNED — pick a lot and tap Re-tag";
        }
        updateBubble();
        flashBubble();
        toast(msg);
        notifyChange();
    }

    private File save(File src, String community, String date, String lot, long captured) {
        String name = Names.fileName(community, lot, captured, Names.ext(src));
        try {
            // Always a copy: DJI Fly's original is never touched.
            File dir = Store.dateDir(community, date);
            if (!dir.exists() && !dir.mkdirs()) return null;
            File dest = Names.unique(new File(dir, name));
            copy(src, dest);
            dest.setLastModified(captured);
            return dest;
        } catch (Exception e) {
            return null;
        }
    }

    private static void copy(File from, File to) throws Exception {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** Renames the most recent 360 to the currently selected lot (for when you forgot to tap first). */
    private void retagLast() {
        watch.post(() -> {
            String lot = store.currentLot();
            if (lot.isEmpty()) {
                toast("Pick a lot first, then tap Re-tag");
                return;
            }
            JSONArray log = store.log();
            if (log.length() == 0) {
                toast("No 360s logged yet");
                return;
            }
            JSONObject e = log.optJSONObject(log.length() - 1);
            if (e == null) return;
            String community = e.optString("community");
            File old = new File(e.optString("dest"));
            if (!old.exists()) {
                toast("Couldn't find the last 360 file to re-tag");
                return;
            }
            String newName = Names.fileName(community, lot, e.optLong("time"), Names.ext(old));
            File nd = Names.unique(new File(old.getParentFile(), newName));
            if (!old.renameTo(nd)) {
                toast("Re-tag failed — check file access");
                return;
            }
            known.put(nd.getAbsolutePath(), nd.length());
            String oldLot = e.optString("lot");
            try {
                e.put("lot", lot);
                e.put("dest", nd.getAbsolutePath());
            } catch (JSONException ignored) {
            }
            store.setLog(log);
            MediaScannerConnection.scanFile(this, new String[]{old.getAbsolutePath(), nd.getAbsolutePath()}, null, null);

            String next = store.nextUnshot(community, e.optString("date"), lot);
            store.setCurrentLot(next);
            updateBubble();
            flashBubble();
            toast("↺ Lot " + oldLot + " → Lot " + lot + (next.isEmpty() ? "" : "  → next: Lot " + next));
            notifyChange();
        });
    }
}
