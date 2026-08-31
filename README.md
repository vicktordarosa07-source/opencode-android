# Opencode Desktop Android — APK WebView

Transforma o **Opencode Desktop** (Go + Web) em APK nativo Android.

> **Arquitetura Senior Desktop:** `WebView` + `opencode serve` em `127.0.0.1:4096` + `GOOS=android arm64`

## Como funciona
1. Cross-compile `opencode` para Android (`GOOS=android GOARCH=arm64`)
2. APK extrai binário de `assets/opencode` para `filesDir` (`chmod 700`)
3. `MainActivity` inicia `opencode serve --port 4096 --hostname 127.0.0.1` via `ProcessBuilder`
4. `WebView` carrega `http://127.0.0.1:4096` — Desktop completo como app nativo
5. Sem PTY, sem JitPack, sem NDK JNI — build 100% limpo

## Quick Start
```bash
./scripts/build-opencode-android.sh
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Estrutura
```
OpencodeAndroid/
├── scripts/build-opencode-android.sh
├── app/src/main/java/com/opencode/desktop/
│   ├── MainActivity.kt (WebView + Server)
│   ├── BinaryManager.kt (extract + chmod)
│   └── OpencodeApplication.kt
├── app/src/main/AndroidManifest.xml (usesCleartextTraffic)
└── .github/workflows/build.yml (Build Desktop APK)
```

## Instalar via GitHub
Após push, baixe em **Actions > Artifacts > opencode-desktop-apk**

Link: https://github.com/vicktordarosa07-source/opencode-android/actions

