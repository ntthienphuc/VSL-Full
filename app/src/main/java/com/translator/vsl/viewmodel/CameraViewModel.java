package com.translator.vsl.viewmodel;

import android.Manifest;
import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
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
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.common.util.concurrent.ListenableFuture;
import com.translator.vsl.handler.VideoTranslationHandler;
import com.translator.vsl.view.CameraActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * ViewModel quản lý logic Camera, Quay video, Gửi API và Dịch offline.
 */
public class CameraViewModel extends AndroidViewModel {

    // Client ID duy nhất cho thiết bị (có thể lưu trong SharedPreferences để tái sử dụng)
    private final String clientId;

    // LiveData cho giao diện người dùng
    private final MutableLiveData<String> timerText = new MutableLiveData<>("00:00");
    private final MutableLiveData<Boolean> captureButtonState = new MutableLiveData<>(false);
    private final MutableLiveData<Pair<String, Boolean>> toastMessage = new MutableLiveData<>();
    private final MutableLiveData<Boolean> flashEnabled = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> isRecording = new MutableLiveData<>(false);

    // Thành phần camera và video capture
    private Camera camera;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording = null;
    private long recordingStartTime;

    // ImageAnalysis dùng cho xử lý nếu cần
    private ImageAnalysis imageAnalysis;

