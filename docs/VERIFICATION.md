# Prüfung der Android Preview

Status: Durchführung läuft. Ein erfolgreicher Build allein ist keine Gerätefreigabe.

Die CI trennt Komponententests, Android Lint, APK-Build und Instrumentierung. Die Instrumentierung läuft auf einem Android-35-Emulator (x86-64, Pixel-6-Profil, emulierte Front- und Rückkamera) im Flugmodus. Protokolle, Screenshots und erzeugte Videos werden als Build-Artefakte archiviert.

## Prüffälle

1. Endliche, nicht konstante MiDaS-Tiefe und mindestens zwei erkannte Objekte im lokalen Referenzfoto; beide mitgelieferten Modelle starten offline.
2. Echter GLES-Shader: asymmetrische Farbflächen behalten ihre Orientierung. Eine harte Tiefengrenze erzeugt auf einer einfarbigen Oberfläche keine künstliche Konturlinie.
3. Konverter: synthetisches Video mit 90°-Rotationsmetadaten, asymmetrischen Farben, variablen Zeitstempeln und Ton. Framezahl, relative Zeitstempel, Bildabmessungen, Orientierung und Audiospur werden verglichen. Die Quelldatei bleibt bytegleich.
4. Abbruch entfernt Teildateien; derselbe Konverter kann danach einen Stereoexport mit zwei vollen Ansichten abschließen.
5. Kamera: Live-KI bereit, echte Aufnahme über den SurfaceProcessor, Mikrofon, Pause/Fortsetzen, Abschluss, abspielbare Hochformatdatei und Videosammlung.
6. Komponententests: stabile ruhende Tiefe, Zurückweisen falscher Historie nach einem Szenenwechsel, ungültige Eingaben, Parametergrenzen und 61 eindeutige Stile.

## Auf einem physischen Telefon zusätzlich prüfen

Die emulierte Kamera, der Software-Grafiktreiber und Android-Encoder decken keine vollständige Geräteflotte ab. Für eine Produktionsfreigabe fehlen insbesondere:

- Front-/Rückkamera, Hoch-/Querformat und Orientierungssperre auf mehreren Herstellern;
- lange Aufnahmen mit Ton, Hitze, wenig Speicher und eingehenden Anrufen;
- UHD/hohe Datenraten, herstellerspezifische Encoder, HEVC/HDR und nicht unterstützte Eingaben;
- Motivqualität bei Haaren, dünnen Ästen, Glas, schnellen Schwenks und verdeckten Objekten;
- subjektive PMDD-Wirkung mit den Original-Beispielvideos des Nutzers.

Es wird keine generelle Schlierenfreiheit oder vollständige Objektsegmentierung behauptet. Die automatisierten Tests prüfen konkrete Regressionen und technische Erhaltungseigenschaften.
