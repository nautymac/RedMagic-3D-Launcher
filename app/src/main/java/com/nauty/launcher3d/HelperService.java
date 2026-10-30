package com.nauty.launcher3d;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
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
    private Object sessions;
    private Context shell;
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
    public void setSurface(int displayId, final Surface surface) {
        final VirtualDisplay vd;
        synchronized (displays) { vd = displays.get(displayId); }
        if (vd == null) return;
        try {
            onMain(new Callable<Void>() {
                @Override public Void call() { vd.setSurface(surface); return null; }
            });
            Log.i(TAG, "디스플레이 " + displayId + " 출력면 " + (surface == null ? "뗌" : "바꿈"));
        } catch (Throwable t) {
            Log.e(TAG, "출력면 교체 실패", t);
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

    @Override
    public String processState(String pkg) {
        StringBuilder sb = new StringBuilder();
        for (String line : run("/system/bin/dumpsys", "activity", "processes", pkg).split("\n")) {
            String t = line.trim();
            if (t.startsWith("*APP*") || t.startsWith("curProcState") || t.startsWith("curCapability")) {
                sb.append(t).append('\n');
            }
        }
        sb.append(run("/system/bin/cmd", "appops", "get", pkg, "CAMERA").trim());
        return sb.toString();
    }

    // --- 재생 조작 ---
    // 유튜브 세션은 빨리감기/되감기 동작을 내놓지 않는다(실기 actions=8615: 재생·멈춤·다음·
    // 위치 이동). 그래서 미디어 키 대신 세션의 위치 이동(seekTo)을 쓴다. shell 은
    // MEDIA_CONTENT_CONTROL 이 있어 다른 앱의 세션을 볼 수 있다.

    @Override
    public String playPause(String pkg) {
        try {
            MediaController c = controller(pkg);
            if (c == null) return "재생 중인 영상이 없음";
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) c.getTransportControls().pause();
            else c.getTransportControls().play();
            Log.i(TAG, pkg + " 재생/멈춤 (상태 " + (s == null ? "?" : s.getState()) + ")");
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "재생/멈춤 실패", t);
            return "재생/멈춤 실패";
        }
    }

    @Override
    public String seek(String pkg, long deltaMs) {
        try {
            MediaController c = controller(pkg);
            PlaybackState s = c == null ? null : c.getPlaybackState();
            if (s == null) return "재생 중인 영상이 없음";
            long pos = s.getPosition();
            if (s.getState() == PlaybackState.STATE_PLAYING) {
                pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
            }
            c.getTransportControls().seekTo(Math.max(0, pos + deltaMs));
            Log.i(TAG, pkg + " 위치 " + pos + " → " + Math.max(0, pos + deltaMs));
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "위치 이동 실패", t);
            return "위치 이동 실패";
        }
    }

    private MediaController controller(String pkg) throws Exception {
        List<?> tokens = sessionTokens();
        for (Object t : tokens) {
            MediaController c = new MediaController(shellContext(), (MediaSession.Token) t);
            if (pkg.equals(c.getPackageName())) return c;
        }
        Log.i(TAG, pkg + " 재생 세션 없음 (" + tokens.size() + "개 중)");
        return null;
    }

    /**
     * 시스템 재생 세션 서비스에 바로 묻는다. MediaSessionManager 는 앱 프로세스에서만 채워지는
     * 미디어 모듈 초기화에 기대는데, 이 프로세스에선 그걸 넣어 줘도 생성자가 null 을 만났다
     * (실기 0.1.21·0.1.22-test). 서비스의 getSessions 는 버전마다 사용자 인자가 int 또는 UserHandle.
     */
    private List<?> sessionTokens() throws Exception {
        Object svc;
        synchronized (this) {
            if (sessions == null) {
                android.os.IBinder b = (android.os.IBinder) Class.forName("android.os.ServiceManager")
                        .getMethod("getService", String.class).invoke(null, "media_session");
                sessions = Class.forName("android.media.session.ISessionManager$Stub")
                        .getMethod("asInterface", android.os.IBinder.class).invoke(null, b);
            }
            svc = sessions;
        }
        for (Method m : svc.getClass().getMethods()) {
            if (!m.getName().equals("getSessions") || m.getParameterTypes().length != 2) continue;
            Class<?> user = m.getParameterTypes()[1];
            Object arg = user == int.class ? (Object) 0 : android.os.Process.myUserHandle();
            return (List<?>) m.invoke(svc, null, arg);
        }
        throw new NoSuchMethodException("ISessionManager.getSessions");
    }

    private synchronized Context shellContext() throws Exception {
        if (shell == null) shell = new ShellContext(systemContext());
        return shell;
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
