package com.lotshots.drone;

import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test tool: snapshot every file on the controller, take photos in DJI Fly, snapshot again,
 * and report what appeared — to find where DJI Fly keeps its previews and what's inside them.
 */
public final class WhatChanged {
    private WhatChanged() {
    }

    /** path -> {size, lastModified} */
    public static volatile Map<String, long[]> before = null;
    public static volatile long startedAt = 0;
    public static volatile String lastReport = null;

    private static final int MAX_FILES = 400000;
    private static final Pattern DJI_NAME = Pattern.compile("DJI_\\d{14}_\\d{4}_[A-Z]");

    /** Phone storage, microSD card(s), and DJI Fly's own folders where Android allows. */
    public static List<File> roots(Context c) {
        Set<File> r = new LinkedHashSet<>();
        File ext = Environment.getExternalStorageDirectory();
        r.add(ext);
        File[] dirs = c.getExternalFilesDirs(null);
        if (dirs != null) for (File d : dirs) {
            if (d == null) continue;
            String p = d.getAbsolutePath();
            int i = p.indexOf("/Android/data");
            if (i > 0) r.add(new File(p.substring(0, i)));
        }
        return new ArrayList<>(r);
    }

    public static Map<String, long[]> snapshot(List<File> roots) {
        Map<String, long[]> m = new HashMap<>();
        for (File root : roots) walk(root, 0, m);
        return m;
    }

