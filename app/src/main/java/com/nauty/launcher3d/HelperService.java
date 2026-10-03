package com.nauty.launcher3d;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/**
 * shell 권한으로 도는 도우미. Shizuku 가 UserService 로 띄운다 (앱 프로세스가 아니다).
 *
 * 일반 앱이 만든 가상 디스플레이는 "신뢰되지 않은" 디스플레이라 다른 앱의 화면을 올릴 수
 * 없다(실기 로그: "Not allow to launch ... on display N"). shell 은 ADD_TRUSTED_DISPLAY 를
 * 갖고 있으므로 여기서 신뢰된 디스플레이를 만든다. 디스플레이의 화면은 ViewActivity 가
 * 가진 입력면(CNSDK 입력면 또는 2D→3D 엔진 입력면)이고, AIDL 로 받는다.
 *
 * 데모(DepthFlix 의 VdHelper)를 app_process 대신 Shizuku 로 띄우도록 옮긴 것이다.
 * 디스플레이 플래그·시스템 컨텍스트·터치 주입 방식은 데모 그대로다.
 */
public class HelperService extends IHelper.Stub {

    private static final String TAG = "L3DHelper";
    private static final String SHELL = "com.android.shell";

    private final SparseArray<VirtualDisplay> displays = new SparseArray<>();
    private DisplayManager dm;
    private Object im;
    private Method inject;
    private Method setDisplay;

    public HelperService() {
        Log.i(TAG, "도우미 시작 uid " + Process.myUid());
    }

    /** Shizuku 13 은 Context 를 받는 생성자를 먼저 찾는다. 우리는 shell 컨텍스트를 따로 만든다. */
    public HelperService(Context ignored) {
        this();
    }

    @Override
    public void destroy() {
        Log.i(TAG, "도우미 종료");
        synchronized (displays) {
            for (int i = 0; i < displays.size(); i++) displays.valueAt(i).release();
            displays.clear();
        }
        System.exit(0);
    }

    @Override
    public int createDisplay(final Surface surface, final int w, final int h, final int dpi) {
        try {
            // 데모는 메인 루퍼 스레드에서 만들었다. 같게 맞춘다.
            VirtualDisplay vd = onMain(new Callable<VirtualDisplay>() {
                @Override public VirtualDisplay call() throws Exception {
                    int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                            | (1 << 6)      // SUPPORTS_TOUCH
                            | (1 << 8)      // DESTROY_CONTENT_ON_REMOVAL
                            | (1 << 10);    // TRUSTED
                    return displayManager().createVirtualDisplay("launcher3d", w, h, dpi, surface, flags);
                }
            });
            int id = vd.getDisplay().getDisplayId();
            synchronized (displays) { displays.put(id, vd); }
            Log.i(TAG, "디스플레이 " + id + " " + w + "x" + h + " dpi " + dpi);
            return id;
        } catch (Throwable t) {
            Log.e(TAG, "디스플레이 생성 실패", t);
            return -1;
        }
    }

    @Override
    public void releaseDisplay(int displayId) {
        VirtualDisplay vd;
        synchronized (displays) {
            vd = displays.get(displayId);
            displays.remove(displayId);
        }
        if (vd != null) {
            vd.release();
            Log.i(TAG, "디스플레이 " + displayId + " 해제");
        }
    }

    @Override
    public String launch(String component, int displayId) {
        String pkg = component.substring(0, component.indexOf('/'));
        // 이미 떠 있는 앱은 am start 가 기존 태스크를 앞으로 가져올 뿐 이 디스플레이로 옮기지
        // 않을 수 있다. 데모처럼 처음부터 새로 띄우기 위해 먼저 끈다.
        run("/system/bin/cmd", "activity", "force-stop", pkg);
        String out = run("/system/bin/cmd", "activity", "start-activity",
                "--display", String.valueOf(displayId), "-n", component);
        Log.i(TAG, "실행 " + component + " @" + displayId + ": " + out.trim());
        return out.contains("Error") || out.contains("Exception") ? out.trim() : null;
    }

    @Override
    public void injectMotion(MotionEvent e, int displayId) {
        try {
            injectEvent(e, displayId);
        } finally {
            e.recycle();
        }
    }

    @Override
    public void injectKey(int keyCode, int displayId) {
        long now = SystemClock.uptimeMillis();
        for (int action : new int[] { KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP }) {
            KeyEvent k = new KeyEvent(now, now, action, keyCode, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD);
            injectEvent(k, displayId);
        }
    }

    @Override
    public int taskCount(int displayId) {
        try {
            Object atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            List<?> l = (List<?>) atm.getClass().getMethod("getAllRootTaskInfosOnDisplay", int.class)
                    .invoke(atm, displayId);
            return l == null ? 0 : l.size();
        } catch (Throwable t) {
            Log.e(TAG, "태스크 조회 실패", t);
            return -1;
        }
    }

    // --- 입력 주입 (데모 VdHelper.Injector 와 같다) ---

    private void injectEvent(InputEvent e, int displayId) {
        try {
            synchronized (this) {
                if (im == null) {
                    Class<?> imc = Class.forName("android.hardware.input.InputManager");
                    im = imc.getDeclaredMethod("getInstance").invoke(null);
                    inject = imc.getMethod("injectInputEvent", InputEvent.class, int.class);
                    setDisplay = InputEvent.class.getMethod("setDisplayId", int.class);
                }
            }
            setDisplay.invoke(e, displayId);
            inject.invoke(im, e, 0);   // INJECT_INPUT_EVENT_MODE_ASYNC
        } catch (Throwable t) {
            Log.e(TAG, "inject 실패", t);
        }
    }

    // --- 시스템 컨텍스트 (scrcpy 와 같은 방식) ---

    private synchronized DisplayManager displayManager() throws Exception {
        if (dm == null) {
            Constructor<DisplayManager> c = DisplayManager.class.getDeclaredConstructor(Context.class);
            c.setAccessible(true);
            dm = c.newInstance(new ShellContext(systemContext()));
        }
        return dm;
    }

    private static Context systemContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        // Shizuku 가 이미 ActivityThread 를 만들어 두었으면 그것을 쓴다.
        Object thread = at.getDeclaredMethod("currentActivityThread").invoke(null);
        if (thread == null) {
            Constructor<?> ctor = at.getDeclaredConstructor();
            ctor.setAccessible(true);
            thread = ctor.newInstance();
            java.lang.reflect.Field cur = at.getDeclaredField("sCurrentActivityThread");
            cur.setAccessible(true);
            cur.set(null, thread);
        }
        Method m = at.getDeclaredMethod("getSystemContext");
        return (Context) m.invoke(thread);
    }

    private static final class ShellContext extends ContextWrapper {
        ShellContext(Context base) { super(base); }
        @Override public String getPackageName() { return SHELL; }
        @Override public String getOpPackageName() { return SHELL; }
        @Override public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(Process.SHELL_UID).setPackageName(SHELL).build();
        }
        @Override public Context getApplicationContext() { return this; }
    }

    // --- 유틸 ---

    private static <T> T onMain(Callable<T> c) throws Exception {
        Looper main = Looper.getMainLooper();
        if (main == null || main.isCurrentThread()) return c.call();
        FutureTask<T> f = new FutureTask<>(c);
        Handler h = new Handler(main);
        h.post(f);
        try {
            return f.get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            // 메인 루퍼가 돌고 있지 않다 — 이 스레드에서 직접 한다.
            h.removeCallbacks(f);
            return c.call();
        }
    }

    private static String run(String... cmd) {
        try {
            java.lang.Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            }
            p.waitFor();
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return "Error: " + t;
        }
    }
}
