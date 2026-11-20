package com.alhorrtv.app

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Privacy lock: which categories and which individual channels / movies / series the user chose to hide, plus the
 * password that protects the manager. Hidden rows are kept in the local database under the kind "hidden:<kind>", so every
 * existing screen (which always filters by kind) stops showing them without any query being changed.
 * The password is never stored: only a salted PBKDF2 hash.
 */
internal object HiddenStore {
    const val PREFIX = "hidden:"
    private const val FILE = "privacy_lock"
    private const val SEP = '\u0001'

    fun hiddenKind(kind: String) = PREFIX + kind
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ password

    fun hasPassword(ctx: Context): Boolean = prefs(ctx).contains("pw_hash")

    fun setPassword(ctx: Context, password: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(ctx).edit()
            .putString("pw_salt", android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
            .putString("pw_hash", hash(password, salt))
            .remove("fails").remove("lock_until").apply()
    }

    /** Seconds the manager stays locked after too many wrong passwords (0 = not locked). */
    fun lockedForSeconds(ctx: Context): Int {
        val left = prefs(ctx).getLong("lock_until", 0L) - System.currentTimeMillis()
        return if (left > 0) ((left + 999) / 1000).toInt() else 0
    }

    fun check(ctx: Context, password: String): Boolean {
        if (lockedForSeconds(ctx) > 0) return false
        val p = prefs(ctx)
        val salt = try { android.util.Base64.decode(p.getString("pw_salt", "") ?: "", android.util.Base64.NO_WRAP) } catch (_: Exception) { return false }
        val stored = p.getString("pw_hash", null) ?: return false
        val ok = MessageDigest.isEqual(hash(password, salt).toByteArray(), stored.toByteArray())
        if (ok) p.edit().remove("fails").remove("lock_until").apply()
        else {
            val fails = p.getInt("fails", 0) + 1
            // 5 wrong attempts lock the manager for one minute (a second minute for each further batch of 5).
            val e = p.edit().putInt("fails", fails)
            if (fails % 5 == 0) e.putLong("lock_until", System.currentTimeMillis() + 60_000L * (fails / 5))
            e.apply()
        }
        return ok
    }

    private fun hash(password: String, salt: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, 12_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    // ------------------------------------------------------------------ hidden lists

    fun hiddenCategories(ctx: Context, kind: String): Set<String> = HashSet(prefs(ctx).getStringSet("hc_$kind", emptySet()) ?: emptySet())

    fun hiddenItemIds(ctx: Context, kind: String): Set<String> = hiddenItems(ctx, kind).mapTo(HashSet()) { it.first }

    /** (id, name) of the individually hidden items of [kind]. */
    fun hiddenItems(ctx: Context, kind: String): List<Pair<String, String>> =
        (prefs(ctx).getStringSet("hi_$kind", emptySet()) ?: emptySet()).map {
            val i = it.indexOf(SEP); if (i < 0) it to it else it.substring(0, i) to it.substring(i + 1)
        }.sortedBy { it.second.lowercase() }

    fun setCategoryHidden(ctx: Context, kind: String, id: String, hidden: Boolean) {
        val set = hiddenCategories(ctx, kind).toMutableSet()
        if (hidden) set.add(id) else set.remove(id)
        prefs(ctx).edit().putStringSet("hc_$kind", set).apply()
    }

    fun setItemHidden(ctx: Context, kind: String, id: String, name: String, hidden: Boolean) {
        val set = (prefs(ctx).getStringSet("hi_$kind", emptySet()) ?: emptySet()).filterNot { it == id || it.startsWith("$id$SEP") }.toMutableSet()
        if (hidden) set.add("$id$SEP$name")
        prefs(ctx).edit().putStringSet("hi_$kind", set).apply()
    }

    /** Forgets the password and every hidden category / item (the database part is undone by [XtreamRepository.showEverything]). */
    fun clearAll(ctx: Context) { prefs(ctx).edit().clear().apply() }

    fun hiddenCount(ctx: Context): Int = Kinds.CONTENT.sumOf { hiddenCategories(ctx, it).size + hiddenItemIds(ctx, it).size }
}
