package com.alhorrtv.app

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Shown right after login (and after an automatic login). Mirrors LoadingScreen.tsx with
 * the breathing logo, subtitle, neon progress track, and 3 live section sync status cards.
 */
class LoadingActivity : FullscreenActivity(), SyncManager.Listener {
    private val executor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var opened = false
    private lateinit var content: View
    private lateinit var phaseTitle: TextView
    private val statusViews = HashMap<String, TextView>()
    private val counts = ConcurrentHashMap<String, Int>()
    private val doneKinds = ConcurrentHashMap<String, Boolean>()
    private val numFormat = NumberFormat.getNumberInstance(Locale.US)

    override val restoresFocus: Boolean get() = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val server = intent.getStringExtra("server").orEmpty()
        val user = intent.getStringExtra("username").orEmpty()
        val pass = intent.getStringExtra("password").orEmpty()
        if (server.isBlank() || user.isBlank()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        buildUi()
        // Fade the screen in only if the wait is not instant, so a fully cached library goes straight to Home with no flash.
        content.alpha = 0f
        ui.postDelayed({ if (!opened) content.animate().alpha(1f).setDuration(250).start() }, 350)

        SyncManager.add(this)
        SyncManager.ensureAll(this, server, user, pass)
        val repo = XtreamRepository(this, server, user, pass)
        val started = SystemClock.uptimeMillis()
        executor.execute {
            while (!isFinishing && !isDestroyed) {
                val localCounts = HashMap<String, Int>()
                val ready = Kinds.CONTENT.all { kind ->
                    val dbCount = try { repo.count(kind) } catch (_: Exception) { 0 }
                    val synced = maxOf(SyncManager.loaded(kind), dbCount)
                    localCounts[kind] = synced
                    !SyncManager.isRunning(kind) || synced >= 400
                }
                ui.post {
                    if (!isFinishing && !isDestroyed) {
                        localCounts.forEach { (k, c) ->
                            if (c > (counts[k] ?: 0)) counts[k] = c
                            if (!SyncManager.isRunning(k) && c > 0) doneKinds[k] = true
                        }
                        renderStatusCards()
                    }
                }
                if (ready || SystemClock.uptimeMillis() - started > 12_000L) {
                    if (SystemClock.uptimeMillis() - started > 12_000L) {
                        ui.post { if (!isFinishing && !isDestroyed) phaseTitle.text = "جارٍ استكمال المزامنة في الخلفية…" }
                    }
                    break
                }
                try { Thread.sleep(280) } catch (_: InterruptedException) { return@execute }
            }
            ui.post { openHome() }
        }
    }

    override fun onSyncState(kind: String, running: Boolean, loaded: Int, ok: Boolean?) {
        if (isFinishing || isDestroyed) return
        if (loaded > 0) counts[kind] = maxOf(counts[kind] ?: 0, loaded)
        if (!running) doneKinds[kind] = true
        renderStatusCards()
    }

    private fun renderStatusCards() {
        Kinds.CONTENT.forEach { k ->
            val tv = statusViews[k] ?: return@forEach
            val done = doneKinds[k] == true
            val count = counts[k] ?: SyncManager.loaded(k)
            tv.text = when {
                done -> "جاهز ✓"
                count > 0 -> numFormat.format(count)
                else -> "…"
            }
        }
    }

    private fun openHome() {
        if (opened || isFinishing || isDestroyed) return
        opened = true
        startActivity(Intent(this, HomeActivity::class.java).apply {
            putExtra("server", intent.getStringExtra("server"))
            putExtra("username", intent.getStringExtra("username"))
            putExtra("password", intent.getStringExtra("password"))
            intent.getStringExtra("user_info")?.let { putExtra("user_info", it) }
        })
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { background = HexPatternDrawable() }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.logo_alhorr_login)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "الحر الأصلي"
            startAnimation(AlphaAnimation(1f, 0.55f).apply {
                duration = 900
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            })
        }
        column.addView(logo, LinearLayout.LayoutParams(dp(156), dp(156)))

        phaseTitle = tv("جاري تحميل سيرفر ALHUR..TV", 20f, UI.TEXT, true).apply {
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_RTL
        }
        column.addView(phaseTitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })

        column.addView(tv("يرجى الانتظار لحظات بينما يتم تجهيز القنوات والمكتبة", 12.5f, UI.MUTED).apply {
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_RTL
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })

        // Track + Indeterminate Neon Bar matching Web LoadingScreen
        val track = FrameLayout(this).apply {
            background = roundBg(UI.PANEL2, 99, UI.STROKE, 1)
            clipRounded(dpf(99))
        }
        track.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(UI.NEON)
        }, FrameLayout.LayoutParams(-1, dp(14), Gravity.CENTER))
        column.addView(track, LinearLayout.LayoutParams(dp(390), dp(7)).apply { topMargin = dp(20) })

        // 3 Live section sync cards (البث المباشر، الأفلام، المسلسلات)
        val cardsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        Kinds.CONTENT.forEachIndexed { idx, k ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = roundBg(UI.PANEL, 12, UI.STROKE, 1)
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            card.addView(tv(Kinds.title(k), 11.5f, UI.MUTED, true).apply {
                gravity = Gravity.CENTER
                maxLines = 1
            })
            val valView = tv("…", 13f, UI.NEON, true).apply {
                gravity = Gravity.CENTER
                maxLines = 1
            }
            statusViews[k] = valView
            card.addView(valView, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            cardsRow.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (idx < Kinds.CONTENT.size - 1) marginEnd = dp(10)
            })
        }
        column.addView(cardsRow, LinearLayout.LayoutParams(dp(390), -2).apply { topMargin = dp(22) })

        root.addView(column, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        content = column
        setContentView(root)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { opened = true; finish() }

    override fun onDestroy() {
        SyncManager.remove(this)
        ui.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }
}
