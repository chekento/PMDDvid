# Änderungen

## 0.1.4 · Live Depth Freshness / No-Trail Geometry

- Schlieren aus dem realen 0.1.3-Testclip erneut analysiert. Hauptursache war nicht mehr RGB-Frame-Blending, sondern **geometrische Reprojektion mit einer bereits veralteten Tiefenkarte** während Kamerabewegung.
- `DepthFrame` speichert jetzt getrennt den **Quellzeitpunkt des analysierten Kameraframes** und den Zeitpunkt, an dem die KI-Inferenz fertig wurde. Eine gerade fertig berechnete Depth gilt damit nicht mehr fälschlich als bildaktuell.
- Live-Rendering trennt nun **Depth-Vertrauen** von **Geometrie-Vertrauen**: Relief/Ton darf kurz weich auslaufen, räumliche Pixelverschiebung/Parallaxe wird bei veralteter Depth sehr schnell auf null gesetzt.
- Geometrische Live-Parallaxe ist nur noch innerhalb eines sehr kurzen Freshness-Fensters aktiv. Bei normaler lokaler MiDaS-Inferenz wird dadurch eine veraltete Depth-Kante nicht mehr auf einen neueren RGB-Frame verschoben.
- RGB/Depth-Guide-Abgleich deutlich verschärft; Bereiche mit bereits kleiner Luma-Abweichung verlieren Depth-Vertrauen früher.
- Stereo-/Offline-Konverter bleibt voll geometrisch, weil dort RGB und Depth synchron für denselben Quellframe berechnet werden.
- Gemeinsamer Renderer erhält explizite Parameter für `depthTrust` und `geometryTrust`, statt intern inkompatible Live-/Medienzeitstempel zu vergleichen.
- PMDD-Aufnahme wird sofort freigegeben; die lokale Depth ergänzt sich asynchron. Ein vorübergehender KI-Ausfall blockiert die Aufnahme nicht mehr.
- Release **v0.1.4** erfolgreich durch Build, Unit-Tests, Android Lint und Android-35-Gerätetest validiert; APK und SHA-256-Prüfsumme veröffentlicht.

## 0.1.3 · Clean Motion / Deep Z

- Tiefen-Historie grundlegend überarbeitet: **kein rekursives Zurückschreiben geglätteter Depth-Frames** mehr. Dadurch können alte Tiefenwerte nicht über mehrere Frames zu sichtbaren Schleppen anwachsen.
- Neuer bewegungs- und kantenabhängiger **Anti-Trail-Filter** mit History-Clamping auf die aktuelle lokale Tiefenumgebung, Scene-Cut-Reset sowie harter Verwerfung bei Disocclusion, großen Depth-Sprüngen und starken Luma-Kanten.
- **Single-View-Parallaxe** aus dem aktuellen RGB-Frame ergänzt. Sie erhöht den räumlichen Eindruck, ohne vorherige RGB-Frames zu mischen.
- Reprojektion wird an Farb- und Tiefenkanten automatisch zurückgenommen; neuer **Kantenschutz** verhindert Überziehen an Hecken, Gebäudekanten, Fahrzeugen, Schildern und anderen Freistellkanten.
- Signierter Z-Raum auf bis zu **±6 Z**, Standard auf **48 weiche Layer**, Maximum auf **64 Layer** und stärkere Ebenentrennung erweitert.
- Nichtlineare Z-Spreizung verstärkt besonders den mittleren Tiefenraum, ohne Vorder-/Hintergrund-Endpunkte abzuschneiden.
- Relief, Kontaktwirkung, lokale Kontraststaffelung, Atmosphäre und Tiefenunschärfe neu auf den erweiterten Z-Raum abgestimmt.
- Neue Regler: **Single-View-Parallaxe**, **Kantenschutz**, **Schlierenunterdrückung**; Ebenentrennung bis 150 %.
- Neue Presets **Deep PMDD** und **Clean Depth** zusätzlich zur überarbeiteten Vivid-Standardvorgabe.
- Live-Aufnahme und Video-Konverter verwenden dieselben Anti-Trail- und Tiefenparameter.
- Neue Tests prüfen maximale Z-Werte, neue Layer-Grenzen und dass bewegte Tiefenkanten keine Historien-Schleppe aufbauen.
- Release **v0.1.3** erfolgreich durch Build, Unit-Tests, Android Lint und Android-35-Gerätetest validiert; APK und SHA-256-Prüfsumme veröffentlicht.

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
