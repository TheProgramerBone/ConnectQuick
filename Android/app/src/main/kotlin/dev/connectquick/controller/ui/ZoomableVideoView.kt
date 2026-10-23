package dev.connectquick.controller.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import dev.connectquick.controller.input.Viewport
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The PC's screen, drawn by libwebrtc's EglRenderer into a TextureView. Unlike a SurfaceView, a
 * TextureView honours a transform matrix, which is what makes pinch-zoom and panning possible.
 * The owner sizes this view to the video's aspect ratio (see [onVideoSize]), so the frame fills it
 * exactly; [viewport] holds the zoom/pan and is applied with [applyViewport].
 */
class ZoomableVideoView(context: Context, eglContext: EglBase.Context) : TextureView(context), TextureView.SurfaceTextureListener, VideoSink {
    private val renderer = EglRenderer("cq-screen")
    private val main = Handler(Looper.getMainLooper())
    private var lastW = 0
    private var lastH = 0
    private var released = false

    val viewport = Viewport(maxScale = 5f)

    /** Called on the main thread when the incoming video changes size (and on the first frame). */
    var onVideoSize: ((Int, Int) -> Unit)? = null

    val videoSize: Pair<Int, Int>? get() = if (lastW > 0 && lastH > 0) lastW to lastH else null

    init {
        renderer.init(eglContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        surfaceTextureListener = this
        isOpaque = true
    }

    override fun onFrame(frame: VideoFrame) {
        val w = frame.rotatedWidth
        val h = frame.rotatedHeight
        if (w != lastW || h != lastH) {
            lastW = w; lastH = h
            main.post { onVideoSize?.invoke(w, h) }
        }
        renderer.onFrame(frame)
    }

    fun applyViewport() {
        viewport.setSize(width.toFloat(), height.toFloat())
        setTransform(Matrix().apply {
            setScale(viewport.scale, viewport.scale)
            postTranslate(viewport.offsetX, viewport.offsetY)
        })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyViewport()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (!released) renderer.createEglSurface(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // The EGL surface must be gone before the texture is released, or the render thread draws into a dead surface.
        val done = CountDownLatch(1)
        renderer.releaseEglSurface { done.countDown() }
        done.await(2, TimeUnit.SECONDS)
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    fun release() {
        if (released) return
        released = true
        renderer.release()
    }
}
