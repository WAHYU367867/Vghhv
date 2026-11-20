package com.alhorrtv.app

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Neon-glass settings screen for Android TV (reference: "معلومات الحساب" panel on the left, vertical menu on the right).
 * Tabs: الحساب (account info + actions) · الإعدادات (library refresh, theme, login, support) · المشغلات (player options).
 * All values come from the logged-in account / device; nothing is hard-coded. The layout is designed on a 960x540dp canvas.
 */
class SettingsActivity : FullscreenActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var credentials: CredentialStore
    private var server = ""
    private var username = ""
    private var password = ""
    private var account: JSONObject? = null

    private val navItems = ArrayList<View>()
    private val panels = ArrayList<View>()
    private var currentTab = 0

    // Live account cards (re-bound when user_info arrives late).
    private lateinit var usernameValue: TextView
    private lateinit var devicesValue: TextView
    private lateinit var statusValue: TextView
    private lateinit var expiryValue: TextView
    private lateinit var accountsLabel: TextView

    private val density get() = resources.displayMetrics.density
    private val compact: Boolean get() = false
    private fun pxf(dp: Float) = dp * density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        credentials = CredentialStore(this)
        server = intent.getStringExtra("server").orEmpty()
        username = intent.getStringExtra("username").orEmpty()
        password = intent.getStringExtra("password").orEmpty()
        account = try { intent.getStringExtra("user_info")?.let(::JSONObject) } catch (_: Exception) { null }
        buildUi()
        loadAccountIfMissing()
        rememberCurrentAccount()
    }

    // ---------------------------------------------------------------- small view helpers

    private fun icon(res: Int, tint: Int, sizeDp: Int): ImageView = ImageView(this).apply {
        setImageResource(res); setColorFilter(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Round translucent bubble with a glowing icon inside (the "مضيئة" icons of the reference). */
    private fun bubble(res: Int, tint: Int, sizeDp: Int): ImageView = ImageView(this).apply {
        setImageResource(res); setColorFilter(tint)
        val pad = dp(sizeDp / 5 + 1); setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(46, Color.red(tint), Color.green(tint), Color.blue(tint))); setStroke(dp(1), Color.argb(120, Color.red(tint), Color.green(tint), Color.blue(tint))) }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Makes a view D-pad focusable with a short, cheap scale animation (the neon border comes from its background). */
    private fun View.neonFocus(scale: Float = 1.03f, clickable: Boolean = true) {
        isFocusable = true; isClickable = clickable; isFocusableInTouchMode = false
        setOnFocusChangeListener { v, focused ->
            v.animate().scaleX(if (focused) scale else 1f).scaleY(if (focused) scale else 1f).setDuration(110).start()
        }
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    // ---------------------------------------------------------------- real values

    private fun cleanField(key: String): String? = account?.optString(key, "")?.takeIf { it.isNotBlank() && it != "null" }

    private fun statusText(): String {
        if (FreeCatalog.isFreeServer(server)) return "نشط (الباقة المجانية)"
        return when (cleanField("status")?.lowercase()) {
            "active" -> "نشط"; "expired" -> "منتهي"; "banned" -> "محظور"; "disabled" -> "معطّل"; null -> "غير متاح"; else -> cleanField("status")!!
        }
    }

    private fun expiryText(): CharSequence {
        if (FreeCatalog.isFreeServer(server)) return "مفتوح (مجاني)"
        val epoch = account?.optLong("exp_date", 0L) ?: 0L
        if (epoch <= 0L) return "غير متاح"
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(Date(epoch * 1000L))
        val days = ((epoch * 1000L - System.currentTimeMillis()) / 86_400_000L).toInt()
        val sub = if (days >= 0) "متبقي $days يوم" else "انتهى الاشتراك"
        return SpannableString("$date\n$sub").apply { setSpan(RelativeSizeSpan(0.72f), date.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    private fun devicesText(): String {
        if (FreeCatalog.isFreeServer(server)) return "مفتوح"
        val used = cleanField("active_cons") ?: "0"
        val max = cleanField("max_connections") ?: "—"
        return "$used / $max"
    }

    private fun deviceId(): String = try {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)?.takeIf { it.isNotBlank() }?.uppercase(Locale.ENGLISH) ?: "غير متاح"
    } catch (_: Exception) { "غير متاح" }

    private fun versionName(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "—" } catch (_: Exception) { "—" }

    private fun statusColor(): Int = if (FreeCatalog.isFreeServer(server)) Neon.TEAL else when (cleanField("status")?.lowercase()) {
        "active" -> Neon.TEAL; null -> Neon.MUTED; else -> Neon.RED
    }

    private fun bindAccount() {
        if (::profileSub.isInitialized) { profileSub.text = profileLine(); profileSub.setTextColor(statusColor()) }
        usernameValue.text = username.ifBlank { "غير متاح" }
        devicesValue.text = devicesText()
        statusValue.text = statusText(); statusValue.setTextColor(statusColor())
        expiryValue.text = expiryText()
    }

    /** When Settings is opened right after an automatic (saved-session) login there is no user_info yet: fetch it once. */
    private fun loadAccountIfMissing() {
        if (account != null || server.isBlank() || username.isBlank()) return
        executor.execute {
            val info = try { XtreamRepository(this, server, username, password).account()?.optJSONObject("user_info") } catch (_: Exception) { null }
            if (info != null) runOnUiThread { if (!isFinishing && !isDestroyed) { account = info; bindAccount() } }
        }
    }

    /** Keeps the logged-in account in the saved-accounts list (only when "save login" is on) and refreshes the count. */
    private fun rememberCurrentAccount() {
        executor.execute {
            try {
                if (getSharedPreferences("login_options", MODE_PRIVATE).getBoolean("remember_login", true) && username.isNotBlank() && password.isNotBlank())
                    credentials.addAccount(server, username, password)
            } catch (_: Exception) { }
            val count = try { credentials.accounts().size } catch (_: Exception) { 0 }
            runOnUiThread { if (!isFinishing && !isDestroyed) updateAccountsLabel(count) }
        }
    }

    private fun updateAccountsLabel(count: Int) { accountsLabel.text = "الحسابات ($count)" }

    // ---------------------------------------------------------------- UI building

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = HexPatternDrawable()
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(if (compact) 10 else 16), dp(16), dp(if (compact) 10 else 16), dp(8)) }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        // Main row. RTL: the first child sits on the right (menu), the second fills the rest (panel on the left).
        val main = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(main, lp(-1, 0, 1f))
        main.addView(buildSidebar(), lp(dp(if (compact) 76 else 240), -1))
        val content = FrameLayout(this)
        main.addView(content, lp(0, -1, 1f).apply { marginStart = dp(if (compact) 10 else 20) })
        panels.add(buildSettingsPanel()); panels.add(buildAccountPanel()); panels.add(buildPlayersPanel())
        panels.forEach { content.addView(it, FrameLayout.LayoutParams(-1, -1)) }

        column.addView(buildBottomBar(), lp(-1, dp(46)))
        setContentView(root)
        selectTab(1)   // opens on الإعدادات, which shows the "معلومات الحساب" screen exactly like the reference image

        // Entering the menu from the content jumps to the active tab; moving inside the menu previews tabs.
        root.viewTreeObserver.addOnGlobalFocusChangeListener { oldF, newF ->
            val idx = navItems.indexOf(newF)
            if (idx < 0) return@addOnGlobalFocusChangeListener
            if (oldF == null || navItems.contains(oldF)) { if (idx < 3) selectTab(idx) }
            else if (idx != currentTab) navItems[currentTab].post { navItems[currentTab].requestFocus() }
        }
        navItems[1].post { navItems[1].requestFocus() }
    }

    private val navPaint = ArrayList<() -> Unit>()

    private fun selectTab(index: Int) {
        currentTab = index
        for (i in 0 until 3) { navItems[i].isSelected = i == index; panels[i].visibility = if (i == index) View.VISIBLE else View.GONE }
        for (i in navBars.indices) navBars[i].alpha = if (i == index) 1f else 0.3f
        navPaint.forEach { it() }
    }

    // ---- privacy lock (hide categories / single items behind a password)
    private var privacyBox: LinearLayout? = null
    private val privacy by lazy {
        PrivacySheet(this, XtreamRepository(this, server, username, password), executor) { renderPrivacy() }
    }

    private fun renderPrivacy() {
        val box = privacyBox ?: return
        box.removeAllViews()
        val locked = HiddenStore.hasPassword(this)
        val hidden = HiddenStore.hiddenCount(this)
        val sub = if (!locked) "أخفِ فئات أو قنوات أو أفلام أو مسلسلات بكلمة مرور"
            else if (hidden > 0) "$hidden عنصر / فئة مخفية" else "لا يوجد شيء مخفي"
        settingRow(box, R.drawable.ic_set_shield, UI.NEON, "إخفاء المحتوى", sub,
            valueText(if (locked) "مفعّل" else "غير مفعّل", if (locked) Neon.TEAL else Neon.MUTED)) {
            if (!locked) privacy.setPassword { privacy.openManager() } else privacy.askPassword { privacy.openManager() }
        }
        if (locked) settingRow(box, R.drawable.ic_set_info, Neon.GOLD, "نسيت كلمة المرور؟", "إعادة التعيين وحذف كل المخفي", chevron()) { privacy.forgot() }
    }

    // ---- professional grouped-list building blocks (group card > rows with icon chip, text and a trailing control)

    private fun group(parent: LinearLayout, title: String): LinearLayout {
        parent.addView(tv(title, 13f, UI.NEON, true).apply { setPadding(dp(4), dp(20), dp(4), dp(8)) }, lp(-1, -2))
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        parent.addView(card, lp(-1, -2))
        return card
    }

    private fun chevron(): View = tv("‹", 24f, UI.MUTED_DIM, true).apply { tag = "chev" }
    private fun valueText(text: String, color: Int = Neon.MUTED): TextView = tv(text, 12.5f, color, true).apply { setPadding(dp(4), 0, dp(4), 0) }
    private fun valueWithChevron(text: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(valueText(text)); addView(chevron())
    }

    /** Redesigned setting card: title + description + chevron. Focus = lighter surface, 2dp neon border, neon title, scale 1.03. */
    private fun settingRow(parent: LinearLayout, iconRes: Int, accent: Int, title: String, subtitle: String, trailing: View?, onClick: (() -> Unit)? = null): LinearLayout {
        val d = density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(64)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = Neon.card(12f * d, 1f, 0f)
        }
        val chip = ImageView(this).apply {
            setImageResource(iconRes); setColorFilter(accent); setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { cornerRadius = 10f * d; setColor(UI.alpha(accent, 40)) }
        }
        row.addView(chip, lp(dp(38), dp(38)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
        val titleView = tv(title, 16f, Neon.TEXT, true)
        texts.addView(titleView)
        if (subtitle.isNotBlank()) texts.addView(tv(subtitle, 12.5f, UI.MUTED).apply { setPadding(0, dp(3), 0, 0) })
        row.addView(texts, lp(0, -2, 1f))
        if (trailing != null) row.addView(trailing)
        if (onClick != null) {
            row.neonFocus(1.03f); row.setOnClickListener { onClick() }
            val base = row.onFocusChangeListener
            row.setOnFocusChangeListener { v, focused ->
                base?.onFocusChange(v, focused)
                titleView.setTextColor(if (focused) UI.NEON else Neon.TEXT)
                (v.findViewWithTag<View>("chev") as? TextView)?.setTextColor(if (focused) UI.NEON else UI.MUTED_DIM)
            }
        }
        parent.addView(row, lp(-1, -2).apply { bottomMargin = dp(10) })
        return row
    }

    private fun switchRow(parent: LinearLayout, iconRes: Int, accent: Int, title: String, subtitle: String, initial: Boolean, onChange: (Boolean, NeonSwitch) -> Unit): LinearLayout {
        val sw = NeonSwitch(this).apply { checked = initial }
        return settingRow(parent, iconRes, accent, title, subtitle, sw) { sw.checked = !sw.checked; onChange(sw.checked, sw) }
    }

    /** Three-way selector for the automatic library refresh (same modes and storage as before). */
    private fun refreshSelector(parent: LinearLayout) {
        val modes = listOf(SyncManager.MODE_SIX_HOURS, SyncManager.MODE_DAILY, SyncManager.MODE_MANUAL)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(dp(8), dp(2), dp(8), dp(10)) }
        val chips = ArrayList<TextView>()
        fun paint() {
            val current = SyncManager.refreshMode(this)
            chips.forEachIndexed { i, chip ->
                val on = modes[i] == current
                chip.background = if (on) gradientBg(intArrayOf(UI.NEON, UI.NEON_DEEP), 14, GradientDrawable.Orientation.LEFT_RIGHT, Color.WHITE, 1)
                    else roundBg(UI.PANEL2, 14, UI.STROKE, 1)
                chip.setTextColor(if (on) Color.parseColor("#001018") else UI.MUTED)
            }
        }
        modes.forEach { mode ->
            val chip = tv(SyncManager.refreshLabel(mode), 12.5f, Neon.TEXT, true).apply {
                gravity = Gravity.CENTER; maxLines = 1; setPadding(dp(6), dp(10), dp(6), dp(10)); neonFocus(1.04f)
                setOnClickListener {
                    SyncManager.setRefreshMode(this@SettingsActivity, mode); paint()
                    Toast.makeText(this@SettingsActivity, "تم ضبط التحديث: ${SyncManager.refreshLabel(mode)}", Toast.LENGTH_SHORT).show()
                }
            }
            chips.add(chip); bar.addView(chip, lp(0, -2, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        paint(); parent.addView(bar, lp(-1, -2))
    }

    private fun expiryDate(): String {
        if (FreeCatalog.isFreeServer(server)) return "حساب مجاني"
        val epoch = account?.optLong("exp_date", 0L) ?: 0L
        return if (epoch <= 0L) "غير متاح" else SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(Date(epoch * 1000L))
    }

    private fun profileLine(): String = when {
        FreeCatalog.isFreeServer(server) -> "${statusText()}  ·  حساب مجاني"
        account == null -> "—"
        else -> "${statusText()}  ·  ينتهي ${expiryDate()}"
    }

    private val navBars = ArrayList<View>()

    /** Sidebar entry: idle quiet, selected = card colour + neon text, focused = solid neon with dark text/icon. */
    private fun cinematicNavItem(label: String, iconRes: Int, accent: Int, onClick: () -> Unit): View {
        val dark = Color.parseColor("#001018")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = if (compact) Gravity.CENTER else Gravity.CENTER_VERTICAL
            setPadding(dp(if (compact) 6 else 14), dp(4), dp(if (compact) 6 else 14), dp(4))
            contentDescription = label
            background = Neon.nav(10f * density, 1f, 0f)
            neonFocus(1.04f); setOnClickListener { onClick() }
        }
        val glyph = icon(iconRes, UI.MUTED, 20)
        val text = tv(label, 15f, UI.MUTED, true).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, 0, 0) }
        row.addView(glyph)
        if (compact) { text.visibility = View.GONE; row.addView(text, lp(0, -2, 0f)) } else row.addView(text, lp(0, -2, 1f))
        fun paint() {
            val focused = row.isFocused; val selected = row.isSelected
            val c = if (focused || selected) UI.NEON else UI.MUTED
            text.setTextColor(c); glyph.setColorFilter(c)
        }
        val base = row.onFocusChangeListener
        row.setOnFocusChangeListener { v, f -> base?.onFocusChange(v, f); paint() }
        navPaint.add { paint() }; paint()
        navItems.add(row); navBars.add(View(this))
        return row
    }

    private fun navItem(label: String, iconRes: Int, accent: Int, onClick: () -> Unit): View {
        return cinematicNavItem(label, iconRes, accent, onClick)
    }

    @Suppress("unused")
    private fun legacyNavItem(label: String, iconRes: Int, accent: Int, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = Neon.nav(pxf(14f), pxf(1f), pxf(3f))
            neonFocus(1.04f); setOnClickListener { onClick() }
        }
        row.addView(icon(iconRes, Neon.TEXT, 20))
        row.addView(tv(label, 14f, Neon.TEXT, true).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, 0, 0) }, lp(0, -2, 1f))
        navItems.add(row)
        return row
    }

    private fun buildSidebar(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = Neon.panel(pxf(16f), pxf(1.4f), pxf(4f))
        }
        // The real logo, untouched (same resource the login screen uses).
        box.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo_alhorr_login); scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "الحر الأصلي"
        }, lp(dp(if (compact) 44 else 116), dp(if (compact) 48 else 122)))
        box.addView(tv("ALHUR TV · الحر الأصلي", 9.5f, UI.NEON, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(8)); if (compact) visibility = View.GONE }, lp(-1, -2))
        box.addView(View(this).apply { setBackgroundColor(UI.STROKE) }, lp(-1, dp(1)).apply { bottomMargin = dp(10) })
        val gap = dp(6)
        box.addView(navItem("الحساب", R.drawable.ic_set_user, UI.NEON) { selectTab(0); panels[0].let { firstFocusable(it)?.requestFocus() } }, lp(-1, 0, 1f).apply { bottomMargin = gap })
        box.addView(navItem("الإعدادات", R.drawable.ic_set_gear, UI.BLUE) { selectTab(1); firstFocusable(panels[1])?.requestFocus() }, lp(-1, 0, 1f).apply { bottomMargin = gap })
        box.addView(navItem("المشغلات", R.drawable.ic_set_play_circle, Neon.TEAL) { selectTab(2); firstFocusable(panels[2])?.requestFocus() }, lp(-1, 0, 1f).apply { bottomMargin = gap })
        box.addView(navItem("رجوع", R.drawable.ic_set_arrow_right, Neon.GOLD) { finish() }, lp(-1, 0, 1f))
        return box
    }

    private fun firstFocusable(v: View): View? {
        val list = ArrayList<View>(); v.addFocusables(list, View.FOCUS_DOWN)
        if (list.isEmpty()) v.addFocusables(list, View.FOCUS_FORWARD)
        return list.firstOrNull { it.isFocusable && it.visibility == View.VISIBLE }
    }

    private fun panelShell(title: String, iconRes: Int): Pair<LinearLayout, LinearLayout> {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (compact) 14 else 28), dp(if (compact) 12 else 22), dp(if (compact) 14 else 28), dp(12))
            background = Neon.panel(pxf(16f), pxf(1.6f), pxf(5f))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(bubble(iconRes, UI.NEON, 34))
        head.addView(tv(title, 21f, Neon.TEXT, true).apply { setPadding(dp(10), 0, 0, 0) })
        panel.addView(head, lp(-1, dp(44)))
        return panel to head
    }

    // ---- الحساب

    private class Card(val root: LinearLayout, val value: TextView)

    private fun infoCard(iconRes: Int, tint: Int, label: String, initial: CharSequence, ltr: Boolean): Card {
        val value = tv("", 15f, Neon.TEXT, true).apply {
            text = initial; gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL; maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (ltr) textDirection = View.TEXT_DIRECTION_LTR
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(4))
            background = Neon.card(pxf(18f), pxf(1f), pxf(3f))
            neonFocus(1.025f, clickable = false)
        }
        row.addView(bubble(iconRes, tint, 34))
        row.addView(tv(label, 11.5f, Neon.MUTED).apply { setPadding(dp(8), 0, dp(8), 0) })
        row.addView(value, lp(0, -2, 1f))
        return Card(row, value)
    }

    private fun actionButton(label: String, iconRes: Int, top: Int, bottom: Int, edge: IntArray, onClick: () -> Unit): Pair<LinearLayout, TextView> {
        val text = tv(label, 14f, Neon.TEXT, true).apply { setPadding(dp(8), 0, 0, 0) }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = Neon.button(pxf(22f), pxf(1.2f), pxf(4f), top, bottom, edge)
            neonFocus(1.05f); setOnClickListener { onClick() }
        }
        val glyph = icon(iconRes, Neon.TEXT, 22)
        val base = row.onFocusChangeListener
        row.setOnFocusChangeListener { v, f ->
            base?.onFocusChange(v, f)
            val c = if (f) Color.parseColor("#001018") else Neon.TEXT
            text.setTextColor(c); glyph.setColorFilter(c)
        }
        row.addView(glyph); row.addView(text)
        return row to text
    }

    private fun buildAccountPanel(): View {
        val (panel, _) = panelShell("معلومات الحساب", R.drawable.ic_set_user)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(grid, lp(-1, 0, 1f).apply { topMargin = dp(4) })

        val cUser = infoCard(R.drawable.ic_set_user, UI.NEON, "اسم المستخدم", username.ifBlank { "غير متاح" }, true)
        val cDevices = infoCard(R.drawable.ic_set_devices, Neon.CYAN, "الأجهزة", devicesText(), true)
        val cStatus = infoCard(R.drawable.ic_set_shield, Neon.TEAL, "الحالة", statusText(), false)
        val cId = infoCard(R.drawable.ic_set_router, Neon.CYAN, "معرف الجهاز", deviceId(), true)
        val cExpiry = infoCard(R.drawable.ic_set_calendar, Neon.GOLD, "تاريخ الانتهاء", expiryText(), false)
        val cVersion = infoCard(R.drawable.ic_set_info, UI.BLUE, "إصدار التطبيق", versionName(), true)
        usernameValue = cUser.value; devicesValue = cDevices.value; statusValue = cStatus.value; expiryValue = cExpiry.value
        statusValue.setTextColor(statusColor())
        statusValue.textSize = 17f
        cId.value.textSize = 13f

        // RTL: first child = right column.
        listOf(cUser to cDevices, cStatus to cId, cExpiry to cVersion).forEach { (a, b) ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(a.root, lp(0, -1, 1f).apply { marginStart = dp(2); marginEnd = dp(5) })
            row.addView(b.root, lp(0, -1, 1f).apply { marginStart = dp(5); marginEnd = dp(2) })
            grid.addView(row, lp(-1, 0, 1f).apply { bottomMargin = dp(2) })
        }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val reset = actionButton("إعادة تعيين الكود", R.drawable.ic_set_refresh, Color.parseColor("#FF1A2D42"), Color.parseColor("#FF121E2E"),
            intArrayOf(UI.NEON, Neon.BLUE, UI.NEON)) { confirmReset() }
        val accounts = actionButton("الحسابات (0)", R.drawable.ic_set_users, Color.parseColor("#FF1C2538"), Color.parseColor("#FF131926"),
            intArrayOf(UI.NEON, UI.NEON_DEEP, UI.NEON)) { showAccounts() }
        val add = actionButton("إضافة حساب", R.drawable.ic_set_adduser, Color.parseColor("#FF123638"), Color.parseColor("#FF0C2326"),
            intArrayOf(Neon.TEAL, UI.NEON, Neon.TEAL)) { addAccount() }
        accountsLabel = accounts.second
        buttons.addView(reset.first, lp(0, -1, 1f).apply { marginEnd = dp(4) })
        buttons.addView(accounts.first, lp(0, -1, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        buttons.addView(add.first, lp(0, -1, 1f).apply { marginStart = dp(4) })
        panel.addView(buttons, lp(-1, dp(54)).apply { topMargin = dp(4) })
        return panel
    }

    // ---- الإعدادات

    private fun sectionCard(parent: LinearLayout, title: String, subtitle: String? = null, content: LinearLayout.() -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = Neon.card(pxf(18f), pxf(1f), pxf(3f)).also { /* static card: glow reserved, focus state never used */ }
        }
        box.addView(tv(title, 15f, Neon.GOLD, true))
        if (!subtitle.isNullOrBlank()) box.addView(tv(subtitle, 11f, Neon.MUTED).apply { setPadding(0, dp(2), 0, dp(6)) })
        box.content()
        parent.addView(box, lp(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun focusRow(v: View) { v.background = Neon.card(pxf(12f), pxf(1f), pxf(2f)); v.neonFocus(1.02f); v.setPadding(dp(10), dp(6), dp(10), dp(6)) }

    private lateinit var profileName: TextView
    private lateinit var profileSub: TextView
    private lateinit var profileAvatar: TextView

    private fun buildSettingsPanel(): View {
        val (panel, _) = panelShell("الحساب", R.drawable.ic_set_user)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(6), dp(2), dp(10)) }
        panel.addView(ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false; addView(body) }, lp(-1, 0, 1f))
        val d = density

        // ---- account card
        val profile = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundBg(UI.PANEL, 16, UI.STROKE, 1)
        }
        profileAvatar = tv(username.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "؟", 24f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(UI.alpha(UI.NEON, 60)); setStroke((2 * d).toInt(), UI.NEON) }
        }
        profile.addView(profileAvatar, lp(dp(58), dp(58)))
        val who = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        profileName = tv(username.ifBlank { "الحساب" }, 18f, Neon.TEXT, true).apply { textDirection = View.TEXT_DIRECTION_LTR; gravity = Gravity.START; maxLines = 1 }
        profileSub = tv(profileLine(), 12.5f, statusColor(), true).apply { setPadding(0, dp(3), 0, 0) }
        who.addView(profileName); who.addView(profileSub)
        profile.addView(who, lp(0, -2, 1f))
        body.addView(profile, lp(-1, -2).apply { topMargin = dp(4) })

        // ---- library
        val library = group(body, "المكتبة والتحديث")
        settingRow(library, R.drawable.ic_set_refresh, Neon.BLUE, "تحديث المكتبة تلقائيًا", "تُحفظ العناصر على الجهاز وتظهر الدفعات أثناء التنزيل", null)
        refreshSelector(library)
        settingRow(library, R.drawable.ic_set_refresh, Neon.CYAN, "تحديث المكتبة الآن", "القنوات والأفلام والمسلسلات", chevron()) {
            SyncManager.ensureAll(this, server, username, password, force = true)
            Toast.makeText(this, "بدأ تحديث المكتبة في الخلفية؛ ستظهر العناصر تدريجيًا.", Toast.LENGTH_LONG).show()
        }
        settingRow(library, R.drawable.ic_set_grid, Neon.GOLD, "تنظيف الكاش", "الصور المؤقتة وبيانات الدليل فقط، دون المكتبة أو المفضلة", chevron()) {
            executor.execute {
                val freed = try { ImageLoader.clearCache(this) } catch (_: Exception) { 0L }
                EpgCache.clear()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) Toast.makeText(this,
                        "تم تنظيف الكاش (${android.text.format.Formatter.formatShortFileSize(this, freed)}).", Toast.LENGTH_LONG).show()
                }
            }
        }

        // ---- security
        val security = group(body, "الأمان والخصوصية")
        val privacyInner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        security.addView(privacyInner)
        privacyBox = privacyInner
        renderPrivacy()
        switchRow(security, R.drawable.ic_set_shield, Neon.TEAL, "حفظ تسجيل الدخول", "مشفّر على هذا الجهاز لتسجيل الدخول تلقائيًا",
            getSharedPreferences("login_options", MODE_PRIVATE).getBoolean("remember_login", true)) { checked, sw ->
            getSharedPreferences("login_options", MODE_PRIVATE).edit().putBoolean("remember_login", checked).apply()
            if (checked) try { credentials.save(server, username, password); credentials.addAccount(server, username, password) } catch (_: Exception) {
                getSharedPreferences("login_options", MODE_PRIVATE).edit().putBoolean("remember_login", false).apply()
                sw.checked = false
                Toast.makeText(this, "تعذر حفظ بيانات الدخول المشفّرة.", Toast.LENGTH_LONG).show()
            } else credentials.clear()
        }
        fun adultSubLabel(enabled: Boolean): String {
            val count = AdultStore.getChannels(this).size
            val status = if (enabled) "مفعّل في القائمة الرئيسية (يتطلب تأكيد +18)" else "معطّل ومخفي من القائمة الرئيسية"
            return if (count > 0) "$status · $count قناة" else status
        }
        val adultRow = switchRow(security, R.drawable.ic_set_adult18, Color.parseColor("#E8384F"), "قسم Adult 18+",
            adultSubLabel(AdultStore.isEnabled(this)), AdultStore.isEnabled(this)) { checked, _ ->
            AdultStore.setEnabled(this, checked)
            if (!checked) AdultStore.clearAgeConfirmation()
            Toast.makeText(this, if (checked) "تم تفعيل قسم Adult 18+" else "تم تعطيل وإخفاء قسم Adult 18+", Toast.LENGTH_SHORT).show()
        }
        val adultSubTv = (adultRow.getChildAt(1) as? LinearLayout)?.getChildAt(1) as? TextView
        val origAdultClick = adultRow
        val adultSw = adultRow.getChildAt(2) as? NeonSwitch
        origAdultClick.setOnClickListener {
            val sw = adultSw ?: return@setOnClickListener
            sw.checked = !sw.checked
            val checked = sw.checked
            AdultStore.setEnabled(this, checked)
            if (!checked) AdultStore.clearAgeConfirmation()
            adultSubTv?.text = adultSubLabel(checked)
            Toast.makeText(this, if (checked) "تم تفعيل قسم Adult 18+" else "تم تعطيل وإخفاء قسم Adult 18+", Toast.LENGTH_SHORT).show()
        }

        // ---- support
        val support = group(body, "الدعم والمعلومات")
        settingRow(support, R.drawable.ic_set_info, Neon.TEAL, "تواصل معنا عبر واتساب", "الدعم الفني وتجديد الاشتراك · ${SupportContact.DISPLAY}", chevron()) {
            SupportContact.openWhatsApp(this, "السلام عليكم، أريد الاستفسار عن تجديد اشتراك تطبيق ALHUR TV.")
        }
        settingRow(support, R.drawable.ic_set_info, UI.BLUE, "إصدار التطبيق", "ALHUR TV · الحر الأصلي", valueText(versionName()).apply { textDirection = View.TEXT_DIRECTION_LTR })

        // ---- sign out
        val (logout, _) = actionButton("تسجيل الخروج", R.drawable.ic_set_arrow_right, Color.parseColor("#FF381620"), Color.parseColor("#FF220D14"),
            intArrayOf(UI.MOVIE, Neon.RED, UI.MOVIE)) { confirmLogout() }
        body.addView(logout, lp(-1, dp(48)).apply { topMargin = dp(20); bottomMargin = dp(8) })
        return panel
    }

    // ---- المشغلات

    private fun buildPlayersPanel(): View {
        val (panel, _) = panelShell("المشغلات", R.drawable.ic_set_play_circle)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(6), dp(2), dp(6)) }
        panel.addView(ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false; addView(body) }, lp(-1, 0, 1f))

        sectionCard(body, "المشغّل الداخلي", "المشغّل الافتراضي للبث المباشر والأفلام والمسلسلات") {
            addView(tv("يعمل التطبيق بمشغّل Media3 ExoPlayer المدمج، ويدعم روابط HLS وروابط الفيديو المباشرة.", 12.5f, Neon.TEXT).apply { setLineSpacing(dp(3).toFloat(), 1f) })
        }
        sectionCard(body, "المشغّل البديل", "للبث الذي لا يستطيع المشغّل الداخلي فك ترميزه") {
            val prefs = getSharedPreferences("player_options", MODE_PRIVATE)
            val alt = CheckBox(this@SettingsActivity).apply {
                text = "عرض خيار المشغّل البديل عند فشل التشغيل"; setTextColor(Neon.TEXT); buttonTintList = ColorStateList.valueOf(Neon.GOLD)
                isChecked = prefs.getBoolean("offer_alternate_on_error", true)
                focusRow(this)
                setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("offer_alternate_on_error", checked).apply() }
            }
            addView(alt)
            addView(tv("عند الفشل يمكنك فتح الرابط في VLC أو أي مشغّل فيديو مثبّت على الجهاز.", 10.5f, Neon.MUTED).apply { setPadding(dp(4), dp(6), dp(4), 0) })
        }
        return panel
    }

    // ---- الشريط السفلي

    private fun buildBottomBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(4))
            background = roundBg(Color.parseColor("#131722"), 16, UI.STROKE, 1)
        }
        // RTL: right → left = icons, slogan, app name.
        val icons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun mini(res: Int, label: String, tint: Int) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            col.addView(icon(res, tint, 18)); col.addView(tv(label, 9f, Neon.MUTED).apply { gravity = Gravity.CENTER })
            icons.addView(col, lp(dp(64), -2))
        }
        mini(R.drawable.ic_set_series, "مسلسلات", UI.BLUE); mini(R.drawable.ic_set_movie, "أفلام", UI.GOLD); mini(R.drawable.ic_set_live, "بث مباشر", UI.NEON)
        bar.addView(icons, lp(-2, -1))

        val middle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun line(fromLeft: Boolean) = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                if (fromLeft) intArrayOf(Color.TRANSPARENT, UI.NEON) else intArrayOf(Neon.TEAL, Color.TRANSPARENT))
        }
        val slogan = tv("◆  مشاهدة بلا حدود  ◆", 15f, UI.NEON, true).apply { gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0) }
        slogan.post {
            val w = slogan.paint.measureText(slogan.text.toString())
            slogan.paint.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(UI.NEON, Color.parseColor("#7BE6FF"), Neon.TEAL), null, Shader.TileMode.CLAMP)
            slogan.invalidate()
        }
        middle.addView(line(false), lp(dp(90), dp(1))); middle.addView(slogan); middle.addView(line(true), lp(dp(90), dp(1)))
        bar.addView(middle, lp(0, -1, 1f))
        bar.addView(tv("الحر الأصلي", 11f, Neon.MUTED, true).apply { setPadding(dp(4), 0, dp(4), 0) })
        return bar
    }

    // ---------------------------------------------------------------- actions

    /** Themed "add account" sheet: username + password, verified against the SAME server as the current account, then saved and opened. */
    private fun addAccount() {
        if (server.isBlank()) { Toast.makeText(this, "تعذر تحديد السيرفر الحالي.", Toast.LENGTH_LONG).show(); return }
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        fun field(hintText: String, iconRes: Int, password: Boolean): Pair<LinearLayout, android.widget.EditText> {
            val input = android.widget.EditText(this).apply {
                hint = hintText; setHintTextColor(Neon.MUTED); setTextColor(Neon.TEXT); textSize = 15f; setSingleLine(true)
                background = null; setPadding(dp(10), 0, dp(10), 0); textDirection = View.TEXT_DIRECTION_LTR
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                inputType = if (password) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = if (password) android.view.inputmethod.EditorInfo.IME_ACTION_DONE else android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(dp(10), 0, dp(10), 0); background = Neon.card(pxf(16f), pxf(1f), pxf(2f))
                setAddStatesFromChildren(true)      // the glow follows the focused EditText inside
            }
            box.addView(bubble(iconRes, UI.NEON, 32))
            box.addView(input, lp(0, -1, 1f))
            return box to input
        }
        val (userBox, userInput) = field("اسم المستخدم", R.drawable.ic_set_user, false)
        val (passBox, passInput) = field("كلمة المرور", R.drawable.ic_set_shield, true)
        var visible = false
        val eye = ImageView(this).apply {
            setImageResource(R.drawable.ic_set_eye); setColorFilter(Neon.MUTED); setPadding(dp(6), dp(6), dp(6), dp(6))
            contentDescription = "إظهار أو إخفاء كلمة المرور"; neonFocus(1.1f)
            setOnClickListener {
                visible = !visible
                passInput.transformationMethod = if (visible) null else android.text.method.PasswordTransformationMethod.getInstance()
                passInput.setSelection(passInput.text.length)
                setColorFilter(if (visible) Neon.GOLD else Neon.MUTED)
            }
        }
        passBox.addView(eye, lp(dp(34), dp(34)))
        userInput.setOnEditorActionListener { _, _, _ -> passInput.requestFocus(); true }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(22), dp(18), dp(22), dp(18)); background = Neon.panel(pxf(16f), pxf(1.6f), pxf(5f))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(bubble(R.drawable.ic_set_adduser, Neon.TEAL, 44))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        titles.addView(tv("إضافة حساب جديد", 20f, Neon.TEXT, true))
        titles.addView(tv("سيُستخدم الاتصال المحفوظ تلقائيًا", 11f, Neon.MUTED).apply { gravity = Gravity.START })
        head.addView(titles, lp(0, -2, 1f))
        panel.addView(head, lp(-1, -2))
        panel.addView(tv("سيتم تسجيل الدخول بالحساب الجديد على نفس السيرفر وحفظه في قائمة الحسابات.", 11.5f, Neon.MUTED).apply { setPadding(0, dp(10), 0, dp(12)) })
        panel.addView(userBox, lp(-1, dp(50)).apply { bottomMargin = dp(10) })
        panel.addView(passBox, lp(-1, dp(50)))
        val error = tv("", 12.5f, Neon.RED, true).apply { gravity = Gravity.CENTER; visibility = View.GONE; setPadding(0, dp(10), 0, 0) }
        panel.addView(error, lp(-1, -2))

        val (addRow, addText) = actionButton("إضافة وتسجيل الدخول", R.drawable.ic_set_adduser, Color.parseColor("#FF123638"), Color.parseColor("#FF0C2326"),
            intArrayOf(Neon.TEAL, UI.NEON, Neon.TEAL)) { }
        val (cancelRow, _) = actionButton("إلغاء", R.drawable.ic_set_arrow_right, UI.PANEL2, UI.PANEL,
            Neon.BORDER_IDLE) { dialog.dismiss() }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(addRow, lp(0, -1, 1.5f).apply { marginEnd = dp(6) })
        buttons.addView(cancelRow, lp(0, -1, 1f).apply { marginStart = dp(6) })
        panel.addView(buttons, lp(-1, dp(48)).apply { topMargin = dp(16) })

        fun setBusy(busy: Boolean) {
            addText.text = if (busy) "جارٍ التحقق…" else "إضافة وتسجيل الدخول"
            addRow.isEnabled = !busy; addRow.alpha = if (busy) 0.6f else 1f
            userInput.isEnabled = !busy; passInput.isEnabled = !busy
        }
        fun showError(message: String?) { error.text = message.orEmpty(); error.visibility = if (message == null) View.GONE else View.VISIBLE }

        fun submit() {
            val u = userInput.text.toString().trim(); val p = passInput.text.toString()
            if (u.isBlank()) { showError("أدخل اسم المستخدم"); userInput.requestFocus(); return }
            if (p.isBlank()) { showError("أدخل كلمة المرور"); passInput.requestFocus(); return }
            val isFree = FreeCatalog.isFreeCredentials(u, p)
            val targetServer = if (isFree) FreeCatalog.FREE_SERVER
                else if (FreeCatalog.isFreeServer(server) || server.isBlank()) FreeCatalog.PAID_DEFAULT_SERVER
                else server
            showError(null); setBusy(true)
            executor.execute {
                val acc = try {
                    if (isFree) FreeCatalog.freeAccountInfo()
                    else XtreamRepository(this, targetServer, u, p).account()
                } catch (_: Exception) { null }
                val info = acc?.optJSONObject("user_info")
                val auth = if (acc == null) 0 else info?.optInt("auth", acc.optInt("auth", 0)) ?: acc.optInt("auth", 0)
                val active = info?.optString("status", "")?.equals("Active", true) ?: true
                val ok = auth == 1 && active
                if (ok) {
                    try { credentials.save(targetServer, u, p); credentials.addAccount(targetServer, u, p) } catch (_: Exception) { }
                    getSharedPreferences("login_options", MODE_PRIVATE).edit().putBoolean("remember_login", true).apply()
                    SyncManager.reset(this)
                    try { ContentDatabase.get(this).clearAllTables() } catch (_: Exception) { }
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (ok) {
                        dialog.dismiss()
                        startActivity(Intent(this, HomeActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            .putExtra("server", targetServer).putExtra("username", u).putExtra("password", p)
                            .putExtra("user_info", info?.toString()))
                        finish()
                    } else {
                        setBusy(false)
                        showError(if (acc == null) "تعذر الاتصال بالسيرفر. تحقق من الإنترنت ثم حاول مجددًا."
                            else "بيانات الدخول غير صحيحة أو الحساب غير نشط.")
                    }
                }
            }
        }
        addRow.setOnClickListener { submit() }
        passInput.setOnEditorActionListener { _, _, _ -> submit(); true }

        dialog.setContentView(panel)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(480), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        }
        userInput.requestFocus()
    }

    private fun isCurrent(a: CredentialStore.SavedAccount) =
        a.username == username &&
            FreeCatalog.isFreeServer(FreeCatalog.resolveServer(a.server, a.username, a.password)) ==
            FreeCatalog.isFreeServer(FreeCatalog.resolveServer(server, username, password))
    /** Themed bottom-less sheet shared by the accounts list and the confirmation dialogs. */
    private fun sheet(widthDp: Int, fill: (LinearLayout, android.app.Dialog) -> Unit): android.app.Dialog {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(16), dp(20), dp(16)); background = Neon.panel(pxf(16f), pxf(1.6f), pxf(5f))
        }
        fill(panel, dialog)
        dialog.setContentView(panel)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(widthDp), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.65f)
        }
        return dialog
    }

    private fun goldLine(): View = View(this).apply {
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.TRANSPARENT, Neon.GOLD, Color.TRANSPARENT))
    }

    private fun showAccounts() {
        val initial = credentials.accounts()
        updateAccountsLabel(initial.size)
        sheet(500) { panel, dialog ->
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(bubble(R.drawable.ic_set_users, UI.NEON, 44))
            val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            val title = tv("", 20f, Neon.TEXT, true)
            titles.addView(title)
            titles.addView(tv("اضغط على حساب للتبديل إليه", 11.5f, Neon.MUTED))
            head.addView(titles, lp(0, -2, 1f))
            panel.addView(head, lp(-1, -2))
            panel.addView(goldLine(), lp(-1, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(10) })

            val listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(listHost) }
            panel.addView(scroll, lp(-1, dp(120)))

            fun render() {
                val list = credentials.accounts()
                title.text = "الحسابات (${list.size})"
                updateAccountsLabel(list.size)
                listHost.removeAllViews()
                scroll.layoutParams = lp(-1, if (list.isEmpty()) dp(70) else minOf(list.size, 4) * dp(66) + dp(6))
                if (list.isEmpty()) {
                    listHost.addView(tv("لا توجد حسابات محفوظة.\nفعّل «حفظ تسجيل الدخول» من تبويب «الحساب».", 13f, Neon.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0) }, lp(-1, -2))
                    return
                }
                list.forEach { account ->
                    val current = isCurrent(account)
                    val accent = if (current) Neon.TEAL else UI.NEON
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                        setPadding(dp(10), dp(6), dp(10), dp(6)); background = Neon.card(pxf(18f), pxf(1f), pxf(3f))
                        neonFocus(1.02f)
                        setOnClickListener { if (current) Toast.makeText(this@SettingsActivity, "هذا هو الحساب الحالي.", Toast.LENGTH_SHORT).show() else { dialog.dismiss(); switchTo(account) } }
                    }
                    val avatar = tv(account.username.take(1).uppercase(Locale.ENGLISH), 18f, Color.WHITE, true).apply {
                        gravity = Gravity.CENTER
                        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(accent, UI.PANEL2)).apply { shape = GradientDrawable.OVAL; setStroke(dp(1), UI.alpha(Color.WHITE, 110)) }
                    }
                    card.addView(avatar, lp(dp(42), dp(42)))
                    val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(6), 0) }
                    texts.addView(tv(account.username, 15f, Neon.TEXT, true).apply { textDirection = View.TEXT_DIRECTION_LTR; gravity = Gravity.START; maxLines = 1 })
                    val isFreeAcc = FreeCatalog.isFreeServer(account.server) || FreeCatalog.isFreeCredentials(account.username, account.password)
                    texts.addView(tv(if (isFreeAcc) "حساب مجاني" else "حساب مدفوع محفوظ", 10.5f, Neon.MUTED).apply { gravity = Gravity.START; maxLines = 1 })
                    card.addView(texts, lp(0, -2, 1f))
                    if (current) card.addView(tv("الحالي", 11.5f, Neon.TEAL, true).apply {
                        gravity = Gravity.CENTER; setPadding(dp(12), dp(4), dp(12), dp(4))
                        background = roundBg(UI.alpha(Neon.TEAL, 40), 14, UI.alpha(Neon.TEAL, 170), 1)
                    })
                    else {
                        card.addView(tv("تبديل", 11.5f, UI.NEON, true).apply {
                            gravity = Gravity.CENTER; setPadding(dp(12), dp(4), dp(12), dp(4))
                            background = roundBg(UI.alpha(UI.NEON, 40), 14, UI.alpha(UI.NEON, 170), 1)
                        })
                        val delete = ImageView(this).apply {
                            setImageResource(R.drawable.ic_set_logout); setColorFilter(Neon.RED); setPadding(dp(8), dp(8), dp(8), dp(8)); contentDescription = "حذف الحساب"
                            background = roundBg(UI.alpha(Neon.RED, 30), 16, UI.alpha(Neon.RED, 140), 1)
                            neonFocus(1.1f)
                            setOnClickListener { credentials.removeAccount(account.server, account.username); render() }
                        }
                        card.addView(delete, lp(dp(34), dp(34)).apply { marginStart = dp(8) })
                    }
                    listHost.addView(card, lp(-1, dp(60)).apply { bottomMargin = dp(6) })
                }
            }
            render()

            val (addRow, _) = actionButton("إضافة حساب", R.drawable.ic_set_adduser, Color.parseColor("#FF123638"), Color.parseColor("#FF0C2326"),
                intArrayOf(Neon.TEAL, UI.NEON, Neon.TEAL)) { dialog.dismiss(); addAccount() }
            val (closeRow, _) = actionButton("إغلاق", R.drawable.ic_set_arrow_right, UI.PANEL2, UI.PANEL, Neon.BORDER_IDLE) { dialog.dismiss() }
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(addRow, lp(0, -1, 1.3f).apply { marginEnd = dp(6) })
            buttons.addView(closeRow, lp(0, -1, 1f).apply { marginStart = dp(6) })
            panel.addView(buttons, lp(-1, dp(48)).apply { topMargin = dp(12) })
        }
    }

    private fun switchTo(target: CredentialStore.SavedAccount) {
        if (isCurrent(target)) { Toast.makeText(this, "هذا هو الحساب الحالي.", Toast.LENGTH_SHORT).show(); return }
        val targetServer = FreeCatalog.resolveServer(target.server, target.username, target.password, FreeCatalog.PAID_DEFAULT_SERVER)
        try { credentials.save(targetServer, target.username, target.password) } catch (_: Exception) { }
        getSharedPreferences("login_options", MODE_PRIVATE).edit().putBoolean("remember_login", true).apply()
        SyncManager.reset(this)
        executor.execute {
            try { ContentDatabase.get(this).clearAllTables() } catch (_: Exception) { }
            runOnUiThread {
                startActivity(Intent(this, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra("server", targetServer)
                    .putExtra("username", target.username).putExtra("password", target.password))
                finish()
            }
        }
    }

    private fun confirmReset() = confirmExit("إعادة تعيين الكود",
        "سيتم حذف بيانات الدخول المحفوظة والمحتوى المخزّن على هذا الجهاز، ثم تعود إلى شاشة إدخال الكود من جديد.", "إعادة تعيين",
        R.drawable.ic_set_refresh, UI.NEON, Color.parseColor("#FF1A2D42"), Color.parseColor("#FF121E2E"))

    private fun confirmLogout() = confirmExit("تسجيل الخروج", "سيتم حذف بيانات الدخول والمحتوى المخزّن على هذا الجهاز.", "خروج",
        R.drawable.ic_set_logout, UI.MOVIE, Color.parseColor("#FF381620"), Color.parseColor("#FF220D14"))

    private fun confirmExit(title: String, message: String, positive: String, iconRes: Int, tint: Int, top: Int, bottom: Int) {
        sheet(460) { panel, dialog ->
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(bubble(iconRes, tint, 46))
            head.addView(tv(title, 21f, Neon.TEXT, true).apply { setPadding(dp(12), 0, 0, 0) })
            panel.addView(head, lp(-1, -2))
            panel.addView(goldLine(), lp(-1, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(12) })
            panel.addView(tv(message, 13.5f, Neon.TEXT).apply { setLineSpacing(dp(3).toFloat(), 1f) }, lp(-1, -2))
            panel.addView(tv("الحسابات الأخرى المحفوظة تبقى متاحة.", 11.5f, Neon.MUTED).apply { setPadding(0, dp(8), 0, 0) }, lp(-1, -2))
            val (yes, _) = actionButton(positive, iconRes, top, bottom, intArrayOf(tint, Neon.GOLD, tint)) {
                dialog.dismiss()
                // Other saved accounts stay available; only the current session is removed.
                val others = try { credentials.accounts().filterNot { isCurrent(it) } } catch (_: Exception) { emptyList() }
                credentials.clear()
                others.forEach { try { credentials.addAccount(it.server, it.username, it.password) } catch (_: Exception) { } }
                SyncManager.reset(this)
                executor.execute {
                    try { ContentDatabase.get(this).clearAllTables() } catch (_: Exception) { }
                    runOnUiThread {
                        startActivity(Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("no_auto", true))
                        finish()
                    }
                }
            }
            val (no, _) = actionButton("إلغاء", R.drawable.ic_set_arrow_right, UI.PANEL2, UI.PANEL, Neon.BORDER_IDLE) { dialog.dismiss() }
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(yes, lp(0, -1, 1.3f).apply { marginEnd = dp(6) })
            buttons.addView(no, lp(0, -1, 1f).apply { marginStart = dp(6) })
            panel.addView(buttons, lp(-1, dp(48)).apply { topMargin = dp(16) })
            no.post { no.requestFocus() }   // the safe choice has focus first
        }
    }

    override fun onResume() {
        super.onResume()
        if (::accountsLabel.isInitialized) executor.execute {
            val count = try { credentials.accounts().size } catch (_: Exception) { 0 }
            runOnUiThread { if (!isFinishing && !isDestroyed) updateAccountsLabel(count) }
        }
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}
