package com.alhorrtv.app

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors

class MainActivity : FullscreenActivity() {
    private val server = FreeCatalog.PAID_DEFAULT_SERVER
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var statusText: TextView
    private lateinit var connectButton: View
    private lateinit var connectLabel: TextView
    private lateinit var statusRow: View
    private lateinit var rememberLogin: CheckBox
    private lateinit var credentialStore: CredentialStore
    private lateinit var togglePassword: ImageView
    private lateinit var loginProgress: ProgressBar
    private var passwordVisible = false
    private val hintText = "تُحفظ بيانات الدخول مشفّرة على هذا الجهاز فقط"

    private enum class Status { HINT, LOADING, ERROR, WARNING }

    /** Single place that styles the status line (hint / loading with spinner / error / warning). */
    private fun setStatus(message: String, kind: Status) {
        statusRow.visibility = if (kind == Status.HINT) View.GONE else View.VISIBLE
        statusText.text = message
        val (fill, stroke, textColor) = when (kind) {
            Status.HINT -> Triple(Color.TRANSPARENT, Color.TRANSPARENT, Color.parseColor("#94A3B8"))
            Status.LOADING -> Triple(0x2600D2FF, 0x7300D2FF, Color.parseColor("#7BE6FF"))
            Status.ERROR -> Triple(0x26E8384F, 0x66E8384F, Color.parseColor("#FF7A90"))
            Status.WARNING -> Triple(0x26FFB800, 0x66FFB800, Color.parseColor("#F5D477"))
        }
        statusRow.background = roundBg(fill, 10, stroke, if (kind == Status.HINT) 0 else 1)
        statusText.setTextColor(textColor)
        loginProgress.visibility = if (kind == Status.LOADING) View.VISIBLE else View.GONE
    }

