package com.liuml.simfingerprint.ui

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.liuml.simfingerprint.R

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun Context.text(
    value: CharSequence,
    sizeSp: Float = 15f,
    @ColorRes color: Int = R.color.text_primary,
    bold: Boolean = false,
): TextView = TextView(this).apply {
    text = value
    textSize = sizeSp
    setTextColor(ContextCompat.getColor(this@text, color))
    setLineSpacing(0f, 1.18f)
    if (bold) setTypeface(typeface, Typeface.BOLD)
}

internal fun Context.card(content: View): MaterialCardView = MaterialCardView(this).apply {
    radius = dp(18).toFloat()
    cardElevation = dp(1).toFloat()
    strokeWidth = dp(1)
    strokeColor = 0xFFE3E8F0.toInt()
    setCardBackgroundColor(ContextCompat.getColor(this@card, R.color.card_background))
    addView(
        content,
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
}

internal fun sectionLayout(context: Context): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(context.dp(20), context.dp(18), context.dp(20), context.dp(18))
}

internal fun LinearLayout.addSpaced(view: View, topDp: Int = 10) {
    addView(
        view,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(topDp)
        },
    )
}

