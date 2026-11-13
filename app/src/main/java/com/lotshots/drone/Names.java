package com.lotshots.drone;

import android.graphics.BitmapFactory;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** File naming, lot-list parsing, and 360 detection. */
public final class Names {
    private Names() {
    }

    private static final Pattern RANGE = Pattern.compile("^\\s*(?:lots?\\s*)?(\\d{1,4})\\s*[-–—]\\s*(\\d{1,4})\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBERS_ONLY = Pattern.compile("^[\\d\\s,;]+$");
    private static final Pattern LOT = Pattern.compile("\\b(\\d{1,4}[A-Za-z]?)\\b");
    private static final Pattern OURS = Pattern.compile(".+_Lot[^_]+_\\d{4}-\\d{2}-\\d{2}_\\d{4}(_\\d+)?\\.[A-Za-z]+$");

    /** "Red Cedar Farms" -> "RedCedarFarms" */
    public static String clean(String s) {
        String c = s.replaceAll("[^A-Za-z0-9]", "");
        return c.isEmpty() ? "Community" : c;
    }

    /** "42" -> "042", "7A" -> "007A", "UNASSIGNED" stays. */
    public static String padLot(String lot) {
        Matcher m = Pattern.compile("^(\\d+)(.*)$").matcher(lot);
        if (m.matches()) {
            try {
                return String.format(Locale.US, "%03d%s", Integer.parseInt(m.group(1)), m.group(2));
            } catch (NumberFormatException ignored) {
            }
        }
        return lot.replaceAll("[^A-Za-z0-9]", "");
    }

    /** Miralon_Lot042_2026-11-11_1432.jpg */
    public static String fileName(String community, String lot, long timeMs, String ext) {
        String t = new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date(timeMs));
        return clean(community) + "_Lot" + padLot(lot) + "_" + t + "." + ext;
    }

    public static boolean isOurs(String name) {
        return OURS.matcher(name).matches();
    }

    public static String ext(File f) {
        String n = f.getName();
        int i = n.lastIndexOf('.');
        return i >= 0 ? n.substring(i + 1).toLowerCase(Locale.US) : "jpg";
    }

    /** Adds _2, _3 ... if the name is taken. */
    public static File unique(File f) {
        if (!f.exists()) return f;
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        String base = dot >= 0 ? n.substring(0, dot) : n;
        String ext = dot >= 0 ? n.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            File c = new File(f.getParentFile(), base + "_" + i + ext);
            if (!c.exists()) return c;
        }
        return new File(f.getParentFile(), base + "_" + System.currentTimeMillis() + ext);
    }

    public static boolean isJpeg(File f) {
        String n = f.getName().toLowerCase(Locale.US);
        return f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg"));
    }

    /** "8192x4096", or "?" if it can't be read. */
    public static String dims(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        return o.outWidth > 0 ? o.outWidth + "x" + o.outHeight : "?";
    }

    /** A stitched sphere panorama is 2:1 (e.g. 8192x4096). */
    public static boolean is360(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return false;
        double r = (double) o.outWidth / o.outHeight;
        return r > 1.9 && r < 2.1;
    }

    /**
     * Turns pasted text into a lot list. Accepts:
     *  one lot per line ("42", "Lot 42", "42, Available", "115A"),
     *  ranges ("2-17"), or a single line of numbers ("1, 2, 3").
     */
    public static List<String> parseLots(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher r = RANGE.matcher(line);
            if (r.matches()) {
                int a = Integer.parseInt(r.group(1)), b = Integer.parseInt(r.group(2));
                int lo = Math.min(a, b), hi = Math.max(a, b);
                if (hi - lo <= 2000) for (int i = lo; i <= hi; i++) out.add(String.valueOf(i));
                continue;
            }
            if (NUMBERS_ONLY.matcher(line).matches()) {
                for (String p : line.split("[\\s,;]+")) if (!p.isEmpty()) out.add(stripZeros(p));
                continue;
            }
            Matcher m = LOT.matcher(line);
            if (m.find()) out.add(stripZeros(m.group(1)));
        }
        return new ArrayList<>(out);
    }

    private static String stripZeros(String s) {
        String t = s.replaceFirst("^0+(?=\\d)", "");
        return t.toUpperCase(Locale.US);
    }
}
