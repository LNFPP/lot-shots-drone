package com.lotshots.drone;

import android.graphics.BitmapFactory;
import android.os.Environment;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the previews DJI Fly saves when you open its album, picks out the 360s, and matches each one
 * to the lot whose Set -> Finished window contains the 360's first-frame time.
 */
public final class LotMatcher {
    private LotMatcher() {
    }

    /** One 360 on the drone, known from its preview name. */
    public static final class Pano {
        public String droneFile;   // DJI_20261113083900_0492_D.JPG
        public long started;       // first of the 25 frames (from the drone file name)
        public long created;       // drone file created = stitch finished (kMDItemFSCreationDate on the Mac)
        public long sizeBytes;
    }

    public static final class Row {
        public JSONObject window;
        public List<Pano> panos = new ArrayList<>();
    }

    public static final class Result {
        public List<Row> rows = new ArrayList<>();        // one per lot window for this community + date
        public List<Pano> unmatched = new ArrayList<>();  // 360s that day that fall in no lot window
        public int pano360sThatDay;
        public boolean folderReadable;
    }

    // Photo_-2147213632_DJI_20261113083900_0492_D_JPG_63041536_0_202611138406_photo_preview.jpg
    private static final Pattern NAME = Pattern.compile(
            "^(Photo|Video)_-?\\d+_(DJI_(\\d{14})_\\d{4}_[A-Z])_([A-Za-z0-9]+)_(\\d+)_\\d+_(\\d+)_photo_(?:thunmbnail|thumbnail|preview)\\.jpg$");

    /** Within this many seconds of the window edges still counts (clock rounding). */
    private static final long SLACK_MS = 3000;
    /** A 360 is 2:1; regular photos are 16:9 (thumbnail 192x90 vs 160x90; preview 2048x1024 vs 960x540). */
    private static final long BIG_FILE = 30L * 1024 * 1024;

    public static File previewDir() {
        return new File(Environment.getExternalStorageDirectory(), "Android/data/dji.go.v5/cache/ImageCaches");
    }

    private static long parseStart(String yyyyMMddHHmmss) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
            f.setLenient(false);
            return f.parse(yyyyMMddHHmmss).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * DJI writes the created time without leading zeros (e.g. "202611138406" = 2026-11-13 8:40:06),
     * so try every way to split it and keep the one just after the 360 started.
     */
    static long parseCreated(String digits, long started) {
        if (digits.length() < 9 || started == 0) return 0;
        int year;
        try {
            year = Integer.parseInt(digits.substring(0, 4));
        } catch (Exception e) {
            return 0;
        }
        String rest = digits.substring(4);
        long best = 0, bestDiff = Long.MAX_VALUE;
        List<int[]> splits = new ArrayList<>();
        split(rest, 0, new int[5], 0, splits);
        for (int[] v : splits) {
            int mo = v[0], d = v[1], h = v[2], mi = v[3], se = v[4];
            if (mo < 1 || mo > 12 || d < 1 || d > 31 || h > 23 || mi > 59 || se > 59) continue;
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(year, mo - 1, d, h, mi, se);
            long t = c.getTimeInMillis();
            long diff = t - started;
            if (diff >= -5000 && diff < 30 * 60 * 1000L && Math.abs(diff) < bestDiff) {
                best = t;
                bestDiff = Math.abs(diff);
            }
        }
        return best;
    }

    private static void split(String s, int pos, int[] cur, int idx, List<int[]> out) {
        if (idx == 5) {
            if (pos == s.length()) out.add(cur.clone());
            return;
        }
        for (int len = 1; len <= 2 && pos + len <= s.length(); len++) {
            cur[idx] = Integer.parseInt(s.substring(pos, pos + len));
            split(s, pos + len, cur, idx + 1, out);
        }
    }

    private static boolean twoToOne(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return false;
        double r = (double) o.outWidth / o.outHeight;
        return r > 1.9 && r < 2.3;
    }

