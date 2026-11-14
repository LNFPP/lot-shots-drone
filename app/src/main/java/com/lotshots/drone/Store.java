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

    /** null = controller storage (DCIM/LotShots). Otherwise a folder on the microSD card. */
    private static volatile String saveRoot = null;

    public static File internalRoot() {
        return new File(Environment.getExternalStorageDirectory(), "DCIM/LotShots");
    }

    public static File outputRoot() {
        String r = saveRoot;
        return r != null ? new File(r) : internalRoot();
    }

    public static boolean onSdCard() {
        return saveRoot != null;
    }

    public void setSaveRoot(String r) {
        saveRoot = r;
        if (r == null) sp.edit().remove("saveRoot").apply();
        else sp.edit().putString("saveRoot", r).apply();
    }

    /**
     * Finds a writable LotShots folder on the microSD card. Tries the card's top level first
     * (easy to find in Finder); if the controller doesn't allow that, uses this app's own folder on the card.
     * Returns null if there's no card.
     */
    public static String findSdRoot(Context c) {
        File[] dirs = c.getExternalFilesDirs(null);
        if (dirs == null) return null;
        for (int i = 1; i < dirs.length; i++) {
            File appDir = dirs[i];
            if (appDir == null) continue;
            String p = appDir.getAbsolutePath();
            int cut = p.indexOf("/Android/data");
            if (cut > 0) {
                File top = new File(p.substring(0, cut), "LotShots");
                if (writable(top)) return top.getAbsolutePath();
            }
            File inApp = new File(appDir, "LotShots");
            if (writable(inApp)) return inApp.getAbsolutePath();
        }
        return null;
    }

    private static boolean writable(File dir) {
        try {
            if (!dir.exists() && !dir.mkdirs()) return false;
            File t = new File(dir, ".lotshots_test");
            if (!t.createNewFile() && !t.exists()) return false;
            t.delete();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static File exportDir() {
        return new File(Environment.getExternalStorageDirectory(), "Documents/LotShots");
    }

    private static final Object LOCK = new Object();
    private final SharedPreferences sp;

    public Store(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("lotshots", Context.MODE_PRIVATE);
        saveRoot = sp.getString("saveRoot", null);
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

    // ---------- locations (Flight Log places that aren't communities) ----------

    public List<String> locationNames() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString("locations", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        } catch (JSONException ignored) {
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** Adds a location unless it already exists (any capitalization). Returns the name to use. */
    public String addLocation(String name) {
        synchronized (LOCK) {
            for (String n : locationNames()) if (n.equalsIgnoreCase(name)) return n;
            JSONArray a;
            try {
                a = new JSONArray(sp.getString("locations", "[]"));
            } catch (JSONException e) {
                a = new JSONArray();
            }
            a.put(name);
            sp.edit().putString("locations", a.toString()).apply();
            return name;
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
        deleteWindowsFor(community, date);
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
        if (community.equals(active()) && date.equals(activeDate())) {
            setActiveDate("");
            setOpenWindowId(0);
            setCurrentLot("");
        }
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

    /** Only stitched 360 spheres (2:1 images) are ever counted. */
    public boolean only360() {
        return true;
    }

    // ---------- flight log (time at each place; nothing to do with photos) ----------
    // Stored as {"2026-11-12": [{"id":..,"type":"community"|"location","place":..,"start":ms,"stop":ms}, ...]}

    private JSONObject flights() {
        try {
            return new JSONObject(sp.getString("flights", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private void saveFlights(JSONObject o) {
        sp.edit().putString("flights", o.toString()).apply();
    }

    public static String today() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
    }

    /** Flight-log dates, newest first. */
    public List<String> flightDates() {
        synchronized (LOCK) {
            List<String> out = new ArrayList<>();
            Iterator<String> it = flights().keys();
            while (it.hasNext()) out.add(it.next());
            Collections.sort(out, Collections.reverseOrder());
            return out;
        }
    }

    /** Adds today's date if missing. Returns false if it was already there. */
    public boolean addFlightDate(String date) {
        synchronized (LOCK) {
            JSONObject o = flights();
            if (o.has(date)) return false;
            try {
                o.put(date, new JSONArray());
            } catch (JSONException ignored) {
            }
            saveFlights(o);
            return true;
        }
    }

    public void deleteFlightDate(String date) {
        synchronized (LOCK) {
            JSONObject o = flights();
            o.remove(date);
            saveFlights(o);
        }
    }

    public List<JSONObject> sessions(String date) {
        synchronized (LOCK) {
            List<JSONObject> out = new ArrayList<>();
            JSONArray a = flights().optJSONArray(date);
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e != null) out.add(e);
            }
            return out;
        }
    }

    public void addSession(String date, String type, String place) {
        synchronized (LOCK) {
            JSONObject o = flights();
            JSONArray a = o.optJSONArray(date);
            if (a == null) a = new JSONArray();
            JSONObject e = new JSONObject();
            try {
                e.put("id", System.currentTimeMillis());
                e.put("type", type);
                e.put("place", place);
                e.put("start", 0);
                e.put("stop", 0);
                a.put(e);
                o.put(date, a);
            } catch (JSONException ignored) {
            }
            saveFlights(o);
        }
    }

    /** field = "start" or "stop"; value 0 clears it. Starting one timer stops any other running timer. */
    public void setSessionTime(String date, long id, String field, long value) {
        synchronized (LOCK) {
            JSONObject o = flights();
            long now = System.currentTimeMillis();
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String d = it.next();
                JSONArray a = o.optJSONArray(d);
                if (a == null) continue;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject e = a.optJSONObject(i);
                    if (e == null) continue;
                    try {
                        if (d.equals(date) && e.optLong("id") == id) {
                            e.put(field, value);
                        } else if ("start".equals(field) && value > 0 && e.optLong("start") > 0 && e.optLong("stop") == 0) {
                            e.put("stop", now);
                        }
                    } catch (JSONException ignored) {
                    }
                }
            }
            saveFlights(o);
        }
    }

    public void deleteSession(String date, long id) {
        synchronized (LOCK) {
            JSONObject o = flights();
            JSONArray a = o.optJSONArray(date), keep = new JSONArray();
            if (a == null) return;
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e != null && e.optLong("id") != id) keep.put(e);
            }
            try {
                o.put(date, keep);
            } catch (JSONException ignored) {
            }
            saveFlights(o);
        }
    }

    /** The running timer, as {date, session} — or null if none is running. */
    public Object[] runningSession() {
        synchronized (LOCK) {
            JSONObject o = flights();
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String d = it.next();
                JSONArray a = o.optJSONArray(d);
                if (a == null) continue;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject e = a.optJSONObject(i);
                    if (e != null && e.optLong("start") > 0 && e.optLong("stop") == 0) return new Object[]{d, e};
                }
            }
            return null;
        }
    }

    // ---------- watcher activity (what the 360 watcher saw and why) ----------

    public void addWatchLog(String msg) {
        synchronized (LOCK) {
            JSONArray a;
            try {
                a = new JSONArray(sp.getString("watchLog", "[]"));
            } catch (JSONException e) {
                a = new JSONArray();
            }
            String t = new java.text.SimpleDateFormat("h:mm:ss a", java.util.Locale.US).format(new java.util.Date());
            a.put(t + "  " + msg);
            JSONArray keep = new JSONArray();
            for (int i = Math.max(0, a.length() - 40); i < a.length(); i++) keep.put(a.optString(i));
            sp.edit().putString("watchLog", keep.toString()).apply();
        }
    }

    /** Newest first. */
    public List<String> watchLog() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString("watchLog", "[]"));
            for (int i = a.length() - 1; i >= 0; i--) out.add(a.optString(i));
        } catch (JSONException ignored) {
        }
        return out;
    }

    public void clearWatchLog() {
        sp.edit().remove("watchLog").apply();
    }

    // ---------- lot windows: Set lot -> "Finished stitch? Yes" ----------
    // [{"id", "community", "date", "lot", "set": ms, "finish": ms or 0}]

    private JSONArray windowsAll() {
        try {
            return new JSONArray(sp.getString("lotWindows", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private void saveWindows(JSONArray a) {
        sp.edit().putString("lotWindows", a.toString()).apply();
    }

    /** Records "lot set at this time" and returns its id. */
    public long openWindow(String community, String date, String lot, long setMs) {
        synchronized (LOCK) {
            JSONArray a = windowsAll();
            JSONObject e = new JSONObject();
            long id = setMs;
            try {
                e.put("id", id);
                e.put("community", community);
                e.put("date", date);
                e.put("lot", lot);
                e.put("set", setMs);
                e.put("finish", 0);
            } catch (JSONException ignored) {
            }
            a.put(e);
            saveWindows(a);
            return id;
        }
    }

    public void finishWindow(long id, long finishMs) {
        synchronized (LOCK) {
            JSONArray a = windowsAll();
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e != null && e.optLong("id") == id) try {
                    e.put("finish", finishMs);
                } catch (JSONException ignored) {
                }
            }
            saveWindows(a);
        }
    }

    /** Cancel: removes the lot and its set time completely. */
    public void cancelWindow(long id) {
        synchronized (LOCK) {
            JSONArray a = windowsAll(), keep = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e != null && e.optLong("id") != id) keep.put(e);
            }
            saveWindows(keep);
        }
    }

    public void deleteWindowsFor(String community, String date) {
        synchronized (LOCK) {
            JSONArray a = windowsAll(), keep = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e == null) continue;
                if (community.equals(e.optString("community")) && date.equals(e.optString("date"))) continue;
                keep.put(e);
            }
            saveWindows(keep);
        }
    }

    /** The lot currently waiting for "Finished stitch?" (0 = none). */
    public long openWindowId() {
        return sp.getLong("openWindowId", 0);
    }

    public void setOpenWindowId(long id) {
        sp.edit().putLong("openWindowId", id).apply();
    }

    /** Windows for one community + date (null community = every community that date), oldest first. */
    public List<JSONObject> windows(String community, String date) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray a = windowsAll();
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e == null || !date.equals(e.optString("date"))) continue;
            if (community != null && !community.equals(e.optString("community"))) continue;
            out.add(e);
        }
        Collections.sort(out, (x, y) -> Long.compare(x.optLong("set"), y.optLong("set")));
        return out;
    }

    public Set<String> loggedLots(String community, String date) {
        Set<String> s = new HashSet<>();
        for (JSONObject e : windows(community, date)) if (e.optLong("finish") > 0) s.add(e.optString("lot"));
        return s;
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
