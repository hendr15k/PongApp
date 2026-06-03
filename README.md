# Pong

Klassisches Pong-Spiel als native Android-App (Kotlin, kein Compose, reine Canvas-Renderung).

## Features

- Spieler (blau) auf der linken Seite, KI (rot) auf der rechten Seite
- Ball (grün) prallt mit dynamischem Winkel abhängig vom Treffpunkt am Paddel ab
- Geschwindigkeit steigt pro Paddel-Treffer leicht an
- Touch-Steuerung: Spieler-Paddel folgt der Y-Position des Fingers
- Punktezähler, Erste/r auf 7 Punkte gewinnt, Neustart per Tap
- Fullscreen-Activity, Hochformat, Bildschirm bleibt an

## Anforderungen

- Android 5.0 (API 21) oder höher
- Ziel-SDK: 34

## Build

```bash
./gradlew :app:assembleDebug
```

Die APK landet unter `app/build/outputs/apk/debug/app-debug.apk`.

## Installation

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Projektstruktur

```
app/src/main/
├── AndroidManifest.xml
├── java/com/example/pong/
│   ├── MainActivity.kt   # Fullscreen-Activity
│   ├── PongView.kt       # Game-Loop, Rendering, Eingabe
│   ├── Paddle.kt         # Paddel-Geometrie
│   └── Ball.kt           # Ball-Zustand
└── res/                  # Farben, Strings, Theme, App-Icon
```
