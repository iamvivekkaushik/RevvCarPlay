package com.shilapi.xcertplay.embed

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * The view RevvCarPlay places inside a host app's SurfaceView through SurfaceControlViewHost:
 * the decoded CarPlay picture, a status line while there is none, and touch straight to the
 * iPhone. The host sees only pixels; this view runs in RevvCarPlay's process.
 */
@SuppressLint("ViewConstructor")
internal class EmbeddedCarPlayView(context: Context) : FrameLayout(context) {
    private val video = TextureView(context)
    private val message = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        gravity = Gravity.CENTER
        val pad = (24 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
    }
    private var surface: Surface? = null
    private val statusListener: (EmbeddedCarPlay.Status) -> Unit = ::show

    init {
        setBackgroundColor(Color.BLACK)
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        video.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                val created = Surface(texture)
                surface = created
                EmbeddedCarPlay.attachSurface(created)
                layoutVideo(width, height)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = layoutVideo(width, height)
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                surface?.let { EmbeddedCarPlay.clearSurface(it); it.release() }
                surface = null
                return true
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        EmbeddedCarPlay.addListener(statusListener)
    }

    override fun onDetachedFromWindow() {
        EmbeddedCarPlay.removeListener(statusListener)
        super.onDetachedFromWindow()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!EmbeddedCarPlay.hostForwardsTouch) EmbeddedCarPlay.sendTouch(event, width, height)
        return true
    }

    private fun show(status: EmbeddedCarPlay.Status) {
        message.visibility = if (status.videoActive) View.GONE else View.VISIBLE
        message.text = status.detail
        if (width > 0 && height > 0) layoutVideo(width, height)
    }

    /** Keeps the CarPlay canvas's shape when the host view's differs (letterbox, never stretch). */
    private fun layoutVideo(width: Int, height: Int) {
        val content = EmbeddedCarPlay.videoLayout(width, height) ?: return
        video.setTransform(Matrix().apply {
            setScale(content.width / width, content.height / height)
            postTranslate(content.left, content.top)
        })
    }
}
