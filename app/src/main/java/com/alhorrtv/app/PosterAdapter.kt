package com.alhorrtv.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

internal class PosterAdapter(
    private val onClick: (ContentEntity) -> Unit,
    private val onFavorite: (ContentEntity) -> Unit,
    private val railWidthDp: Int = 0,
    var showBadge: Boolean = false
) : RecyclerView.Adapter<PosterAdapter.Holder>() {

    private val items = ArrayList<ContentEntity>()
    private var favorites: Set<String> = emptySet()

    init { setHasStableIds(true) }

    class Holder(
        val frame: AspectFrame,
        val image: ImageView,
        val title: TextView,
        val badge: TextView,
        val rating: TextView,
        val favBtn: TextView
    ) : RecyclerView.ViewHolder(frame)

    fun submit(list: List<ContentEntity>, favs: Set<String>, append: Boolean) {
        val oldFavs = favorites
        favorites = favs
        if (!append && items.size == list.size && items.indices.all {
                items[it].contentId == list[it].contentId &&
                    items[it].kind == list[it].kind &&
                    items[it].rating == list[it].rating
            }) {
            for (i in items.indices) {
                val key = "${items[i].kind}:${items[i].contentId}"
                if ((key in oldFavs) != (key in favs)) notifyItemChanged(i)
            }
            return
        }
        if (!append) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        } else {
            val start = items.size; items.addAll(list); notifyItemRangeInserted(start, list.size)
        }
    }

    fun setFavorites(favs: Set<String>) {
        val old = favorites; favorites = favs
        for (i in items.indices) {
            val key = "${items[i].kind}:${items[i].contentId}"
            if ((key in old) != (key in favs)) notifyItemChanged(i)
        }
    }

    override fun getItemCount(): Int = items.size
    override fun getItemId(position: Int): Long {
        val item = items[position]
        return (item.kind.hashCode().toLong() shl 32) or (item.contentId.hashCode().toLong() and 0xffffffffL)
    }
    override fun getItemViewType(position: Int): Int = if (items[position].kind == Kinds.LIVE) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val live = viewType == 1
        val frame = AspectFrame(ctx, if (live) 0.78f else 1.5f)
        frame.layoutParams = RecyclerView.LayoutParams(
            if (railWidthDp > 0) ctx.dp(railWidthDp) else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(ctx.dp(5), ctx.dp(5), ctx.dp(5), ctx.dp(5)) }

        frame.background = ctx.roundBg(Color.parseColor("#131722"), 12, UI.alpha(UI.STROKE, 204), 1)
        frame.clipRounded(ctx.dpf(12))

        val image = ImageView(ctx).apply {
            scaleType = if (live) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            if (live) {
                setBackgroundColor(Color.parseColor("#0F131F"))
                setPadding(ctx.dp(18), ctx.dp(14), ctx.dp(18), ctx.dp(38))
            }
        }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))

        val title = ctx.tv("", 12.5f, UI.TEXT, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.BOTTOM or Gravity.RIGHT
            setPadding(ctx.dp(10), ctx.dp(30), ctx.dp(10), ctx.dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, 0xB3000000.toInt(), 0xF2000000.toInt())
            )
        }
        frame.addView(title, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // Top-right side-by-side badges (Kind + Rating), matching Web PosterCard.tsx
        val badge = ctx.tv("", 10f, Color.WHITE, true).apply {
            setPadding(ctx.dp(7), ctx.dp(2), ctx.dp(7), ctx.dp(2))
        }
        val rating = ctx.tv("", 10f, UI.GOLD, true).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(6), ctx.dp(2), ctx.dp(6), ctx.dp(2))
            background = ctx.roundBg(0xBF000000.toInt(), 6, 0x4DFFB800, 1)
            visibility = View.GONE
        }
        val tags = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        tags.addView(badge, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ctx.dp(4) })
        tags.addView(rating, LinearLayout.LayoutParams(-2, -2))
        frame.addView(tags, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.RIGHT).apply {
            setMargins(ctx.dp(6), ctx.dp(6), ctx.dp(6), 0)
        })

        // Top-left Favorite Heart button matching Web PosterCard.tsx
        val favBtn = ctx.tv("♥", 12.5f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            isFocusable = false
            isClickable = true
        }
        frame.addView(favBtn, FrameLayout.LayoutParams(ctx.dp(26), ctx.dp(26), Gravity.TOP or Gravity.LEFT).apply {
            setMargins(ctx.dp(6), ctx.dp(6), ctx.dp(6), 0)
        })

        val holder = Holder(frame, image, title, badge, rating, favBtn)
        frame.makeFocusable(1.04f, UI.NEON, 2, 12)
        frame.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(items[p])
        }
        frame.setOnLongClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onFavorite(items[p])
            true
        }
        favBtn.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onFavorite(items[p])
        }
        return holder
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val item = items[position]
        val ctx = h.frame.context
        h.title.text = item.name

        h.badge.visibility = if (showBadge) View.VISIBLE else View.GONE
        h.badge.text = Kinds.badge(item.kind)
        val badgeColor = if (item.kind == Kinds.LIVE) UI.LIVE_RED else UI.accent(item.kind)
        h.badge.background = ctx.roundBg(UI.alpha(badgeColor, 217), 6)

        val stars = if (item.kind == Kinds.LIVE) "" else item.rating
        h.rating.visibility = if (stars.isBlank()) View.GONE else View.VISIBLE
        if (stars.isNotBlank()) h.rating.text = "★ $stars"

        val isFav = "${item.kind}:${item.contentId}" in favorites
        h.favBtn.background = if (isFav) {
            ctx.roundBg(0xE6E8384F.toInt(), 99, 0xFFFF7B8B.toInt(), 1)
        } else {
            ctx.roundBg(0x99000000.toInt(), 99, 0x33FFFFFF, 1)
        }
        h.favBtn.setTextColor(if (isFav) Color.WHITE else 0xCCFFFFFF.toInt())

        ImageLoader.bind(ctx, h.image, item.imageUrl, UI.PANEL2, item.name, item.kind)
    }
}
