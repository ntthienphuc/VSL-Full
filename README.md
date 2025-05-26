# 📱 Vietnamese Sign Language (VSL) Translator – Android (Java / CameraX)

This Android application turns a mobile device into a **real-time translator** for Vietnamese Sign Language (VSL), supporting two primary modes:

1. **Normal Mode** – records a full video and sends it once to the `/spoter` API.
2. **Realtime Mode** – segments video every second and sends chunks continuously to the `/spoter_segmented` API (with caching, delay warnings, and accuracy alerts).

Additional features:
- **Offline Translation** using a TFLite model if no internet is available.
- **VSL Dictionary** WebView integration.
- **Data Contribution Uploads** with labels via `/upload` API.
- **Configurable Server IP and Video Quality (HD/SD)** in Settings.
- **Vietnamese Text-to-Speech (TTS)** support.
- **Terms of Use and How-To Guide** (WebView).

---

## 🌳 Project Structure (Simplified)

```
app/
 ├─ java/com/translator/vsl/
 │   ├─ viewmodel/
 │   │   ├─ CameraViewModel.java       # CameraX + Realtime Queue
 │   │   └─ HomeViewModel.java
 │   ├─ view/
 │   │   ├─ *.Activity.java             # Camera, Upload, Settings, Help, Terms
 │   └─ handler/VideoTranslationHandler.java
 └─ res/                      # layout, drawable, etc.
```

---

## ⚙️ Setup & Build Instructions

> **Requirements**:
> - Android Studio Hedgehog (AGP ≥ 8.6)
> - JDK 11
> - Android SDK 34+, minSdk 29
> - Physical device with Camera2 and Android 10+

1. Clone the repository and open in Android Studio.
2. Sync Gradle (using `libs.versions.toml`).
3. Add model files to `assets/`:
   ```
   model-final-new.tflite
   label400.txt
   ```
4. Build & run on a physical device.

---

## 🏃 Quick Navigation

| Screen         | Actions |
|----------------|---------|
| **Splash**     | Fade-in logo → HomeActivity |
| **Home**       | Start camera, open dictionary, open settings |
| **Camera**     | Record in Normal/Realtime mode, switch camera, flash toggle |
| **Settings**   | Change API IP, select video quality (HD/SD), upload data |
| **Upload**     | Record video with label, upload to `/upload` endpoint |

> Permissions required: `CAMERA`, `RECORD_AUDIO`, `WRITE_EXTERNAL_STORAGE` (runtime-granted).

---

## 🪠 Architecture Overview

```mermaid
graph LR
    CameraActivity -->|bind| CameraViewModel
    UploadActivity -->|bind| Upload logic
    CameraViewModel --> CameraX video --> Queue((BlockingQueue))
    Queue -- Worker --> Sender[OkHttp\n/spoter_segmented]
    Sender -->|JSON| Parser --> LiveData --> UI

    CameraViewModel -- offline --> TFLite[VideoTranslationHandler]
    UploadActivity -- OkHttp --> API[/upload]
```

- **MVVM + LiveData**: reactive UI updates.
- **BlockingQueue + Executor**: 1 FPS real-time streaming.
- **Diagnostics**:
  - `CACHE_THRESHOLD = 30` triggers cache overload warning.
  - `SLOW_RESPONSE_LIMIT = 4` if each response > 1.5s.
  - `lowScoreCount >= 3` for TTS hints.
- **Text-to-Speech**: plays back gloss results in Vietnamese.

---

## 🔧 Configuration Options

| Preference Key     | Default             | Description |
|--------------------|---------------------|-------------|
| `api_ip`           | `14.224.194.242`    | IP address of your backend server |
| `video_quality`    | `HD`                | Switch between `HD` and `SD` mode |
| `angle_threshold`  | `140`               | Hand angle threshold for inference |
| `top_k`            | `3`                 | Number of top predictions returned |

---

## ⛰ Common Issues & Solutions

| Symptom                        | Cause             | Solution |
|--------------------------------|-------------------|----------|
| **"Cache Overload"**          | Slow uploads      | Switch to SD or disable realtime |
| **"Network too slow"**        | Ping > 1.5s       | Switch to Wi-Fi or retry later |
| **"Increase resolution"**     | Score < 0.99      | Get closer, improve lighting |
| **No voice feedback (TTS)**   | Missing Vietnamese voice pack | Enable in Google TTS settings |
