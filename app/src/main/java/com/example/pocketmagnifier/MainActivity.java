package com.example.pocketmagnifier;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Collections;

public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final int CAMERA_PERMISSION = 10;
    private TextureView preview;
    private TextView status;
    private TextView torchButton;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private CaptureRequest.Builder request;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private String cameraId;
    private Rect sensor;
    private float maximumZoom = 1f;
    private float zoom = 1f;
    private int maximumTorch = 1;
    private int torchLevel = 0;
    private float downX;
    private float downY;
    private float downZoom;
    private int downTorch;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        buildInterface();
    }

    private void buildInterface() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        preview = new TextureView(this);
        preview.setSurfaceTextureListener(this);
        preview.setOnTouchListener(this::onGesture);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));

        status = label("1.0×  •  Torch off", 18);
        FrameLayout.LayoutParams statusLayout = new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        statusLayout.topMargin = dp(44);
        root.addView(status, statusLayout);

        TextView help = label("↕  MAGNIFY       ↔  LIGHT", 15);
        FrameLayout.LayoutParams helpLayout = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        helpLayout.bottomMargin = dp(36);
        root.addView(help, helpLayout);

        torchButton = label("☀", 30);
        torchButton.setGravity(Gravity.CENTER);
        torchButton.setContentDescription("Toggle torch");
        torchButton.setOnClickListener(v -> {
            torchLevel = torchLevel == 0 ? Math.max(1, maximumTorch / 2) : 0;
            updateCamera();
        });
        FrameLayout.LayoutParams buttonLayout = new FrameLayout.LayoutParams(dp(64), dp(64),
                Gravity.BOTTOM | Gravity.RIGHT);
        buttonLayout.setMargins(0, 0, dp(24), dp(24));
        root.addView(torchButton, buttonLayout);
        setContentView(root);
    }

    private TextView label(String text, float size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(Color.WHITE);
        view.setBackgroundColor(0x99000000);
        view.setPadding(dp(14), dp(9), dp(14), dp(9));
        return view;
    }

    private boolean onGesture(View view, MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX(); downY = event.getY();
            downZoom = zoom; downTorch = torchLevel;
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float height = Math.max(1, view.getHeight());
            float width = Math.max(1, view.getWidth());
            zoom = clamp(downZoom + (downY - event.getY()) / height * maximumZoom * 1.5f,
                    1f, maximumZoom);
            torchLevel = Math.round(clamp(downTorch + (event.getX() - downX) / width
                    * maximumTorch * 1.5f, 0, maximumTorch));
            updateCamera();
            return true;
        }
        return event.getActionMasked() == MotionEvent.ACTION_UP;
    }

    @Override protected void onResume() {
        super.onResume();
        cameraThread = new HandlerThread("MagnifierCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        if (preview.isAvailable()) openCamera();
    }

    @Override protected void onPause() {
        closeCamera();
        if (cameraThread != null) cameraThread.quitSafely();
        cameraThread = null;
        super.onPause();
    }

    private void openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            return;
        }
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics info = manager.getCameraCharacteristics(id);
                Integer facing = info.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    sensor = info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                    Float max = info.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                    maximumZoom = max == null ? 1f : Math.min(max, 10f);
                    Integer strength = info.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
                    maximumTorch = strength == null ? 1 : Math.max(1, strength);
                    manager.openCamera(id, cameraCallback, cameraHandler);
                    return;
                }
            }
            showMessage("No rear camera found");
        } catch (CameraAccessException | SecurityException error) {
            showMessage("Camera unavailable");
        }
    }

    private final CameraDevice.StateCallback cameraCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice device) { camera = device; startPreview(); }
        @Override public void onDisconnected(CameraDevice device) { device.close(); camera = null; }
        @Override public void onError(CameraDevice device, int error) {
            device.close(); camera = null; showMessage("Camera error " + error);
        }
    };

    private void startPreview() {
        try {
            SurfaceTexture texture = preview.getSurfaceTexture();
            if (texture == null || camera == null) return;
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            StreamConfigurationMap map = manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = chooseSize(map == null ? null : map.getOutputSizes(SurfaceTexture.class));
            texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
            configureTransform(size);
            Surface surface = new Surface(texture);
            request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(surface);
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            camera.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession value) {
                            session = value; updateCamera();
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession value) {
                            showMessage("Preview unavailable");
                        }
                    }, cameraHandler);
        } catch (CameraAccessException error) { showMessage("Could not start preview"); }
    }

    private Size chooseSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(1920, 1080);
        Size best = sizes[0];
        for (Size size : sizes) {
            long pixels = (long) size.getWidth() * size.getHeight();
            long bestPixels = (long) best.getWidth() * best.getHeight();
            if (pixels <= 1920L * 1080 && pixels > bestPixels) best = size;
        }
        return best;
    }

    private void configureTransform(Size buffer) {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees = rotation == Surface.ROTATION_90 ? 90 : rotation == Surface.ROTATION_270 ? 270
                : rotation == Surface.ROTATION_180 ? 180 : 0;
        Matrix matrix = new Matrix();
        float sx = (float) preview.getWidth() / buffer.getHeight();
        float sy = (float) preview.getHeight() / buffer.getWidth();
        float scale = Math.max(sx, sy);
        matrix.setScale(scale, scale, preview.getWidth() / 2f, preview.getHeight() / 2f);
        matrix.postRotate(degrees - 90, preview.getWidth() / 2f, preview.getHeight() / 2f);
        preview.setTransform(matrix);
    }

    private void updateCamera() {
        if (request == null || session == null || sensor == null) return;
        int cropWidth = Math.round(sensor.width() / zoom);
        int cropHeight = Math.round(sensor.height() / zoom);
        int left = sensor.centerX() - cropWidth / 2;
        int top = sensor.centerY() - cropHeight / 2;
        request.set(CaptureRequest.SCALER_CROP_REGION,
                new Rect(left, top, left + cropWidth, top + cropHeight));
        request.set(CaptureRequest.FLASH_MODE,
                torchLevel > 0 ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        // Android 15/API 35 can vary torch strength as part of the active camera request.
        if (android.os.Build.VERSION.SDK_INT >= 35 && maximumTorch > 1 && torchLevel > 0) {
            request.set(CaptureRequest.FLASH_STRENGTH_LEVEL, torchLevel);
        }
        try { session.setRepeatingRequest(request.build(), null, cameraHandler); }
        catch (CameraAccessException | IllegalArgumentException ignored) {
            // Some camera HALs report strength levels but permit only a binary torch in preview.
        }
        runOnUiThread(() -> {
            int percent = maximumTorch == 0 ? 0 : Math.round(torchLevel * 100f / maximumTorch);
            status.setText(String.format("%.1f×  •  Torch %s", zoom,
                    torchLevel == 0 ? "off" : percent + "%"));
            torchButton.setTextColor(torchLevel == 0 ? Color.WHITE : 0xffffd54f);
        });
    }

    private void closeCamera() {
        if (session != null) { session.close(); session = null; }
        if (camera != null) { camera.close(); camera = null; }
        request = null;
    }

    private void showMessage(String message) { runOnUiThread(() -> status.setText(message)); }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CAMERA_PERMISSION && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) openCamera();
        else showMessage("Camera permission is required");
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) { openCamera(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        if (camera != null) { closeCamera(); openCamera(); }
    }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { closeCamera(); return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }
}