    /** All 360s DJI Fly has previews for, keyed by drone file name. */
    public static Map<String, Pano> scanPanos() {
        Map<String, Pano> out = new LinkedHashMap<>();
        File[] files = previewDir().listFiles();
        if (files == null) return out;
        Map<String, Boolean> isPano = new LinkedHashMap<>();
        for (File f : files) {
            Matcher m = NAME.matcher(f.getName());
            if (!m.matches() || !m.group(1).equals("Photo") || !m.group(4).equalsIgnoreCase("JPG")) continue;
            String drone = m.group(2) + ".JPG";
            long size;
            try {
                size = Long.parseLong(m.group(5));
            } catch (Exception e) {
                size = 0;
            }
            boolean pano = (isPano.containsKey(drone) && isPano.get(drone)) || twoToOne(f) || size >= BIG_FILE;
            isPano.put(drone, pano);
            if (!out.containsKey(drone)) {
                Pano p = new Pano();
                p.droneFile = drone;
                p.started = parseStart(m.group(3));
                p.created = parseCreated(m.group(6), p.started);
                p.sizeBytes = size;
                out.put(drone, p);
            }
        }
        Map<String, Pano> only = new LinkedHashMap<>();
        for (Map.Entry<String, Pano> e : out.entrySet()) if (Boolean.TRUE.equals(isPano.get(e.getKey()))) only.put(e.getKey(), e.getValue());
        return only;
    }

    static String day(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ms));
    }

    /** Matches 360s to this community's lot windows for `date`. */
    public static Result match(Store store, String community, String date) {
        Result r = new Result();
        r.folderReadable = previewDir().listFiles() != null;
        List<Pano> todays = new ArrayList<>();
        for (Pano p : scanPanos().values()) if (p.started > 0 && day(p.started).equals(date)) todays.add(p);
        todays.sort((a, b) -> Long.compare(a.started, b.started));
        r.pano360sThatDay = todays.size();

        Set<String> usedAnyCommunity = new HashSet<>();
        long now = System.currentTimeMillis();
        for (JSONObject w : store.windows(null, date)) {
            long from = w.optLong("set") - SLACK_MS;
            long to = (w.optLong("finish") > 0 ? w.optLong("finish") : now) + SLACK_MS;
            Row row = new Row();
            row.window = w;
            for (Pano p : todays) {
                if (p.started >= from && p.started <= to) {
                    row.panos.add(p);
                    usedAnyCommunity.add(p.droneFile);
                }
            }
            if (community.equals(w.optString("community"))) r.rows.add(row);
        }
        for (Pano p : todays) if (!usedAnyCommunity.contains(p.droneFile)) r.unmatched.add(p);
        return r;
    }

    private static String iso(long ms) {
        return ms <= 0 ? "" : new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date(ms));
    }

    private static String q(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** One row per 360 (or per lot with no 360 found), plus 360s that weren't in any lot window. */
    public static String csv(String community, Result r) {
        StringBuilder sb = new StringBuilder("Community,Lot,Lot set,Stitch finished (tapped),Drone file,Shot started,File created,Size (MB),Status\n");
        for (Row row : r.rows) {
            JSONObject w = row.window;
            String base = q(community) + "," + q(w.optString("lot")) + "," + iso(w.optLong("set")) + "," + iso(w.optLong("finish")) + ",";
            if (row.panos.isEmpty()) {
                sb.append(base).append(",,,,").append(w.optLong("finish") > 0 ? "no 360 found" : "not finished").append('\n');
            }
            for (Pano p : row.panos) {
                sb.append(base).append(q(p.droneFile)).append(',').append(iso(p.started)).append(',').append(iso(p.created)).append(',')
                        .append(String.format(Locale.US, "%.1f", p.sizeBytes / 1048576.0)).append(',')
                        .append(row.panos.size() > 1 ? row.panos.size() + " 360s in this lot" : "matched").append('\n');
            }
        }
        for (Pano p : r.unmatched) {
            sb.append(q(community)).append(",,,,").append(q(p.droneFile)).append(',').append(iso(p.started)).append(',').append(iso(p.created)).append(',')
                    .append(String.format(Locale.US, "%.1f", p.sizeBytes / 1048576.0)).append(",not in any lot window\n");
        }
        return sb.toString();
    }
}
