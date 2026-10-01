# Architektur

## Live-Aufnahme

CameraX liefert einen `SurfaceTexture`-Stream. Ein eigener GL-Thread verarbeitet ihn mit `PmddGl` und schreibt in die von CameraX angebotenen Vorschau- und Recorder-Oberflächen. Für **jede** Oberfläche wird `SurfaceOutput.updateTransformMatrix` verwendet. Diese Transformation berücksichtigt Rotation, Crop und die gewünschte Spiegelung; Videoaufnahmen sind ausdrücklich nicht gespiegelt. Zeitstempel stammen aus dem Kamera-Stream und werden mit `eglPresentationTimeANDROID` weitergereicht.

Die Tiefenanalyse tastet das gleiche RGB-Eingangsbild über eine bekannte Matrix ab. Zu jeder Tiefenkarte wird diese Matrix gespeichert. Im Shader bildet `inverse(AnalyseMatrix) × AusgabeMatrix` die Ausgabe auf die Tiefenkarte ab. Ein Frontkamera-Spiegelbild in der Vorschau kann so korrekt zur ungespiegelten Aufnahmedatei gehören.

Ein einzelner CPU-Worker hält die ONNX-Sessions offen. Es gibt höchstens eine Analyse in Arbeit und keine anwachsende Frame-Warteschlange. Live wird frühestens nach 350 ms erneut analysiert; langsame Modelle reduzieren die Tiefenrate, nicht absichtlich die Encoder-Bildrate. 256×256-Pixel-Leseback und CPU-Inferenz brauchen trotzdem Zeit und Energie.

Die GL-Oberflächen werden erst freigegeben, wenn CameraX ihre Nutzung beendet hat. Fehler stoppen gegebenenfalls die Aufnahme und werden angezeigt. Wenn die KI nicht verfügbar ist, bleibt die Originalaufnahme erreichbar. Ein Grafikfehler benötigt einen erneuten Kamerastart; es gibt keine endlose Wiederholung auf einem verlorenen Gerät.

## Tiefe und Artefaktbegrenzung

- MiDaS v2.1 Small, festes 256×256-RGB-Eingangsbild, kontinuierliche inverse Tiefe. Der hier verwendete offizielle **ONNX-Export** erwartet RGB 0…1; dessen Referenzpfad `tf/run_onnx.py` normalisiert nicht nochmals mit ImageNet-Mittelwerten. Das ist vom PyTorch-Pfad zu unterscheiden.
- SSD MobileNet V1 erkennt Bereiche, keine Segmentierungsmasken. Ähnliche Tiefenwerte innerhalb eines erkannten Bereichs werden nur um maximal 12 % zum Objektanker hin korrigiert.
- Die Tiefenhistorie berücksichtigt eine grobe Translation. Fotometrisch unpassende oder deutlich abweichende Werte bekommen kein starkes historisches Gewicht. Bei gleichwertigen Vergleichen gewinnt die kleinste Bewegung; dies verhindert eine erfundene Drift bei stillen Motiven.
- Die aktuelle Bildhelligkeit wird mit der zur Analyse gespeicherten Helligkeit verglichen. Bei Abweichung und mit zunehmendem Alter nimmt der Tiefeneinfluss ab. Dies ersetzt keinen vollständigen optischen Fluss und ist kein präziser Objekttracker.
- Die natürliche Darstellung bearbeitet lokale Details, Kontraststaffelung, Atmosphäre und dezente Unschärfe. Sie quantisiert die Tiefe nicht in sichtbare Stufen und zeichnet keine Tiefennormalen als künstliche Schatten. Keine RGB-Historie, keine wandernden Wellen und kein Verschieben des normalen Videobildes.
- Die geschätzten Stereoansichten haben begrenzte horizontale Disparität. An großen Tiefensprüngen wird die Verschiebung reduziert. Keine vollständige Disocclusion-Rekonstruktion, keine metrisch vermessene Szene.

## Vivid aus PMDDcam 0.4.0

Der Videopfad übernimmt Schattenaufhellung, Highlight-Rolloff, Vibrance, geschützte Tonwertendpunkte und Chroma-Kompression aus dem Vivid-Fotolook. Der lokale Helligkeitsgrund wird auf der GPU kantenbewusst aus dem aktuellen RGB-Frame geschätzt. Anders als beim Foto gibt es keine pro Frame neu skalierten Histogramm-Endpunkte und keine verzögerten Helligkeitsfelder aus der KI: Das vermeidet eine zusätzliche Quelle für Pumpen. Daher ist der Look eine Videoanpassung und keine pixelidentische Kopie des Foto-Renderers. Die Vivid-Vorgabe nutzt 125 % Tiefe mit weniger Dunst und geringerer Relief-/Schärfungsstärke.

## Konversion

Media3 Transformer decodiert das Quellmedium, verarbeitet alle ausgegebenen Frames und encodiert MP4. Der `BaseGlShaderProgram` hat einen einzelnen Ausgabepuffer; langsame Inferenz erzeugt Rückstau, keinen expliziten Frame-Drop-Effekt. Die GL-Analyse und -Ausgabe verwenden denselben Shader wie Live. Media3-SDR-Texturen liegen linear vor; der Renderer wechselt für die fotografische Bearbeitung näherungsweise über Gamma 2,2 in den Wahrnehmungsraum und zurück.

Es gibt kein Resize und keinen Bildratenfilter. Kamera-Metadaten werden vom Decoder berücksichtigt. Der Encoder darf die Auflösung nicht automatisch heruntersetzen. HDR wird ausdrücklich nach SDR tone-gemappt. Media3 erhält eine Audioausgabe in AAC; kompatible Audiospuren können durchgereicht werden. Wiedercodierung kann Quantisierung, Farbumwandlung und Encoderabweichungen verursachen.

Die Quell-Datei wird nur gelesen. Eine Ausgabe liegt bis zum erfolgreichen Abschluss im privaten Zwischenverzeichnis; anschließend wird sie in die Sammlung verschoben. Abbruch entfernt die betreffende Teildatei. Ein Prozessabsturz kann verwaiste Zwischendateien zurücklassen; diese werden nicht als fertige Aufnahmen angeboten.

## Quellen

- [CameraX SurfaceProcessor](https://developer.android.com/reference/androidx/camera/core/SurfaceProcessor)
- [CameraX SurfaceOutput](https://developer.android.com/reference/androidx/camera/core/SurfaceOutput)
- [Media3 Transformer](https://developer.android.com/media/media3/transformer)
- [MiDaS ONNX-Referenz](https://github.com/isl-org/MiDaS/blob/master/tf/run_onnx.py)
- [SSD MobileNet V1-12](https://github.com/onnx/models/tree/main/validated/vision/object_detection_segmentation/ssd-mobilenetv1)
