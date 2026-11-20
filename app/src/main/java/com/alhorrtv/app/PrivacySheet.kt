package com.alhorrtv.app

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService

/**
 * Neon dialogs of the privacy lock: set / ask for the password, "forgot password" reset, and the manager where the user hides
 * or shows categories and single channels / movies / series by name. Data work is done by [XtreamRepository] / [HiddenStore].
 */
internal class PrivacySheet(
    private val activity: FullscreenActivity,
    private val repo: XtreamRepository,
    private val io: ExecutorService,
    private val onChanged: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private fun dp(v: Int) = activity.dp(v)
    private fun pxf(v: Float) = v * activity.resources.displayMetrics.density
    private fun ui(block: () -> Unit) = activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) block() }
    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    // ------------------------------------------------------------------ building blocks

    private fun panel(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(20), dp(16), dp(20), dp(16)); background = Neon.panel(pxf(26f), pxf(1.6f), pxf(5f))
    }

    private fun show(content: View, widthDp: Int, heightDp: Int = WindowManager.LayoutParams.WRAP_CONTENT): Dialog {
        val hostFocus = activity.currentFocus
        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(widthDp), if (heightDp > 0) dp(heightDp) else heightDp)
            setDimAmount(0.65f); setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setOnDismissListener {
            hostFocus?.takeIf { it.isShown && it.isFocusable }?.let { v -> v.post { v.requestFocus() } }
        }
        dialog.show()
        return dialog
    }

    private fun View.scaleOnFocus(scale: Float) {
        isFocusable = true; isClickable = true; isFocusableInTouchMode = false
        setOnFocusChangeListener { v, f -> v.animate().scaleX(if (f) scale else 1f).scaleY(if (f) scale else 1f).setDuration(110).start() }
    }

    private fun button(label: String, top: Int, bottom: Int, edge: IntArray, onClick: () -> Unit): TextView =
        activity.tv(label, 14f, Neon.TEXT, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(10), 0)
            background = Neon.button(pxf(22f), pxf(1.2f), pxf(4f), top, bottom, edge)
            scaleOnFocus(1.05f)
            val base = onFocusChangeListener
            setOnFocusChangeListener { v, focused ->
                base?.onFocusChange(v, focused)
                setTextColor(if (focused) Color.parseColor("#001018") else Neon.TEXT)
            }
            setOnClickListener { onClick() }
        }

    private fun primary(label: String, onClick: () -> Unit) =
        button(label, UI.PANEL2, Color.parseColor("#FF0A3A4A"), intArrayOf(UI.NEON, Neon.TEAL, UI.NEON), onClick)
    private fun neutral(label: String, onClick: () -> Unit) =
        button(label, UI.PANEL2, UI.PANEL, intArrayOf(UI.STROKE, UI.NEON_DEEP, UI.STROKE), onClick)
    private fun danger(label: String, onClick: () -> Unit) =
        button(label, Color.parseColor("#FF2E141E"), Color.parseColor("#FF1C0C12"), intArrayOf(UI.MOVIE, Neon.RED, UI.MOVIE), onClick)

    private fun headline(parent: LinearLayout, iconRes: Int, title: String, subtitle: String) {
        val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(activity).apply {
            setImageResource(iconRes); setColorFilter(Neon.GOLD); setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(46, 242, 210, 122)); setStroke(dp(1), Color.argb(120, 242, 210, 122)) }
        }, lp(dp(40), dp(40)))
        val titles = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        titles.addView(activity.tv(title, 19f, Neon.TEXT, true))
        if (subtitle.isNotBlank()) titles.addView(activity.tv(subtitle, 11f, Neon.MUTED))
        head.addView(titles)
        parent.addView(head, lp(-1, -2))
        parent.addView(View(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.TRANSPARENT, Neon.GOLD, Color.TRANSPARENT))
        }, lp(-1, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(10) })
    }

    private fun field(hint: String, password: Boolean): Pair<LinearLayout, EditText> {
        val input = EditText(activity).apply {
            this.hint = hint; setHintTextColor(Neon.MUTED); setTextColor(Neon.TEXT); textSize = 15f; setSingleLine()
            background = null; textDirection = View.TEXT_DIRECTION_LTR; gravity = Gravity.START or Gravity.CENTER_VERTICAL
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), 0, dp(14), 0)
            background = Neon.card(pxf(16f), pxf(1f), pxf(3f))
        }
        box.addView(input, lp(-1, dp(46)))
        return box to input
    }

    private fun message(parent: LinearLayout, text: String, color: Int = Neon.MUTED): TextView =
        activity.tv(text, 12.5f, color).apply { setLineSpacing(dp(3).toFloat(), 1f); setPadding(0, dp(4), 0, dp(4)) }.also { parent.addView(it, lp(-1, -2)) }

    // ------------------------------------------------------------------ password dialogs

    /** First-time setup: new password + confirmation, then [onDone]. */
    fun setPassword(onDone: () -> Unit) {
        val p = panel()
        headline(p, R.drawable.ic_set_shield, "تعيين كلمة المرور", "تحمي إدارة المحتوى المخفي")
        val (newBox, newInput) = field("كلمة المرور الجديدة (4 أحرف على الأقل)", true)
        val (confirmBox, confirmInput) = field("أعد كتابة كلمة المرور", true)
        p.addView(newBox, lp(-1, dp(52))); p.addView(confirmBox, lp(-1, dp(52)).apply { topMargin = dp(10) })
        val status = message(p, "اكتب كلمة مرور تتذكرها. إن نسيتها فلن تُستعاد، بل تُعاد التعيين مع حذف كل المخفي.")
        lateinit var dialog: Dialog
        val save = primary("حفظ كلمة المرور") {
            val a = newInput.text.toString(); val b = confirmInput.text.toString()
            when {
                a.length < 4 -> { status.text = "كلمة المرور قصيرة: 4 أحرف على الأقل."; status.setTextColor(Neon.RED) }
                a != b -> { status.text = "كلمتا المرور غير متطابقتين."; status.setTextColor(Neon.RED) }
                else -> { io.execute { HiddenStore.setPassword(activity, a); ui { dialog.dismiss(); onChanged(); onDone() } } }
            }
        }
        val cancel = neutral("إلغاء") { dialog.dismiss() }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(save, lp(0, dp(46), 1.4f).apply { marginEnd = dp(5) }); row.addView(cancel, lp(0, dp(46), 1f).apply { marginStart = dp(5) })
        p.addView(row, lp(-1, -2).apply { topMargin = dp(12) })
        dialog = show(p, 440)
        newInput.requestFocus()
    }

    /** Asks for the password; [onOk] runs only after a correct one. */
    fun askPassword(onOk: () -> Unit) {
        val p = panel()
        headline(p, R.drawable.ic_set_shield, "أدخل كلمة المرور", "لفتح إدارة المحتوى المخفي")
        val (box, input) = field("كلمة المرور", true)
        p.addView(box, lp(-1, dp(52)))
        val status = message(p, "")
        lateinit var dialog: Dialog
        fun submit() {
            val wait = HiddenStore.lockedForSeconds(activity)
            if (wait > 0) { status.text = "محاولات خاطئة كثيرة. حاول بعد $wait ثانية."; status.setTextColor(Neon.RED); return }
            val entered = input.text.toString()
            io.execute {
                val ok = HiddenStore.check(activity, entered)
                ui {
                    if (ok) { dialog.dismiss(); onOk() }
                    else {
                        val left = HiddenStore.lockedForSeconds(activity)
                        status.text = if (left > 0) "محاولات خاطئة كثيرة. حاول بعد $left ثانية." else "كلمة المرور غير صحيحة."
                        status.setTextColor(Neon.RED); input.selectAll()
                    }
                }
            }
        }
        input.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        input.setOnEditorActionListener { _, action, _ -> if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
        val ok = primary("فتح") { submit() }
        val cancel = neutral("إلغاء") { dialog.dismiss() }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(ok, lp(0, dp(46), 1.3f).apply { marginEnd = dp(5) }); row.addView(cancel, lp(0, dp(46), 1f).apply { marginStart = dp(5) })
        p.addView(row, lp(-1, -2).apply { topMargin = dp(8) })
        p.addView(activity.tv("نسيت كلمة المرور؟", 12.5f, Neon.GOLD, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(6)); scaleOnFocus(1.05f)
            setOnClickListener { dialog.dismiss(); forgot() }
        }, lp(-1, -2))
        dialog = show(p, 440)
        input.requestFocus()
    }

    /** "Forgot password": explains the only way out (everything hidden is shown again, then a new password is set). */
    fun forgot() {
        val p = panel()
        headline(p, R.drawable.ic_set_info, "نسيت كلمة المرور؟", "تعليمات إعادة التعيين")
        message(p, "كلمة المرور لا تُحفظ نصًا ولا يمكن استرجاعها، والحل الوحيد هو إعادة التعيين:", Neon.TEXT)
        message(p, "١) يُحذف كل ما أخفيته: الفئات والأفلام والمسلسلات والقنوات، ويظهر المحتوى كله من جديد.")
        message(p, "٢) تضع كلمة مرور جديدة.")
        message(p, "٣) تخفي الفئات والعناصر التي تريدها من جديد.")
        message(p, "نصيحة: احتفظ بكلمة المرور في مكان آمن.", Neon.GOLD)
        lateinit var dialog: Dialog
        val reset = danger("حذف كل المخفي وإعادة التعيين") {
            io.execute {
                try { repo.showEverything() } catch (_: Exception) { }
                ui { dialog.dismiss(); toast("ظهر كل المحتوى المخفي. ضع الآن كلمة مرور جديدة."); onChanged(); setPassword { openManager() } }
            }
        }
        val cancel = neutral("إلغاء") { dialog.dismiss() }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(cancel, lp(0, dp(46), 1f).apply { marginEnd = dp(5) }); row.addView(reset, lp(0, dp(46), 1.6f).apply { marginStart = dp(5) })
        p.addView(row, lp(-1, -2).apply { topMargin = dp(12) })
        dialog = show(p, 460)
        cancel.requestFocus()   // the safe choice has the focus first
    }

    // ------------------------------------------------------------------ manager

    private var kind = Kinds.SERIES
    private var tab = 0

    /** The manager (call only after the password was entered or just created). */
    fun openManager() {
        val p = panel()
        headline(p, R.drawable.ic_set_shield, "إدارة المحتوى المخفي", "الفئات، أو مسلسل / فيلم / قناة بعينها باسمها")
        val kindChips = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val tabChips = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        p.addView(kindChips, lp(-1, dp(40))); p.addView(tabChips, lp(-1, dp(40)).apply { topMargin = dp(6) })
        val searchHost = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val (searchBox, searchInput) = field("اكتب اسم المسلسل أو الفيلم أو القناة…", false)
        searchHost.addView(searchBox, lp(-1, dp(48)).apply { topMargin = dp(8) })
        p.addView(searchHost, lp(-1, -2))
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(list) }
        p.addView(scroll, lp(-1, 0, 1f).apply { topMargin = dp(8) })
        lateinit var dialog: Dialog
        val footer = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        footer.addView(neutral("تغيير كلمة المرور") { setPassword { toast("تم تغيير كلمة المرور.") } }, lp(0, dp(44), 1f).apply { marginEnd = dp(5) })
        footer.addView(primary("تم") { dialog.dismiss(); onChanged() }, lp(0, dp(44), 1f).apply { marginStart = dp(5) })
        p.addView(footer, lp(-1, -2).apply { topMargin = dp(10) })

        var gen = 0
        fun emptyNote(text: String) { list.addView(activity.tv(text, 13f, Neon.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, 0) }, lp(-1, -2)) }
        fun restoreListFocus(focusKey: String?, fallbackIndex: Int) {
            if (focusKey == null && fallbackIndex < 0) return
            list.post {
                if (list.childCount == 0) {
                    val activeTab = tabChips.findViewWithTag<View>("tab:$tab")
                    activeTab?.requestFocus()
                    return@post
                }
                val match = if (focusKey != null) list.findViewWithTag<View>(focusKey) else null
                val target = match ?: if (fallbackIndex >= 0) list.getChildAt(fallbackIndex.coerceAtMost(list.childCount - 1)) else null
                target?.requestFocus()
            }
        }
        fun row(key: String, label: String, state: String, stateColor: Int, accent: Int, onClick: (Int) -> Unit) {
            val r = LinearLayout(activity).apply {
                tag = key
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(dp(10), dp(4), dp(10), dp(4)); background = Neon.card(pxf(14f), pxf(1f), pxf(2f))
                scaleOnFocus(1.02f)
            }
            r.setOnClickListener { onClick(list.indexOfChild(r)) }
            r.addView(ImageView(activity).apply { setImageResource(R.drawable.ic_set_eye); setColorFilter(accent) }, lp(dp(22), dp(22)))
            r.addView(activity.tv(label, 14f, Neon.TEXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(10), 0, dp(8), 0) }, lp(0, -2, 1f))
            r.addView(activity.tv(state, 12f, stateColor, true))
            list.addView(r, lp(-1, dp(44)).apply { bottomMargin = dp(5) })
        }

        fun render(focusRowKey: String? = null, fallbackIndex: Int = -1) {
            val my = ++gen
            list.removeAllViews()
            searchHost.visibility = if (tab == 1) View.VISIBLE else View.GONE
            when (tab) {
                0 -> io.execute {
                    val rows = try { repo.manageCategories(kind) } catch (_: Exception) { emptyList() }
                    ui {
                        if (my != gen) return@ui
                        if (rows.isEmpty()) emptyNote("لا توجد فئات بعد. افتح هذا القسم مرة ليتم تحميل فئاته.")
                        rows.forEach { (cat, hidden) ->
                            val key = "cat:${cat.categoryId}"
                            row(key, cat.categoryName, if (hidden) "مخفية" else "ظاهرة", if (hidden) Neon.RED else Neon.TEAL, if (hidden) Neon.MUTED else Neon.GOLD) { idx ->
                                io.execute { repo.setCategoryHidden(kind, cat.categoryId, !hidden); ui { onChanged(); render(key, idx) } }
                            }
                        }
                        restoreListFocus(focusRowKey, fallbackIndex)
                    }
                }
                1 -> {
                    val q = searchInput.text.toString().trim()
                    if (q.isEmpty()) { emptyNote("اكتب اسمًا للبحث ثم اختر العنصر لإخفائه."); return }
                    io.execute {
                        val found = try { repo.browse(kind, null, q, 40, 0) } catch (_: Exception) { emptyList() }
                        ui {
                            if (my != gen) return@ui
                            if (found.isEmpty()) emptyNote("لا توجد نتائج.")
                            found.forEach { item ->
                                val key = "item:${item.contentId}"
                                row(key, item.name, "إخفاء", Neon.RED, Neon.GOLD) { idx ->
                                    io.execute { repo.setItemHidden(kind, item.contentId, item.name, true); ui { toast("تم إخفاء: ${item.name}"); onChanged(); render(key, idx) } }
                                }
                            }
                            restoreListFocus(focusRowKey, fallbackIndex)
                        }
                    }
                }
                else -> io.execute {
                    val cats = try { repo.manageCategories(kind).filter { it.second } } catch (_: Exception) { emptyList() }
                    val items = HiddenStore.hiddenItems(activity, kind)
                    ui {
                        if (my != gen) return@ui
                        if (cats.isEmpty() && items.isEmpty()) emptyNote("لا يوجد شيء مخفي في هذا القسم.")
                        cats.forEach { (cat, _) ->
                            val key = "ucat:${cat.categoryId}"
                            row(key, "فئة: ${cat.categoryName}", "إظهار", Neon.TEAL, Neon.MUTED) { idx ->
                                io.execute { repo.setCategoryHidden(kind, cat.categoryId, false); ui { onChanged(); render(key, idx) } }
                            }
                        }
                        items.forEach { (id, name) ->
                            val key = "uitem:$id"
                            row(key, name, "إظهار", Neon.TEAL, Neon.MUTED) { idx ->
                                io.execute { repo.setItemHidden(kind, id, name, false); ui { onChanged(); render(key, idx) } }
                            }
                        }
                        restoreListFocus(focusRowKey, fallbackIndex)
                    }
                }
            }
        }

        fun renderChips(focusTag: String? = null) {
            kindChips.removeAllViews(); tabChips.removeAllViews()
            var targetFocus: View? = null
            listOf(Kinds.LIVE to "القنوات", Kinds.MOVIES to "الأفلام", Kinds.SERIES to "المسلسلات").forEach { (k, label) ->
                val chipTag = "kind:$k"
                val v = activity.chip(label, kind == k, UI.accent(k)).apply {
                    tag = chipTag
                    setOnClickListener { kind = k; renderChips(chipTag); render() }
                }
                if (focusTag == chipTag) targetFocus = v
                kindChips.addView(v, lp(-2, dp(36)).apply { marginEnd = dp(8) })
            }
            listOf("الفئات", "إخفاء بالاسم", "المخفي الآن").forEachIndexed { i, label ->
                val chipTag = "tab:$i"
                val v = activity.chip(label, tab == i, Neon.GOLD).apply {
                    tag = chipTag
                    setOnClickListener { tab = i; renderChips(chipTag); render() }
                }
                if (focusTag == chipTag) targetFocus = v
                tabChips.addView(v, lp(-2, dp(36)).apply { marginEnd = dp(8) })
            }
            targetFocus?.let { v -> kindChips.post { v.requestFocus() } }
        }
        var pending: Runnable? = null
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                pending?.let(main::removeCallbacks); pending = Runnable { if (tab == 1) render() }; main.postDelayed(pending!!, 400)
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        dialog = show(p, 560, 470)
        renderChips(); render()
    }
}