    private static void walk(File dir, int depth, Map<String, long[]> m) {
        if (depth > 14 || m.size() > MAX_FILES) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                walk(f, depth + 1, m);
            } else {
                m.put(f.getAbsolutePath(), new long[]{f.length(), f.lastModified()});
            }
        }
    }

    public static String fmt(long ms) {
        if (ms <= 0) return "-";
        return new SimpleDateFormat("yyyy-MM-dd h:mm:ss.SSS a (Z)", Locale.US).format(new Date(ms));
    }

    /** Builds the report text: new/changed files, then Android's photo list. */
    public static String report(Context c, Map<String, long[]> after) {
        StringBuilder sb = new StringBuilder();
        sb.append("Lot Shots — What changed? test\n");
        sb.append("Started:  ").append(fmt(startedAt)).append('\n');
        sb.append("Stopped:  ").append(fmt(System.currentTimeMillis())).append('\n');
        sb.append("Time zone: ").append(java.util.TimeZone.getDefault().getID()).append('\n');
        sb.append("Files checked: ").append(before == null ? 0 : before.size()).append(" before, ").append(after.size()).append(" after\n\n");

        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, long[]> e : after.entrySet()) {
            long[] b = before == null ? null : before.get(e.getKey());
            if (b == null || b[0] != e.getValue()[0] || b[1] != e.getValue()[1]) changed.add(e.getKey());
        }
        changed.sort((a, b) -> Long.compare(after.get(a)[1], after.get(b)[1]));

        sb.append("=== NEW OR CHANGED FILES (").append(changed.size()).append(") ===\n");
        if (changed.isEmpty()) sb.append("(none — DJI Fly may keep previews where other apps can't see)\n");
        int shown = 0;
        for (String p : changed) {
            if (shown++ >= 150) {
                sb.append("… and ").append(changed.size() - 150).append(" more\n");
                break;
            }
            sb.append('\n').append(describe(new File(p), before != null && before.containsKey(p)));
        }

        sb.append("\n=== ANDROID PHOTO LIST (added since Start) ===\n");
        sb.append(mediaStore(c, startedAt / 1000 - 5));
        return sb.toString();
    }

    /** Everything we can learn about one file. */
    public static String describe(File f, boolean existedBefore) {
        StringBuilder sb = new StringBuilder();
        sb.append(existedBefore ? "[CHANGED] " : "[NEW] ").append(f.getAbsolutePath()).append('\n');
        sb.append("  size: ").append(f.length()).append(" bytes\n");
        sb.append("  last modified: ").append(fmt(f.lastModified())).append('\n');
        try {
            BasicFileAttributes a = Files.readAttributes(f.toPath(), BasicFileAttributes.class);
            sb.append("  created (Android): ").append(fmt(a.creationTime().toMillis())).append('\n');
            sb.append("  last accessed: ").append(fmt(a.lastAccessTime().toMillis())).append('\n');
        } catch (Exception ignored) {
        }

        // Is it an image (even without a .jpg ending)?
        if (f.length() > 0 && f.length() < 120L * 1024 * 1024) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth > 0) {
                double r = (double) o.outWidth / o.outHeight;
                sb.append("  image: ").append(o.outWidth).append(" x ").append(o.outHeight)
                        .append(String.format(Locale.US, "  (shape %.3f : 1%s)", r, r > 1.9 && r < 2.1 ? " = 2:1, looks like a 360" : ""))
                        .append("  type: ").append(o.outMimeType).append('\n');
                try {
                    ExifInterface ex = new ExifInterface(f.getAbsolutePath());
                    String[] tags = {"DateTime", "DateTimeOriginal", "DateTimeDigitized", "OffsetTime", "OffsetTimeOriginal",
                            "Make", "Model", "ImageDescription", "Software", "ImageWidth", "ImageLength",
                            "PixelXDimension", "PixelYDimension", "GPSLatitude", "GPSLongitude"};
                    StringBuilder exs = new StringBuilder();
                    for (String t : tags) {
                        String v = ex.getAttribute(t);
                        if (v != null && !v.trim().isEmpty()) exs.append("    ").append(t).append(" = ").append(v.trim()).append('\n');
                    }
                    sb.append(exs.length() > 0 ? "  photo details (EXIF):\n" + exs : "  photo details (EXIF): none\n");
                } catch (Exception e) {
                    sb.append("  photo details (EXIF): couldn't read\n");
                }
            }
        }

        // Look inside the first part of the file for 360 tags, DJI data and an original drone file name.
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 512 * 1024)];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            String s = new String(buf, 0, n, StandardCharsets.ISO_8859_1);
            List<String> found = new ArrayList<>();
            if (s.contains("equirectangular")) found.add("360 tag (equirectangular)");
            if (s.contains("GPano")) found.add("GPano 360 data");
            if (s.contains("drone-dji") || s.contains("dji:")) found.add("DJI drone data");
            if (s.contains("p\u0000a\u0000n\u0000o\u0000")) found.add("'pano' keyword");
            Matcher m = DJI_NAME.matcher(s);
            Set<String> names = new LinkedHashSet<>();
            while (m.find() && names.size() < 5) names.add(m.group());
            Matcher cd = Pattern.compile("xmp:CreateDate=\"([^\"]+)\"").matcher(s);
            if (cd.find()) found.add("xmp:CreateDate " + cd.group(1));
            if (!names.isEmpty()) found.add("drone file name inside: " + names);
            sb.append("  inside file: ").append(found.isEmpty() ? "nothing special" : String.join("; ", found)).append('\n');
        } catch (Exception e) {
            sb.append("  inside file: couldn't read (").append(e.getMessage()).append(")\n");
        }
        Matcher nm = DJI_NAME.matcher(f.getName());
        if (nm.find()) sb.append("  drone file name in file name: ").append(nm.group()).append('\n');
        return sb.toString();
    }

    /** What Android's own photo list (MediaStore) gained since `sinceSec`. */
    private static String mediaStore(Context c, long sinceSec) {
        StringBuilder sb = new StringBuilder();
        Uri uri = MediaStore.Files.getContentUri("external");
        String[][] projections = {
                {"_data", "_display_name", "_size", "width", "height", "date_added", "date_modified", "datetaken", "mime_type"},
                {"_data", "_display_name", "_size", "date_added", "date_modified", "mime_type"}};
        for (String[] proj : projections) {
            try (Cursor cur = c.getContentResolver().query(uri, proj, "date_added >= ?",
                    new String[]{String.valueOf(sinceSec)}, "date_added ASC")) {
                if (cur == null) return "(not available)\n";
                if (cur.getCount() == 0) return "(nothing added)\n";
                while (cur.moveToNext()) {
                    for (String col : proj) {
                        int i = cur.getColumnIndex(col);
                        if (i < 0) continue;
                        String v = cur.getString(i);
                        if (v == null) continue;
                        if (col.startsWith("date_") && !v.isEmpty()) v = v + "  = " + fmt(Long.parseLong(v) * 1000);
                        else if (col.equals("datetaken") && !v.isEmpty()) v = v + "  = " + fmt(Long.parseLong(v));
                        sb.append("  ").append(col).append(": ").append(v).append('\n');
                    }
                    sb.append('\n');
                }
                return sb.toString();
            } catch (Exception e) {
                // try the shorter column list
            }
        }
        return "(couldn't read)\n";
    }
}
