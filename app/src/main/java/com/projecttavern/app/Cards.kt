package com.projecttavern.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Inflater

data class CardImport(
    val character: Character? = null,
    val world: WorldBook? = null,
    val entries: List<WorldBookEntry> = emptyList(),
)

object Cards {
    fun importBytes(ctx: Context, bytes: ByteArray): CardImport {
        val pngJson = extractPngChara(bytes)
        val text = pngJson ?: bytes.toString(Charsets.UTF_8).trim().removePrefix("\uFEFF")
        if (text.isBlank()) return CardImport()
        return importJson(ctx, text)
    }

    fun importJson(ctx: Context, raw: String): CardImport {
        val text = raw.trim()
        if (text.isBlank()) return CardImport()
        return try {
            val root = JsonParser.parseString(text)
            if (!root.isJsonObject) return CardImport()
            val obj = root.asJsonObject
            if (obj.has("characters") && obj.has("conversations") && obj.has("worldBooks")) {
                return CardImport()
            }
            when {
                obj.has("spec") && obj.get("spec").asString.contains("world", true) -> importWorldPack(obj)
                obj.has("world") && obj.has("entries") -> importWorldPack(obj)
                obj.has("spec") && obj.get("spec").asString.contains("chara", true) -> importCharaV2(ctx, obj)
                obj.has("data") && obj.get("data").isJsonObject -> importCharaV2(ctx, obj)
                obj.has("name") && (obj.has("first_mes") || obj.has("firstMessage") || obj.has("description")) -> {
                    if (obj.has("first_mes") || obj.has("mes_example") || obj.has("firstMes")) importCharaV1(ctx, obj)
                    else {
                        val ch = Store.importCharacter(text)
                        CardImport(character = ch)
                    }
                }
                else -> {
                    val ch = Store.importCharacter(text)
                    CardImport(character = ch)
                }
            }
        } catch (_: Exception) {
            CardImport(character = Store.importCharacter(text))
        }
    }

    fun exportCharacterV2Json(ch: Character): String {
        val data = JsonObject()
        data.addProperty("name", ch.name)
        data.addProperty("description", ch.description)
        data.addProperty("personality", ch.personality)
        data.addProperty("scenario", ch.scenario)
        data.addProperty("first_mes", ch.firstMessage)
        data.addProperty("mes_example", ch.exampleDialogues)
        data.addProperty("system_prompt", ch.systemPrompt)
        data.addProperty("creator_notes", ch.creatorNotes)
        val tags = JsonArray(); ch.tags.forEach { tags.add(it) }
        data.add("tags", tags)
        val alts = JsonArray(); ch.alternateGreetings.forEach { alts.add(it) }
        data.add("alternate_greetings", alts)
        val worldId = Store.defaultWorldId(ch.id)
        if (worldId != null) {
            val book = JsonObject()
            val arr = JsonArray()
            Store.state.entries.filter { it.worldBookId == worldId }.forEach { e ->
                arr.add(entryToChara(e))
            }
            book.add("entries", arr)
            data.add("character_book", book)
        }
        val root = JsonObject()
        root.addProperty("spec", "chara_card_v2")
        root.addProperty("spec_version", "2.0")
        root.add("data", data)
        return root.toString()
    }

