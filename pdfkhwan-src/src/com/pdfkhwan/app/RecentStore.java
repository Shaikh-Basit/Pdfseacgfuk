package com.pdfkhwan.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/* loaded from: classes.dex */
public class RecentStore {
    private static final String K_RECENTS = "recents";
    private static final String K_SETTINGS = "settings";
    private static final String PREFS = "pdfkhwan";
    private static RecentStore inst;
    private final SharedPreferences sp;

    private RecentStore(Context context) {
        this.sp = context.getApplicationContext().getSharedPreferences(PREFS, 0);
    }

    public static String fileKey(String str, long j) {
        if (str == null) {
            str = "pdf";
        }
        return String.valueOf(str) + "_" + j;
    }

    public static synchronized RecentStore get(Context context) {
        RecentStore recentStore;
        synchronized (RecentStore.class) {
            if (inst == null) {
                inst = new RecentStore(context);
            }
            recentStore = inst;
        }
        return recentStore;
    }

    public void addRecent(String str, String str2, long j, int i) {
        /* v1.29 FIX: synchronized (read-modify-write) + optJSONObject — a
           malformed entry used to abort the ENTIRE save (recents froze
           permanently) and two concurrent writers could lose an update. */
        synchronized (RecentStore.class) {
        try {
            JSONArray recents = getRecents();
            JSONArray jSONArray = new JSONArray();
            JSONObject jSONObject = new JSONObject();
            jSONObject.put("uri", str);
            jSONObject.put("name", str2);
            jSONObject.put("size", j);
            jSONObject.put("pages", i);
            jSONObject.put("ts", System.currentTimeMillis());
            jSONArray.put(jSONObject);
            for (int i2 = 0; i2 < recents.length() && jSONArray.length() < 30; i2++) {
                JSONObject jSONObject2 = recents.optJSONObject(i2);
                if (jSONObject2 == null || str.equals(jSONObject2.optString("uri"))) {
                    continue;
                }
                jSONArray.put(jSONObject2);
            }
            this.sp.edit().putString(K_RECENTS, jSONArray.toString()).apply();
        } catch (Exception unused) {
        }
        }
    }

    public JSONArray getBookmarks(String str) {
        try {
            return getPosition(str).optJSONArray("bm") == null ? new JSONArray() : getPosition(str).getJSONArray("bm");
        } catch (Exception unused) {
            return new JSONArray();
        }
    }

    public boolean getBool(String str, boolean z) {
        try {
            return new JSONObject(this.sp.getString(K_SETTINGS, "{}")).optBoolean(str, z);
        } catch (Exception unused) {
            return z;
        }
    }

    public int getInt(String str, int i) {
        try {
            return new JSONObject(this.sp.getString(K_SETTINGS, "{}")).optInt(str, i);
        } catch (Exception unused) {
            return i;
        }
    }

    public JSONObject getPosition(String str) {
        try {
            return new JSONObject(this.sp.getString("pos_" + str, "{}"));
        } catch (Exception unused) {
            return new JSONObject();
        }
    }

    public JSONArray getRecents() {
        try {
            return new JSONArray(this.sp.getString(K_RECENTS, "[]"));
        } catch (Exception unused) {
            return new JSONArray();
        }
    }

    public boolean hasBookmark(String str, int i) {
        JSONArray bookmarks = getBookmarks(str);
        for (int i2 = 0; i2 < bookmarks.length(); i2++) {
            if (bookmarks.optInt(i2, -1) == i) {
                return true;
            }
        }
        return false;
    }

    public void removeRecent(String str) {
        /* v1.29 FIX: same hardening as addRecent */
        synchronized (RecentStore.class) {
        try {
            JSONArray recents = getRecents();
            JSONArray jSONArray = new JSONArray();
            for (int i = 0; i < recents.length(); i++) {
                JSONObject jSONObject = recents.optJSONObject(i);
                if (jSONObject == null || str.equals(jSONObject.optString("uri"))) {
                    continue;
                }
                jSONArray.put(jSONObject);
            }
            this.sp.edit().putString(K_RECENTS, jSONArray.toString()).apply();
        } catch (Exception unused) {
        }
        }
    }

