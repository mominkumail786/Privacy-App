package com.example.privacyguard.bridge

import android.content.Context
import android.view.View
import androidx.camera.view.PreviewView
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

/** Holds the camera picture view that Flutter currently shows, so the camera session can draw into it. */
object PreviewRegistry {
    @Volatile
    var current: PreviewView? = null
}

/**
 * Creates the native camera picture for Flutter's `AndroidView(viewType: "privacyguard/camera_preview")`.
 *
 * COMPATIBLE mode makes PreviewView use a TextureView. A SurfaceView does not work reliably inside a Flutter
 * platform view, a TextureView does. (Not yet tried on a real phone.)
 */
class CameraPreviewFactory : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val view = PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        PreviewRegistry.current = view
        return object : PlatformView {
            override fun getView(): View = view

            override fun dispose() {
                if (PreviewRegistry.current === view) PreviewRegistry.current = null
            }
        }
    }
}
