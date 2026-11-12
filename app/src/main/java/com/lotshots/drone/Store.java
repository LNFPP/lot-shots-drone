package com.lotshots.drone;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** All saved app state, kept in SharedPreferences as JSON. Shared by the screen and the bubble. */
public class Store {

    public static File outputRoot() {
        return new File(Environment.getExternalStorageDirectory(), "DCIM/LotShots");
    }

    public static File exportDir() {
        return new File(Environment.getExternalStorageDirectory(), "Documents/LotShots");
    }

    private static final Object LOCK = new Object();
    private final SharedPreferences sp;

    public Store(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("lotshots", Context.MODE_PRIVATE);
    }

    // ---------- communities ----------

    private JSONObject communities() {
        try {
            return new JSONObject(sp.getString("communities", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public List<String> communityNames() {
        synchronized (LOCK) {
            JSONObject o = communities();
            List<String> names = new ArrayList<>();
            Iterator<String> it = o.keys();
            while (it.hasNext()) names.add(it.next());
            Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
            return names;
        }
    }

    public List<String> lots(String community) {
        synchronized (LOCK) {
            List<String> out = new ArrayList<>();
            JSONArray a = communities().optJSONArray(community);
            if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
            return out;
        }
    }

    /** Adds or replaces a community. Names match case-insensitively, so "miralon" replaces "Miralon". */
    public String putCommunity(String name, List<String> lots) {
        synchronized (LOCK) {
            JSONObject o = communities();
            Iterator<String> it = o.keys();
            String existing = null;
            while (it.hasNext()) {
                String k = it.next();
                if (k.equalsIgnoreCase(name)) existing = k;
            }
            if (existing != null) o.remove(existing);
            JSONArray a = new JSONArray();
            for (String l : lots) a.put(l);
            try {
                o.put(name, a);
            } catch (JSONException ignored) {
            }
            sp.edit().putString("communities", o.toString()).apply();
            if (existing != null && existing.equals(active())) setActive(name);
            return existing;
        }
    }

    public void deleteCommunity(String name) {
        synchronized (LOCK) {
            JSONObject o = communities();
            o.remove(name);
            sp.edit().putString("communities", o.toString()).apply();
            if (name.equals(active())) {
                setActive("");
                setActiveDate("");
                setCurrentLot("");
            }
        }
    }

    // ---------- active community / lot ----------

    public String active() {
        return sp.getString("active", "");
    }

    public void setActive(String c) {
        sp.edit().putString("active", c).apply();
    }

    public String currentLot() {
        return sp.getString("currentLot", "");
    }

    public void setCurrentLot(String lot) {
        sp.edit().putString("currentLot", lot).apply();
    }

    /** The date folder (e.g. 2026-11-11) new 360s go into, for the active community. */
    public String activeDate() {
        return sp.getString("activeDate", "");
    }

    public void setActiveDate(String d) {
        sp.edit().putString("activeDate", d).apply();
    }

    public static File communityDir(String community) {
        return new File(outputRoot(), Names.clean(community));
    }

    public static File dateDir(String community, String date) {
        return new File(communityDir(community), date);
    }

    /** Date folders for a community, newest first. The folders on disk are the source of truth. */
    public List<String> dates(String community) {
        List<String> out = new ArrayList<>();
        File[] dirs = communityDir(community).listFiles();
        if (dirs != null) for (File d : dirs) if (d.isDirectory() && !d.getName().startsWith(".")) out.add(d.getName());
        Collections.sort(out, Collections.reverseOrder());
        return out;
    }

    /** Creates today's folder (or returns it if it already exists). */
    public String createDate(String community) {
        String d = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
        File dir = dateDir(community, d);
        if (!dir.exists() && !dir.mkdirs()) return "";
        return d;
    }

    public int count360s(String community, String date) {
        File[] files = dateDir(community, date).listFiles();
        int n = 0;
        if (files != null) for (File f : files) if (Names.isJpeg(f)) n++;
        return n;
    }

    /** Deletes a date folder and its 360s from the controller, and drops those log entries. */
    public boolean deleteDate(String community, String date) {
        boolean ok = deleteRecursive(dateDir(community, date));
        synchronized (LOCK) {
            JSONArray a = log(), keep = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e == null) continue;
                if (community.equals(e.optString("community")) && date.equals(e.optString("date"))) continue;
                keep.put(e);
            }
            setLog(keep);
        }
        if (community.equals(active()) && date.equals(activeDate())) setActiveDate("");
        return ok;
    }

    private static boolean deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        return !f.exists() || f.delete();
    }

    // ---------- settings ----------

    public List<String> watchFolders() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString("watchFolders", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        } catch (JSONException ignored) {
        }
        return out;
    }

    public void setWatchFolders(List<String> folders) {
        JSONArray a = new JSONArray();
        for (String f : folders) a.put(f);
        sp.edit().putString("watchFolders", a.toString()).apply();
    }

    /** Only treat 2:1 images (stitched 360 spheres) as lot shots. */
    public boolean only360() {
        return sp.getBoolean("only360", true);
    }

    public void setOnly360(boolean b) {
        sp.edit().putBoolean("only360", b).apply();
    }

    // ---------- shot log ----------

    public JSONArray log() {
        synchronized (LOCK) {
            try {
                return new JSONArray(sp.getString("log", "[]"));
            } catch (JSONException e) {
                return new JSONArray();
            }
        }
    }

    public void setLog(JSONArray a) {
        synchronized (LOCK) {
            sp.edit().putString("log", a.toString()).apply();
        }
    }

    public void addLog(JSONObject entry) {
        synchronized (LOCK) {
            JSONArray a = log();
            a.put(entry);
            sp.edit().putString("log", a.toString()).apply();
        }
    }

    /** Log entries for one community + date folder, oldest first. */
    public List<JSONObject> entries(String community, String date) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray a = log();
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e != null && community.equals(e.optString("community")) && date.equals(e.optString("date"))) out.add(e);
        }
        return out;
    }

    /** Lots that already have a 360 in this date folder. */
    public Set<String> shotLots(String community, String date) {
        Set<String> s = new HashSet<>();
        for (JSONObject e : entries(community, date)) s.add(e.optString("lot"));
        return s;
    }

    /** Next lot in list order after `from` that has no 360 yet (wraps around). "" if all are done. */
    public String nextUnshot(String community, String date, String from) {
        List<String> lots = lots(community);
        if (lots.isEmpty()) return "";
        Set<String> shot = shotLots(community, date);
        int start = lots.indexOf(from);
        for (int step = 1; step <= lots.size(); step++) {
            String l = lots.get((start + step + lots.size()) % lots.size());
            if (!shot.contains(l)) return l;
        }
        return "";
    }
}