    // Timer hiển thị cho quá trình ghi video
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            long elapsedMillis = System.currentTimeMillis() - recordingStartTime;
            long seconds = (elapsedMillis / 1000) % 60;
            long minutes = (elapsedMillis / (1000 * 60)) % 60;
            timerText.postValue(String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds));
            timerHandler.postDelayed(this, 100);
        }
    };

    // Text-to-Speech
    private TextToSpeech tts;
    private Context context;

    // --- Các thành phần mới cho việc gửi video liên tục ---
    private final BlockingQueue<File> videoQueue = new LinkedBlockingQueue<>();
    private final ExecutorService videoSenderExecutor = Executors.newSingleThreadExecutor();
    // ----------------------------------------------------------

    // Flag để kiểm soát chế độ realtime
    private volatile boolean isRealtimeActive = false;

    // SharedPreferences để lưu trữ địa chỉ IP
    private SharedPreferences sharedPreferences;

    public CameraViewModel(@NonNull Application application) {
        super(application);
        clientId = UUID.randomUUID().toString();
        sharedPreferences = application.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
        initializeTextToSpeech(application);
        startVideoSender();
    }

    // LiveData getters
    public LiveData<String> getTimerText() { return timerText; }
    public LiveData<Boolean> getCaptureButtonState() { return captureButtonState; }
    public LiveData<Pair<String, Boolean>> getToastMessage() { return toastMessage; }
    public LiveData<Boolean> getFlashEnabledState() { return flashEnabled; }
    public LiveData<Boolean> getIsRecording() { return isRecording; }

    // Khởi tạo camera
    public void startCamera(int cameraFacing, PreviewView previewView) {
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(previewView.getContext());
        providerFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = providerFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HD))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);

                if (imageAnalysis == null) {
                    imageAnalysis = new ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build();
                }

                context = previewView.getContext();
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(cameraFacing)
                        .build();

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle((CameraActivity) previewView.getContext(),
                        cameraSelector, preview, imageAnalysis, videoCapture);

            } catch (Exception e) {
                Log.e("CameraError", "Lỗi khởi tạo camera: " + e.getMessage(), e);
                toastMessage.postValue(new Pair<>("Lỗi khởi tạo camera!", false));
                tts.speak("Lỗi khởi tạo camera!", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            }
        }, ContextCompat.getMainExecutor(previewView.getContext()));
    }

    // Xử lý nút capture: chọn giữa chế độ Normal và Real-time
    public void captureVideoWithOptions(PreviewView previewView, boolean isRealTime) {
        if (recording != null || Boolean.TRUE.equals(isRecording.getValue())) {
            stopRecording();
        } else if (isRealTime) {
            isRealtimeActive = true;
            toastMessage.postValue(new Pair<>("Bắt đầu quay trực tiếp", false));
            tts.speak("Bắt đầu quay trực tiếp", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            startRecordingRealTime(previewView);
        } else {
            isRealtimeActive = false;
            startRecordingNormal(previewView);
        }
    }

    // Quay video Normal
    private void startRecordingNormal(PreviewView previewView) {
        String name = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(System.currentTimeMillis());
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video");

        if (ActivityCompat.checkSelfPermission(previewView.getContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            toastMessage.postValue(new Pair<>("Thiếu quyền ghi âm!", false));
            tts.speak("Thiếu quyền ghi âm!", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            return;
        }

        MediaStoreOutputOptions options = new MediaStoreOutputOptions.Builder(
                previewView.getContext().getContentResolver(),
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(contentValues)
                .build();

        recordingStartTime = System.currentTimeMillis();
        timerHandler.post(timerRunnable);

        recording = videoCapture.getOutput().prepareRecording(previewView.getContext(), options)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(previewView.getContext()), videoRecordEvent -> {
                    if (videoRecordEvent instanceof VideoRecordEvent.Start) {
                        isRecording.postValue(true);
                        captureButtonState.postValue(true);
                    } else if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                        handleRecordingFinalizationNormal((VideoRecordEvent.Finalize) videoRecordEvent, previewView);
                    }
                });
    }

    // Quay video Real-time
    private void startRecordingRealTime(PreviewView previewView) {
        timerText.postValue("00:00");

        String name = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.getDefault()).format(System.currentTimeMillis());
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video");

        MediaStoreOutputOptions options = new MediaStoreOutputOptions.Builder(
                previewView.getContext().getContentResolver(),
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(contentValues)
                .build();

        if (ActivityCompat.checkSelfPermission(previewView.getContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            toastMessage.postValue(new Pair<>("Thiếu quyền ghi âm!", false));
            tts.speak("Thiếu quyền ghi âm!", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            return;
        }

        recordingStartTime = System.currentTimeMillis();
        timerHandler.post(timerRunnable);

        try {
            recording = videoCapture.getOutput().prepareRecording(previewView.getContext(), options)
                    .withAudioEnabled()
                    .start(ContextCompat.getMainExecutor(previewView.getContext()), videoRecordEvent -> {
                        if (videoRecordEvent instanceof VideoRecordEvent.Start) {
                            captureButtonState.postValue(true);
                            isRecording.postValue(true);
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                if (recording != null) {
                                    recording.stop();
                                }
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    if (isRealtimeActive) {
                                        startRecordingRealTime(previewView);
                                    }
                                }, 0);
                            }, 1000);
                        } else if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                            handleRecordingFinalizationRealtime((VideoRecordEvent.Finalize) videoRecordEvent, previewView);
                        }
                    });
        } catch (Exception e) {
            Log.e("RealTime", "Lỗi ghi video trực tiếp: " + e.getMessage(), e);
            toastMessage.postValue(new Pair<>("Lỗi ghi video trực tiếp!", false));
            tts.speak("Lỗi ghi video trực tiếp!", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
        }
    }
    // Dừng ghi video
    private void stopRecording() {
        if (recording != null) {
            recording.stop();
            recording = null;
        }
        isRecording.postValue(false);
        captureButtonState.postValue(false);
        timerHandler.removeCallbacks(timerRunnable);
        timerText.postValue("00:00");
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context), ImageProxy::close);
        if (isRealtimeActive) {
            toastMessage.postValue(new Pair<>("Đã dừng quay trực tiếp", false));
            tts.speak("Đã dừng quay trực tiếp", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            isRealtimeActive = false;
            for (File file : videoQueue) {
                if (file.exists()) {
                    boolean deleted = file.delete();
                    if (deleted) {
                        MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, null, null);
                    }
                }
            }
            videoQueue.clear();
        }
    }

    // Reset giao diện
    private void resetRecordingUI(PreviewView previewView) {
        captureButtonState.postValue(false);
        isRecording.postValue(false);
        timerHandler.removeCallbacks(timerRunnable);
        timerText.postValue("00:00");
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(previewView.getContext()), ImageProxy::close);
    }

    // Xử lý finalize chế độ Normal
    private void handleRecordingFinalizationNormal(VideoRecordEvent.Finalize finalizeEvent, PreviewView previewView) {
        resetRecordingUI(previewView);
        Uri videoUri = finalizeEvent.getOutputResults().getOutputUri();
        translateNormalVideo(previewView.getContext(), videoUri);
    }

    // Xử lý finalize chế độ Real-time
    private void handleRecordingFinalizationRealtime(VideoRecordEvent.Finalize finalizeEvent, PreviewView previewView) {
        resetRecordingUI(previewView);
        Uri videoUri = finalizeEvent.getOutputResults().getOutputUri();
        File videoFile = getFileFromUri(previewView.getContext(), videoUri);
        if (videoFile.exists()) {
            videoQueue.offer(videoFile);
        }
    }

    // Dịch video Normal
    public void translateNormalVideo(Context context, Uri videoUri) {
        if (isInternetAvailable(context)) {
            toastMessage.postValue(new Pair<>("Đang gửi video để dịch.", true));
            tts.speak("Đang gửi video để dịch.", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            callNormalTranslationApi(context, videoUri);
        } else {
            toastMessage.postValue(new Pair<>("Không có kết nối, sử dụng chế độ dịch offline.", true));
            tts.speak("Không có kết nối, sử dụng chế độ dịch offline.", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            callTranslationModel(context, videoUri);
        }
    }

    // Dịch video Real-time
    public void translateRealtimeVideo(Context context, Uri videoUri) {
        if (isInternetAvailable(context)) {
            callRealtimeTranslationApi(context, videoUri);
        } else {
            toastMessage.postValue(new Pair<>("Không có kết nối, sử dụng chế độ dịch offline.", true));
            tts.speak("Không có kết nối, sử dụng chế độ dịch offline.", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            callTranslationModel(context, videoUri);
        }
    }

    // Dịch offline
    private void callTranslationModel(Context context, Uri videoUri) {
        try {
            VideoTranslationHandler translator = new VideoTranslationHandler(context, "model-final-new.tflite", "label400.txt");
            translator.translateVideoAsync(context, videoUri)
                    .thenAccept(result -> {
                        toastMessage.postValue(new Pair<>("Kết quả: " + result, true));
                        tts.speak(result, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
                        translator.close();
                    })
                    .exceptionally(ex -> {
                        toastMessage.postValue(new Pair<>("Lỗi dịch offline.", true));
                        tts.speak("Lỗi dịch offline.", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
                        translator.close();
                        return null;
                    });
        } catch (IOException e) {
            toastMessage.postValue(new Pair<>("Lỗi tải model.", true));
            tts.speak("Lỗi tải model.", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
        }
    }

    // API Normal
    private void callNormalTranslationApi(Context context, Uri videoUri) {
        String ip = sharedPreferences.getString("api_ip", "192.168.0.100");
        String apiUrl = "http://" + ip + ":80/spoter";
        File videoFile = getFileFromUri(context, videoUri);
        if (!videoFile.exists()) {
            toastMessage.postValue(new Pair<>("File video không tồn tại!", true));
            tts.speak("File video không tồn tại!", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            return;
        }
        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);
        builder.addFormDataPart("video_file", videoFile.getName(),
                RequestBody.create(MediaType.parse("video/mp4"), videoFile));
        builder.addFormDataPart("angle_threshold", "140");
        builder.addFormDataPart("top_k", "3");

        RequestBody reqBody = builder.build();
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(100, TimeUnit.SECONDS)
                .readTimeout(100, TimeUnit.SECONDS)
                .writeTimeout(100, TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder()
                .url(apiUrl)
                .post(reqBody)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                toastMessage.postValue(new Pair<>("Không kết nối được máy chủ.", true));
                tts.speak("Không kết nối được máy chủ.", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    String body = response.body().string();
                    parseFullVideoApiResult(body);
                } else {
                    Log.e("NormalAPI", "Lỗi máy chủ: " + response.code());
                    toastMessage.postValue(new Pair<>("Lỗi máy chủ!", true));
                    tts.speak("Lỗi máy chủ!", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
                }
            }
        });
    }

    // API Real-time
    private void callRealtimeTranslationApi(Context context, Uri videoUri) {
        translateRealtimeVideo(context, videoUri);
    }

    // Parse kết quả Normal
    private void parseFullVideoApiResult(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray resultsMerged = obj.optJSONArray("results_merged");
            if (resultsMerged == null || resultsMerged.length() == 0) {
                toastMessage.postValue(new Pair<>("Không phát hiện cử chỉ!", false));
                tts.speak("Không phát hiện cử chỉ!", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
                return;
            }
            List<String> glosses = new ArrayList<>();
            for (int i = 0; i < resultsMerged.length(); i++) {
                JSONObject block = resultsMerged.getJSONObject(i);
                JSONArray preds = block.getJSONArray("predictions");
                if (preds.length() > 0) {
                    JSONObject top = preds.getJSONObject(0);
                    String gloss = top.getString("gloss");
                    double score = top.getDouble("score");
                    glosses.add(gloss + String.format(" (%.1f%%)", score * 100));
                }
            }
            if (!glosses.isEmpty()) {
                showGlossesOneByOne(glosses, 0);
            } else {
                toastMessage.postValue(new Pair<>("Không phát hiện cử chỉ!", true));
                tts.speak("Không phát hiện cử chỉ!", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            }
        } catch (JSONException e) {
            toastMessage.postValue(new Pair<>("Lỗi phân tích kết quả.", true));
            tts.speak("Lỗi phân tích kết quả.", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
        }
    }

    // Parse kết quả Real-time
    private void parseRealtimeResponse(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            if (obj.has("predictions") && obj.getJSONArray("predictions").length() > 0) {
                JSONArray preds = obj.getJSONArray("predictions");
                JSONObject top = preds.getJSONObject(0);
                String gloss = top.getString("gloss");
                double score = top.getDouble("score");
                String display = gloss + String.format(" (%.1f%%)", score * 100);
                toastMessage.postValue(new Pair<>(display, true));
                tts.speak(gloss, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString());
            }
        } catch (JSONException e) {
            Log.e("RealTimeParse", "Lỗi phân tích kết quả: " + e.getMessage());
        }
    }

    // Hiển thị gloss từng phần
    private final Handler glossHandler = new Handler(Looper.getMainLooper());
    private void showGlossesOneByOne(List<String> glosses, int index) {
        if (index >= glosses.size()) {
            toastMessage.postValue(new Pair<>("Kết thúc dự đoán.", false));
            tts.speak("Kết thúc dự đoán.", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            return;
        }
        String text = glosses.get(index);
        toastMessage.postValue(new Pair<>(text, true));
        String glossOnly = text.split(" \\(")[0];
        tts.speak(glossOnly, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString());
        glossHandler.postDelayed(() -> showGlossesOneByOne(glosses, index + 1), 2000);
    }

    // Lấy file từ Uri
    private File getFileFromUri(Context context, Uri uri) {
        File tempFile = new File(context.getCacheDir(), "temp_video.mp4");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(tempFile)) {
            if (in == null) {
                throw new FileNotFoundException("openInputStream returned null");
            }
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
        } catch (IOException e) {
            Log.e("getFileFromUri", "Lỗi: " + e.getMessage());
        }
        return tempFile;
    }

    // Điều khiển flash
    public void toggleFlash() {
        if (camera == null) {
            toastMessage.postValue(new Pair<>("Camera chưa khởi tạo.", false));
            tts.speak("Camera chưa khởi tạo.", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            return;
        }
        if (camera.getCameraInfo().hasFlashUnit()) {
            boolean isFlashOn = (camera.getCameraInfo().getTorchState().getValue() == 1);
            camera.getCameraControl().enableTorch(!isFlashOn);
            flashEnabled.postValue(!isFlashOn);
        } else {
            toastMessage.postValue(new Pair<>("Flash không khả dụng.", false));
            tts.speak("Flash không khả dụng.", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
        }
    }

    // Kiểm tra kết nối Internet
    private boolean isInternetAvailable(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
                return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
            }
        }
        return false;
    }

    // Khởi tạo TextToSpeech
    private void initializeTextToSpeech(Context context) {
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                Locale vietnamese = new Locale("vi");
                int res = tts.setLanguage(vietnamese);
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTS", "Ngôn ngữ tiếng Việt không hỗ trợ hoặc thiếu dữ liệu");
                    Intent installIntent = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
                    installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(installIntent);
                }
            } else {
                Log.e("TTS", "Khởi tạo TTS thất bại");
            }
        });
    }

    // Gửi video từ queue
    private void startVideoSender() {
        videoSenderExecutor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    File videoFile = videoQueue.take();
                    boolean success = sendVideoToApi(videoFile);
                    if (!success) {
                        Thread.sleep(1000);
                        videoQueue.offer(videoFile);
                    } else {
                        if (videoFile.exists()) {
                            boolean deleted = videoFile.delete();
                            if (deleted) {
                                MediaScannerConnection.scanFile(context, new String[]{videoFile.getAbsolutePath()}, null, null);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e("VideoSender", "Lỗi gửi video: " + e.getMessage());
                }
            }
        });
    }

    // Gửi video tới API
    private boolean sendVideoToApi(File videoFile) {
        String ip = sharedPreferences.getString("api_ip", "192.168.0.100");
        String apiUrl = "http://" + ip + ":80/spoter_segmented";
        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);
        builder.addFormDataPart("video_file", videoFile.getName(),
                RequestBody.create(MediaType.parse("video/mp4"), videoFile));
        builder.addFormDataPart("clientId", clientId);
        builder.addFormDataPart("angle_threshold", "120");
        builder.addFormDataPart("top_k", "3");

        RequestBody reqBody = builder.build();
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(100, TimeUnit.SECONDS)
                .readTimeout(100, TimeUnit.SECONDS)
                .writeTimeout(100, TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder()
                .url(apiUrl)
                .post(reqBody)
                .build();
        try {
            Response response = client.newCall(request).execute();
            if (response.isSuccessful()) {
                String body = response.body().string();
                parseRealtimeResponse(body);
                return true;
            } else {
                Log.e("VideoSender", "Lỗi máy chủ: " + response.code());
                return false;
            }
        } catch (IOException e) {
            Log.e("VideoSender", "Lỗi kết nối: " + e.getMessage());
            return false;
        }
    }

    // Xóa video trong thư mục CameraX-Video
    public void clearCameraXVideos() {
        if (context == null) {
            toastMessage.postValue(new Pair<>("Context chưa được khởi tạo!", false));
            tts.speak("Context chưa được khởi tạo!", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            return;
        }

        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = new String[] { MediaStore.Video.Media._ID };
        String selection = MediaStore.Video.Media.RELATIVE_PATH + "=?";
        String[] selectionArgs = new String[] { "Movies/CameraX-Video/" };

        try {
            int deletedCount = 0;
            List<Uri> videoUrisToDelete = new ArrayList<>();

            try (Cursor cursor = context.getContentResolver().query(
                    collection, projection, selection, selectionArgs, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID));
                        Uri videoUri = Uri.withAppendedPath(collection, String.valueOf(id));
                        videoUrisToDelete.add(videoUri);
                    } while (cursor.moveToNext());
                }
            }

            if (!videoUrisToDelete.isEmpty()) {
                for (Uri videoUri : videoUrisToDelete) {
                    int rowsDeleted = context.getContentResolver().delete(videoUri, null, null);
                    if (rowsDeleted > 0) {
                        deletedCount++;
                    }
                }
                toastMessage.postValue(new Pair<>("Đã xóa " + deletedCount + " video.", false));
                tts.speak("Đã xóa " + deletedCount + " video.", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            } else {
                toastMessage.postValue(new Pair<>("Không tìm thấy video nào trong CameraX-Video!", false));
                tts.speak("Không tìm thấy video nào trong CameraX-Video!", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            }

        } catch (Exception e) {
            Log.e("ClearCameraX", "Lỗi khi xóa video: " + e.getMessage(), e);
            toastMessage.postValue(new Pair<>("Lỗi khi xóa video!", false));
            tts.speak("Lỗi khi xóa video!", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        timerHandler.removeCallbacks(timerRunnable);
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        videoSenderExecutor.shutdownNow();
    }
}