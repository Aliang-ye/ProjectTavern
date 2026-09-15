package com.projecttavern.app

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

fun Context.dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun letter(name: String) = name.trim().take(1).ifBlank { "?" }

fun inflateRow(parent: LinearLayout, title: String, subtitle: String, meta: String = "", onClick: () -> Unit): View {
    val v = LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
    v.findViewById<TextView>(R.id.avatar).text = letter(title)
    v.findViewById<TextView>(R.id.title).text = title
    v.findViewById<TextView>(R.id.subtitle).text = subtitle
    v.findViewById<TextView>(R.id.meta).text = meta
    v.setOnClickListener { onClick() }
    parent.addView(v)
    return v
}

fun chip(ctx: Context, text: String, on: Boolean, onClick: () -> Unit): TextView {
    val t = TextView(ctx)
    val padH = ctx.dp(12)
    val padV = ctx.dp(8)
    t.text = text
    t.setPadding(padH, padV, padH, padV)
    t.setTextColor(ContextCompat.getColor(ctx, if (on) R.color.on_candle else R.color.ink))
    t.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
    t.setOnClickListener { onClick() }
    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    lp.marginEnd = ctx.dp(8)
    t.layoutParams = lp
    return t
}

fun confirm(ctx: Context, msg: String, onYes: () -> Unit) {
    AlertDialog.Builder(ctx)
        .setMessage(msg)
        .setPositiveButton(Store.t("delete")) { _, _ -> onYes() }
        .setNegativeButton(Store.t("cancel"), null)
        .show()
}

fun actionLabel(ctx: Context, text: String, color: Int, onClick: () -> Unit): TextView {
    val t = TextView(ctx)
    t.text = text
    t.setTextColor(color)
    t.textSize = 12f
    t.setPadding(0, ctx.dp(4), ctx.dp(14), ctx.dp(4))
    t.setOnClickListener { onClick() }
    t.setTypeface(Typeface.DEFAULT)
    return t
}
