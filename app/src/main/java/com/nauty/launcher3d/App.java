package com.nauty.launcher3d;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Helper.init(this);
    }

    // --- 앱별 설정 ---

    /** 2D→3D 엔진 깊이 기본값. 데모에서 확인한 값. */
    public static final float DEFAULT_GAIN = 0.3f;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("apps", Context.MODE_PRIVATE);
    }

    /** true 면 앱이 SBS 로 그린다고 보고 엔진 없이 그대로 위빙한다. */
    static boolean isSbs(Context c, String pkg) {
        return prefs(c).getBoolean(pkg + ".sbs", false);
    }

    static float gain(Context c, String pkg) {
        return prefs(c).getFloat(pkg + ".gain", DEFAULT_GAIN);
    }

    static void save(Context c, String pkg, boolean sbs, float gain) {
        prefs(c).edit().putBoolean(pkg + ".sbs", sbs).putFloat(pkg + ".gain", gain).apply();
    }

    static boolean isFavorite(Context c, String pkg) {
        return prefs(c).getBoolean(pkg + ".fav", false);
    }

    static void setFavorite(Context c, String pkg, boolean fav) {
        prefs(c).edit().putBoolean(pkg + ".fav", fav).apply();
    }

    /** 앱 선택 화면에서 즐겨찾기만 보일지. */
    static boolean favoritesOnly(Context c) {
        return prefs(c).getBoolean("view.favoritesOnly", false);
    }

    static void setFavoritesOnly(Context c, boolean on) {
        prefs(c).edit().putBoolean("view.favoritesOnly", on).apply();
    }

    static void saveGain(Context c, String pkg, float gain) {
        prefs(c).edit().putFloat(pkg + ".gain", gain).apply();
    }
}