    private fun setPasswordVisible(visible: Boolean) {
        passwordVisible = visible
        val cursor = passwordInput.selectionEnd
        passwordInput.inputType = InputType.TYPE_CLASS_TEXT or
            if (visible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
        if (cursor >= 0) passwordInput.setSelection(cursor.coerceAtMost(passwordInput.text.length))
        togglePassword.setImageResource(if (visible) R.drawable.ic_login_eye else R.drawable.ic_login_eye_off)
        togglePassword.contentDescription = if (visible) "إخفاء كلمة المرور" else "إظهار كلمة المرور"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        credentialStore = CredentialStore(this)
        // Saved session: go straight to the home screen (no login and no reload on every launch).
        val savedUser = credentialStore.username(); val savedPass = credentialStore.password()
        val rememberSaved = getSharedPreferences("login_options", MODE_PRIVATE).getBoolean("remember_login", true)
        val addMode = intent.getBooleanExtra("add_account", false)   // opened from Settings > إضافة حساب
        if (rememberSaved && !addMode && !intent.getBooleanExtra("no_auto", false) && !savedUser.isNullOrBlank() && !savedPass.isNullOrBlank()) {
            val targetServer = FreeCatalog.resolveServer(credentialStore.server(), savedUser, savedPass, server)
            startActivity(Intent(this, LoadingActivity::class.java).putExtra("server", targetServer).putExtra("username", savedUser).putExtra("password", savedPass))
            finish(); return
        }
        // Never pop the on-screen keyboard automatically on a TV; it opens when a field is activated with OK.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        setContentView(R.layout.activity_main)
        // The window is edge-to-edge, so lift the form above the keyboard ourselves; the form scrolls to the focused field.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.loginRoot)) { v, insets ->
            v.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            insets
        }
        findViewById<View>(R.id.loginShell)?.apply {
            clipRounded(dpf(22))
            if (Build.VERSION.SDK_INT >= 28) {
                elevation = dpf(14)
                outlineAmbientShadowColor = Color.parseColor("#6600D2FF")
                outlineSpotShadowColor = Color.parseColor("#8800D2FF")
            }
        }
        listOf(
            R.id.featIcon1 to "#2FB4FF",
            R.id.featIcon2 to "#E8384F",
            R.id.featIcon3 to "#25D9BD",
            R.id.featIcon4 to "#FFB800"
        ).forEach { (id, hex) ->
            val c = Color.parseColor(hex)
            findViewById<View>(id)?.background = roundBg(UI.alpha(c, 42), 8, UI.alpha(c, 120), 1)
        }
        // Keep XML bg_art_login ImageView backdrop visible behind the translucent login panel.
        findViewById<TextView>(R.id.welcomeName).apply {
            val width = paint.measureText(text.toString())
            paint.shader = LinearGradient(0f, 0f, width, 0f,
                intArrayOf(Color.parseColor("#00D2FF"), Color.parseColor("#7BE6FF"), Color.parseColor("#FFFFFF")),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        togglePassword = findViewById(R.id.togglePassword)
        loginProgress = findViewById(R.id.loginProgress)
        statusRow = findViewById(R.id.statusRow)
        connectLabel = findViewById(R.id.connectLabel)
        val helpRow = findViewById<View>(R.id.helpRow)
        val helpIcon = findViewById<ImageView>(R.id.helpIcon)
        val helpLabel = findViewById<TextView>(R.id.helpLabel)
        helpRow.setOnClickListener { showHelp() }
        helpRow.setOnFocusChangeListener { _, focused ->
            val c = if (focused) UI.NEON else UI.MUTED
            helpIcon.setColorFilter(c)
            helpLabel.setTextColor(c)
        }
        val contactRow = findViewById<View>(R.id.contactRow)
        val contactLabel = findViewById<TextView>(R.id.contactLabel)
        contactRow.setOnClickListener {
            SupportContact.openWhatsApp(this, "السلام عليكم، أريد الاستفسار عن الحصول على حساب في تطبيق الحر الأصلي.")
        }
        contactRow.setOnFocusChangeListener { _, focused ->
            contactLabel.setTextColor(if (focused) UI.GREEN else UI.MUTED)
        }
        findViewById<TextView>(R.id.versionText).text = try {
            "v" + packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "" }
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        rememberLogin = findViewById(R.id.rememberLogin)
        statusText = findViewById(R.id.statusText)
        connectButton = findViewById(R.id.connectButton)
        if (Build.VERSION.SDK_INT >= 28) {
            connectButton.elevation = dpf(10)
            connectButton.outlineAmbientShadowColor = Color.parseColor("#8800D2FF")
            connectButton.outlineSpotShadowColor = Color.parseColor("#AA00D2FF")
        }
        rememberLogin.isChecked = rememberSaved
        if (!addMode) {
            credentialStore.username()?.let(usernameInput::setText)
            credentialStore.password()?.let(passwordInput::setText)
        }
        connectButton.setOnClickListener { connect() }
        connectLabel.setTextColor(Color.parseColor("#001018"))
        togglePassword.setOnClickListener { setPasswordVisible(!passwordVisible) }
        setStatus(hintText, Status.HINT)
        usernameInput.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_NEXT || event?.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                passwordInput.requestFocus(); true
            } else false
        }
        passwordInput.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER) { connect(); true } else false
        }
        usernameInput.post { if (!usernameInput.hasFocus()) usernameInput.requestFocus() }
    }

    private fun connect() {
        val user = usernameInput.text.toString().trim()
        val pass = passwordInput.text.toString()
        if (user.isBlank()) { setStatus("أدخل اسم المستخدم للمتابعة.", Status.ERROR); usernameInput.requestFocus(); return }
        if (pass.isBlank()) { setStatus("أدخل كلمة المرور للمتابعة.", Status.ERROR); passwordInput.requestFocus(); return }
        val isFree = FreeCatalog.isFreeCredentials(user, pass)
        val targetServer = if (isFree) FreeCatalog.FREE_SERVER else server
        connectButton.isEnabled = false
        connectLabel.text = "جارٍ الدخول…"
        setStatus(if (isFree) "جارٍ تفعيل الحساب المجاني…" else "جارٍ التحقق من بيانات الدخول…", Status.LOADING)
        executor.execute {
            val account = if (isFree) FreeCatalog.freeAccountInfo() else XtreamRepository(this, server, user, pass).account()
            val info = account?.optJSONObject("user_info")
            val auth = if (account == null) 0 else info?.optInt("auth", account.optInt("auth", 0)) ?: account.optInt("auth", 0)
            val active = info?.optString("status", "")?.equals("Active", true) ?: true
            val prevServer = credentialStore.server().orEmpty()
            val prevUser = credentialStore.username().orEmpty()
            val switchingAccount = intent.getBooleanExtra("add_account", false) ||
                (prevUser.isNotBlank() && (prevUser != user || FreeCatalog.isFreeServer(prevServer) != isFree))
            if (auth == 1 && active && switchingAccount) {
                // Switching between free & paid (or adding another account): drop previous cached library cleanly.
                SyncManager.reset(this)
                try { ContentDatabase.get(this).clearAllTables() } catch (_: Exception) { }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                connectButton.isEnabled = true
                connectLabel.text = "دخول"
                if (auth == 1 && active) {
                    setStatus(hintText, Status.HINT)
                    val remember = rememberLogin.isChecked
                    val options = getSharedPreferences("login_options", MODE_PRIVATE)
                    options.edit().putBoolean("remember_login", remember).apply()
                    if (remember) {
                        try { credentialStore.save(targetServer, user, pass); credentialStore.addAccount(targetServer, user, pass) }
                        catch (_: Exception) {
                            options.edit().putBoolean("remember_login", false).apply()
                            rememberLogin.isChecked = false
                            setStatus("تم الدخول، لكن تعذر حفظ الجلسة على الجهاز.", Status.WARNING)
                        }
                    } else credentialStore.clear()
                    startActivity(Intent(this, LoadingActivity::class.java).apply {
                        if (intent.getBooleanExtra("add_account", false)) {
                            // New account added from Settings: start a fresh task so the old account's screens are gone.
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                        putExtra("server", targetServer)
                        putExtra("username", user)
                        putExtra("password", pass)
                        putExtra("user_info", info?.toString())
                    })
                    finish()
                } else {
                    setStatus(if (account == null) "تعذر الاتصال بالخادم. تحقق من اتصال الإنترنت ثم حاول مرة أخرى."
                              else "بيانات الدخول غير صحيحة أو الحساب غير نشط.", Status.ERROR)
                    connectButton.requestFocus()
                }
            }
        }
    }

    private fun showHelp() {
        val before = currentFocus
        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = roundBg(Color.parseColor("#131722"), 18, UI.NEON, 1)
            clipRounded(dpf(18))
            if (Build.VERSION.SDK_INT >= 28) {
                elevation = dpf(14)
                outlineAmbientShadowColor = Color.parseColor("#7700D2FF")
                outlineSpotShadowColor = Color.parseColor("#9900D2FF")
            }
        }
        // Header matching Web LoginScreen help modal
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0F131E"))
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        val titleWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleWrap.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_login_help)
            setColorFilter(UI.NEON)
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
        titleWrap.addView(tv("مساعدة في تسجيل الدخول", 15f, Color.WHITE, true))
        head.addView(titleWrap, LinearLayout.LayoutParams(0, -2, 1f))
        val closeIcon = tv("✕", 14f, UI.MUTED, true).apply {
            gravity = Gravity.CENTER
            background = roundBg(Color.parseColor("#1C2230"), 8, UI.STROKE, 1)
            makeFocusable(1.06f, UI.NEON, 2, 8)
            setOnClickListener { dialog.dismiss() }
        }
        head.addView(closeIcon, LinearLayout.LayoutParams(dp(30), dp(30)))
        root.addView(head)
        root.addView(View(this).apply { setBackgroundColor(UI.STROKE) }, LinearLayout.LayoutParams(-1, dp(1)))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(18))
        }
        fun bullet(text: CharSequence) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            row.addView(tv("•", 14f, UI.NEON, true).apply {
                setPadding(0, 0, dp(8), 0)
            })
            row.addView(TextView(this).apply {
                this.text = text
                textSize = 12.5f
                setTextColor(Color.parseColor("#CBD5E1"))
                setLineSpacing(0f, 1.3f)
                gravity = Gravity.START
            }, LinearLayout.LayoutParams(0, -2, 1f))
            body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }

        bullet("أدخل اسم المستخدم وكلمة المرور كما زوّدك بهما مزود الخدمة للاشتراك المدفوع.")
        val freeSpan = SpannableStringBuilder().apply {
            append("للباقة المجانية (قنوات عربية مجانية + أفلام ومسلسلات مفتوحة): اسم المستخدم ")
            val uStart = length
            append(FreeCatalog.FREE_USER)
            setSpan(ForegroundColorSpan(UI.NEON), uStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(TypefaceSpan("monospace"), uStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(" وكلمة المرور ")
            val pStart = length
            append(FreeCatalog.FREE_PASS)
            setSpan(ForegroundColorSpan(UI.NEON), pStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(TypefaceSpan("monospace"), pStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(".")
        }
        bullet(freeSpan)
        bullet("تأكد من اتصال الجهاز بالإنترنت وانتبه للأحرف الكبيرة والصغيرة؛ يمكنك إظهار كلمة المرور بزر العين للتأكد منها.")
        bullet("للحصول على حساب مدفوع تواصل معنا عبر واتساب: ${SupportContact.DISPLAY}")

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(18))
        }
        val whatsappBtn = tv("تواصل عبر واتساب", 12.5f, Color.parseColor("#001018"), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(9), dp(16), dp(9))
            val idle = roundBg(Color.parseColor("#25D9BD"), 12)
            val focused = roundBg(Color.parseColor("#4BF2D6"), 12, Color.WHITE, 2)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(android.R.attr.state_pressed), focused)
                addState(intArrayOf(), idle)
            }
            isFocusable = true; isClickable = true; isFocusableInTouchMode = false
            setOnClickListener {
                dialog.dismiss()
                SupportContact.openWhatsApp(this@MainActivity, "السلام عليكم، أحتاج مساعدة في تسجيل الدخول إلى تطبيق الحر الأصلي.")
            }
        }
        val closeBtn = tv("إغلاق", 12.5f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(9), dp(18), dp(9))
            val idle = roundBg(Color.parseColor("#1C2230"), 12, UI.STROKE, 1)
            val focused = roundBg(Color.parseColor("#253048"), 12, UI.NEON, 2)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(android.R.attr.state_pressed), focused)
                addState(intArrayOf(), idle)
            }
            isFocusable = true; isClickable = true; isFocusableInTouchMode = false
            setOnClickListener { dialog.dismiss() }
        }
        footer.addView(whatsappBtn, LinearLayout.LayoutParams(-2, -2))
        footer.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        footer.addView(closeBtn, LinearLayout.LayoutParams(-2, -2))
        root.addView(footer)

        dialog.setOnDismissListener {
            before?.post { if (before.isShown && before.isFocusable) before.requestFocus() }
        }
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(450), WindowManager.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.75f)
        }
        closeBtn.post { closeBtn.requestFocus() }
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}
