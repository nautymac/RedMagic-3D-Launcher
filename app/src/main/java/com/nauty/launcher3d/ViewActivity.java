package com.nauty.launcher3d;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.leia.core.PlatformInitArgs;
import com.leia.sdk.LeiaSDK;
import com.leia.sdk.graphics.SurfaceTextureReadyCallback;
import com.leia.sdk.views.InputViewsAsset;
import com.leia.sdk.views.InterlacedSurfaceView;
import com.leia.sdk.views.InterlacedSurfaceViewConfigAccessor;
import com.leia.sdk.views.ScaleType;
import com.leiainc.leiamediasdk.LeiaMediaSDK;
import com.leiainc.leiamediasdk.interfaces.MonoVideoSurfaceRenderer;

/**
 * 3D 화면. 고른 앱을 신뢰된 가상 디스플레이에 띄우고, 그 화면을 3D 로 위빙한다.
 *
 * <pre>
 * [2D 모드] 앱 ──▶ 가상 디스플레이 1920x1200 ──▶ 엔진 입력면 ──▶ Leia 2D→3D 엔진 ──▶ CNSDK 입력면 ──▶ 위빙
 * [SBS 모드] 앱 ──▶ 가상 디스플레이 1920x1200 ──▶ CNSDK 입력면 ────────────────────────────▶ 위빙
 * 화면 터치 ──▶ 디스플레이 좌표로 변환 ──▶ 도우미 ──▶ injectInputEvent(displayId)
 * </pre>
 * 위빙·엔진 연결은 DepthFlix 데모(VdSpikeActivity)를 그대로 옮긴 것이다.
 *
 * 디스플레이 크기는 두 모드가 같다. 그래서 실행 중에 모드를 바꿀 때 디스플레이의 출력면만
 * 갈아 끼우면 되고, 앱은 크기 변화를 겪지 않아 그대로 돈다. SBS 는 한 화면에 좌우를 담는
 * 절반 폭 SBS(눈당 960x1200)로 받는다 — SBS 영상과 에뮬레이터의 기본 출력이 이 형식이다.
 *
 * 뒤로 제스처는 앱에 KEYCODE_BACK 으로 넘긴다. 화면 위쪽에서 끌어내리거나 세 손가락으로 터치하면
 * 위에 도구 막대(나가기·모드·깊이·눈 추적 상태)가 잠깐 나온다. 이 화면을 나가면 디스플레이를
 * 없애고, 그 위의 앱 화면도 함께 닫힌다. 앱이 스스로 끝나 디스플레이가 비면 이 화면도 닫는다.
 */
public class ViewActivity extends Activity {

    static final String EXTRA_COMPONENT = "component";
    static final String EXTRA_LABEL = "label";

    private static final String TAG = "L3D";
    private static final int FRAME_W = 3840;   // CNSDK 입력: 두 눈. 눈당 1920x1200 = 패널과 같은 16:10
    private static final int FRAME_H = 1200;
    private static final int DISPLAY_W = FRAME_W / 2;   // 가상 디스플레이: 두 모드 공통
    private static final int DPI = 240;

    /** 모드 전환(엔진 해제·생성, 출력면 교체)을 한 줄로 세운다. 엔진 생성이 1초쯤 블록한다. */
    private final java.util.concurrent.ExecutorService pipeline =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile boolean switching;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private String component, pkg;
    private boolean sbs;
    private float gain;

    private LeiaSDK sdk;
    private InterlacedSurfaceView leiaView;
    private Surface out;
    private volatile boolean sdkReady = false;
    private boolean wantActive = false;

    // 얼굴추적기는 공유 서비스라 켜 달라고 해도 바로 안 올 수 있다(직전 앱이 놓는 중). 그래서
    // 3D 가 켜지면 SDK 가 "돌고 있다"고 할 때까지 몇 번 다시 요청하고, 그 뒤로는 시청자 위치를
    // 가끔 로그에 남긴다. 위빙이 얼굴을 따라가는지는 화면이 아니라 로그로 확인한다.
    private static final int TRACKER_ATTEMPTS = 5;
    private static final long TRACKER_RETRY_MS = 400;
    private static final int FACE_SAMPLES_FAST = 10;
    private int trackerAttempt;
    private int faceSamples;
    private boolean viewerAnnounced;
    private boolean trackerReasked;
    private boolean lastSeen;

