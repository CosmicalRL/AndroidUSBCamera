# Browser sources

The demo app now includes an interactive browser source layer over the UVC preview.

## Current workflow

1. Tap **WEB** in the top toolbar.
2. Enter a web URL.
3. The page appears over the camera preview.
4. Drag the source to reposition it.
5. Drag the `↘` handle to resize it.
6. Tap the source to show its controls.
7. Use `↻` to rotate by 90 degrees.
8. Use **Crop** and drag the page inside the cropped frame.
9. Use **URL** to change the page or `✕` to remove the source.

## Important encoder note

The browser source is currently a UI-layer source. The existing `CameraUVC`/`RenderManager` recording path encodes the OpenGL camera render texture, not the Android view hierarchy. Therefore the browser source is visible in the preview but is **not yet burned into recorded MP4/replay-buffer output**.

`BrowserSourceOverlay.getTransformState()` provides the current transform/crop state for the next compositor step. Full recording/replay compositing should be implemented in the OpenGL render pipeline so the same composited texture is sent to both `EncodeRender` and the replay-buffer H.264 callback.

This keeps the preview implementation independent from the encoder and avoids falsely treating a WebView overlay as part of the encoded camera frames.
