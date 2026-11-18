package com.johancarrion.instaladorpwa

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Resultado de analizar una URL: nombre, URL de inicio, color y un ícono adaptativo de 432x432. */
data class PwaInfo(
    val name: String,
    val startUrl: String,
    val themeColor: Int,
    val icon: Bitmap,
    val hasManifest: Boolean
)

object PwaFetcher {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"
    private const val ICON_SIZE = 432

    private class IconCandidate(val url: String, val size: Int, val maskable: Boolean)

    private val linkRe = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val metaRe = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val titleRe = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun normalize(input: String): String {
        val s = input.trim()
        return if (s.contains("://")) s else "https://$s"
    }

    fun fetch(input: String): PwaInfo {
        val pageUrl = normalize(input)
        val page = getText(pageUrl)
        val finalUrl = page.first
        val html = page.second

        val links = linkRe.findAll(html).map { it.value }.toList()
        fun rels(tag: String) = (attr(tag, "rel") ?: "").lowercase().split(Regex("\\s+"))

        var name: String? = null
        var startUrl = finalUrl
        var theme: Int? = null
        var background: Int? = null
        var hasManifest = false
        val candidates = mutableListOf<IconCandidate>()

        val manifestHref = links.firstOrNull { "manifest" in rels(it) }?.let { attr(it, "href") }
        if (manifestHref != null) {
            try {
                val manifestUrl = resolve(finalUrl, manifestHref)
                val m = JSONObject(getText(manifestUrl).second)
                hasManifest = true
                name = m.optString("short_name").ifBlank { m.optString("name") }.ifBlank { null }
                m.optString("start_url").takeIf { it.isNotBlank() }?.let { startUrl = resolve(manifestUrl, it) }
                theme = parseColor(m.optString("theme_color"))
                background = parseColor(m.optString("background_color"))
                val icons = m.optJSONArray("icons")
                if (icons != null) {
                    for (i in 0 until icons.length()) {
                        val ic = icons.optJSONObject(i) ?: continue
                        val src = ic.optString("src").ifBlank { continue }
                        val type = ic.optString("type")
                        if (type.contains("svg") || src.substringBefore('?').endsWith(".svg", true)) continue
                        candidates += IconCandidate(
                            resolve(manifestUrl, src),
                            firstNumber(ic.optString("sizes")),
                            ic.optString("purpose").contains("maskable", true)
                        )
                    }
                }
            } catch (_: Exception) {
                // Si el manifest falla seguimos con los datos del HTML
            }
        }

        if (theme == null) {
            theme = metaRe.findAll(html).map { it.value }
                .firstOrNull { attr(it, "name")?.equals("theme-color", true) == true }
                ?.let { parseColor(attr(it, "content")) }
        }
        if (name == null) {
            name = titleRe.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                ?: runCatching { URL(finalUrl).host }.getOrNull() ?: "PWA"
        }

        // Respaldo: íconos declarados en el HTML
        for (tag in links) {
            val r = rels(tag)
            if ("apple-touch-icon" in r || "icon" in r) {
                val href = attr(tag, "href") ?: continue
                if (attr(tag, "type")?.contains("svg") == true || href.substringBefore('?').endsWith(".svg", true)) continue
                val size = firstNumber(attr(tag, "sizes") ?: "").let { if (it == 0 && "apple-touch-icon" in r) 180 else it }
                candidates += IconCandidate(resolve(finalUrl, href), size, false)
            }
        }

        // Primero maskable (encaja con el ícono adaptativo), luego el más grande
        val ordered = candidates.sortedWith(compareByDescending<IconCandidate> { it.maskable }.thenByDescending { it.size })
        var bitmap: Bitmap? = null
        var maskable = false
        for (c in ordered) {
            val b = runCatching { getBitmap(c.url) }.getOrNull()
            if (b != null) {
                bitmap = b
                maskable = c.maskable
                break
            }
        }

        val themeColor = theme ?: Color.parseColor("#202124")
        val icon = buildAdaptiveIcon(bitmap, maskable, background, themeColor, name!!)
        return PwaInfo(name, startUrl, themeColor, icon, hasManifest)
    }

    // ---------- Ícono ----------

    private fun buildAdaptiveIcon(src: Bitmap?, maskable: Boolean, bg: Int?, theme: Int, name: String): Bitmap {
        val out = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        if (src == null) {
            canvas.drawColor(theme or 0xFF000000.toInt())
            paint.color = if (isLight(theme)) Color.BLACK else Color.WHITE
            paint.textSize = ICON_SIZE * 0.42f
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            val y = ICON_SIZE / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(letter, ICON_SIZE / 2f, y, paint)
            return out
        }

        val corner = src.getPixel(1.coerceAtMost(src.width - 1), 1.coerceAtMost(src.height - 1))
        val bgColor = when {
            Color.alpha(corner) > 200 -> corner
            bg != null -> bg or 0xFF000000.toInt()
            else -> Color.WHITE
        }
        canvas.drawColor(bgColor)

        val target = if (maskable) ICON_SIZE else (ICON_SIZE * 0.62f).toInt()
        val left = (ICON_SIZE - target) / 2
        canvas.drawBitmap(src, null, Rect(left, left, left + target, left + target), paint)
        return out
    }

    private fun isLight(color: Int): Boolean =
        (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255 > 0.6

    // ---------- Utilidades de red / parseo ----------

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "es,en;q=0.8")
        return c
    }

    /** Devuelve (URL final tras redirecciones, contenido). */
    private fun getText(url: String): Pair<String, String> {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val bytes = c.inputStream.use { readLimited(it, 2_000_000) }
            return c.url.toString() to String(bytes, Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    private fun getBitmap(url: String): Bitmap? {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) return null
            val bytes = c.inputStream.use { readLimited(it, 5_000_000) }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            return if (bmp.width > 512) Bitmap.createScaledBitmap(bmp, 512, 512 * bmp.height / bmp.width, true) else bmp
        } finally {
            c.disconnect()
        }
    }

    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun attr(tag: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
            .find(tag)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }

    private fun resolve(base: String, ref: String): String = URL(URL(base), ref.trim()).toString()

    private fun firstNumber(sizes: String): Int =
        Regex("\\d+").find(sizes)?.value?.toIntOrNull() ?: 0

    private fun parseColor(value: String?): Int? =
        if (value.isNullOrBlank()) null else runCatching { Color.parseColor(value.trim()) }.getOrNull()
}