    // 추적기는 별도 시스템 서비스이고, Android 는 앞에 있는 앱이 카메라 권리를 빌려줄 때만 서비스가
    // 카메라를 쓰게 한다. CNSDK 가 거는 바인딩(BIND_AUTO_CREATE|BIND_IMPORTANT)엔 그게 없어서,
    // 서비스가 포그라운드로 갈 때의 상태에 따라 카메라가 "disabled by policy" 로 거부되곤 했다
    // (프로세스가 갓 뜬 직후엔 거부, 오래 살아 있으면 성공). 그래서 3D 가 켜져 있는 동안 이 앱이
    // 카메라 권리를 넘겨주는 바인딩(BIND_INCLUDE_CAPABILITIES)을 하나 더 쥐고 있는다.
    private static final String TRACKER_PACKAGE = "com.leia.headtrackingservice";
    private static final String TRACKER_SERVICE = "com.leia.headtrackingservice.HeadTrackingService";
    private static final int FACE_SAMPLES_BEFORE_REASK = 4;
    private android.content.ServiceConnection trackerLease;

    private volatile Object monoEngine;
    private Surface monoIn;

    private volatile int displayId = -1;
    private volatile boolean destroyed;

    private LinearLayout toolbar;
    private Button modeButton, minus, plus;
    private TextView gainText, trackText;
    private boolean touchToToolbar;
    /** 세 손가락 터치로 막대를 연 동작. 손가락이 다 떨어질 때까지 앱에 보내지 않는다. */
    private boolean menuGesture;
    /** 위쪽 이 높이 안에서 시작해 이만큼 끌어내리면 막대를 연다. */
    private static final int PULL_ZONE_DP = 48;
    private static final int PULL_DISTANCE_DP = 80;
    private boolean pullDown;
    private float pullStartY;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        component = getIntent().getStringExtra(EXTRA_COMPONENT);
        if (component == null || Helper.get() == null) {
            Toast.makeText(this, "도우미가 연결되지 않았습니다", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        pkg = component.substring(0, component.indexOf('/'));
        sbs = App.isSbs(this, pkg);
        gain = App.gain(this, pkg);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        leiaView = new InterlacedSurfaceView(this);
        root.addView(leiaView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(buildToolbar(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        setContentView(root);
        goFullscreen();
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
            @Override public void onSystemUiVisibilityChange(int v) {
                // 사용자가 가장자리를 쓸어 시스템 바를 꺼냈다 → 도구 막대도 같이 보여 준다.
                if ((v & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) showToolbar();
            }
        });

        InputViewsAsset asset = new InputViewsAsset();
        asset.CreateEmptySurfaceForVideo(FRAME_W, FRAME_H, new SurfaceTextureReadyCallback() {
            @Override public void onSurfaceTextureReady(SurfaceTexture st) {
                st.setDefaultBufferSize(FRAME_W, FRAME_H);
                out = new Surface(st);
                pipeline.execute(new Runnable() { @Override public void run() {
                    if (sbs) attach(out);
                    else if (!buildMono()) fail("Leia 2D→3D 엔진을 쓸 수 없습니다");
                }});
            }
        });
        leiaView.setViewAsset(asset);

        try (InterlacedSurfaceViewConfigAccessor c = leiaView.getConfig()) {
            c.setSourceSize(FRAME_W, FRAME_H);
            c.setNumTiles(2, 1);
            c.setScaleType(ScaleType.FIT_CENTER);
        } catch (Throwable t) {
            Log.e(TAG, "config 설정 실패", t);
        }

        initSdk();
    }

    // --- 2D 모드: Leia 2D→3D 엔진(기기의 신경망 변환기) ---

    /**
     * 엔진을 CNSDK 입력면 위에 만들고, 그 입력면(눈 하나 크기 1920x1200)을 디스플레이에 붙인다.
     * 엔진은 시차를 만들어 2타일 SBS 로 CNSDK 입력면에 그린다. pipeline 스레드에서만 부른다.
     */
    private boolean buildMono() {
        LeiaMediaSDK media = LeiaMediaSDK.getInstance(this);
        android.content.Context svc = media == null ? null : LeiaMediaSDK.serviceContext(this);
        if (svc == null) return false;
        final java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        Object engine = media.createMonoVideoSurfaceRenderer(svc, out,
                new MonoVideoSurfaceRenderer.SurfaceTextureCallback() {
                    @Override public void onSurfaceTextureReady(SurfaceTexture st) {
                        st.setDefaultBufferSize(DISPLAY_W, FRAME_H);
                        monoIn = new Surface(st);
                        ready.countDown();
                    }
                });
        if (engine == null) return false;
        monoEngine = engine;
        try { ((MonoVideoSurfaceRenderer) engine).setGainMultiplier(gain); } catch (Throwable ignored) { }
        try { ready.await(3, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        if (monoIn == null) return false;
        Log.i(TAG, "2D→3D 엔진 생성 gain " + gain);
        attach(monoIn);
        return true;
    }

    /** 엔진을 내린다. 그 전에 디스플레이를 엔진 입력면에서 떼어 둔다. pipeline 스레드에서만 부른다. */
    private void dropMono() {
        IHelper h = Helper.get();
        if (h != null && displayId >= 0) {
            try { h.setSurface(displayId, null); } catch (Throwable ignored) { }
        }
        Object e = monoEngine;
        monoEngine = null;
        if (e != null) {
            try { ((MonoVideoSurfaceRenderer) e).release(); } catch (Throwable ignored) { }
        }
        if (monoIn != null) { monoIn.release(); monoIn = null; }
    }

    // --- 가상 디스플레이 + 앱 실행 (도우미가 한다) ---

    /** 디스플레이가 없으면 만들고 앱을 실행한다. 있으면 출력면만 바꾼다. pipeline 스레드에서만 부른다. */
    private void attach(Surface s) {
        IHelper h = Helper.get();
        if (h == null) { fail("도우미 연결이 끊겼습니다"); return; }
        try {
            if (displayId >= 0) {
                h.setSurface(displayId, s);
                return;
            }
            int id = h.createDisplay(s, DISPLAY_W, FRAME_H, DPI);
            if (id < 0) { fail("가상 디스플레이를 만들지 못했습니다"); return; }
            displayId = id;
            if (destroyed) { h.releaseDisplay(id); return; }
            String err = h.launch(component, id);
            if (err != null) { fail("실행 실패: " + err); return; }
            Log.i(TAG, component + " → 디스플레이 " + id);
            ui.postDelayed(watch, 4000);
        } catch (Throwable t) {
            Log.e(TAG, "디스플레이/실행 실패", t);
            fail("도우미 오류: " + t.getMessage());
        }
    }

    /**
     * 실행 중에 2D→3D 와 SBS 를 바꾼다. 앱은 그대로 두고 디스플레이의 출력면만 갈아 끼운다.
     * 엔진과 디스플레이가 CNSDK 입력면을 동시에 쥘 수 없어서(한 면에 생산자는 하나), 항상
     * 먼저 떼고 나서 붙인다.
     */
    private void toggleMode() {
        if (switching || out == null || displayId < 0) return;
        switching = true;
        final boolean toSbs = !sbs;
        modeButton.setEnabled(false);
        pipeline.execute(new Runnable() { @Override public void run() {
            boolean ok;
            if (toSbs) {
                dropMono();
                attach(out);
                ok = true;
            } else {
                IHelper h = Helper.get();
                if (h != null) { try { h.setSurface(displayId, null); } catch (Throwable ignored) { } }
                ok = buildMono();
                if (!ok) attach(out);   // 엔진이 안 되면 SBS 로 남는다
            }
            final boolean done = ok;
            ui.post(new Runnable() { @Override public void run() {
                if (done) {
                    sbs = toSbs;
                    App.save(ViewActivity.this, pkg, sbs, gain);
                    Log.i(TAG, "모드 전환 → " + (sbs ? "SBS" : "2D→3D"));
                } else {
                    Toast.makeText(ViewActivity.this, "2D→3D 엔진을 만들지 못했습니다", Toast.LENGTH_SHORT).show();
                }
                switching = false;
                modeButton.setEnabled(true);
                showMode();
                scheduleHideToolbar();
            }});
        }});
    }

    /** 앱이 끝나 디스플레이가 비면 이 화면도 닫는다. 두 번 연속 비어 있어야 닫는다. */
    private final Runnable watch = new Runnable() {
        private boolean sawTask, emptyOnce;

        @Override public void run() {
            if (destroyed) return;
            IHelper h = Helper.get();
            int n = -1;
            try { if (h != null) n = h.taskCount(displayId); } catch (Throwable ignored) { }
            if (n > 0) {
                sawTask = true;
                emptyOnce = false;
            } else if (n == 0) {
                if (emptyOnce) {
                    if (!sawTask) Toast.makeText(ViewActivity.this,
                            "이 앱은 3D 화면에서 실행되지 않았습니다", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                emptyOnce = true;
            }
            ui.postDelayed(this, 2000);
        }
    };

    private void fail(final String msg) {
        Log.e(TAG, msg);
        ui.post(new Runnable() { @Override public void run() {
            Toast.makeText(ViewActivity.this, msg, Toast.LENGTH_LONG).show();
            finish();
        }});
    }

    // --- 입력 ---

    /**
     * 화면 터치를 가상 디스플레이 좌표로 바꿔 도우미에게 보낸다.
     *
     * 화면(2560x1600)과 디스플레이(1920x1200)는 같은 16:10 이고 FIT_CENTER 로 꽉 차므로
     * 2D→3D 에선 배율 하나로 맞는다. SBS 에선 보이는 그림이 디스플레이 왼쪽 절반(왼눈)을
     * 가로로 두 배 늘린 것이므로, 가로만 절반으로 줄여 왼눈 좌표로 보낸다.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touchToToolbar = hits(toolbar, ev);
            menuGesture = false;
            pullDown = ev.getY() < dp(PULL_ZONE_DP);
            pullStartY = ev.getY();
        }
        if (touchToToolbar) {
            scheduleHideToolbar();
            return super.dispatchTouchEvent(ev);
        }
        IHelper h = Helper.get();
        View v = leiaView;
        if (h == null || displayId < 0 || v.getWidth() == 0 || v.getHeight() == 0) return true;
        // 막대를 여는 두 가지: 화면 위쪽에서 한 손가락으로 아래로 끌어내리기, 세 손가락 터치.
        // 시스템 바를 쓸어 꺼내는 방법은 앱이 포커스를 가져가면 안 통한다(실기: DroidDeck 이 가상
        // 디스플레이에서 포커스를 쥐자 이 화면에 알림이 안 옴). 그래서 터치를 직접 본다.
        // 앱에는 이미 보낸 터치를 취소로 끝내 주고, 나머지는 손가락이 다 떨어질 때까지 삼킨다.
        if (action == MotionEvent.ACTION_POINTER_DOWN) pullDown = false;
        boolean pulled = pullDown && action == MotionEvent.ACTION_MOVE
                && ev.getY() - pullStartY > dp(PULL_DISTANCE_DP);
        boolean threeFingers = action == MotionEvent.ACTION_POINTER_DOWN && ev.getPointerCount() >= 3;
        if (!menuGesture && (pulled || threeFingers)) {
            menuGesture = true;
            pullDown = false;
            MotionEvent cancel = MotionEvent.obtain(ev);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            inject(h, cancel, v);
            if (threeFingers && toolbar.getVisibility() == View.VISIBLE) ui.post(hideToolbar);
            else showToolbar();
            return true;
        }
        if (menuGesture) return true;
        inject(h, MotionEvent.obtain(ev), v);
        return true;
    }

    /** 터치를 디스플레이 좌표로 바꿔 보낸다. e 는 여기서 recycle 한다. */
    private void inject(IHelper h, MotionEvent e, View v) {
        float sx = (sbs ? DISPLAY_W / 2f : DISPLAY_W) / v.getWidth();
        float sy = (float) FRAME_H / v.getHeight();
        Matrix m = new Matrix();
        m.setScale(sx, sy);
        e.transform(m);
        try {
            h.injectMotion(e, displayId);
        } catch (Throwable t) {
            Log.e(TAG, "터치 전달 실패", t);
        } finally {
            e.recycle();
        }
    }

    private static boolean hits(View v, MotionEvent ev) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        Rect r = new Rect();
        return v.getGlobalVisibleRect(r) && r.contains((int) ev.getRawX(), (int) ev.getRawY());
    }

    /** 뒤로 제스처·버튼은 앱에 넘긴다. 나가기는 도구 막대에서. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (ev.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (ev.getAction() == KeyEvent.ACTION_UP && !ev.isCanceled()) {
                IHelper h = Helper.get();
                if (h != null && displayId >= 0) {
                    try { h.injectKey(KeyEvent.KEYCODE_BACK, displayId); } catch (Throwable ignored) { }
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    // --- 도구 막대 ---

    private View buildToolbar() {
        toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(0xCC202020);
        int pad = dp(8);
        toolbar.setPadding(pad, pad, pad, pad);
        toolbar.setVisibility(View.GONE);

        Button exit = new Button(this);
        exit.setText("나가기");
        exit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        toolbar.addView(exit);

        modeButton = new Button(this);
        modeButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleMode(); }
        });
        toolbar.addView(modeButton);

        minus = new Button(this);
        minus.setText("깊이 −");
        minus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setGain(gain - 0.05f); }
        });
        gainText = new TextView(this);
        gainText.setTextColor(Color.WHITE);
        gainText.setPadding(pad * 2, 0, pad * 2, 0);
        plus = new Button(this);
        plus.setText("깊이 +");
        plus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setGain(gain + 0.05f); }
        });
        toolbar.addView(minus);
        toolbar.addView(gainText);
        toolbar.addView(plus);

        trackText = new TextView(this);
        trackText.setTextColor(Color.WHITE);
        trackText.setPadding(pad * 2, 0, pad, 0);
        trackText.setText("눈 추적: 확인 중");
        toolbar.addView(trackText);

        showGain();
        showMode();
        return toolbar;
    }

    /** 모드 버튼은 지금 모드를 보여 준다. 깊이는 2D→3D 에서만 의미가 있다. */
    private void showMode() {
        modeButton.setText(sbs ? "모드: SBS" : "모드: 2D→3D");
        int depth = sbs ? View.GONE : View.VISIBLE;
        minus.setVisibility(depth);
        gainText.setVisibility(depth);
        plus.setVisibility(depth);
    }

    private void showTracking(String s) {
        if (trackText != null) trackText.setText("눈 추적: " + s);
    }

    private void setGain(float g) {
        gain = Math.max(0.1f, Math.min(1.0f, Math.round(g * 20f) / 20f));
        App.saveGain(this, pkg, gain);
        Object e = monoEngine;
        if (e != null) {
            try { ((MonoVideoSurfaceRenderer) e).setGainMultiplier(gain); } catch (Throwable ignored) { }
        }
        showGain();
    }

    private void showGain() {
        if (gainText != null) gainText.setText(String.format("%.2f", gain));
    }

    private void showToolbar() {
        toolbar.setVisibility(View.VISIBLE);
        scheduleHideToolbar();
    }

    private final Runnable hideToolbar = new Runnable() {
        @Override public void run() {
            toolbar.setVisibility(View.GONE);
            goFullscreen();
        }
    };

    private void scheduleHideToolbar() {
        ui.removeCallbacks(hideToolbar);
        ui.postDelayed(hideToolbar, 4000);
    }

    /**
     * 상태바·내비바를 숨긴다. 그대로 두면 남은 영역에 FIT_CENTER 로 맞춰져 좌우에 검은 띠가
     * 생긴다. STICKY 가 아닌 몰입 모드라, 가장자리를 쓸면 바가 나오고 그 변화를 알 수 있다.
     */
    private void goFullscreen() {
        Window w = getWindow();
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        w.setAttributes(lp);
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && toolbar != null && toolbar.getVisibility() != View.VISIBLE) goFullscreen();
    }

    // --- CNSDK (데모와 같다) ---

    private void initSdk() {
        try {
            LeiaSDK.InitArgs args = new LeiaSDK.InitArgs();
            args.platform = new PlatformInitArgs();
            args.platform.app = getApplication();
            args.platform.activity = this;    // 빠지면 createSDK 가 null 을 준다
            args.platform.context = this;
            args.enableFaceTracking = true;
            args.delegate = new LeiaSDK.Delegate() {
                @Override public void didInitialize(LeiaSDK s) {
                    // 이전 세션이 백라이트를 3D 로 남겼을 수 있다 — 한 번 내려서 초기화한다.
                    try { s.enableBacklight(false); } catch (Throwable ignored) { }
                    // 얼굴이 잠깐 안 보일 때 2D 로 떨어지는 것을 막는다.
                    try { s.enableNoFaceMode(false); } catch (Throwable ignored) { }
                    sdkReady = true;
                    ui.post(new Runnable() { @Override public void run() { applyActive(); } });
                }
                @Override public void onFaceTrackingStarted(LeiaSDK s) { Log.i(TAG, "얼굴추적 시작"); }
                @Override public void onFaceTrackingStopped(LeiaSDK s) { Log.i(TAG, "얼굴추적 정지"); }
                @Override public void onFaceTrackingFatalError(LeiaSDK s) { Log.e(TAG, "얼굴추적 오류"); }
            };
            sdk = LeiaSDK.createSDK(args);
            Log.i(TAG, "createSDK -> " + (sdk == null ? "null" : "ok"));
        } catch (Throwable t) {
            Log.e(TAG, "createSDK 예외", t);
        }
    }

    private void applyActive() {
        if (sdk == null || !sdkReady) return;
        lendCameraToTracker(wantActive);
        try {
            sdk.enableFaceTracking(wantActive);
            sdk.enableBacklight(wantActive);
        } catch (Throwable t) {
            Log.e(TAG, "백라이트 전환 실패", t);
        }
        if (wantActive) {
            ui.removeCallbacks(trackerWatch);
            trackerAttempt = 0;
            faceSamples = 0;
            viewerAnnounced = false;
            trackerReasked = false;
            ui.postDelayed(trackerWatch, TRACKER_RETRY_MS);
        } else {
            ui.removeCallbacks(trackerWatch);
        }
    }

    /** 추적 서비스에 이 앱의 카메라 권리를 넘겨주는 바인딩을 쥐거나 놓는다. 바인더 자체는 쓰지 않는다. */
    private void lendCameraToTracker(boolean hold) {
        if (hold) {
            if (trackerLease != null) return;
            android.content.ServiceConnection lease = new android.content.ServiceConnection() {
                @Override public void onServiceConnected(android.content.ComponentName n, android.os.IBinder b) {
                    Log.i(TAG, "추적 서비스에 카메라 권리 넘겨줌(바인딩 유지)");
                }
                @Override public void onServiceDisconnected(android.content.ComponentName n) {
                    Log.w(TAG, "3D 중에 추적 서비스가 사라짐");
                }
            };
            try {
                android.content.Intent i = new android.content.Intent();
                i.setClassName(TRACKER_PACKAGE, TRACKER_SERVICE);
                int flags = android.content.Context.BIND_AUTO_CREATE | android.content.Context.BIND_IMPORTANT
                        | android.content.Context.BIND_INCLUDE_CAPABILITIES;
                if (bindService(i, lease, flags)) trackerLease = lease;
                else { Log.w(TAG, "추적 서비스 바인딩 실패"); try { unbindService(lease); } catch (Throwable ignored) { } }
            } catch (Throwable t) {
                Log.w(TAG, "추적 서비스 바인딩 실패: " + t);
            }
        } else if (trackerLease != null) {
            try { unbindService(trackerLease); } catch (Throwable ignored) { }
            trackerLease = null;
        }
    }

    private final Runnable trackerWatch = new Runnable() {
        @Override public void run() {
            if (destroyed || sdk == null || !sdkReady || !wantActive) return;
            try {
                if (!sdk.isFaceTrackingStarted()) {
                    trackerAttempt++;
                    // enable 은 준비, start 는 실행. 기본 시점으로 위빙된 화면은 추적기를 아예 안 켠
                    // 것과 똑같이 보이므로 둘 다 명시적으로 부른다.
                    sdk.enableFaceTracking(true);
                    sdk.startFaceTracking(true);
                    boolean started = sdk.isFaceTrackingStarted();
                    Log.i(TAG, "얼굴추적기 " + (started ? "시작됨" : "아직 안 돎") + " (시도 " + trackerAttempt + "/" + TRACKER_ATTEMPTS + ")");
                    if (!started) {
                        if (trackerAttempt < TRACKER_ATTEMPTS) {
                            showTracking("시작 중");
                            ui.postDelayed(this, TRACKER_RETRY_MS);
                        } else {
                            Log.w(TAG, "얼굴추적기가 시작되지 않음 — 기본 시점으로 위빙");
                            showTracking("사용 불가");
                            Toast.makeText(ViewActivity.this, "눈 추적 사용 불가", Toast.LENGTH_SHORT).show();
                        }
                        return;
                    }
                }
                com.leia.core.Vector3 face = sdk.getPrimaryFace();
                boolean seen = face != null && (face.x != 0f || face.y != 0f || face.z != 0f);
                faceSamples++;
                showTracking(seen ? "동작 중" : "얼굴 안 보임");
                // 자리 잡은 뒤에는 상태가 바뀌거나 열 번에 한 번만 남긴다.
                if (faceSamples < FACE_SAMPLES_FAST || seen != lastSeen || faceSamples % 10 == 0)
                    Log.i(TAG, "시청자 " + (seen ? face.x + "," + face.y + "," + face.z : "안 보임")
                            + " (표본 " + faceSamples + ", " + (sbs ? "SBS" : "2D→3D") + ")");
                lastSeen = seen;
                if (seen && !viewerAnnounced) {
                    viewerAnnounced = true;
                    Toast.makeText(ViewActivity.this, "눈 추적 동작 중", Toast.LENGTH_SHORT).show();
                }
                // 돌고는 있는데 한참 아무도 안 보이면 보통 카메라가 안 열린 것(서비스는 거부되면 재시도 안 함).
                // 한 번 더 요청하면 서비스의 포그라운드 세션이 위 바인딩 아래서 새로 시작된다.
                if (!seen && !viewerAnnounced && !trackerReasked && faceSamples >= FACE_SAMPLES_BEFORE_REASK) {
                    trackerReasked = true;
                    Log.w(TAG, faceSamples + "초 동안 시청자 없음 — 카메라 거부 가능성, 추적기 재요청");
                    sdk.enableFaceTracking(false);
                    sdk.enableFaceTracking(true);
                }
                // 처음엔 1초마다, 자리 잡은 뒤엔 3초마다 (도구 막대의 표시가 너무 늦지 않게).
                ui.postDelayed(this, faceSamples < FACE_SAMPLES_FAST ? 1000 : 3000);
            } catch (Throwable t) {
                Log.w(TAG, "얼굴추적기 확인 실패: " + t);
            }
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        wantActive = true;
        if (leiaView != null) leiaView.onResume();
        if (sdk != null) { try { sdk.onResume(); } catch (Throwable ignored) { } }
        applyActive();
    }

    @Override
    protected void onPause() {
        super.onPause();
        wantActive = false;
        applyActive();
        if (sdk != null) { try { sdk.onPause(); } catch (Throwable ignored) { } }
        if (leiaView != null) leiaView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        lendCameraToTracker(false);
        // 디스플레이를 먼저 없앤다 — 앱이 우리 입력면에 그리는 것을 멈추고, 앱 화면도 닫힌다.
        IHelper h = Helper.get();
        if (h != null && displayId >= 0) {
            try { h.releaseDisplay(displayId); } catch (Throwable ignored) { }
        }
        displayId = -1;
        pipeline.shutdownNow();
        if (monoEngine != null) {
            try { ((MonoVideoSurfaceRenderer) monoEngine).release(); } catch (Throwable ignored) { }
            monoEngine = null;
        }
        if (monoIn != null) { monoIn.release(); monoIn = null; }
        if (leiaView != null) {
            try { leiaView.releaseInputViewsAsset(); } catch (Throwable ignored) { }
        }
        try { LeiaSDK.shutdownSDK(); } catch (Throwable ignored) { }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
