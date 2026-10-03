package com.nauty.launcher3d;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;

import rikka.shizuku.Shizuku;

/**
 * 앱 쪽에서 shell 도우미(HelperService)를 띄우고 붙잡아 두는 곳.
 *
 * Shizuku 가 돌고 있고 권한을 받았으면 UserService 로 도우미를 띄운다. daemon 을 끄므로
 * 우리 앱 프로세스가 죽으면 도우미도 함께 죽고, 도우미가 만든 디스플레이도 사라진다.
 */
public final class Helper {

    private static final String TAG = "L3D";
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final int PERMISSION_REQUEST = 3001;

    public enum State { NO_SHIZUKU_APP, NOT_RUNNING, NO_PERMISSION, CONNECTING, READY }

    public interface Listener { void onHelperState(State s); }

    private static Context app;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static volatile IHelper service;
    private static boolean binding;

    private static final Shizuku.UserServiceArgs ARGS = new Shizuku.UserServiceArgs(
            new ComponentName(BuildConfig.APPLICATION_ID, HelperService.class.getName()))
            .daemon(false)
            .processNameSuffix("helper")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE);

    private static final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            if (b == null || !b.pingBinder()) return;
            service = IHelper.Stub.asInterface(b);
            binding = false;
            Log.i(TAG, "도우미 연결됨");
            notifyState();
        }
        @Override public void onServiceDisconnected(ComponentName n) {
            service = null;
            binding = false;
            Log.i(TAG, "도우미 연결 끊김");
            notifyState();
        }
    };

    static void init(Context c) {
        app = c.getApplicationContext();
        Shizuku.addBinderReceivedListenerSticky(new Shizuku.OnBinderReceivedListener() {
            @Override public void onBinderReceived() { connect(); }
        });
        Shizuku.addBinderDeadListener(new Shizuku.OnBinderDeadListener() {
            @Override public void onBinderDead() {
                service = null;
                binding = false;
                notifyState();
            }
        });
        Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
            @Override public void onRequestPermissionResult(int requestCode, int grantResult) {
                if (requestCode == PERMISSION_REQUEST) connect();
            }
        });
    }

    public static IHelper get() { return service; }

    public static State state() {
        if (service != null) return State.READY;
        if (!Shizuku.pingBinder()) {
            try {
                app.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
                return State.NOT_RUNNING;
            } catch (PackageManager.NameNotFoundException e) {
                return State.NO_SHIZUKU_APP;
            }
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return State.NO_PERMISSION;
        return State.CONNECTING;
    }

    /** 상태에 따라 권한을 요청하거나 도우미를 띄운다. */
    public static void connect() {
        main.post(new Runnable() { @Override public void run() {
            State s = state();
            if (s == State.NO_PERMISSION) {
                if (!Shizuku.shouldShowRequestPermissionRationale()) Shizuku.requestPermission(PERMISSION_REQUEST);
            } else if (s == State.CONNECTING && !binding) {
                binding = true;
                try {
                    Shizuku.bindUserService(ARGS, conn);
                } catch (Throwable t) {
                    binding = false;
                    Log.e(TAG, "도우미 실행 실패", t);
                }
            }
            notifyState();
        }});
    }

    /** Shizuku 앱을 연다 (재부팅 뒤 "시작" 을 누르러). */
    public static void openShizuku(Context c) {
        android.content.Intent i = c.getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
        if (i != null) c.startActivity(i);
    }

    public static void addListener(Listener l) { listeners.add(l); }
    public static void removeListener(Listener l) { listeners.remove(l); }

    private static void notifyState() {
        main.post(new Runnable() { @Override public void run() {
            State s = state();
            for (Listener l : listeners) l.onHelperState(s);
        }});
    }
}
