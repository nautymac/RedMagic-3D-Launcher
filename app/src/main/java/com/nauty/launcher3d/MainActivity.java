package com.nauty.launcher3d;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 앱 선택 화면. 고르면 3D 화면(ViewActivity)에서 그 앱을 띄운다.
 * 길게 누르면 앱별 설정(2D 변환/SBS, 깊이)을 바꾼다.
 */
public class MainActivity extends Activity implements Helper.Listener {

    private TextView status;
    private Button action;
    private GridView grid;
    private final List<Entry> apps = new ArrayList<>();

    private static final class Entry {
        String label, pkg, component;
        Drawable icon;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, 0);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        bar.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        action = new Button(this);
        bar.addView(action);
        root.addView(bar);

        grid = new GridView(this);
        grid.setColumnWidth(dp(112));
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setVerticalSpacing(dp(8));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(0, pad, 0, pad);
        grid.setClipToPadding(false);
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) { start(apps.get(pos)); }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                settings(apps.get(pos));
                return true;
            }
        });

        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Helper.addListener(this);
        Helper.connect();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Helper.removeListener(this);
    }

    @Override
    public void onHelperState(Helper.State s) {
        action.setVisibility(View.VISIBLE);
        action.setOnClickListener(null);
        switch (s) {
            case NO_SHIZUKU_APP:
                status.setText("Shizuku 앱이 없습니다. 먼저 설치하세요.");
                action.setVisibility(View.GONE);
                break;
            case NOT_RUNNING:
                status.setText("Shizuku 가 꺼져 있습니다. Shizuku 앱에서 \"시작\" 을 누르세요 (무선 디버깅 필요).");
                action.setText("Shizuku 열기");
                action.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { Helper.openShizuku(MainActivity.this); }
                });
                break;
            case NO_PERMISSION:
                status.setText("Shizuku 권한이 필요합니다.");
                action.setText("권한 요청");
                action.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { Helper.connect(); }
                });
                break;
            case CONNECTING:
                status.setText("도우미 연결 중…");
                action.setVisibility(View.GONE);
                break;
            case READY:
                status.setText("준비됨 — 앱을 누르면 3D 로 실행합니다. 길게 누르면 설정.");
                action.setVisibility(View.GONE);
                break;
        }
    }

    private void start(Entry e) {
        if (Helper.get() == null) {
            Toast.makeText(this, "도우미가 연결되지 않았습니다", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, ViewActivity.class)
                .putExtra(ViewActivity.EXTRA_COMPONENT, e.component)
                .putExtra(ViewActivity.EXTRA_LABEL, e.label));
    }

    private void settings(final Entry e) {
        int pad = dp(20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        final RadioGroup mode = new RadioGroup(this);
        final RadioButton mono = new RadioButton(this);
        mono.setText("2D 앱 → 3D 변환 (Leia 엔진)");
        mono.setId(View.generateViewId());
        final RadioButton sbs = new RadioButton(this);
        sbs.setText("SBS 로 그리는 앱 (변환 없이 좌우 그대로)");
        sbs.setId(View.generateViewId());
        mode.addView(mono);
        mode.addView(sbs);
        mode.check(App.isSbs(this, e.pkg) ? sbs.getId() : mono.getId());
        box.addView(mode);

        final TextView depthLabel = new TextView(this);
        depthLabel.setPadding(0, pad, 0, 0);
        box.addView(depthLabel);
        final SeekBar depth = new SeekBar(this);
        depth.setMax(18);   // 0.10 ~ 1.00, 0.05 단위
        depth.setProgress(Math.round((App.gain(this, e.pkg) - 0.1f) / 0.05f));
        box.addView(depth);
        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                depthLabel.setText(String.format("깊이 (2D 변환): %.2f", 0.1f + p * 0.05f));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        };
        depth.setOnSeekBarChangeListener(l);
        l.onProgressChanged(depth, depth.getProgress(), false);

        new AlertDialog.Builder(this)
                .setTitle(e.label)
                .setView(box)
                .setPositiveButton("저장", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        App.save(MainActivity.this, e.pkg, mode.getCheckedRadioButtonId() == sbs.getId(),
                                0.1f + depth.getProgress() * 0.05f);
                        ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
                    }
                })
                .setNeutralButton("3D 로 실행", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        App.save(MainActivity.this, e.pkg, mode.getCheckedRadioButtonId() == sbs.getId(),
                                0.1f + depth.getProgress() * 0.05f);
                        start(e);
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void loadApps() {
        new Thread(new Runnable() { @Override public void run() {
            PackageManager pm = getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            final List<Entry> list = new ArrayList<>();
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                if (ri.activityInfo.packageName.equals(getPackageName())) continue;
                Entry e = new Entry();
                e.pkg = ri.activityInfo.packageName;
                e.component = e.pkg + "/" + ri.activityInfo.name;
                e.label = String.valueOf(ri.loadLabel(pm));
                e.icon = ri.loadIcon(pm);
                list.add(e);
            }
            final Collator col = Collator.getInstance();
            Collections.sort(list, new Comparator<Entry>() {
                @Override public int compare(Entry a, Entry b) { return col.compare(a.label, b.label); }
            });
            runOnUiThread(new Runnable() { @Override public void run() {
                apps.clear();
                apps.addAll(list);
                grid.setAdapter(new Adapter());
            }});
        }}, "load-apps").start();
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int i) { return apps.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View v, ViewGroup parent) {
            TextView t = (TextView) v;
            if (t == null) {
                t = new TextView(MainActivity.this);
                t.setGravity(Gravity.CENTER_HORIZONTAL);
                t.setMaxLines(2);
                t.setPadding(dp(4), dp(8), dp(4), dp(8));
                t.setCompoundDrawablePadding(dp(6));
            }
            Entry e = apps.get(i);
            Drawable d = e.icon;
            d.setBounds(0, 0, dp(56), dp(56));
            t.setCompoundDrawables(null, d, null, null);
            t.setText(App.isSbs(MainActivity.this, e.pkg) ? e.label + "\n(SBS)" : e.label);
            return t;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
