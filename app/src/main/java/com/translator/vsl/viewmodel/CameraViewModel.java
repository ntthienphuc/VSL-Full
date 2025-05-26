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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * ViewModel quản lý Camera X + ghi video + gửi API + dịch offline.
 * BẢN NÀY đã xoá clip gốc realtime NGAY sau khi sao chép sang cache.
 */
public class CameraViewModel extends AndroidViewModel {

    /* ===============================================================
                               FIELDS
       =============================================================== */
    private final String clientId;

    private final MutableLiveData<String>                  timerText         = new MutableLiveData<>("00:00");
    private final MutableLiveData<Boolean>                 captureButtonState= new MutableLiveData<>(false);
    private final MutableLiveData<Pair<String, Boolean>>   toastMessage      = new MutableLiveData<>();
    private final MutableLiveData<Boolean>                 flashEnabled      = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean>                 isRecording       = new MutableLiveData<>(false);

    private Camera                         camera;
    private VideoCapture<Recorder>         videoCapture;
    private Recording                      recording = null;
    private long                           recordingStartTime;
    private ImageAnalysis                  imageAnalysis;

    private final Handler                  timerHandler  = new Handler(Looper.getMainLooper());
    private final Runnable                 timerRunnable = () -> {
        long elapsed = System.currentTimeMillis() - recordingStartTime;
        long sec = (elapsed / 1000) % 60;
        long min = (elapsed / 60000) % 60;
        timerText.postValue(String.format(Locale.getDefault(), "%02d:%02d", min, sec));
        timerHandler.postDelayed(this.timerRunnable, 100);
    };

    private TextToSpeech                   tts;
    private Context                        context;

    /* ---------- realtime queue ---------- */
    private final BlockingQueue<File>      videoQueue          = new LinkedBlockingQueue<>();
    private final ExecutorService          videoSenderExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean               isRealtimeActive    = false;

    private final SharedPreferences        sharedPreferences;

    /* ---------- monitoring fields ---------- */
    private int                            cacheSize           = 0;
    private final int                      CACHE_THRESHOLD     = 30;
    private int                            slowResponseCount   = 0;
    private final int                      SLOW_RESPONSE_LIMIT = 4;

    /* ---------- new field for low score tracking ---------- */
    private int                            lowScoreCount       = 0;

    /* ---------- new fields for notification timing ---------- */
    private long                           lastCacheWarningTime = 0;
    private long                           lastSlowResponseWarningTime = 0;
    private long                           lastLowScoreWarningTime = 0;
    private final long                     WARNING_INTERVAL = 7000; // 10 seconds

    private boolean                        isCacheOverloaded = false;
    private boolean                        isNetworkSlow = false;
    private boolean                        isLowScore = false;

    /* ===============================================================
                               CONSTRUCTOR
       =============================================================== */
    public CameraViewModel(@NonNull Application app) {
        super(app);
        clientId          = UUID.randomUUID().toString();
        sharedPreferences = app.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
        initializeTextToSpeech(app);
        startVideoSender();
    }

    /* ===============================================================
                               LIVEDATA
       =============================================================== */
    public LiveData<String> getTimerText()                    { return timerText; }
    public LiveData<Boolean> getCaptureButtonState()          { return captureButtonState; }
    public LiveData<Pair<String, Boolean>> getToastMessage()  { return toastMessage; }
    public LiveData<Boolean> getFlashEnabledState()           { return flashEnabled; }
    public LiveData<Boolean> getIsRecording()                 { return isRecording; }

