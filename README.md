# PMDDvid

Native Android video camera in the visual style of **PMDDcam 0.2.0**, with a local video converter as a secondary tool. By Kolja Werner Schumann (KoSch), developed with ChatGPT.

The first preview is under verification. An installable APK is attached to a successful [Android build](https://github.com/chekento/PMDDvid/actions).

- Fullscreen camera, front/rear switching, tap focus and pinch zoom.
- Live PMDD processing is applied to both the preview and the recorded video through the same CameraX surface processor.
- Continuous depth shading and detail enhancement from PMDDcam 0.2.0; no animated wave overlays or painted depth-edge shadows.
- 60 video-adapted looks, adjustable depth, atmospheric separation and detail.
- Original/PMDD switch, audio selection, torch, grid, pause/resume and resolution selection.
- Bundled MiDaS and SSD models run locally on CPU in a single bounded analysis worker. The recorder does not wait for every neural inference.
- Converter: PMDD rendering, depth-map video, or full-width stereoscopic side-by-side. Each decoded input frame is analyzed, using its source timestamp; no requested downscale or frame-rate reduction.
- Local collection, playback, sharing, file export and gallery save. Imported originals remain untouched.

## Formats and limits

Recording and conversion output **MPEG-4 / H.264 in MP4**, SDR, 8-bit. AVI, MPEG, MOV, MP4 and WebM import depends on the container and codecs that the device can decode. No universal AVI/MPEG encoder is bundled. HDR input is tone-mapped to SDR. Codec/size errors are surfaced instead of silently lowering converter output resolution.

Normal MP4 contains a fixed perceptual depth treatment, not a full volumetric scene or head tracking. SBS provides two estimated views for a compatible viewer. Live depth updates depend on device speed; the camera and video encoder run independently. Source frame preservation applies to offline conversion, not a guarantee that every physical camera exposure is delivered under overload.

Recordings are initially stored in private app storage. Save or share important clips; uninstalling removes app-private videos. Leaving the app stops recording. Microphone permission is optional. There is no INTERNET permission or cloud upload.

## Build

JDK 17, Android SDK 35, Build Tools 35.0.0. Models are fetched and SHA-256 verified at build time, then work offline.

```sh
bash scripts/fetch-model.sh
./gradlew testDebugUnitTest lintDebug assembleRelease
```

The preview signing key is intentionally public for reproducible preview upgrades. Use a private identity for production. Third-party model and runtime notices are in `app/src/main/assets/THIRD_PARTY.txt`.