    fun exportCharacterPng(ctx: Context, ch: Character): File {
        val json = exportCharacterV2Json(ch)
        val b64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val bmp = avatarBitmap(ch) ?: placeholderBitmap(ch.name)
        val pngOut = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, pngOut)
        val injected = injectTextChunk(pngOut.toByteArray(), "chara", b64)
        val safe = (ch.name.ifBlank { "character" }).replace(Regex("[^\\w\\u4e00-\\u9fff]"), "_")
        val file = File(ctx.cacheDir, "$safe.png")
        file.writeBytes(injected)
        return file
    }

    fun exportWorldPack(world: WorldBook): String {
        val copy = world.copy(willAlternateGreetings = world.willAlternateGreetings.toMutableList())
        val entries = Store.state.entries.filter { it.worldBookId == world.id }
        val root = JsonObject()
        root.addProperty("spec", "project_tavern_world_v1")
        root.add("world", Store.gson.toJsonTree(copy))
        root.add("entries", Store.gson.toJsonTree(entries))
        return root.toString()
    }

    private fun importWorldPack(obj: JsonObject): CardImport {
        val worldObj = obj.getAsJsonObject("world") ?: obj
        val world = Store.gson.fromJson(worldObj, WorldBook::class.java) ?: return CardImport()
        world.id = Store.nid()
        world.createdAt = Store.now()
        world.updatedAt = Store.now()
        world.willAlternateGreetings = world.willAlternateGreetings ?: mutableListOf()
        val entries = mutableListOf<WorldBookEntry>()
        val arr = obj.getAsJsonArray("entries")
        arr?.forEach { el ->
            val e = Store.gson.fromJson(el, WorldBookEntry::class.java) ?: return@forEach
            e.id = Store.nid()
            e.worldBookId = world.id
            e.keys = e.keys ?: mutableListOf()
            e.secondaryKeys = e.secondaryKeys ?: mutableListOf()
            entries.add(e)
        }
        Store.state.worldBooks.add(0, world)
        Store.state.entries.addAll(entries)
        Store.persist()
        return CardImport(world = world, entries = entries)
    }

    private fun importCharaV2(ctx: Context, obj: JsonObject): CardImport {
        val data = obj.getAsJsonObject("data") ?: obj
        return importCharaV1(ctx, data)
    }

    private fun importCharaV1(ctx: Context, data: JsonObject): CardImport {
        val ch = Character(
            id = Store.nid(),
            name = str(data, "name"),
            description = str(data, "description"),
            personality = str(data, "personality"),
            scenario = str(data, "scenario"),
            firstMessage = str(data, "first_mes", "firstMes", "firstMessage"),
            exampleDialogues = str(data, "mes_example", "mesExample", "exampleDialogues"),
            systemPrompt = str(data, "system_prompt", "systemPrompt"),
            creatorNotes = str(data, "creator_notes", "creatorNotes"),
            tags = strList(data, "tags"),
            alternateGreetings = strList(data, "alternate_greetings", "alternateGreetings"),
            createdAt = Store.now(),
            updatedAt = Store.now(),
        )
        val avatarField = str(data, "avatar")
        if (avatarField.startsWith("data:")) {
            ch.avatar = Store.materializeDataUrl(ctx, avatarField, "avatar_${ch.id}")
        }
        Store.state.characters.add(0, ch)
        val book = data.getAsJsonObject("character_book")
        var world: WorldBook? = null
        val entries = mutableListOf<WorldBookEntry>()
        if (book != null) {
            world = WorldBook(
                id = Store.nid(),
                name = "${ch.name} lore",
                description = str(book, "name", "description").ifBlank { "${ch.name} character book" },
                createdAt = Store.now(),
                updatedAt = Store.now(),
            )
            book.getAsJsonArray("entries")?.forEach { el ->
                if (!el.isJsonObject) return@forEach
                val e = el.asJsonObject
                val entry = WorldBookEntry(
                    id = Store.nid(),
                    worldBookId = world.id,
                    name = str(e, "comment", "name").ifBlank { "entry" },
                    keys = strList(e, "keys"),
                    secondaryKeys = strList(e, "secondary_keys", "secondaryKeys"),
                    content = str(e, "content"),
                    priority = int(e, "insertion_order", "priority", fallback = 50),
                    enabled = bool(e, "enabled", true),
                    constant = bool(e, "constant", false),
                    probability = int(e, "probability", fallback = 100),
                    insertionPosition = position(e),
                )
                entries.add(entry)
            }
            Store.state.worldBooks.add(0, world)
            Store.state.entries.addAll(entries)
            Store.setDefaultWorld(ch.id, world.id)
        } else {
            Store.persist()
        }
        return CardImport(character = ch, world = world, entries = entries)
    }

    private fun entryToChara(e: WorldBookEntry): JsonObject {
        val o = JsonObject()
        o.addProperty("name", e.name)
        o.addProperty("comment", e.name)
        o.addProperty("content", e.content)
        val keys = JsonArray(); e.keys.forEach { keys.add(it) }
        o.add("keys", keys)
        val sec = JsonArray(); e.secondaryKeys.forEach { sec.add(it) }
        o.add("secondary_keys", sec)
        o.addProperty("enabled", e.enabled)
        o.addProperty("constant", e.constant)
        o.addProperty("insertion_order", e.priority)
        o.addProperty("probability", e.probability)
        o.addProperty("position", e.insertionPosition)
        return o
    }

    private fun str(o: JsonObject, vararg keys: String): String {
        for (k in keys) {
            if (o.has(k) && !o.get(k).isJsonNull) {
                val v = o.get(k)
                if (v.isJsonPrimitive) return v.asString
            }
        }
        return ""
    }

    private fun strList(o: JsonObject, vararg keys: String): MutableList<String> {
        for (k in keys) {
            if (!o.has(k) || o.get(k).isJsonNull) continue
            val v = o.get(k)
            if (v.isJsonArray) return v.asJsonArray.mapNotNull { it.asString?.trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (v.isJsonPrimitive) return v.asString.split(Regex("[,，]")).map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        }
        return mutableListOf()
    }

    private fun int(o: JsonObject, vararg keys: String, fallback: Int): Int {
        for (k in keys) {
            if (o.has(k) && o.get(k).isJsonPrimitive) {
                return try { o.get(k).asInt } catch (_: Exception) { fallback }
            }
        }
        return fallback
    }

    private fun bool(o: JsonObject, key: String, fallback: Boolean): Boolean {
        if (!o.has(key) || o.get(key).isJsonNull) return fallback
        return try { o.get(key).asBoolean } catch (_: Exception) { fallback }
    }

    private fun position(e: JsonObject): String {
        val raw = str(e, "position", "insertion_position")
        return when (raw.lowercase()) {
            "0", "before_char", "before" -> "before_char"
            else -> "after_char"
        }
    }

    private fun indexOfZero(data: ByteArray, start: Int = 0): Int {
        for (i in start until data.size) if (data[i] == 0.toByte()) return i
        return -1
    }

    fun extractPngChara(bytes: ByteArray): String? {
        if (bytes.size < 16) return null
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (!bytes.take(8).toByteArray().contentEquals(sig)) return null
        val dis = DataInputStream(ByteArrayInputStream(bytes, 8, bytes.size - 8))
        try {
            while (dis.available() >= 12) {
                val len = dis.readInt()
                val typeBytes = ByteArray(4)
                dis.readFully(typeBytes)
                val type = String(typeBytes, Charsets.US_ASCII)
                if (len < 0 || len > dis.available() - 4) break
                val data = ByteArray(len)
                dis.readFully(data)
                dis.skipBytes(4)
                when (type) {
                    "tEXt" -> {
                        val nul = indexOfZero(data)
                        if (nul > 0) {
                            val key = String(data, 0, nul, Charsets.ISO_8859_1)
                            if (key == "chara" || key == "ccv3") {
                                val payload = String(data, nul + 1, data.size - nul - 1, Charsets.ISO_8859_1)
                                return decodeCharaPayload(payload)
                            }
                        }
                    }
                    "zTXt" -> {
                        val nul = indexOfZero(data)
                        if (nul > 0) {
                            val key = String(data, 0, nul, Charsets.ISO_8859_1)
                            if (key == "chara" || key == "ccv3") {
                                val compressed = data.copyOfRange(nul + 2, data.size)
                                val inf = Inflater()
                                inf.setInput(compressed)
                                val out = ByteArrayOutputStream()
                                val buf = ByteArray(1024)
                                while (!inf.finished()) {
                                    val n = inf.inflate(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                }
                                inf.end()
                                return decodeCharaPayload(out.toString("ISO-8859-1"))
                            }
                        }
                    }
                    "iTXt" -> {
                        val nul = indexOfZero(data)
                        if (nul > 0) {
                            val key = String(data, 0, nul, Charsets.ISO_8859_1)
                            if (key == "chara" || key == "ccv3") {
                                val compressed = data[nul + 1].toInt()
                                var idx = nul + 3
                                val skipC = indexOfZero(data, idx)
                                if (skipC >= 0) idx = skipC + 1
                                val skipT = indexOfZero(data, idx)
                                if (skipT >= 0) idx = skipT + 1
                                val payloadBytes = data.copyOfRange(idx, data.size)
                                val payload = if (compressed == 1) {
                                    val inf = Inflater()
                                    inf.setInput(payloadBytes)
                                    val out = ByteArrayOutputStream()
                                    val buf = ByteArray(1024)
                                    while (!inf.finished()) {
                                        val n = inf.inflate(buf)
                                        if (n <= 0) break
                                        out.write(buf, 0, n)
                                    }
                                    inf.end()
                                    out.toString(Charsets.UTF_8.name())
                                } else String(payloadBytes, Charsets.UTF_8)
                                return decodeCharaPayload(payload)
                            }
                        }
                    }
                    "IEND" -> break
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun decodeCharaPayload(payload: String): String {
        val trimmed = payload.trim()
        return try {
            String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            trimmed
        }
    }

    private fun injectTextChunk(png: ByteArray, keyword: String, text: String): ByteArray {
        val sig = png.copyOfRange(0, 8)
        val body = png.copyOfRange(8, png.size)
        val keywordBytes = keyword.toByteArray(Charsets.ISO_8859_1)
        val textBytes = text.toByteArray(Charsets.ISO_8859_1)
        val data = keywordBytes + byteArrayOf(0) + textBytes
        val type = "tEXt".toByteArray(Charsets.US_ASCII)
        val len = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.size).array()
        val crcSrc = type + data
        val crc = CRC32().apply { update(crcSrc) }.value.toInt()
        val crcBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc).array()
        val chunk = len + type + data + crcBytes
        var insertAt = body.size
        var offset = 0
        while (offset + 8 <= body.size) {
            val clen = ByteBuffer.wrap(body, offset, 4).order(ByteOrder.BIG_ENDIAN).int
            if (offset + 12 + clen > body.size) break
            val ctype = String(body, offset + 4, 4, Charsets.US_ASCII)
            if (ctype == "IEND") {
                insertAt = offset
                break
            }
            offset += 12 + clen
        }
        return sig + body.copyOfRange(0, insertAt) + chunk + body.copyOfRange(insertAt, body.size)
    }

    private fun avatarBitmap(ch: Character): Bitmap? {
        val path = ch.avatar ?: return null
        if (path.startsWith("data:")) return null
        val file = File(path)
        if (!file.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxSide / sample > 768) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) { null }
    }

    private fun placeholderBitmap(name: String): Bitmap {
        val bmp = Bitmap.createBitmap(512, 768, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.parseColor("#1C1410"))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.parseColor("#C4A574")
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 48f
        c.drawText(name.ifBlank { "Tavern" }.take(12), 256f, 380f, paint)
        return bmp
    }
}