    /* ===============================================================
                               CAMERA INIT
       =============================================================== */
    public void startCamera(int cameraFacing, PreviewView pv) {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(pv.getContext());
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(pv.getSurfaceProvider());

                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(getCurrentVideoQuality(pv.getContext())))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);

                if (imageAnalysis == null) {
                    imageAnalysis = new ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build();
                }

                context = pv.getContext();
                CameraSelector selector = new CameraSelector.Builder()
                        .requireLensFacing(cameraFacing)
                        .build();

                provider.unbindAll();
                camera = provider.bindToLifecycle((CameraActivity) pv.getContext(),
                        selector, preview, imageAnalysis, videoCapture);

            } catch (Exception ex) {
                Log.e("CameraInit", ex.getMessage(), ex);
                toastMessage.postValue(new Pair<>("Lỗi khởi tạo camera!", false));
                tts.speak("Lỗi khởi tạo camera!", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            }
        }, ContextCompat.getMainExecutor(pv.getContext()));
    }

    /* ===============================================================
                      CAPTURE BUTTON (Normal / Realtime)
       =============================================================== */
    public void captureVideoWithOptions(PreviewView pv, boolean realtime) {
        if (recording != null || Boolean.TRUE.equals(isRecording.getValue())) {
            stopRecording();
        } else if (realtime) {
            isRealtimeActive = true;
            toastMessage.postValue(new Pair<>("Bắt đầu quay trực tiếp", false));
            tts.speak("Bắt đầu quay trực tiếp", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            startRecordingRealTime(pv);
        } else {
            isRealtimeActive = false;
            startRecordingNormal(pv);
        }
    }

    /* ===============================================================
                               NORMAL
       =============================================================== */
    private void startRecordingNormal(PreviewView pv) {

        String name = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(System.currentTimeMillis());

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        cv.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video");

        if (ActivityCompat.checkSelfPermission(pv.getContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {

            toastMessage.postValue(new Pair<>("Thiếu quyền ghi âm!", false));
            tts.speak("Thiếu quyền ghi âm!", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            return;
        }

        MediaStoreOutputOptions opts = new MediaStoreOutputOptions.Builder(
                pv.getContext().getContentResolver(),
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(cv)
                .build();

        recordingStartTime = System.currentTimeMillis();
        timerHandler.post(timerRunnable);

        recording = videoCapture.getOutput()
                .prepareRecording(pv.getContext(), opts)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(pv.getContext()), ev -> {
                    if (ev instanceof VideoRecordEvent.Start) {
                        captureButtonState.postValue(true);
                        isRecording.postValue(true);
                    } else if (ev instanceof VideoRecordEvent.Finalize) {
                        handleRecordingFinalizationNormal((VideoRecordEvent.Finalize) ev, pv);
                    }
                });
    }

    private Quality getCurrentVideoQuality(Context ctx) {
        String q = ctx.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                .getString("video_quality", "HD");
        return q.equals("SD") ? Quality.SD : Quality.HD;
    }

    /* ===============================================================
                               REAL-TIME
       =============================================================== */
    private void startRecordingRealTime(PreviewView pv) {

        timerText.postValue("00:00");

        String name = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.getDefault())
                .format(System.currentTimeMillis());

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        cv.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video");

        if (ActivityCompat.checkSelfPermission(pv.getContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {

            toastMessage.postValue(new Pair<>("Thiếu quyền ghi âm!", false));
            tts.speak("Thiếu quyền ghi âm!", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            return;
        }

        MediaStoreOutputOptions opts = new MediaStoreOutputOptions.Builder(
                pv.getContext().getContentResolver(),
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(cv)
                .build();

        recordingStartTime = System.currentTimeMillis();
        timerHandler.post(timerRunnable);

        try {
            recording = videoCapture.getOutput()
                    .prepareRecording(pv.getContext(), opts)
                    .withAudioEnabled()
                    .start(ContextCompat.getMainExecutor(pv.getContext()), ev -> {

                        if (ev instanceof VideoRecordEvent.Start) {
                            captureButtonState.postValue(true);
                            isRecording.postValue(true);

                            // cắt mỗi 1 s
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                if (recording != null) recording.stop();

                                new Handler(Looper.getMainLooper()).post(() -> {
                                    if (isRealtimeActive) startRecordingRealTime(pv);
                                });
                            }, 1000);

                        } else if (ev instanceof VideoRecordEvent.Finalize) {
                            handleRecordingFinalizationRealtime((VideoRecordEvent.Finalize) ev, pv);
                        }
                    });
        } catch (Exception ex) {
            Log.e("RealTime", ex.getMessage(), ex);
            toastMessage.postValue(new Pair<>("Lỗi ghi video trực tiếp!", false));
            tts.speak("Lỗi ghi video trực tiếp!", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
        }
    }

    /* ===============================================================
                              STOP / RESET
       =============================================================== */
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
            tts.speak("Đã dừng quay trực tiếp!", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            isRealtimeActive = false;

            for (File f : videoQueue) if (f.exists()) f.delete();
            videoQueue.clear();
            clearCameraXVideos();
        }
    }

    private void resetRecordingUI(PreviewView pv) {
        captureButtonState.postValue(false);
        isRecording.postValue(false);
        timerHandler.removeCallbacks(timerRunnable);
        timerText.postValue("00:00");
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(pv.getContext()), ImageProxy::close);
    }

    /* ===============================================================
                     FINALIZATION HANDLERS
       =============================================================== */
    private void handleRecordingFinalizationNormal(VideoRecordEvent.Finalize ev, PreviewView pv) {
        resetRecordingUI(pv);
        Uri uri = ev.getOutputResults().getOutputUri();
        translateNormalVideo(pv.getContext(), uri);
    }

    private void handleRecordingFinalizationRealtime(VideoRecordEvent.Finalize ev, PreviewView pv) {
        resetRecordingUI(pv);
        Uri uri = ev.getOutputResults().getOutputUri();
        File f = getFileFromUri(pv.getContext(), uri, true);   // xoá gốc ngay
        if (f.exists()) {
            videoQueue.offer(f);
            cacheSize++;
            if (cacheSize > CACHE_THRESHOLD) {
                isCacheOverloaded = true;
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastCacheWarningTime > WARNING_INTERVAL) {
                    toastMessage.postValue(new Pair<>("Cache quá tải! Vui lòng kiểm tra kết nối.", false));
                    tts.speak("Cache quá tải! Vui lòng kiểm tra kết nối.", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
                    lastCacheWarningTime = currentTime;
                }
            } else {
                isCacheOverloaded = false;
            }
        }
    }

    /* ===============================================================
                       TRANSLATION DISPATCHERS
       =============================================================== */
    public void translateNormalVideo(Context ctx, Uri uri) {
        if (isInternetAvailable(ctx)) {
            toastMessage.postValue(new Pair<>("Đang gửi video để dịch.", true));
            tts.speak("Đang gửi video để dịch.", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            callNormalTranslationApi(ctx, uri);
        } else {
            toastMessage.postValue(new Pair<>("Không có kết nối, dùng dịch offline.", true));
            tts.speak("Không có kết nối, dùng dịch offline.", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            callTranslationModel(ctx, uri);
        }
    }

    public void translateRealtimeVideo(Context ctx, Uri uri) {
        if (isInternetAvailable(ctx)) {
            callRealtimeTranslationApi(ctx, uri);
        } else {
            toastMessage.postValue(new Pair<>("Không có kết nối, dùng dịch offline.", true));
            tts.speak("Không có kết nối, dùng dịch offline.", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            callTranslationModel(ctx, uri);
        }
    }

    /* ===============================================================
                     OFFLINE TRANSLATION (unchanged)
       =============================================================== */
    private void callTranslationModel(Context ctx, Uri uri) {
        try {
            VideoTranslationHandler vh = new VideoTranslationHandler(ctx,
                    "model-final-new.tflite", "label400.txt");
            vh.translateVideoAsync(ctx, uri)
                    .thenAccept(res -> {
                        toastMessage.postValue(new Pair<>("Kết quả: " + res, true));
                        tts.speak(res, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
                        vh.close();
                    })
                    .exceptionally(ex -> {
                        toastMessage.postValue(new Pair<>("Lỗi dịch offline.", true));
                        tts.speak("Lỗi dịch offline.", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
                        vh.close();
                        return null;
                    });
        } catch (IOException e) {
            toastMessage.postValue(new Pair<>("Lỗi tải model.", true));
            tts.speak("Lỗi tải model.", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
        }
    }

    /* ===============================================================
                          NORMAL API
       =============================================================== */
    private void callNormalTranslationApi(Context ctx, Uri uri) {
        String ip = sharedPreferences.getString("api_ip", "14.224.194.242");
        String url = "http://" + ip + ":7000/spoter";

        File vid = getFileFromUri(ctx, uri);   // không xoá gốc trong chế độ normal
        if (!vid.exists()) {
            toastMessage.postValue(new Pair<>("File video không tồn tại!", true));
            tts.speak("File video không tồn tại!", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            return;
        }

        MultipartBody.Builder mb = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("video_file", vid.getName(), RequestBody.create(MediaType.parse("video/mp4"), vid))
                .addFormDataPart("angle_threshold", "140")
                .addFormDataPart("top_k", "3");

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(100, TimeUnit.SECONDS)
                .readTimeout(100, TimeUnit.SECONDS)
                .writeTimeout(100, TimeUnit.SECONDS)
                .build();

        Request req = new Request.Builder().url(url).post(mb.build()).build();

        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call c, IOException e) {
                toastMessage.postValue(new Pair<>("Không kết nối được máy chủ.", true));
                tts.speak("Không kết nối được máy chủ.", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            }
            @Override public void onResponse(Call c, Response r) throws IOException {
                if (r.isSuccessful()) {
                    parseFullVideoApiResult(r.body().string());
                } else {
                    Log.e("NormalAPI", "Server error " + r.code());
                    toastMessage.postValue(new Pair<>("Lỗi máy chủ!", true));
                    tts.speak("Lỗi máy chủ!", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
                }
            }
        });
    }

    /* ===============================================================
                          REALTIME API
       =============================================================== */
    private void callRealtimeTranslationApi(Context ctx, Uri uri) {
        File f = getFileFromUri(ctx, uri, true);  // xoá gốc
        if (f.exists()) {
            videoQueue.offer(f);
            cacheSize++;
            if (cacheSize > CACHE_THRESHOLD) {
                isCacheOverloaded = true;
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastCacheWarningTime > WARNING_INTERVAL) {
                    toastMessage.postValue(new Pair<>("Cache quá tải! Vui lòng kiểm tra kết nối.", false));
                    tts.speak("Cache quá tải! Vui lòng kiểm tra kết nối.", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
                    lastCacheWarningTime = currentTime;
                }
            } else {
                isCacheOverloaded = false;
            }
        }
    }

    /* ===============================================================
                        PARSE API RESULTS
       =============================================================== */
    private void parseFullVideoApiResult(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray merged = obj.optJSONArray("results_merged");
            if (merged == null || merged.length() == 0) {
                toastMessage.postValue(new Pair<>("Không phát hiện cử chỉ!", false));
                tts.speak("Không phát hiện cử chỉ!", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
                return;
            }
            List<String> glosses = new ArrayList<>();
            for (int i = 0; i < merged.length(); i++) {
                JSONObject blk = merged.getJSONObject(i);
                JSONArray preds = blk.getJSONArray("predictions");
                if (preds.length() > 0) {
                    JSONObject top = preds.getJSONObject(0);
                    glosses.add(top.getString("gloss"));
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

    private void parseRealtimeResponse(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            if (obj.has("predictions") && obj.getJSONArray("predictions").length() > 0) {
                JSONObject top = obj.getJSONArray("predictions").getJSONObject(0);
                String gloss = top.getString("gloss");
                double score = top.getDouble("score");
                if (score < 0.99) {
                    lowScoreCount++;
                    if (lowScoreCount >= 3) {
                        isLowScore = true;
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastLowScoreWarningTime > WARNING_INTERVAL) {
                            String warning = "Vui lòng tăng độ phân giải.";
                            toastMessage.postValue(new Pair<>(warning, true));
                            tts.speak(warning, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString());
                            lastLowScoreWarningTime = currentTime;
                        }
                    }
                    String message = "Ý bạn là " + gloss;
                    toastMessage.postValue(new Pair<>(message, true));
                    tts.speak(message, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString());
                } else {
                    lowScoreCount = 0; // Reset nếu score >= 0.99
                    isLowScore = false;
                    toastMessage.postValue(new Pair<>(gloss + String.format(" (%.1f%%)", score * 100), true));
                    tts.speak(gloss, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString());
                }
            }
        } catch (JSONException e) {
            Log.e("RealTimeParse", e.getMessage());
        }
    }

    /* ===============================================================
                        SHOW GLOSSES SEQUENTIALLY
       =============================================================== */
    private final Handler glossHandler = new Handler(Looper.getMainLooper());
    private void showGlossesOneByOne(List<String> gs, int idx) {
        if (idx >= gs.size()) {
            toastMessage.postValue(new Pair<>("Kết thúc dự đoán.", false));
            tts.speak("Kết thúc dự đoán.", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            return;
        }
        String text = gs.get(idx);
        toastMessage.postValue(new Pair<>(text, true));
        tts.speak(text.split(" \\(")[0], TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString());
        glossHandler.postDelayed(() -> showGlossesOneByOne(gs, idx + 1), 2000);
    }

    /* ===============================================================
                         FILE COPY + DELETE ORIGINAL
       =============================================================== */
    private File getFileFromUri(Context ctx, Uri uri, boolean deleteOriginal) {
        File tmp = new File(ctx.getCacheDir(), "temp_" + System.currentTimeMillis() + ".mp4");
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(tmp)) {

            if (in == null) throw new FileNotFoundException("InputStream null");
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) != -1) out.write(buf, 0, len);

        } catch (IOException ex) {
            Log.e("getFileFromUri", ex.getMessage());
        }

        if (deleteOriginal) {
            try {
                ctx.getContentResolver().delete(uri, null, null);
            } catch (Exception ex) {
                Log.e("DeleteOriginal", ex.getMessage());
            }
        }
        return tmp;
    }
    private File getFileFromUri(Context ctx, Uri uri) {     // overload giữ nguyên
        return getFileFromUri(ctx, uri, false);
    }

    /* ===============================================================
                               FLASH
       =============================================================== */
    public void toggleFlash() {
        if (camera == null) {
            toastMessage.postValue(new Pair<>("Camera chưa khởi tạo.", false));
            tts.speak("Camera chưa khởi tạo.", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            return;
        }
        if (camera.getCameraInfo().hasFlashUnit()) {
            boolean on = camera.getCameraInfo().getTorchState().getValue() == 1;
            camera.getCameraControl().enableTorch(!on);
            flashEnabled.postValue(!on);
        } else {
            toastMessage.postValue(new Pair<>("Flash không khả dụng.", false));
            tts.speak("Flash không khả dụng.", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
        }
    }

    /* ===============================================================
                            CONNECTIVITY
       =============================================================== */
    private boolean isInternetAvailable(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            Network net = cm.getActiveNetwork();
            if (net != null) {
                NetworkCapabilities cap = cm.getNetworkCapabilities(net);
                return cap != null && (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
            }
        }
        return false;
    }

    /* ===============================================================
                         TEXT-TO-SPEECH INIT
       =============================================================== */
    private void initializeTextToSpeech(Context ctx) {
        tts = new TextToSpeech(ctx, status -> {
            if (status == TextToSpeech.SUCCESS) {
                Locale vi = new Locale("vi");
                int r = tts.setLanguage(vi);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Intent it = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(it);
                }
            } else {
                Log.e("TTS", "init failed");
            }
        });
    }

    /* ===============================================================
                     BACKGROUND SENDER THREAD
       =============================================================== */
    private void startVideoSender() {
        videoSenderExecutor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    File vid = videoQueue.take();
                    long startTime = System.currentTimeMillis();
                    boolean ok = sendVideoToApi(vid);
                    long responseTime = System.currentTimeMillis() - startTime;
                    if (responseTime > 1500) {
                        slowResponseCount++;
                        if (slowResponseCount >= SLOW_RESPONSE_LIMIT) {
                            isNetworkSlow = true;
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastSlowResponseWarningTime > WARNING_INTERVAL) {
                                toastMessage.postValue(new Pair<>("Mạng không đủ để sử dụng realtime. Vui lòng kiểm tra lại.", false));
                                tts.speak("Mạng không đủ để sử dụng realtime. Vui lòng kiểm tra lại.", TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
                                lastSlowResponseWarningTime = currentTime;
                            }
                        }
                    } else {
                        slowResponseCount = 0;
                        isNetworkSlow = false;
                    }
                    if (!ok) {
                        Thread.sleep(1000);
                        videoQueue.offer(vid);
                    } else if (vid.exists()) {
                        vid.delete();
                        cacheSize--;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception ex) {
                    Log.e("VideoSender", ex.getMessage());
                }
            }
        });
    }

    private boolean sendVideoToApi(File vid) {
        String ip = sharedPreferences.getString("api_ip", "14.224.194.242");
        String url = "http://" + ip + ":7000/spoter_segmented";

        MultipartBody.Builder mb = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("video_file", vid.getName(), RequestBody.create(MediaType.parse("video/mp4"), vid))
                .addFormDataPart("clientId", clientId)
                .addFormDataPart("angle_threshold", "140")
                .addFormDataPart("top_k", "3");

        OkHttpClient c = new OkHttpClient.Builder()
                .connectTimeout(100, TimeUnit.SECONDS)
                .readTimeout(100, TimeUnit.SECONDS)
                .writeTimeout(100, TimeUnit.SECONDS)
                .build();

        Request req = new Request.Builder().url(url).post(mb.build()).build();
        try (Response res = c.newCall(req).execute()) {
            if (res.isSuccessful()) {
                parseRealtimeResponse(res.body().string());
                return true;
            } else {
                Log.e("VideoSender", "Server " + res.code());
                return false;
            }
        } catch (IOException ex) {
            Log.e("VideoSender", ex.getMessage());
            return false;
        }
    }

    /* ===============================================================
                         DELETE CameraX FOLDER
       =============================================================== */
    public void clearCameraXVideos() {
        if (context == null) return;

        Uri col = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String sel  = MediaStore.Video.Media.RELATIVE_PATH + "=?";
        String[] selArgs = { "Movies/CameraX-Video/" };

        try (Cursor cur = context.getContentResolver().query(col,
                new String[]{ MediaStore.Video.Media._ID }, sel, selArgs, null)) {

            if (cur == null) return;
            List<Uri> list = new ArrayList<>();
            while (cur.moveToNext()) {
                long id = cur.getLong(cur.getColumnIndexOrThrow(MediaStore.Video.Media._ID));
                list.add(Uri.withAppendedPath(col, String.valueOf(id)));
            }
            for (Uri u : list) context.getContentResolver().delete(u, null, null);
        } catch (Exception ex) {
            Log.e("ClearCameraX", ex.getMessage(), ex);
        }
    }

    /* ===============================================================
                              CLEAN-UP
       =============================================================== */
    @Override
    protected void onCleared() {
        super.onCleared();
        timerHandler.removeCallbacks(timerRunnable);
        if (tts != null) { tts.stop(); tts.shutdown(); }
        videoSenderExecutor.shutdownNow();
    }
}