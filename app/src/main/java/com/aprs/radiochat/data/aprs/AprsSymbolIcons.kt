/*
 * RadioChat APRS — Android APRS client for KISS BLE radios,
 * DireWolf (KISS TCP) and APRS-IS.
 * Copyright (C) 2026 Luis Almonte (HI3LAG)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.aprs.radiochat.data.aprs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.core.graphics.createBitmap

/**
 * APRS icons from the [hessu/aprs-symbols](https://github.com/hessu/aprs-symbols) sheets
 * (aprs.fi). Assets in `assets/aprs/aprs-symbols-64-{0,1,2}.png`.
 *
 * Table `/` → primary (0), `\` → secondary (1).
 * Any other table character (0-9/A-Z) → overlay on the secondary symbol.
 */
object AprsSymbolIcons {

    private const val COLS = 16
    private const val ROWS = 6
    private const val CELL = 64
    private const val DISPLAY_PX = 48

    private val sheetCache = arrayOfNulls<Bitmap>(3)
    private val iconCache = LruCache<String, Bitmap>(128)

    /** Drawable ready for an osmdroid marker; fallback `/>` (car). */
    fun drawable(context: Context, symbol: String): Drawable {
        val bmp = bitmap(context, symbol)
        return BitmapDrawable(context.resources, bmp).apply {
            setBounds(0, 0, bmp.width, bmp.height)
        }
    }

    /** Decodes the symbol sheets off the UI thread. */
    @Synchronized
    fun preload(context: Context) {
        val app = context.applicationContext
        sheet(app, 0)
        sheet(app, 1)
        sheet(app, 2)
    }

    fun bitmap(context: Context, symbol: String): Bitmap {
        val key = normalize(symbol)
        iconCache.get(key)?.let { return it }

        val (tableId, code, overlay) = parse(key)
        val base = crop(context, tableId, code)
        val composed = if (overlay != null) {
            val over = crop(context, 2, overlay)
            compose(base, over)
        } else {
            base
        }
        val scaled = Bitmap.createScaledBitmap(composed, DISPLAY_PX, DISPLAY_PX, true)
        if (composed !== base && composed !== scaled) composed.recycle()
        iconCache.put(key, scaled)
        return scaled
    }

    fun clearCache() {
        iconCache.evictAll()
        sheetCache.indices.forEach { i ->
            sheetCache[i]?.recycle()
            sheetCache[i] = null
        }
    }

    private fun normalize(symbol: String): String {
        val s = symbol.trim()
        return if (s.length >= 2) s.take(2) else "/>"
    }

    private data class Parsed(val tableId: Int, val code: Char, val overlay: Char?)

    private fun parse(symbol: String): Parsed {
        val table = symbol[0]
        val code = symbol[1]
        return when (table) {
            '/' -> Parsed(0, code, null)
            '\\' -> Parsed(1, code, null)
            else -> Parsed(1, code, table) // overlay char on secondary
        }
    }

    private fun crop(context: Context, tableId: Int, code: Char): Bitmap {
        val sheet = sheet(context, tableId)
        val index = (code.code - 33).coerceIn(0, COLS * ROWS - 1)
        val col = index % COLS
        val row = index / COLS
        return Bitmap.createBitmap(sheet, col * CELL, row * CELL, CELL, CELL)
    }

    @Synchronized
    private fun sheet(context: Context, tableId: Int): Bitmap {
        sheetCache[tableId]?.let { return it }
        val name = "aprs/aprs-symbols-64-$tableId.png"
        val bmp = context.assets.open(name).use { BitmapFactory.decodeStream(it) }
            ?: error("No se pudo cargar $name")
        sheetCache[tableId] = bmp
        return bmp
    }

    private fun compose(base: Bitmap, overlay: Bitmap): Bitmap {
        val out = createBitmap(base.width, base.height)
        val canvas = Canvas(out)
        canvas.drawBitmap(base, 0f, 0f, null)
        canvas.drawBitmap(overlay, 0f, 0f, null)
        return out
    }
}
