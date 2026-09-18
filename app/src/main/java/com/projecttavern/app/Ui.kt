package com.projecttavern.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import java.io.File

fun Context.dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun letter(name: String) = name.trim().take(1).ifBlank { "?" }

private val avatarCache = android.util.LruCache<String, Bitmap>(48)

fun renderAvatar(tv: TextView, img: ImageView?, name: String, avatarPath: String?) {
    if (!avatarPath.isNullOrBlank()) {
        val cached = avatarCache.get(avatarPath)
        if (cached != null) {
            img?.setImageBitmap(cached)
            img?.visibility = View.VISIBLE
            tv.visibility = View.GONE
            return
        }
        val file = File(avatarPath)
        if (file.exists()) {
            try {
                // 先读取图片尺寸，计算合适的 inSampleSize，避免大图 OOM
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(avatarPath, opts)
                val maxSide = 256
                var sample = 1
                while ((opts.outWidth / sample) > maxSide || (opts.outHeight / sample) > maxSide) sample *= 2
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = BitmapFactory.decodeFile(avatarPath, decodeOpts)
                if (bmp != null) {
                    avatarCache.put(avatarPath, bmp)
                    img?.setImageBitmap(bmp)
                    img?.visibility = View.VISIBLE
                    tv.visibility = View.GONE
                    return
                }
            } catch (_: Exception) {}
        }
    }
    img?.setImageDrawable(null)
    img?.visibility = View.GONE
    tv.visibility = View.VISIBLE
    tv.text = letter(name)
}

fun saveAvatar(ctx: Context, id: String, uri: Uri): String? {
    return try {
        val dir = File(ctx.filesDir, "avatars").apply { mkdirs() }
        dir.listFiles()?.filter { it.name.startsWith("${id}_") }?.forEach {
            avatarCache.remove(it.absolutePath)
            it.delete()
        }
        val dest = File(dir, "${id}_${System.currentTimeMillis()}.jpg")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val bmp = BitmapFactory.decodeStream(input) ?: return null
            val maxSide = 512
            val scaled = if (bmp.width > maxSide || bmp.height > maxSide) {
                val ratio = minOf(maxSide.toFloat() / bmp.width, maxSide.toFloat() / bmp.height)
                val w = (bmp.width * ratio).toInt().coerceAtLeast(1)
                val h = (bmp.height * ratio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, w, h, true)
            } else bmp
            dest.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            avatarCache.put(dest.absolutePath, scaled)
            dest.absolutePath
        }
    } catch (_: Exception) {
        null
    }
}

fun Activity.openScreen(target: Class<*>, extras: ((Intent) -> Intent)? = null) {
    val intent = Intent(this, target)
    val resolved = extras?.invoke(intent) ?: intent
    startActivity(resolved)
    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
}

fun inflateRow(parent: LinearLayout, title: String, subtitle: String, meta: String = "", avatarPath: String? = null, onClick: () -> Unit): View {
    val v = LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
    val avatar = v.findViewById<TextView>(R.id.avatar)
    val avatarImg = v.findViewById<ImageView>(R.id.avatarImg)
    val metaView = v.findViewById<TextView>(R.id.meta)
    renderAvatar(avatar, avatarImg, title, avatarPath)
    v.findViewById<TextView>(R.id.title).text = title
    v.findViewById<TextView>(R.id.subtitle).text = subtitle
    metaView.text = meta
    if (meta.isBlank()) {
        metaView.visibility = View.GONE
    } else {
        metaView.visibility = View.VISIBLE
        metaView.setBackgroundResource(R.drawable.bg_chip)
        metaView.setTextColor(ContextCompat.getColor(parent.context, R.color.candle))
        val padH = parent.context.dp(8)
        val padV = parent.context.dp(4)
        metaView.setPadding(padH, padV, padH, padV)
    }
    v.setOnClickListener { onClick() }
    parent.addView(v)
    return v
}

fun inflateSwipeRow(
    parent: LinearLayout,
    title: String,
    subtitle: String,
    meta: String = "",
    avatarPath: String? = null,
    isPinned: Boolean = false,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
): View {
    val ctx = parent.context
    val v = LayoutInflater.from(ctx).inflate(R.layout.item_swipe_row, parent, false)
    val hsv = v.findViewById<android.widget.HorizontalScrollView>(R.id.swipeScrollView)
    val contentCard = v.findViewById<LinearLayout>(R.id.contentCard)
    val avatar = v.findViewById<TextView>(R.id.avatar)
    val avatarImg = v.findViewById<ImageView>(R.id.avatarImg)
    val pinBadge = v.findViewById<TextView>(R.id.pinBadge)
    val titleView = v.findViewById<TextView>(R.id.title)
    val subtitleView = v.findViewById<TextView>(R.id.subtitle)
    val metaView = v.findViewById<TextView>(R.id.meta)
    val btnPin = v.findViewById<TextView>(R.id.btnPin)
    val btnDelete = v.findViewById<TextView>(R.id.btnDelete)

    fun updateCardWidth() {
        val parentWidth = parent.width
        val effectiveWidth = if (parentWidth > 0) {
            parentWidth - parent.paddingLeft - parent.paddingRight
        } else {
            val dm = ctx.resources.displayMetrics
            dm.widthPixels - ctx.dp(32)
        }
        val lp = contentCard.layoutParams
        if (lp.width != effectiveWidth) {
            lp.width = effectiveWidth
            contentCard.layoutParams = lp
        }
    }
    updateCardWidth()
    parent.post { updateCardWidth() }

    renderAvatar(avatar, avatarImg, title, avatarPath)
    titleView.text = title
    subtitleView.text = subtitle
    pinBadge.visibility = if (isPinned) View.VISIBLE else View.GONE
    btnPin.text = if (isPinned) Store.t("unpin") else Store.t("pin")

    if (meta.isBlank()) {
        metaView.visibility = View.GONE
    } else {
        metaView.visibility = View.VISIBLE
        metaView.text = meta
        metaView.setBackgroundResource(R.drawable.bg_chip)
        metaView.setTextColor(ContextCompat.getColor(ctx, R.color.candle))
        val padH = ctx.dp(8)
        val padV = ctx.dp(4)
        metaView.setPadding(padH, padV, padH, padV)
    }

    contentCard.setOnClickListener {
        if (hsv.scrollX > 10) {
            hsv.smoothScrollTo(0, 0)
        } else {
            onClick()
        }
    }

    btnPin.setOnClickListener {
        hsv.smoothScrollTo(0, 0)
        onPin()
    }

    btnDelete.setOnClickListener {
        hsv.smoothScrollTo(0, 0)
        onDelete()
    }

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

fun confirm(ctx: Context, msg: String, positiveLabel: String = Store.t("delete"), onYes: () -> Unit) {
    AlertDialog.Builder(ctx)
        .setMessage(msg)
        .setPositiveButton(positiveLabel) { _, _ -> onYes() }
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

fun Activity.hideKeyboard() {
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
    val v = currentFocus ?: View(this)
    imm?.hideSoftInputFromWindow(v.windowToken, 0)
}

fun setupTouchToHideKeyboard(view: View, activity: Activity) {
    if (view !is EditText) {
        view.setOnTouchListener { _, _ ->
            activity.hideKeyboard()
            false
        }
    }
    if (view is android.view.ViewGroup) {
        for (i in 0 until view.childCount) {
            setupTouchToHideKeyboard(view.getChildAt(i), activity)
        }
    }
}
