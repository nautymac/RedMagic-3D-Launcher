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
 * [2D 모드] 앱 ──▶ 가상 디스플레이 1920x1200 = 엔진 입력면 ──▶ Leia 2D→3D 엔진 ──▶ CNSDK 입력면 ──▶ 위빙
 * [SBS 모드] 앱 ──▶ 가상 디스플레이 3840x1200 = CNSDK 입력면 ─────────────────────────────▶ 위빙
 * 화면 터치 ──▶ 디스플레이 좌표로 변환 ──▶ 도우미 ──▶ injectInputEvent(displayId)
 * </pre>
 * 위빙·엔진 연결은 DepthFlix 데모(VdSpikeActivity)를 그대로 옮긴 것이다.
 *
 * 뒤로 제스처는 앱에 KEYCODE_BACK 으로 넘긴다. 화면 가장자리를 쓸어 시스템 바를 꺼내면
 * 위에 도구 막대(나가기·깊이)가 잠깐 나온다. 이 화면을 나가면 디스플레이를 없애고, 그 위의
 * 앱 화면도 함께 닫힌다. 앱이 스스로 끝나 디스플레이가 비면 이 화면도 닫는다.
 */
public class ViewActivity extends Activity {

    static final String EXTRA_COMPONENT = "component";
    static final String EXTRA_LABEL = "label";

    private static final String TAG = "L3D";
    private static final int FRAME_W = 3840;   // 두 눈. 눈당 1920x1200 = 패널과 같은 16:10
    private static final int FRAME_H = 1200;
    private static final int DPI = 240;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private String component, pkg;
    private boolean sbs;
    private float gain;

    private LeiaSDK sdk;
    private InterlacedSurfaceView leiaView;
    private Surface out;
    private volatile boolean sdkReady = false;
    private boolean wantActive = false;

    private volatile Object monoEngine;
    private Surface monoIn;

    private volatile int displayId = -1;
    private volatile boolean destroyed;

    private LinearLayout toolbar;
    private TextView gainText;
    private boolean touchToToolbar;

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
                if (sbs) startDisplay(out, FRAME_W, FRAME_H);
                else startMono();
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
     * 엔진 입력면을 눈 하나 크기(1920x1200)로 받아 그것을 가상 디스플레이 화면으로 쓴다.
     * 엔진은 시차를 만들어 2타일 SBS 로 CNSDK 입력면에 그린다. 생성이 1초쯤 블록한다.
     */
    private void startMono() {
        new Thread(new Runnable() { @Override public void run() {
            LeiaMediaSDK media = LeiaMediaSDK.getInstance(ViewActivity.this);
            android.content.Context svc = media == null ? null : LeiaMediaSDK.serviceContext(ViewActivity.this);
            if (svc == null) { fail("Leia 2D→3D 엔진을 쓸 수 없습니다"); return; }
            monoEngine = media.createMonoVideoSurfaceRenderer(svc, out,
                    new MonoVideoSurfaceRenderer.SurfaceTextureCallback() {
                        @Override public void onSurfaceTextureReady(SurfaceTexture st) {
                            st.setDefaultBufferSize(FRAME_W / 2, FRAME_H);
                            monoIn = new Surface(st);
                            startDisplay(monoIn, FRAME_W / 2, FRAME_H);
                        }
                    });
            if (monoEngine == null) { fail("2D→3D 엔진 생성 실패"); return; }
            try { ((MonoVideoSurfaceRenderer) monoEngine).setGainMultiplier(gain); } catch (Throwable ignored) { }
            Log.i(TAG, "2D→3D 엔진 생성 gain " + gain);
        }}, "l3d-mono").start();
    }

    // --- 가상 디스플레이 + 앱 실행 (도우미가 한다) ---

    private void startDisplay(final Surface s, final int w, final int h) {
        new Thread(new Runnable() { @Override public void run() {
            IHelper h2 = Helper.get();
            if (h2 == null) { fail("도우미 연결이 끊겼습니다"); return; }
            try {
                int id = h2.createDisplay(s, w, h, DPI);
                if (id < 0) { fail("가상 디스플레이를 만들지 못했습니다"); return; }
                displayId = id;
                if (destroyed) { h2.releaseDisplay(id); return; }
                String err = h2.launch(component, id);
                if (err != null) { fail("실행 실패: " + err); return; }
                Log.i(TAG, component + " → 디스플레이 " + id);
                ui.postDelayed(watch, 4000);
            } catch (Throwable t) {
                Log.e(TAG, "디스플레이/실행 실패", t);
                fail("도우미 오류: " + t.getMessage());
            }
        }}, "l3d-display").start();
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
     * 화면(2560x1600)과 디스플레이 한 눈(1920x1200)은 같은 16:10 이고 FIT_CENTER 로 꽉 차므로
     * 배율 하나로 맞는다. SBS 모드면 디스플레이는 두 눈 폭이지만 앱은 왼쪽 절반에 대응하므로
     * 같은 배율로 왼쪽 눈 좌표가 된다.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touchToToolbar = false;
            if (toolbar.getVisibility() == View.VISIBLE) {
                Rect r = new Rect();
                toolbar.getGlobalVisibleRect(r);
                touchToToolbar = r.contains((int) ev.getRawX(), (int) ev.getRawY());
            }
        }
        if (touchToToolbar) {
            scheduleHideToolbar();
            return super.dispatchTouchEvent(ev);
        }
        IHelper h = Helper.get();
        View v = leiaView;
        if (h == null || displayId < 0 || v.getWidth() == 0) return true;
        float s = (FRAME_W / 2f) / v.getWidth();
        MotionEvent e = MotionEvent.obtain(ev);
        Matrix m = new Matrix();
        m.setScale(s, s);
        e.transform(m);
        try {
            h.injectMotion(e, displayId);
        } catch (Throwable t) {
            Log.e(TAG, "터치 전달 실패", t);
        } finally {
            e.recycle();
        }
        return true;
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

        if (!sbs) {
            Button minus = new Button(this);
            minus.setText("깊이 −");
            minus.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setGain(gain - 0.05f); }
            });
            gainText = new TextView(this);
            gainText.setTextColor(Color.WHITE);
            gainText.setPadding(pad * 2, 0, pad * 2, 0);
            Button plus = new Button(this);
            plus.setText("깊이 +");
            plus.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setGain(gain + 0.05f); }
            });
            toolbar.addView(minus);
            toolbar.addView(gainText);
            toolbar.addView(plus);
            showGain();
        }
        return toolbar;
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
        try {
            sdk.enableFaceTracking(wantActive);
            sdk.enableBacklight(wantActive);
        } catch (Throwable t) {
            Log.e(TAG, "백라이트 전환 실패", t);
        }
    }

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
        // 디스플레이를 먼저 없앤다 — 앱이 우리 입력면에 그리는 것을 멈추고, 앱 화면도 닫힌다.
        IHelper h = Helper.get();
        if (h != null && displayId >= 0) {
            try { h.releaseDisplay(displayId); } catch (Throwable ignored) { }
        }
        displayId = -1;
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
