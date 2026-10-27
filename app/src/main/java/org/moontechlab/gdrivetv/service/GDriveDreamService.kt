package org.moontechlab.gdrivetv.service

import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import coil.load
import coil.transform.Transformation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.moontechlab.gdrivetv.data.api.GoogleDriveApi
import org.moontechlab.gdrivetv.data.auth.GoogleOAuthManager
import org.moontechlab.gdrivetv.data.model.DriveItem
import org.moontechlab.gdrivetv.data.store.PreferenceStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GDriveDreamService : DreamService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private lateinit var prefs: PreferenceStore
    private lateinit var api: GoogleDriveApi
    private val handler = Handler(Looper.getMainLooper())

    private var imageViews = ArrayList<ImageView>(2)
    private var activeIndex = 0
    private var photoList = listOf<DriveItem>()
    private var currentPhotoIndex = 0

    private lateinit var timeView: TextView
    private lateinit var dateView: TextView

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false // Any button press on TV remote wakes TV up
        isFullscreen = true

        prefs = PreferenceStore(applicationContext)
        val authManager = GoogleOAuthManager(prefs)
        api = GoogleDriveApi(authManager)

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
        }

        // Two ImageViews for smooth cross-fading
        for (i in 0..1) {
            val iv = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                alpha = if (i == 0) 1f else 0f
            }
            imageViews.add(iv)
            root.addView(iv)
        }

        // Time and Date Overlay
        val overlay = FrameLayout(this).apply {
            setPadding(60, 40, 60, 40)
        }
        timeView = TextView(this).apply {
            textSize = 36f
            setTextColor(0xFFFFFFFF.toInt())
            setShadowLayer(8f, 0f, 2f, 0xCC000000.toInt())
        }
        dateView = TextView(this).apply {
            textSize = 18f
            setTextColor(0xCCFFFFFF.toInt())
            setShadowLayer(6f, 0f, 2f, 0xCC000000.toInt())
        }

        val textLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(timeView)
            addView(dateView)
        }
        overlay.addView(textLayout)
        root.addView(overlay)

        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        updateClock()
        loadPhotosAndStart()
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        handler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
    }

    private fun updateClock() {
        val now = Date()
        timeView.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        dateView.text = SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(now)
        handler.postDelayed({ updateClock() }, 10_000L)
    }

    private fun loadPhotosAndStart() {
        serviceScope.launch {
            val folderId = prefs.screensaverFolderId.ifBlank { "root" }
            var images = api.collectImages(folderId, maxCount = 2000)
            if (images.isEmpty() && folderId != "root") {
                images = api.collectImages("root", maxCount = 2000)
            }

            val targetMonth = prefs.screensaverMonth
            val targetDay = prefs.screensaverDay
            if (targetMonth != null) {
                val filtered = images.filter { item ->
                    val d = item.dateInfo
                    if (d != null) {
                        d.month == targetMonth && (targetDay == null || d.day == targetDay)
                    } else false
                }
                if (filtered.isNotEmpty()) {
                    images = filtered
                }
            }

            if (images.isNotEmpty()) {
                photoList = images.shuffled()
                showNextPhoto()
            }
        }
    }

    private fun showNextPhoto() {
        if (photoList.isEmpty()) return

        val photo = photoList[currentPhotoIndex]
        currentPhotoIndex = (currentPhotoIndex + 1) % photoList.size

        val nextIndex = (activeIndex + 1) % 2
        val currentIv = imageViews[activeIndex]
        val nextIv = imageViews[nextIndex]

        val url = when {
            photo.thumbnailLink?.contains("/pw/") == true -> {
                val base = photo.thumbnailLink.substringBefore("=")
                "$base=s2560"
            }
            photo.thumbnailLink?.contains("=s220") == true -> {
                photo.thumbnailLink.replace("=s220", "=s2560")
            }
            else -> photo.thumbnailLink ?: api.getHighResMediaUrl(photo.id)
        }

        nextIv.load(url) {
            crossfade(true)
            listener(
                onSuccess = { _, _ ->
                    // Crossfade
                    nextIv.animate().alpha(1f).setDuration(1200).start()
                    currentIv.animate().alpha(0f).setDuration(1200).start()
                    activeIndex = nextIndex

                    // Maintain full image display without cropping
                    nextIv.scaleX = 1.0f
                    nextIv.scaleY = 1.0f

                    val intervalMs = (prefs.slideshowIntervalSeconds * 1000L).coerceAtLeast(3000L)
                    handler.postDelayed({ showNextPhoto() }, intervalMs)
                },
                onError = { _, _ ->
                    // On error, skip to next quickly
                    handler.postDelayed({ showNextPhoto() }, 1500L)
                }
            )
        }
    }
}
