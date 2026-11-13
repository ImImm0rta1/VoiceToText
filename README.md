# Offline Dictation (whisper.cpp, English, no INTERNET permission)

1. Android Studio -> open this folder. SDK Manager -> install NDK (Side by side) and CMake 3.22.1.
2. Put whisper.cpp next to the JNI file:
   git clone https://github.com/ggml-org/whisper.cpp app/src/main/cpp/whisper.cpp
3. Build & run on an arm64 phone (emulators are very slow for whisper).
4. In the app: Models -> tap a download chip (opens your browser) -> Import the ggml-*.bin file.
   Multiple models = pick between them in the Models dialog. base.en is auto-selected if present.

## Two builds
- offline: no INTERNET permission. Import model files you downloaded yourself.
- online ("Offline Dictation (DL)"): has INTERNET only so it can download models from Hugging Face inside the app. Transcription is still fully on-device.
GitHub Actions builds both; download them from the run's Artifacts.
