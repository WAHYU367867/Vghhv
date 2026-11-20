package com.alhorrtv.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** WhatsApp support contact, shared by the login screen and Settings so the number lives in one place. */
internal object SupportContact {
    const val NUMBER = "963958963357"          // international format, digits only (wa.me link)
    const val DISPLAY = "+963 958 963 357"

    /** Opens a WhatsApp chat; on devices without WhatsApp or a browser (common on TVs) the number is shown instead. */
    fun openWhatsApp(activity: Activity, message: String) {
        val uri = Uri.parse("https://wa.me/$NUMBER?text=" + Uri.encode(message))
        try { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (_: Exception) {
            Toast.makeText(activity, "تعذر فتح واتساب على هذا الجهاز. تواصل معنا على الرقم: $DISPLAY", Toast.LENGTH_LONG).show()
        }
    }
}
