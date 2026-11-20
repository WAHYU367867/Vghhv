package com.alhorrtv.app

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 18+ age-warning modal matching the dark neon theme of ALHUR TV.
 * Must be confirmed before opening or displaying the Adult 18+ section.
 */
internal object AdultAgeGate {
    fun show(
        activity: FullscreenActivity,
        onConfirmed: () -> Unit,
        onCanceled: () -> Unit = {}
    ) {
        val beforeFocus = activity.currentFocus
        var confirmed = false
        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)

        val danger = Color.parseColor("#E8384F")
        val dangerLight = Color.parseColor("#FF7B8B")

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = activity.roundBg(Color.parseColor("#131722"), 16, danger, 1)
            clipRounded(activity.dpf(16))
        }

        // Header
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0F131E"))
            setPadding(activity.dp(18), activity.dp(14), activity.dp(18), activity.dp(14))
        }
        head.addView(activity.tv("18+", 11.5f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(activity.dp(8), activity.dp(3), activity.dp(8), activity.dp(3))
            background = activity.roundBg(danger, 6)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = activity.dp(10) })
        head.addView(
            activity.tv("تحذير: قسم للبالغين فقط", 15f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        val closeBtn = activity.tv("✕", 14f, UI.MUTED, true).apply {
            gravity = Gravity.CENTER
            makeFocusable(1.08f, UI.NEON, 1, 8)
            setOnClickListener { dialog.dismiss() }
        }
        head.addView(closeBtn, LinearLayout.LayoutParams(activity.dp(28), activity.dp(28)))
        root.addView(head)
        root.addView(View(activity).apply { setBackgroundColor(UI.STROKE) }, LinearLayout.LayoutParams(-1, activity.dp(1)))

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(18), activity.dp(14), activity.dp(18), activity.dp(18))
        }

        // Warning notice box
        val warnBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
            background = activity.roundBg(UI.alpha(danger, 34), 12, UI.alpha(danger, 120), 1)
        }
        warnBox.addView(activity.tv(
            "أنت على وشك الدخول إلى قسم Adult 18+ المخصص للبالغين فقط (18 عاماً فأكثر). لن يتم عرض أي محتوى قبل تأكيد عمرك صراحةً.",
            12.5f, Color.parseColor("#F1F5F9")
        ).apply { setLineSpacing(0f, 1.25f) })
        body.addView(warnBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.dp(10) })

        // Compliance note
        val noteBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8))
            background = activity.roundBg(UI.PANEL2, 12, UI.STROKE, 1)
        }
        noteBox.addView(activity.tv(
            "القسم مستقل تماماً عن القنوات العادية والأفلام والمسلسلات، ويسمح بإضافة قنوات من مصادر بث قانونية ومصرح بها فقط.",
            11.5f, UI.MUTED
        ).apply { setLineSpacing(0f, 1.2f) })
        body.addView(noteBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.dp(10) })

        // Age confirmation checkbox
        val checkBox = CheckBox(activity).apply {
            text = "أؤكد أن عمري 18 عاماً فأكثر وأتحمل مسؤولية الدخول"
            textSize = 12.5f
            setTextColor(Color.WHITE)
            buttonTintList = ColorStateList.valueOf(danger)
            isChecked = false
            setPadding(activity.dp(8), activity.dp(10), activity.dp(8), activity.dp(10))
            val idle = activity.roundBg(UI.PANEL2, 12, UI.STROKE, 1)
            val focused = activity.roundBg(Color.parseColor("#253048"), 12, UI.NEON, 2)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(), idle)
            }
            isFocusable = true
            isFocusableInTouchMode = false
        }
        body.addView(checkBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.dp(8) })

        val errorTv = activity.tv("", 11.5f, dangerLight, true).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, activity.dp(2), 0, activity.dp(8))
        }
        body.addView(errorTv, LinearLayout.LayoutParams(-1, -2))

        checkBox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) errorTv.visibility = View.GONE
        }

        // Action buttons
        val btns = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, activity.dp(6), 0, 0)
        }
        val cancelBtn = activity.tv("إلغاء والرجوع", 12.5f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(activity.dp(16), activity.dp(10), activity.dp(16), activity.dp(10))
            background = activity.roundBg(UI.PANEL2, 12, UI.STROKE, 1)
            makeFocusable(1.04f, UI.NEON, 2, 12)
            setOnClickListener { dialog.dismiss() }
        }
        val enterBtn = activity.tv("تأكيد العمر والدخول (+18)", 12.5f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(activity.dp(18), activity.dp(10), activity.dp(18), activity.dp(10))
            background = activity.roundBg(danger, 12)
            makeFocusable(1.04f, Color.WHITE, 2, 12)
            setOnClickListener {
                if (!checkBox.isChecked) {
                    errorTv.text = "يرجى تأكيد أن عمرك 18 عاماً فأكثر للمتابعة."
                    errorTv.visibility = View.VISIBLE
                    checkBox.requestFocus()
                    return@setOnClickListener
                }
                confirmed = true
                AdultStore.confirmAge()
                dialog.dismiss()
                onConfirmed()
            }
        }
        btns.addView(cancelBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = activity.dp(10) })
        btns.addView(enterBtn)
        body.addView(btns)
        root.addView(body)

        dialog.setOnDismissListener {
            if (!confirmed) {
                beforeFocus?.takeIf { it.isShown && it.isFocusable }?.let { v -> v.post { v.requestFocus() } }
                onCanceled()
            }
        }
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(activity.dp(460), WindowManager.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.78f)
        }
        cancelBtn.post { cancelBtn.requestFocus() }
    }
}