    public void renameKey(String str, String str2) {
        if (str == null || str2 == null || str.equals(str2)) {
            return;
        }
        try {
            JSONObject position = getPosition(str);
            if (position.length() == 0) {
                return;
            }
            this.sp.edit().putString("pos_" + str2, position.toString()).remove("pos_" + str).apply();
        } catch (Exception unused) {
        }
    }

    public void savePosition(String str, int i, float f, int i2) {
        try {
            JSONObject position = getPosition(str);
            position.put("page", i);
            position.put("zoom", f);
            position.put("pages", i2);
            position.put("ts", System.currentTimeMillis());
            this.sp.edit().putString("pos_" + str, position.toString()).apply();
        } catch (Exception unused) {
        }
    }

    public void setBool(String str, boolean z) {
        try {
            JSONObject jSONObject = new JSONObject(this.sp.getString(K_SETTINGS, "{}"));
            jSONObject.put(str, z);
            this.sp.edit().putString(K_SETTINGS, jSONObject.toString()).apply();
        } catch (Exception unused) {
        }
    }

    public void setInt(String str, int i) {
        try {
            JSONObject jSONObject = new JSONObject(this.sp.getString(K_SETTINGS, "{}"));
            jSONObject.put(str, i);
            this.sp.edit().putString(K_SETTINGS, jSONObject.toString()).apply();
        } catch (Exception unused) {
        }
    }

    /* v1.47: تنظیمات متنی (قفل جهت، حالت کاغذ و…) */
    public String getStr(String str, String def) {
        try {
            String v = new JSONObject(this.sp.getString(K_SETTINGS, "{}")).optString(str, def);
            return v == null || v.length() == 0 ? def : v;
        } catch (Exception unused) {
            return def;
        }
    }

    public void setStr(String str, String v) {
        try {
            JSONObject jSONObject = new JSONObject(this.sp.getString(K_SETTINGS, "{}"));
            jSONObject.put(str, v);
            this.sp.edit().putString(K_SETTINGS, jSONObject.toString()).apply();
        } catch (Exception unused) {
        }
    }

    /* v1.47: تنظیمات عددی long (روشنایی، سرعت اسکرول خودکار و…) */
    public long getLong(String str, long def) {
        try {
            return (long) new JSONObject(this.sp.getString(K_SETTINGS, "{}")).optDouble(str, (double) def);
        } catch (Exception unused) {
            return def;
        }
    }

    public void setLong(String str, long v) {
        try {
            JSONObject jSONObject = new JSONObject(this.sp.getString(K_SETTINGS, "{}"));
            jSONObject.put(str, v);
            this.sp.edit().putString(K_SETTINGS, jSONObject.toString()).apply();
        } catch (Exception unused) {
        }
    }

    public void toggleBookmark(String str, int i) {
        try {
            JSONObject position = getPosition(str);
            JSONArray jSONArray = position.optJSONArray("bm") == null ? new JSONArray() : position.getJSONArray("bm");
            JSONArray jSONArray2 = new JSONArray();
            int i2 = 0;
            boolean z = false;
            for (int i3 = 0; i3 < jSONArray.length(); i3++) {
                int i4 = jSONArray.getInt(i3);
                if (i4 == i) {
                    z = true;
                } else {
                    jSONArray2.put(i4);
                }
            }
            if (!z) {
                jSONArray2.put(i);
                while (i2 < jSONArray2.length()) {
                    int i5 = i2 + 1;
                    for (int i6 = i5; i6 < jSONArray2.length(); i6++) {
                        if (jSONArray2.optInt(i6) < jSONArray2.optInt(i2)) {
                            int optInt = jSONArray2.optInt(i2);
                            jSONArray2.put(i2, jSONArray2.optInt(i6));
                            jSONArray2.put(i6, optInt);
                        }
                    }
                    i2 = i5;
                }
            }
            position.put("bm", jSONArray2);
            this.sp.edit().putString("pos_" + str, position.toString()).apply();
        } catch (Exception unused) {
        }
    }
}
