package com.translator.vsl.viewmodel;

import static com.translator.vsl.handler.VideoUtils.createVideoFromBitmaps;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.Manifest;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
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
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.translator.vsl.handler.CalculateUtils;
import com.translator.vsl.handler.PoseLandmarkerHelper;
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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class CameraViewModel extends AndroidViewModel {

    // **LiveData để theo dõi trạng thái giao diện**
    private final MutableLiveData<String> timerText = new MutableLiveData<>("00:00");
    private final MutableLiveData<Boolean> captureButtonState = new MutableLiveData<>(false);
    private final MutableLiveData<Pair<String, Boolean>> toastMessage = new MutableLiveData<>();
    private final MutableLiveData<Boolean> flashEnabled = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> isRecording = new MutableLiveData<>(false);

    // **Các biến thành viên**
    private Recording recording = null;
    private VideoCapture<Recorder> videoCapture = null;
    private final ExecutorService service = Executors.newSingleThreadExecutor();
    private long recordingStartTime;
    private Camera camera;
    private final Runnable timerRunnable;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private PoseLandmarkerHelper framesHandler;
    private ImageAnalysis imageAnalysis;
    private Context context;
    private final Handler displayHandler = new Handler(Looper.getMainLooper());
    private final List<Bitmap> frameBuffer = new ArrayList<>(); // Buffer lưu frame cho real-time
    private boolean isCropping = false; // Trạng thái đang thu thập frame

    // **Getter cho LiveData**
    public LiveData<Boolean> getFlashEnabledState() { return flashEnabled; }
    public LiveData<Boolean> getIsRecording() { return isRecording; }
    public LiveData<String> getTimerText() { return timerText; }
    public LiveData<Boolean> getCaptureButtonState() { return captureButtonState; }
    public LiveData<Pair<String, Boolean>> getToastMessage() { return toastMessage; }

    // **Constructor**
    public CameraViewModel(@NonNull Application application) {
        super(application);
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                long elapsedMillis = System.currentTimeMillis() - recordingStartTime;
                long seconds = (elapsedMillis / 1000) % 60;
                long minutes = (elapsedMillis / (1000 * 60)) % 60;
                String time = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
                timerText.postValue(time);
                timerHandler.postDelayed(this, 1000);
            }
        };
        initializeTextToSpeech(application);
    }

    // **Khởi động camera**
    public void startCamera(int cameraFacing, PreviewView previewView) {
        ListenableFuture<ProcessCameraProvider> processCameraProvider = ProcessCameraProvider.getInstance(previewView.getContext());
        processCameraProvider.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = processCameraProvider.get();
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
                        .requireLensFacing(cameraFacing).build();

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(
                        (CameraActivity) previewView.getContext(),
                        cameraSelector,
                        preview,
                        imageAnalysis,
                        videoCapture);
            } catch (Exception e) {
                Log.e("CameraError", "Error starting camera: " + e.getMessage());
                toastMessage.postValue(new Pair<>("Error starting camera: " + e.getMessage(), false));
            }
        }, ContextCompat.getMainExecutor(previewView.getContext()));
    }

    // **Chọn chế độ quay video**
    public void captureVideoWithOptions(PreviewView previewView, boolean isRealTime) {
        if (recording != null || isRecording.getValue()) {
            stopRecording();
        } else if (isRealTime) {
            // Khi chuyển sang chế độ real-time, hiện thông báo chức năng đang trong quá trình phát triển
            startRecordingRealTime(previewView);
        } else {
            startRecordingNormal(previewView);
        }
    }

    // **Quay video thông thường (giữ nguyên)**
    private void startRecordingNormal(PreviewView previewView) {
        String name = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.getDefault()).format(System.currentTimeMillis());
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video");

        MediaStoreOutputOptions options = new MediaStoreOutputOptions.Builder(previewView.getContext().getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(contentValues).build();

        if (ActivityCompat.checkSelfPermission(previewView.getContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toastMessage.postValue(new Pair<>("Missing RECORD_AUDIO permission", false));
            return;
        }

        recordingStartTime = System.currentTimeMillis();
        timerHandler.post(timerRunnable);
        recording = videoCapture.getOutput().prepareRecording(previewView.getContext(), options)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(previewView.getContext()), videoRecordEvent -> {
                    if (videoRecordEvent instanceof VideoRecordEvent.Start) {
                        captureButtonState.postValue(true);
                        isRecording.postValue(true);
                    } else if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                        handleRecordingFinalizationNormal((VideoRecordEvent.Finalize) videoRecordEvent, previewView);
                    }
                });
    }

    // **Quay video thời gian thực (chưa được phát triển)**
    private void startRecordingRealTime(PreviewView previewView) {
        // Hiển thị thông báo chức năng real-time đang trong quá trình phát triển
        toastMessage.postValue(new Pair<>("Chức năng này đang trong quá trình phát triển", true));
    }

    // **Phân tích từng frame để phát hiện gesture**
    private void analyzeFrame(ImageProxy imageProxy) {
        Bitmap bitmap = rotateBitmap(imageProxyToBitmap(imageProxy), 90);
        PoseLandmarkerHelper.ResultBundle resultBundle = framesHandler.detectImage(bitmap);

        if (!resultBundle.results.isEmpty()) {
            boolean[] handStatus = CalculateUtils.checkHandStatus(resultBundle.results.get(0), 160);
            boolean isStartGesture = isStartGesture(handStatus);
            boolean isEndGesture = isEndGesture(handStatus);

            if (isStartGesture && !isCropping) {
                isCropping = true;
                frameBuffer.clear();
                Log.d("Gesture", "Bắt đầu thu thập frame");
                toastMessage.postValue(new Pair<>("Bắt đầu thu thập gesture", true));
            } else if (isEndGesture && isCropping) {
                isCropping = false;
                Log.d("Gesture", "Kết thúc thu thập frame, gửi đoạn video");
                toastMessage.postValue(new Pair<>("Đang xử lý đoạn gesture", true));
                processVideoSegment(); // Xử lý segment khi kết thúc gesture
            }

            if (isCropping) {
                frameBuffer.add(bitmap);
            }
        }
        imageProxy.close();
    }

    // **Logic phát hiện gesture bắt đầu: 2 tay với ngón trỏ và giữa giơ lên**
    private boolean isStartGesture(boolean[] handStatus) {
        return handStatus.length >= 4 && handStatus[0] && handStatus[1] && handStatus[2] && handStatus[3];
    }

    // **Logic phát hiện gesture kết thúc: 2 tay với 5 ngón mở rộng**
    private boolean isEndGesture(boolean[] handStatus) {
        return handStatus.length >= 6 && handStatus[4] && handStatus[5];
    }

    // **Xử lý và gửi đoạn video qua API hoặc dịch offline**
    private void processVideoSegment() {
        if (frameBuffer.isEmpty()) return;

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Uri videoUri = createVideoFromBitmaps(context, frameBuffer, 30);

                if (isInternetAvailable(context)) {
                    // Có mạng: Gửi qua API
                    File videoFile = getFileFromUri(context, videoUri);
                    MultipartBody.Builder multipartBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM);
                    multipartBuilder.addFormDataPart("video_file", videoFile.getName(),
                            RequestBody.create(MediaType.parse("video/mp4"), videoFile));
                    multipartBuilder.addFormDataPart("return_type", "json");
                    multipartBuilder.addFormDataPart("angle_threshold", "110");
                    multipartBuilder.addFormDataPart("top_k", "3");
                    RequestBody requestBody = multipartBuilder.build();
                    Request request = new Request.Builder()
                            .url("http://192.168.0.100:80/spoter")
                            .post(requestBody)
                            .build();
                    OkHttpClient client = new OkHttpClient.Builder()
                            .connectTimeout(60, TimeUnit.SECONDS)
                            .readTimeout(60, TimeUnit.SECONDS)
                            .writeTimeout(60, TimeUnit.SECONDS)
                            .build();
                    client.newCall(request).enqueue(new Callback() {
                        @Override
                        public void onFailure(Call call, IOException e) {
                            Log.e("APIRequestError", "Error: " + e.getMessage());
                            toastMessage.postValue(new Pair<>("Lỗi khi gửi video: " + e.getMessage(), false));
                        }

                        @Override
                        public void onResponse(Call call, Response response) throws IOException {
                            if (response.isSuccessful()) {
                                String responseBody = response.body().string();
                                try {
                                    JSONObject jsonObject = new JSONObject(responseBody);
                                    JSONObject resultsMerged = jsonObject.optJSONObject("results_merged");
                                    if (resultsMerged != null) {
                                        JSONArray predictions = resultsMerged.optJSONArray("prediction");
                                        if (predictions != null && predictions.length() > 0) {
                                            JSONArray firstPrediction = predictions.optJSONArray(0);
                                            if (firstPrediction != null && firstPrediction.length() > 0) {
                                                JSONObject firstPredObject = firstPrediction.optJSONObject(0);
                                                if (firstPredObject != null && firstPredObject.has("gloss")) {
                                                    String gloss = firstPredObject.optString("gloss", "Không có dữ liệu");
                                                    displayPrediction(gloss);
                                                }
                                            }
                                        }
                                    }
                                } catch (JSONException e) {
                                    Log.e("JSONParseError", "Error: " + e.getMessage());
                                    toastMessage.postValue(new Pair<>("Lỗi parse JSON: " + e.getMessage(), false));
                                }
                            } else {
                                Log.e("APIResponseError", "Error: " + response.code());
                                toastMessage.postValue(new Pair<>("Lỗi từ server: " + response.code(), false));
                            }
                        }
                    });
                } else {
                    // Không có mạng: Dịch offline
                    VideoTranslationHandler translator = new VideoTranslationHandler(context, "model-final-new.tflite", "label400.txt");
                    translator.translateVideoAsync(context, videoUri)
                            .thenAccept(result -> {
                                Log.d("TranslationResult", "Offline Translation: " + result);
                                displayPrediction(result);
                                translator.close();
                            })
                            .exceptionally(ex -> {
                                Log.e("TranslationError", "Error: " + ex.getMessage());
                                toastMessage.postValue(new Pair<>("Lỗi dịch offline: " + ex.getMessage(), false));
                                translator.close();
                                return null;
                            });
                }
            } catch (Exception e) {
                Log.e("ProcessVideo", "Lỗi: " + e.getMessage());
                toastMessage.postValue(new Pair<>("Lỗi xử lý video: " + e.getMessage(), false));
            } finally {
                frameBuffer.clear();
            }
        });
    }

    // **Hiển thị dự đoán từ server hoặc offline**
    private void displayPrediction(String gloss) {
        toastMessage.postValue(new Pair<>("Dự đoán: " + gloss, true));
        tts.speak(gloss, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString());
        displayHandler.removeCallbacksAndMessages(null);
        displayHandler.postDelayed(() -> toastMessage.postValue(new Pair<>("", false)), 3000);
    }

    // **Dừng quay video**
    private void stopRecording() {
        if (recording != null) {
            recording.stop();
            recording = null;
        }
        timerHandler.removeCallbacks(timerRunnable);
        timerText.postValue("00:00");
        isRecording.postValue(false);
        captureButtonState.postValue(false);
        imageAnalysis.clearAnalyzer();
        isCropping = false;
        frameBuffer.clear();
    }

    // **Xử lý khi quay xong (chế độ thông thường)**
    private void handleRecordingFinalizationNormal(VideoRecordEvent.Finalize finalizeEvent, PreviewView previewView) {
        captureButtonState.postValue(false);
        timerHandler.removeCallbacks(timerRunnable);
        timerText.postValue("00:00");
        isRecording.postValue(false);

        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(previewView.getContext()), ImageProxy::close);

        try {
            translateVideo(previewView.getContext(), finalizeEvent.getOutputResults().getOutputUri());
        } catch (Exception e) {
            Log.e("TranslateError", "Error translating video: " + e.getMessage());
        }
    }

    // **Chuyển ImageProxy thành Bitmap**
    @OptIn(markerClass = ExperimentalGetImage.class)
    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        return CalculateUtils.yuvToRgb(Objects.requireNonNull(imageProxy.getImage()));
    }

    // **Xoay Bitmap**
    public Bitmap rotateBitmap(Bitmap bitmap, float degrees) {
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    // **Khởi tạo TextToSpeech**
    public void initializeTextToSpeech(Context context) {
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                Locale vietnamese = new Locale("vi");
                int result = tts.setLanguage(vietnamese);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTS", "Vietnamese not supported or missing data.");
                    Intent installIntent = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
                    context.startActivity(installIntent);
                } else {
                    Log.d("TTS", "Vietnamese locale set successfully.");
                }
            } else {
                Log.e("TTS", "Initialization failed.");
            }
        });
    }

    // **Dịch video (cho chế độ normal)**
    public void translateVideo(Context context, Uri videoUri) {
        if (isInternetAvailable(context)) {
            toastMessage.postValue(new Pair<>("Đang dịch trực tuyến, vui lòng đợi...", true));
            callTranslationApi(context, videoUri);
        } else {
            toastMessage.postValue(new Pair<>("Không có kết nối mạng, đang dịch ngoại tuyến ...", true));
            callTranslationModel(context, videoUri);
        }
    }

    // **Dịch ngoại tuyến (cho chế độ normal)**
    private void callTranslationModel(Context context, Uri videoUri) {
        try {
            VideoTranslationHandler translator = new VideoTranslationHandler(context, "model-final-new.tflite", "label400.txt");
            translator.translateVideoAsync(context, videoUri)
                    .thenAccept(result -> {
                        Log.d("TranslationResult", "Translation: " + result);
                        toastMessage.postValue(new Pair<>("Có thể ý bạn là từ sau? \n" + result, true));
                        tts.speak(result, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
                        translator.close();
                    })
                    .exceptionally(ex -> {
                        Log.e("TranslationError", "Error: " + ex.getMessage());
                        toastMessage.postValue(new Pair<>("Đã xảy ra lỗi khi dịch: \n " + ex.getMessage(), true));
                        translator.close();
                        return null;
                    });
        } catch (IOException e) {
            Log.e("LoadModelErr", "Error translating video: " + e.getMessage());
            toastMessage.postValue(new Pair<>("Đã xảy ra lỗi khi dịch: \n " + e.getMessage(), false));
        }
    }

    // **Kiểm tra kết nối mạng**
    private boolean isInternetAvailable(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork != null) {
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
                return capabilities != null &&
                        (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
            }
        }
        return false;
    }

    // **Gọi API dịch trực tuyến (cho chế độ normal)**
    private void callTranslationApi(Context context, Uri videoUri) {
        String apiUrl = "http://192.168.0.100:80/spoter";
        String angleThreshold = "110";
        String topK = "3";
        try {
            File videoFile = getFileFromUri(context, videoUri);
            MultipartBody.Builder multipartBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM);
            multipartBuilder.addFormDataPart("video_file", videoFile.getName(),
                    RequestBody.create(MediaType.parse("video/mp4"), videoFile));
            multipartBuilder.addFormDataPart("return_type", "json");
            multipartBuilder.addFormDataPart("angle_threshold", angleThreshold);
            multipartBuilder.addFormDataPart("top_k", topK);
            RequestBody requestBody = multipartBuilder.build();
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .build();
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .post(requestBody)
                    .build();
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    toastMessage.postValue(new Pair<>("Không thể kết nối tới máy chủ: " + e.getMessage(), true));
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful()) {
                        String responseBody = response.body().string();
                        try {
                            JSONObject jsonObject = new JSONObject(responseBody);
                            if (!jsonObject.has("results_merged") || jsonObject.isNull("results_merged")) {
                                toastMessage.postValue(new Pair<>("Lỗi: Không có 'results_merged' trong JSON", true));
                                return;
                            }
                            JSONObject resultsMerged = jsonObject.optJSONObject("results_merged");
                            if (resultsMerged == null) {
                                toastMessage.postValue(new Pair<>("Không phát hiện cử chỉ, vui lòng thử lại.", true));
                                return;
                            }
                            JSONArray predictions = resultsMerged.optJSONArray("prediction");
                            if (predictions == null || predictions.length() == 0) {
                                toastMessage.postValue(new Pair<>("Không phát hiện cử chỉ, vui lòng thử lại.", true));
                                return;
                            }
                            List<String> mainGlosses = new ArrayList<>();
                            for (int i = 0; i < predictions.length(); i++) {
                                JSONArray subPrediction = predictions.optJSONArray(i);
                                if (subPrediction != null && subPrediction.length() > 0) {
                                    JSONObject firstPredObject = subPrediction.optJSONObject(0);
                                    if (firstPredObject != null && firstPredObject.has("gloss")) {
                                        String gloss = firstPredObject.optString("gloss", "Không có dữ liệu");
                                        mainGlosses.add(gloss);
                                    }
                                }
                            }
                            if (!mainGlosses.isEmpty()) {
                                showGlossesOneByOneWithDelay(mainGlosses, 0);
                            } else {
                                toastMessage.postValue(new Pair<>("Không tìm thấy gloss nào", true));
                            }
                        } catch (JSONException e) {
                            toastMessage.postValue(new Pair<>("Lỗi parse JSON: " + e.getMessage(), true));
                        }
                    } else {
                        toastMessage.postValue(new Pair<>("Lỗi: " + response.code(), true));
                    }
                }
            });
        } catch (Exception e) {
            Log.e("APIRequestError", "Error preparing API request: " + e.getMessage());
            toastMessage.postValue(new Pair<>("Đã xảy ra lỗi khi gọi tới máy chủ: \n " + e.getMessage(), true));
        }
    }

    // **Lấy file từ Uri**
    private File getFileFromUri(Context context, Uri uri) throws IOException {
        File tempFile = new File(context.getCacheDir(), "temp_video.mp4");
        try (InputStream inputStream = context.getContentResolver().openInputStream(uri);
             OutputStream outputStream = new FileOutputStream(tempFile)) {
            if (inputStream == null) {
                throw new FileNotFoundException("Unable to open input stream from URI.");
            }
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }
        return tempFile;
    }

    // **Bật/tắt đèn flash**
    public void toggleFlash() {
        if (camera == null) {
            toastMessage.postValue(new Pair<>("Camera is not initialized.", false));
            return;
        }
        if (camera.getCameraInfo().hasFlashUnit()) {
            boolean isFlashOn = camera.getCameraInfo().getTorchState().getValue() == 1;
            camera.getCameraControl().enableTorch(!isFlashOn);
            flashEnabled.setValue(!isFlashOn);
        } else {
            toastMessage.postValue(new Pair<>("Flash is not available at this time.", false));
        }
    }

    // **Dọn dẹp khi ViewModel bị hủy**
    @Override
    protected void onCleared() {
        super.onCleared();
        service.shutdown();
        timerHandler.removeCallbacks(timerRunnable);
        displayHandler.removeCallbacksAndMessages(null);
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    // **Handler để hiển thị gloss từng cái một (cho chế độ normal)**
    private final Handler glossHandler = new Handler(Looper.getMainLooper());

    private void showGlossesOneByOneWithDelay(List<String> glosses, int index) {
        if (index >= glosses.size()) {
            toastMessage.postValue(new Pair<>("Đã hiển thị hết từ.", true));
            return;
        }
        String currentGloss = glosses.get(index);
        toastMessage.postValue(new Pair<>("Có thể ý bạn là từ sau? \n" + (index + 1) + ": " + currentGloss, true));
        tts.speak(currentGloss, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString());
        glossHandler.postDelayed(() -> showGlossesOneByOneWithDelay(glosses, index + 1), 3000);
    }
}
