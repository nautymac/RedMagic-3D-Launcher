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

    static float gain(Context c, String pkg) {
        return prefs(c).getFloat(pkg + ".gain", DEFAULT_GAIN);
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

    // --- screen3d 로 실행 ---

    /** screen3d(별도 앱): 앱 화면에서 NPU 로 깊이를 재 SBS 를 그린다. 그 재생 화면에 띄울 앱을 넘긴다. */
    static final String S3D_PKG = "com.example.screen3d";
    static final String S3D_PLAYER = S3D_PKG + "/" + S3D_PKG + ".PlayerActivity";

    /**
     * screen3d 재생 화면을 다른 앱이 열 수 있을 때만 true. 원본 screen3d 는 이 화면이 닫혀 있어서
     * (exported=false) 이 기능을 쓸 수 없고, 앱 설정에도 항목을 보이지 않는다.
     */
    static boolean s3dReady(Context c) {
        try {
            return c.getPackageManager().getActivityInfo(
                    android.content.ComponentName.unflattenFromString(S3D_PLAYER), 0).exported;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 이 앱을 screen3d 로 띄울지(SBS 로 시작). 앱별로 기억한다. */
    static boolean viaS3d(Context c, String pkg) {
        return prefs(c).getBoolean(pkg + ".viaS3d", false);
    }

    static void setViaS3d(Context c, String pkg, boolean on) {
        prefs(c).edit().putBoolean(pkg + ".viaS3d", on).apply();
    }

    static void saveGain(Context c, String pkg, float gain) {
        prefs(c).edit().putFloat(pkg + ".gain", gain).apply();
    }
}
