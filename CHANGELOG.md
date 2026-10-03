# Änderungen

## 0.1.2 · Maximum Z Depth

- Neuer signierter PMDD-Tiefenraum: Vordergrund **Z < 0**, Fokusebene **Z = 0**, Hintergrund **Z > 0**.
- Standardtiefe auf **±4 Z-Einheiten** und Ebenentrennung auf Maximum gesetzt.
- **32 weiche Tiefenlayer** als Standard; Layer wirken auf Relief, lokale Trennung, Atmosphäre, Bokeh und Stereo-Parallaxe, ohne harte Tiefenkonturen in RGB zu zeichnen.
- Neue Regler für **3D-Z-Tiefe**, **Layeranzahl** und **Fokusebene Z = 0**.
- Signed-Z- und Maximal-Layer-Verhalten durch Unit-Tests abgesichert.
- Release **v0.1.2** erfolgreich durch Build- und Android-Gerätetest validiert; APK und SHA-256-Prüfsumme veröffentlicht.


## 0.1.1 · Adaptive Live Depth

- Live-Tiefenanalyse dynamisch an Inferenzdauer und Gerätezustand angepasst.
- Thermal-Backoff reduziert die KI-Arbeitslast bei hoher Gerätetemperatur, ohne den initialen Tiefen-Bootstrap zu blockieren.
- Kamera-Rendering bleibt von langsamer Tiefenanalyse entkoppelt; höchstens eine KI-Analyse läuft gleichzeitig.
- Build-, Unit- und Android-Gerätetests für den adaptiven Pfad erfolgreich abgeschlossen.

## 0.1.0 · Android Preview

- PMDD Vivid aus PMDDcam 0.4.0 als neuer Standard: offene Schatten, geschützte Lichter, weniger Dunst und begrenzte Schärfungssäume; Tonwertberechnung ausschließlich aus dem aktuellen Frame.
- Native Videokamera als Hauptfunktion; Video-Konverter als Untertool.
- Gemeinsame PMDD-GPU-Verarbeitung für Vorschau, Kameraaufnahme und Konversion.
- Offline-Tiefen- und Objekterkennung mit wiederverwendeten CPU-Modellen.
- Kontinuierliche Tiefe ohne eingebrannte Tiefenkonturen, Wellen oder RGB-Nachziehen.
- Ruhende Motive bekommen bei gleichwertigen Vergleichen keine künstliche Drift.
- 61 Video-Looks im Stil von PMDDcam; einstellbarer Tiefenlook und Originalumschalter.
- Aufnahme mit Ton, Pause/Fortsetzen, Auflösungsauswahl, Fokus, Zoom, Dauerlicht und Raster.
- MP4-Konversion, Tiefenkarten-Video und Stereo-Side-by-Side; keine gewollte Frame- oder Auflösungsreduktion.
- Private Videosammlung, Wiedergabe, Teilen, Datei-/Galerieexport und Löschen.
- Komponententests, Lint und Android-Gerätetests mit Video- und Bildnachweisen.

Bekannte Grenzen: PMDD ist monokulare Tiefengestaltung; kein Kopftracking in normalen MP4-Dateien, keine echte Mehrkamera-Rekonstruktion. Live-Tiefenrate ist geräteabhängig. MP4-Ausgabe in SDR/8 Bit; andere Eingabeformate hängen vom Decoder ab.
