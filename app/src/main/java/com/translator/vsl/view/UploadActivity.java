//UploadActivity.java
package com.translator.vsl.view;

import android.Manifest;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.util.Pair;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import com.google.common.util.concurrent.ListenableFuture;
import com.translator.vsl.R;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class UploadActivity extends AppCompatActivity {

    private PreviewView viewFinder;
    private ImageButton captureButton, toggleFlashButton, flipCameraButton, clearVideosButton, videoStorageButton;
    private TextView recordingTime;
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
    private final MutableLiveData<Boolean> isRecording = new MutableLiveData<>(false);
    private boolean isFlashOn = false;
    private ExecutorService cameraExecutor;
    private Handler timerHandler = new Handler(Looper.getMainLooper());
    private long recordingStartTime;
    private static final int REQUEST_CAMERA_PERMISSION = 100;
    private final MutableLiveData<String> timerText = new MutableLiveData<>("00:00");
    private final MutableLiveData<Boolean> captureButtonState = new MutableLiveData<>(false);
    private SharedPreferences sharedPreferences;

    // Thêm MutableLiveData cho toast messages
    private final MutableLiveData<Pair<String, Boolean>> toastMessage = new MutableLiveData<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_upload);

        // Khởi tạo các view
        viewFinder = findViewById(R.id.viewFinder);
        captureButton = findViewById(R.id.capture);
        toggleFlashButton = findViewById(R.id.toggleFlash);
        flipCameraButton = findViewById(R.id.flipCamera);
        clearVideosButton = findViewById(R.id.clearVideosButton);
        videoStorageButton = findViewById(R.id.videoStorage);
        recordingTime = findViewById(R.id.recordingTime);

        // Khởi tạo SharedPreferences
        sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE);

        // Khởi tạo executor cho CameraX
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Kiểm tra quyền camera
        if (checkCameraPermission()) {
            startCamera();
        } else {
            requestCameraPermission();
        }

        // Xử lý sự kiện các nút
        setupButtonListeners();

        // Quan sát trạng thái timerText để cập nhật UI
        timerText.observe(this, time -> recordingTime.setText(time));
        captureButtonState.observe(this, state -> captureButton.setImageResource(
                state ? R.drawable.baseline_stop_circle_24 : R.drawable.baseline_fiber_manual_record_24));

        // Quan sát toastMessage để hiển thị toast
        toastMessage.observe(this, new Observer<Pair<String, Boolean>>() {
            @Override
            public void onChanged(Pair<String, Boolean> messagePair) {
                if (messagePair != null) {
                    String message = messagePair.first;
                    boolean isLong = messagePair.second;
                    int duration = isLong ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT;
                    Toast.makeText(UploadActivity.this, message, duration).show();
                }
            }
        });
    }

    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                REQUEST_CAMERA_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                toastMessage.setValue(new Pair<>("Camera permission denied", false));
                finish();
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();

                // Thiết lập Preview
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                // Thiết lập VideoCapture với Recorder
                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HD))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);

                // Chọn camera (mặc định là camera sau)
                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture);

            } catch (Exception e) {
                toastMessage.setValue(new Pair<>("Error starting camera: " + e.getMessage(), false));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void setupButtonListeners() {
        // Nút ghi hình
        captureButton.setOnClickListener(v -> {
            if (isRecording.getValue() != null && isRecording.getValue()) {
                stopRecording();
            } else {
                startRecording();
                recordingTime.setVisibility(View.VISIBLE);
            }
        });

        // Nút bật/tắt flash
        toggleFlashButton.setOnClickListener(v -> {
            if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
                isFlashOn = !isFlashOn;
                camera.getCameraControl().enableTorch(isFlashOn);
                toggleFlashButton.setImageResource(isFlashOn ?
                        R.drawable.baseline_flash_on_24 : R.drawable.baseline_flash_off_24);
            } else {
                toastMessage.setValue(new Pair<>("Flash not available", false));
            }
        });

        // Nút chuyển camera
        flipCameraButton.setOnClickListener(v -> {
            cameraSelector = cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA ?
                    CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
            startCamera(); // Khởi động lại camera với camera mới
        });

        // Nút xóa video
        clearVideosButton.setOnClickListener(v -> clearCameraXVideos());

        // Nút xem thư viện video
        videoStorageButton.setOnClickListener(v -> {
            toastMessage.setValue(new Pair<>("View video storage not implemented", false));
        });
    }

    private void startRecording() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toastMessage.setValue(new Pair<>("Missing audio recording permission", false));
            return;
        }

        String name = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video");

        MediaStoreOutputOptions options = new MediaStoreOutputOptions.Builder(
                getContentResolver(),
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(contentValues)
                .build();

        recordingStartTime = System.currentTimeMillis();
        timerHandler.post(timerRunnable);

        recording = videoCapture.getOutput().prepareRecording(this, options)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this), videoRecordEvent -> {
                    if (videoRecordEvent instanceof VideoRecordEvent.Start) {
                        isRecording.postValue(true);
                        captureButtonState.postValue(true);
                    } else if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                        VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) videoRecordEvent;
                        if (finalizeEvent.hasError()) {
                            toastMessage.setValue(new Pair<>("Recording error: " + finalizeEvent.getError(), false));
                            resetRecordingUI();
                        } else {
                            Uri videoUri = finalizeEvent.getOutputResults().getOutputUri();
                            toastMessage.setValue(new Pair<>("Video saved to gallery", false));
                            // Hiển thị dialog để nhập nhãn
                            showLabelInputDialog(videoUri.toString());
                        }
                        resetRecordingUI();
                    }
                });
    }

    private void stopRecording() {
        if (recording != null) {
            recording.stop();
            recording = null;
        }
    }

    private void resetRecordingUI() {
        isRecording.postValue(false);
        captureButtonState.postValue(false);
        timerHandler.removeCallbacks(timerRunnable);
        recordingTime.setText("00:00");
        recordingTime.setVisibility(View.GONE);
    }

    private final Runnable timerRunnable = () -> {
        long elapsedMillis = System.currentTimeMillis() - recordingStartTime;
        long seconds = (elapsedMillis / 1000) % 60;
        long minutes = (elapsedMillis / (1000 * 60)) % 60;
        timerText.postValue(String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds));
        timerHandler.postDelayed(this.timerRunnable, 100);
    };

    // Hiển thị dialog để nhập nhãn
    private void showLabelInputDialog(String videoUri) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Nhập nhãn cho video");

        // Tạo EditText cho dialog
        final EditText input = new EditText(this);
        input.setHint("Nhãn video");
        builder.setView(input);

        // Nút xác nhận
        builder.setPositiveButton("OK", (dialog, which) -> {
            String label = input.getText().toString().trim();
            if (label.isEmpty()) {
                toastMessage.setValue(new Pair<>("Nhãn không được để trống", false));
            } else {
                uploadVideo(videoUri, label);
            }
        });

        // Nút hủy
        builder.setNegativeButton("Hủy", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    // Hàm gọi API upload video
    private void uploadVideo(String videoUri, String label) {
        String ip = sharedPreferences.getString("api_ip", "14.224.194.242");
        String url = "http://" + ip + ":7000/upload";
        Log.d("UploadAPI", "Calling API at URL: " + url + " with Label: " + label);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                OkHttpClient client = new OkHttpClient();
                File videoFile = getFileFromUri(Uri.parse(videoUri));
                if (videoFile == null || !videoFile.exists()) {
                    runOnUiThread(() -> toastMessage.setValue(new Pair<>("Error getting video file", false)));
                    return;
                }

                RequestBody formBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("video_file", "video.mp4",
                                RequestBody.create(videoFile, MediaType.parse("video/mp4")))
                        .addFormDataPart("label", label)
                        .build();

                Request request = new Request.Builder()
                        .url(url)
                        .post(formBody)
                        .build();

                Response response = client.newCall(request).execute();
                if (response.isSuccessful()) {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    Log.d("UploadAPI", "Response received: " + responseBody);
                    runOnUiThread(() -> toastMessage.setValue(new Pair<>("Video uploaded successfully", false)));
                } else {
                    Log.e("UploadAPI", "Upload failed: " + response.message());
                    runOnUiThread(() -> toastMessage.setValue(new Pair<>("Upload failed: " + response.message(), false)));
                }
            } catch (Exception e) {
                Log.e("UploadAPI", "Error uploading video: " + e.getMessage(), e);
                runOnUiThread(() -> toastMessage.setValue(new Pair<>("Error uploading video: " + e.getMessage(), false)));
            } finally {
                executor.shutdown();
            }
        });
    }

    // Hàm lấy file từ URI
    private File getFileFromUri(Uri contentUri) {
        File tempFile = new File(getCacheDir(), "temp_video.mp4");
        try (InputStream in = getContentResolver().openInputStream(contentUri);
             OutputStream out = new FileOutputStream(tempFile)) {
            if (in == null) throw new FileNotFoundException("openInputStream returned null");
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
        } catch (IOException e) {
            Log.e("UploadActivity", "Error getting file from URI: " + e.getMessage());
            return null;
        }
        return tempFile;
    }

    public void clearCameraXVideos() {
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = { MediaStore.Video.Media._ID };
        String selection = MediaStore.Video.Media.RELATIVE_PATH + "=?";
        String[] selectionArgs = { "Movies/CameraX-Video/" };

        try (Cursor cursor = getContentResolver()
                .query(collection, projection, selection, selectionArgs, null)) {
            if (cursor == null) return;

            List<Uri> toDelete = new ArrayList<>();
            while (cursor.moveToNext()) {
                long id = cursor.getLong(
                        cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID));
                toDelete.add(Uri.withAppendedPath(collection, String.valueOf(id)));
            }

            for (Uri videoUri : toDelete) {
                getContentResolver().delete(videoUri, null, null);
            }
            toastMessage.setValue(new Pair<>("Videos cleared", false));
        } catch (Exception e) {
            Log.e("ClearCameraX", "Lỗi khi xóa video: " + e.getMessage(), e);
            toastMessage.setValue(new Pair<>("Error clearing videos", false));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        timerHandler.removeCallbacks(timerRunnable);
        cameraExecutor.shutdown();
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }
}