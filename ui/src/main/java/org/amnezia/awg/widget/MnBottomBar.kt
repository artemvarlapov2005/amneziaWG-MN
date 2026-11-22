package org.amnezia.awg.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import org.amnezia.awg.R

/** Floating navigation whose icon and label always occupy separate layout rows. */
class MnBottomBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    data class Item(val itemId: Int)
    private data class Tab(val id: Int, val root: LinearLayout, val icon: ImageView, val label: TextView)
    private val tabs = mutableListOf<Tab>()
    private var listener: ((Item) -> Boolean)? = null
    var selectedItemId: Int = R.id.tab_configs
        set(value) {
            if (tabs.none { it.id == value }) return
            if (listener?.invoke(Item(value)) == false) return
            field = value
            render()
        }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        addTab(R.id.tab_configs, R.drawable.ic_mn_configs, R.string.mn_configs)
        addTab(R.id.tab_updates, R.drawable.ic_mn_updates, R.string.mn_updates)
        render()
    }
    fun setOnItemSelectedListener(callback: (Item) -> Boolean) { listener = callback }
    private fun addTab(id: Int, drawable: Int, title: Int) {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL; gravity = Gravity.CENTER
            isClickable = true; isFocusable = true
            contentDescription = context.getString(title)
            setOnClickListener { selectedItemId = id }
        }
        val icon = ImageView(context).apply {
            setImageResource(drawable)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val label = TextView(context).apply {
            setText(title); textSize = 13f; gravity = Gravity.CENTER
            setSingleLine(); includeFontPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        box.addView(icon, LayoutParams(dp(24), dp(24)))
        box.addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        addView(box, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        tabs.add(Tab(id, box, icon, label))
    }
    private fun render() {
        val active = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        val normal = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val selected = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer)
        for (tab in tabs) {
            val on = tab.id == selectedItemId
            val shape = GradientDrawable().apply { cornerRadius = dp(32).toFloat(); setColor(if (on) selected else Color.TRANSPARENT) }
            val mask = GradientDrawable().apply { cornerRadius = dp(32).toFloat(); setColor(Color.WHITE) }
            tab.root.background = RippleDrawable(ColorStateList.valueOf((active and 0x00ffffff) or 0x22000000), shape, mask)
            tab.root.isSelected = on
            tab.icon.imageTintList = ColorStateList.valueOf(if (on) active else normal)
            tab.label.setTextColor(if (on) active else normal)
            tab.label.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}
