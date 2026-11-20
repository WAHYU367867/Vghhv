package com.alhorrtv.app

import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * Independent Adult 18+ section for authorized/legal stream sources.
 * Launches the existing PlayerActivity without modifying it or touching normal channels/movies/series.
 */
class AdultActivity : FullscreenActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private val allChannels = ArrayList<AdultChannel>()
    private val visibleChannels = ArrayList<AdultChannel>()
    private val adapter = ChannelAdapter()

    private lateinit var recycler: RecyclerView
    private lateinit var chipRow: LinearLayout
    private lateinit var chipScroll: HorizontalScrollView
    private lateinit var emptyBox: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptySub: TextView
    private lateinit var searchInput: EditText

    private var selectedCategory = ""
    private var query = ""
    private var built = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!AdultStore.isEnabled(this)) {
            Toast.makeText(this, "قسم Adult 18+ معطّل من الإعدادات.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (!AdultStore.isAgeConfirmed()) {
            setContentView(FrameLayout(this).apply { background = HexPatternDrawable() })
            AdultAgeGate.show(
                this,
                onConfirmed = {
                    if (!isFinishing && !isDestroyed && !built) initScreen()
                },
                onCanceled = {
                    if (!isFinishing && !isDestroyed) finish()
                }
            )
            return
        }
        initScreen()
    }

    private fun initScreen() {
        built = true
        buildUi()
        reloadChannels()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        val danger = Color.parseColor("#E8384F")
        val dangerLight = Color.parseColor("#FF7B8B")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = HexPatternDrawable()
        }

        // Top Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xE60A0C10.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        header.addView(tv("→", 18f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = roundBg(Color.parseColor("#131722"), 10, UI.STROKE, 1)
            contentDescription = "رجوع"
            makeFocusable(1.04f, UI.NEON, 2, 10)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))

        header.addView(tv("18+", 11f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = roundBg(danger, 6)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
        }
        titles.addView(tv("Adult 18+", 17f, Color.WHITE, true))
        titles.addView(tv("قسم مستقل · مصادر بث قانونية ومصرح بها", 10.5f, UI.MUTED))
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))

        searchInput = EditText(this).apply {
            hint = "ابحث…"
            setHintTextColor(UI.MUTED_DIM)
            setTextColor(Color.WHITE)
            textSize = 12f
            setSingleLine()
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundBg(Color.parseColor("#131722"), 10, UI.STROKE, 1)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    query = s?.toString().orEmpty()
                    applyFilters()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        header.addView(searchInput, LinearLayout.LayoutParams(dp(170), dp(36)).apply { marginEnd = dp(8) })

        header.addView(tv("+ إضافة قناة", 12f, Color.parseColor("#001018"), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = roundBg(UI.NEON, 10)
            makeFocusable(1.04f, Color.WHITE, 2, 10)
            setOnClickListener { showAddChannelDialog() }
        }, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(8) })

        header.addView(tv("استيراد M3U", 12f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = roundBg(Color.parseColor("#131722"), 10, UI.STROKE, 1)
            makeFocusable(1.04f, UI.NEON, 2, 10)
            setOnClickListener { showImportM3uDialog() }
        }, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(8) })

        header.addView(tv("قفل القسم", 11.5f, dangerLight, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundBg(Color.parseColor("#131722"), 10, UI.alpha(danger, 150), 1)
            makeFocusable(1.04f, danger, 2, 10)
            setOnClickListener {
                AdultStore.clearAgeConfirmation()
                finish()
            }
        }, LinearLayout.LayoutParams(-2, dp(36)))

        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        root.addView(View(this).apply { setBackgroundColor(UI.STROKE) }, LinearLayout.LayoutParams(-1, dp(1)))

        // Category Chips bar
        chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = View.GONE
            addView(chipRow)
        }
        root.addView(chipScroll, LinearLayout.LayoutParams(-1, -2))

        // Compliance strip
        val strip = tv(
            "تُشغَّل القنوات عبر مشغّل الفيديو الداخلي للتطبيق دون أي تأثير على القنوات العادية أو الأفلام والمسلسلات.",
            11.5f, UI.MUTED
        ).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = roundBg(Color.parseColor("#CC131722"), 12, UI.STROKE, 1)
        }
        root.addView(strip, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(10), dp(16), dp(4)) })

        // Body host
        val bodyHost = FrameLayout(this)
        recycler = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@AdultActivity, 2)
            adapter = this@AdultActivity.adapter
            setPadding(dp(12), dp(8), dp(12), dp(16))
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            itemAnimator = null
        }
        bodyHost.addView(recycler, FrameLayout.LayoutParams(-1, -1))

        // Empty State Card
        emptyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(26), dp(28), dp(26))
            background = roundBg(Color.parseColor("#E6131722"), 16, UI.STROKE, 1)
            visibility = View.GONE
        }
        emptyTitle = tv("لا توجد قنوات مضافة في قسم Adult 18+ بعد", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
        }
        emptySub = tv(
            "أضف قنوات من مصادر بث قانونية ومصرح بها (روابط HLS / MP4 أو قوائم M3U مرخّصة) لتشغيلها داخل هذا القسم المستقل.",
            12.5f, UI.MUTED
        ).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
            setPadding(0, dp(8), 0, dp(16))
        }
        val emptyBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        emptyBtns.addView(tv("+ إضافة قناة مصرح بها", 12.5f, Color.parseColor("#001018"), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = roundBg(UI.NEON, 12)
            makeFocusable(1.04f, Color.WHITE, 2, 12)
            setOnClickListener { showAddChannelDialog() }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        emptyBtns.addView(tv("استيراد قائمة M3U", 12.5f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = roundBg(UI.PANEL2, 12, UI.STROKE, 1)
            makeFocusable(1.04f, UI.NEON, 2, 12)
            setOnClickListener { showImportM3uDialog() }
        })
        emptyBox.addView(emptyTitle)
        emptyBox.addView(emptySub)
        emptyBox.addView(emptyBtns)
        bodyHost.addView(emptyBox, FrameLayout.LayoutParams(dp(560), -2, Gravity.CENTER))

        root.addView(bodyHost, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun reloadChannels() {
        allChannels.clear()
        allChannels.addAll(AdultStore.getChannels(this))
        renderChips()
        applyFilters()
    }

    private fun renderChips(focusCategory: String? = null) {
        val cats = allChannels.map { it.category }.distinct().filter { it.isNotBlank() }
        chipRow.removeAllViews()
        if (cats.isEmpty()) {
            chipScroll.visibility = View.GONE
            return
        }
        chipScroll.visibility = View.VISIBLE
        var restoreView: View? = null
        fun addChip(label: String, catValue: String) {
            val active = selectedCategory == catValue
            val chip = tv(label, 12f, if (active) Color.parseColor("#001018") else Color.parseColor("#CBD5E1"), true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = if (active) roundBg(UI.NEON, 99) else roundBg(Color.parseColor("#131722"), 99, UI.STROKE, 1)
                makeFocusable(1.04f, UI.NEON, 2, 99)
                setOnClickListener {
                    selectedCategory = catValue
                    renderChips(catValue)
                    applyFilters()
                }
            }
            if (focusCategory != null && focusCategory == catValue) restoreView = chip
            chipRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        addChip("الكل (${allChannels.size})", "")
        cats.forEach { addChip(it, it) }
        restoreView?.let { v -> chipRow.post { v.requestFocus() } }
    }

    private fun applyFilters() {
        val q = query.trim().lowercase()
        visibleChannels.clear()
        allChannels.filterTo(visibleChannels) { ch ->
            (selectedCategory.isBlank() || ch.category == selectedCategory) &&
                (q.isBlank() || ch.name.lowercase().contains(q) || ch.category.lowercase().contains(q))
        }
        adapter.notifyDataSetChanged()
        if (visibleChannels.isEmpty()) {
            emptyBox.visibility = View.VISIBLE
            if (allChannels.isEmpty()) {
                emptyTitle.text = "لا توجد قنوات مضافة في قسم Adult 18+ بعد"
                emptySub.text = "أضف قنوات من مصادر بث قانونية ومصرح بها (روابط HLS / MP4 أو قوائم M3U مرخّصة) لتشغيلها داخل هذا القسم المستقل."
            } else {
                emptyTitle.text = "لا توجد نتائج مطابقة للبحث"
                emptySub.text = "جرّب تعديل كلمة البحث أو اختيار تصنيف آخر."
            }
        } else {
            emptyBox.visibility = View.GONE
        }
    }

    private fun playChannel(ch: AdultChannel) {
        // Launch the existing PlayerActivity without modifying it or polluting normal live/VOD tables.
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("url", ch.streamUrl)
                .putExtra("title", ch.name)
                .putExtra("image", ch.logoUrl)
        )
    }

    // ------------------------------------------------------------------ Dark Modals

    private fun darkModal(title: String, widthDp: Int, fill: (LinearLayout, Dialog) -> Unit) {
        val before = currentFocus
        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = roundBg(Color.parseColor("#131722"), 16, UI.STROKE, 1)
            clipRounded(dpf(16))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0F131E"))
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        head.addView(tv(title, 15f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(tv("✕", 14f, UI.MUTED, true).apply {
            gravity = Gravity.CENTER
            makeFocusable(1.08f, UI.NEON, 1, 8)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(28), dp(28)))
        root.addView(head)
        root.addView(View(this).apply { setBackgroundColor(UI.STROKE) }, LinearLayout.LayoutParams(-1, dp(1)))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
        }
        fill(body, dialog)
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2))

        dialog.setOnDismissListener {
            ui.post { before?.takeIf { it.isShown && it.isFocusable }?.requestFocus() }
        }
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(widthDp), WindowManager.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.75f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    private fun modalField(hintText: String, ltr: Boolean = false, multiline: Boolean = false): EditText =
        EditText(this).apply {
            hint = hintText
            setHintTextColor(UI.MUTED_DIM)
            setTextColor(Color.WHITE)
            textSize = 13f
            setSingleLine(!multiline)
            if (multiline) {
                minLines = 3
                maxLines = 5
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                gravity = Gravity.TOP or Gravity.START
            } else {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
            }
            if (ltr) textDirection = View.TEXT_DIRECTION_LTR
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundBg(UI.PANEL2, 12, UI.STROKE, 1)
        }

    private fun showAddChannelDialog() {
        darkModal("إضافة قناة مصرح بها (+18)", 460) { body, dialog ->
            val nameIn = modalField("اسم القناة")
            val urlIn = modalField("رابط البث (https://...m3u8 أو mp4)", ltr = true)
            val catIn = modalField("التصنيف (اختياري)").apply { setText("مصادر مصرح بها") }
            val logoIn = modalField("رابط الشعار (اختياري)", ltr = true)
            val authCheck = CheckBox(this).apply {
                text = "أقر بأن مصدر البث قانوني ومصرح ببثه"
                textSize = 12f
                setTextColor(Color.WHITE)
                buttonTintList = ColorStateList.valueOf(UI.NEON)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = roundBg(UI.PANEL2, 12, UI.STROKE, 1)
            }
            val err = tv("", 11.5f, Color.parseColor("#FF7B8B"), true).apply { visibility = View.GONE }

            body.addView(nameIn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            body.addView(urlIn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            body.addView(catIn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            body.addView(logoIn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            body.addView(authCheck, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            body.addView(err, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            val cancel = tv("إلغاء", 12.5f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER; setPadding(dp(16), dp(9), dp(16), dp(9))
                background = roundBg(UI.PANEL2, 12); makeFocusable(1.04f, UI.NEON, 2, 12)
                setOnClickListener { dialog.dismiss() }
            }
            val save = tv("حفظ القناة", 12.5f, Color.parseColor("#001018"), true).apply {
                gravity = Gravity.CENTER; setPadding(dp(18), dp(9), dp(18), dp(9))
                background = roundBg(UI.NEON, 12); makeFocusable(1.04f, Color.WHITE, 2, 12)
                setOnClickListener {
                    val n = nameIn.text.toString().trim()
                    val u = urlIn.text.toString().trim()
                    if (n.isBlank()) { err.text = "يرجى إدخال اسم القناة."; err.visibility = View.VISIBLE; return@setOnClickListener }
                    if (!AdultStore.isValidHttpUrl(u)) { err.text = "أدخل رابط بث صالح يبدأ بـ http:// أو https://"; err.visibility = View.VISIBLE; return@setOnClickListener }
                    if (!authCheck.isChecked) { err.text = "يرجى الإقرار بأن مصدر البث قانوني ومصرح به."; err.visibility = View.VISIBLE; return@setOnClickListener }
                    AdultStore.addChannel(this@AdultActivity, n, u, catIn.text.toString(), logoIn.text.toString())
                    reloadChannels()
                    dialog.dismiss()
                    Toast.makeText(this@AdultActivity, "تمت إضافة القناة إلى قسم Adult 18+", Toast.LENGTH_SHORT).show()
                }
            }
            btns.addView(cancel, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            btns.addView(save)
            body.addView(btns)
            nameIn.post { nameIn.requestFocus() }
        }
    }

    private fun showImportM3uDialog() {
        darkModal("استيراد قائمة M3U مصرح بها", 480) { body, dialog ->
            val urlIn = modalField("رابط قائمة M3U / M3U8", ltr = true)
            val textIn = modalField("أو الصق محتوى M3U مباشرة", ltr = true, multiline = true)
            val authCheck = CheckBox(this).apply {
                text = "أقر بأن جميع مصادر هذه القائمة قانونية ومصرح بها"
                textSize = 12f
                setTextColor(Color.WHITE)
                buttonTintList = ColorStateList.valueOf(UI.NEON)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = roundBg(UI.PANEL2, 12, UI.STROKE, 1)
            }
            val err = tv("", 11.5f, Color.parseColor("#FF7B8B"), true).apply { visibility = View.GONE }

            body.addView(urlIn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            body.addView(textIn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            body.addView(authCheck, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            body.addView(err, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            val cancel = tv("إلغاء", 12.5f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER; setPadding(dp(16), dp(9), dp(16), dp(9))
                background = roundBg(UI.PANEL2, 12); makeFocusable(1.04f, UI.NEON, 2, 12)
                setOnClickListener { dialog.dismiss() }
            }
            lateinit var importBtn: TextView
            importBtn = tv("استيراد القنوات", 12.5f, Color.parseColor("#001018"), true).apply {
                gravity = Gravity.CENTER; setPadding(dp(18), dp(9), dp(18), dp(9))
                background = roundBg(UI.NEON, 12); makeFocusable(1.04f, Color.WHITE, 2, 12)
                setOnClickListener {
                    if (!authCheck.isChecked) {
                        err.text = "يرجى الإقرار بأن القائمة من مصدر بث قانوني ومصرح به."
                        err.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                    val rawText = textIn.text.toString().trim()
                    val listUrl = urlIn.text.toString().trim()
                    if (rawText.isNotEmpty()) {
                        val parsed = AdultStore.parseM3u(rawText)
                        if (parsed.isEmpty()) {
                            err.text = "لم يتم العثور على روابط بث صالحة في النص."
                            err.visibility = View.VISIBLE
                            return@setOnClickListener
                        }
                        val added = AdultStore.importChannels(this@AdultActivity, parsed)
                        reloadChannels()
                        dialog.dismiss()
                        Toast.makeText(this@AdultActivity, "تم استيراد $added قناة مصرح بها", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (!AdultStore.isValidHttpUrl(listUrl)) {
                        err.text = "أدخل رابط قائمة M3U صحيح أو الصق المحتوى مباشرة."
                        err.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                    err.visibility = View.GONE
                    importBtn.isEnabled = false
                    importBtn.text = "جارٍ الاستيراد…"
                    io.execute {
                        val result = runCatching { AdultStore.parseM3u(AdultStore.fetchM3u(listUrl)) }
                        ui.post {
                            if (isFinishing || isDestroyed) return@post
                            importBtn.isEnabled = true
                            importBtn.text = "استيراد القنوات"
                            val parsed = result.getOrNull()
                            if (parsed.isNullOrEmpty()) {
                                err.text = "تعذر جلب القائمة أو لم يتم العثور على قنوات صالحة."
                                err.visibility = View.VISIBLE
                                return@post
                            }
                            val added = AdultStore.importChannels(this@AdultActivity, parsed)
                            reloadChannels()
                            dialog.dismiss()
                            Toast.makeText(this@AdultActivity, "تم استيراد $added قناة مصرح بها", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            btns.addView(cancel, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            btns.addView(importBtn)
            body.addView(btns)
            urlIn.post { urlIn.requestFocus() }
        }
    }

    // ------------------------------------------------------------------ RecyclerView Adapter

    private inner class ChannelHolder(
        val card: LinearLayout,
        val logo: ImageView,
        val badge18: TextView,
        val name: TextView,
        val meta: TextView,
        val playBtn: TextView,
        val delBtn: TextView
    ) : RecyclerView.ViewHolder(card)

    private inner class ChannelAdapter : RecyclerView.Adapter<ChannelHolder>() {
        init { setHasStableIds(true) }

        override fun getItemCount(): Int = visibleChannels.size

        override fun getItemId(position: Int): Long {
            val id = visibleChannels[position].id
            var h = -3750763034362895579L
            for (i in 0 until id.length) h = (h xor id[i].code.toLong()) * 1099511628211L
            return h
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelHolder {
            val card = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val idle = roundBg(0xD9131722.toInt(), 14, UI.STROKE, 1)
                val focused = roundBg(0xE61C2230.toInt(), 14, UI.NEON, 2)
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), focused)
                    addState(intArrayOf(), idle)
                }
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
                isFocusable = true
                isClickable = true
            }

            val logoWrap = FrameLayout(parent.context).apply {
                background = roundBg(Color.parseColor("#0F131F"), 10, UI.STROKE, 1)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            val logo = ImageView(parent.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            val badge18 = tv("18+", 11f, Color.parseColor("#FF7B8B"), true).apply { gravity = Gravity.CENTER }
            logoWrap.addView(logo, FrameLayout.LayoutParams(-1, -1))
            logoWrap.addView(badge18, FrameLayout.LayoutParams(-1, -1))
            card.addView(logoWrap, LinearLayout.LayoutParams(dp(54), dp(48)))

            val texts = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(10), 0)
            }
            val name = tv("", 13.5f, Color.WHITE, true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            val meta = tv("", 11f, UI.MUTED).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0)
            }
            texts.addView(name)
            texts.addView(meta)
            card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))

            val playBtn = tv("▶", 12.5f, UI.NEON, true).apply {
                gravity = Gravity.CENTER
                background = roundBg(0x2600D2FF, 99)
                isFocusable = false
            }
            card.addView(playBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(6) })

            val delBtn = tv("حذف", 10.5f, Color.parseColor("#FF7B8B"), true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = roundBg(UI.PANEL2, 10, UI.STROKE, 1)
                makeFocusable(1.04f, Color.parseColor("#E8384F"), 2, 10)
            }
            card.addView(delBtn, LinearLayout.LayoutParams(-2, -2))

            return ChannelHolder(card, logo, badge18, name, meta, playBtn, delBtn)
        }

        override fun onBindViewHolder(holder: ChannelHolder, position: Int) {
            val ch = visibleChannels[position]
            holder.name.text = ch.name
            holder.meta.text = "${ch.category} · ${ch.sourceLabel}"
            if (ch.logoUrl.isNotBlank()) {
                holder.logo.visibility = View.VISIBLE
                holder.badge18.visibility = View.GONE
                ImageLoader.bind(this@AdultActivity, holder.logo, ch.logoUrl, UI.PANEL2, ch.name, Kinds.LIVE)
            } else {
                holder.logo.visibility = View.GONE
                holder.badge18.visibility = View.VISIBLE
            }
            holder.card.setOnClickListener { playChannel(ch) }
            holder.delBtn.setOnClickListener {
                AdultStore.removeChannel(this@AdultActivity, ch.id)
                reloadChannels()
                Toast.makeText(this@AdultActivity, "تم حذف ${ch.name}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
