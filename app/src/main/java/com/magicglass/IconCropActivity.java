package com.magicglass;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.InputStream;

/**
 * 0.5.0-p189: 图标导入后的交互裁剪页。
 *
 * <p>导入照片 → 进入本页 → 拖动选区域、捏合/滑块调缩放 → 点“用这张”把取景
 * 烘焙成圆形 PNG 存入 media 目录 → 回传文件名给调用方（SettingsActivity），
 * 由它写入 icon.rules 并广播即时生效。</p>
 *
 * <p>本页只做「读用户选的图 + 裁剪 + 存文件」，不碰系统设置进程、不改渲染。</p>
 */
public final class IconCropActivity extends Activity {

    public static final String EXTRA_SRC_URI = "src_uri";
    public static final String EXTRA_RESULT_MEDIA = "media";

    private CropView cropView;
    private SeekBar zoomBar;
    private Uri srcUri;
    private int outSizePx;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            srcUri = getIntent() == null ? null : getIntent().getData();
        } catch (Throwable ignored) {
        }
        float d = getResources().getDisplayMetrics().density;
        outSizePx = Math.max(96, Math.round(96f * (d > 0 ? d : 1f)));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF2F2F7);
        int pad = Math.round(16 * d);
        root.setPadding(pad, Math.round(12 * d), pad, pad);

        TextView title = new TextView(this);
        title.setText("裁剪图标");
        title.setTextSize(18f);
        title.setTextColor(0xFF1C1C1E);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("拖动图片选择保留区域，双指或滑块缩放");
        hint.setTextSize(12f);
        hint.setTextColor(0xFF8E8E93);
        hint.setPadding(0, Math.round(4 * d), 0, Math.round(10 * d));
        root.addView(hint);

        cropView = new CropView(this);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(cropView, cLp);

        LinearLayout zoomRow = new LinearLayout(this);
        zoomRow.setOrientation(LinearLayout.HORIZONTAL);
        zoomRow.setGravity(Gravity.CENTER_VERTICAL);
        zoomRow.setPadding(0, Math.round(14 * d), 0, 0);
        TextView zLabel = new TextView(this);
        zLabel.setText("缩放");
        zLabel.setTextSize(12f);
        zLabel.setTextColor(0xFF8E8E93);
        zoomRow.addView(zLabel);
        zoomBar = new SeekBar(this);
        zoomBar.setMax(300);
        zoomBar.setProgress(0);
        LinearLayout.LayoutParams zLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        zLp.leftMargin = Math.round(10 * d);
        zoomRow.addView(zoomBar, zLp);
        root.addView(zoomRow);

        zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) cropView.setZoomFactor(1f + p / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, Math.round(14 * d), 0, 0);

        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button ok = new Button(this);
        ok.setText("用这张");
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                commit();
            }
        });
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        okLp.leftMargin = Math.round(10 * d);
        actions.addView(ok, okLp);
        root.addView(actions);

        setContentView(root);
        loadSource();
    }

    private void loadSource() {
        if (srcUri == null) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        Bitmap bmp = decodeCapped(srcUri, 1024);
        if (bmp == null) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        cropView.post(new Runnable() {
            @Override public void run() {
                cropView.setBitmap(bmp);
                cropView.resetTransform();
            }
        });
        pending = bmp;
    }

    private Bitmap pending;

    private Bitmap decodeCapped(Uri uri, int capPx) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream in = getContentResolver().openInputStream(uri);
            if (in != null) {
                try {
                    BitmapFactory.decodeStream(in, null, bounds);
                } finally {
                    in.close();
                }
            }
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            if (longest <= 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, longest / capPx);
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            InputStream in2 = getContentResolver().openInputStream(uri);
            if (in2 == null) return null;
            try {
                return BitmapFactory.decodeStream(in2, null, opts);
            } finally {
                in2.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private void commit() {
        try {
            Bitmap out = cropView.exportRound(outSizePx);
            if (out == null) {
                return;
            }
            String name = ConfigStore.importBitmap(this, out, "icon");
            out.recycle();
            if (name == null) {
                return;
            }
            Intent r = new Intent();
            r.putExtra(EXTRA_RESULT_MEDIA, name);
            setResult(RESULT_OK, r);
            finish();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        try {
            if (pending != null && !pending.isRecycled()) pending.recycle();
        } catch (Throwable ignored) {
        }
        pending = null;
        super.onDestroy();
    }
}
