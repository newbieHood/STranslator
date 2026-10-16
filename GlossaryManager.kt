package com.example.screencapture

import android.content.Context
import android.content.SharedPreferences

data class MaskedResult(
    val maskedText: String,
    val maskMap: Map<String, String>
)

class GlossaryManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "screen_translator_glossary_prefs"
        private const val KEY_RAW_GLOSSARY = "raw_glossary_text"
        private const val KEY_KEEP_ALL_CAPS = "keep_all_caps"

        val DEFAULT_GLOSSARY = """
            Buff
            Debuff
            Mana
            Stun
            Crit
            Deck = Bộ bài
            Exhaust = Tiêu hao
        """.trimIndent()

        private val ABBREVIATION_RULES: List<Pair<Regex, String>> = listOf(
            Regex("""\bATK\b""", RegexOption.IGNORE_CASE) to "Attack",
            Regex("""\bDMG\b""", RegexOption.IGNORE_CASE) to "Damage",
            Regex("""\bDEF\b""", RegexOption.IGNORE_CASE) to "Defense",
            Regex("""\bSPD\b""", RegexOption.IGNORE_CASE) to "Speed",
            Regex("""\bCD\b""", RegexOption.IGNORE_CASE) to "Cooldown",
            Regex("""\bHP\b""", RegexOption.IGNORE_CASE) to "Health",
            Regex("""\bLV\b""", RegexOption.IGNORE_CASE) to "Level",
            Regex("""\bMAX\b""", RegexOption.IGNORE_CASE) to "Maximum"
        )
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getRawGlossary(): String {
        return prefs.getString(KEY_RAW_GLOSSARY, DEFAULT_GLOSSARY) ?: DEFAULT_GLOSSARY
    }

    fun saveRawGlossary(rawText: String) {
        prefs.edit().putString(KEY_RAW_GLOSSARY, rawText.trim()).apply()
    }

    fun isKeepAllCaps(): Boolean {
        return prefs.getBoolean(KEY_KEEP_ALL_CAPS, false)
    }

    fun setKeepAllCaps(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_ALL_CAPS, enabled).apply()
    }

    fun getConfigHash(): Int {
        return getRawGlossary().hashCode() * 31 + isKeepAllCaps().hashCode()
    }

    private data class GlossaryEntry(
        val sourceTerm: String,
        val forcedReplacement: String?
    )

    private fun parseGlossaryEntries(): List<GlossaryEntry> {
        return getRawGlossary()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .mapNotNull { line ->
                val eqIndex = line.indexOf('=')
                if (eqIndex != -1) {
                    val key = line.substring(0, eqIndex).trim()
                    val value = line.substring(eqIndex + 1).trim()
                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        GlossaryEntry(key, value)
                    } else null
                } else {
                    GlossaryEntry(line, null)
                }
            }
            .sortedByDescending { it.sourceTerm.length }
            .toList()
    }

    fun preprocessAndMask(text: String): MaskedResult {
        if (text.isBlank()) return MaskedResult(text, emptyMap())

        var workingText = text
        val maskMap = LinkedHashMap<String, String>()
        var maskCounter = 0

        // BƯỚC A1: Masking danh sách từ của người dùng (từ dài đến ngắn)
        val entries = parseGlossaryEntries()
        for (entry in entries) {
            val escapedTerm = Regex.escape(entry.sourceTerm)
            val regex = Regex("""\b$escapedTerm\b""", RegexOption.IGNORE_CASE)

            workingText = regex.replace(workingText) { matchResult ->
                val matchedValue = matchResult.value
                if (matchedValue.startsWith("ZXQ", ignoreCase = true)) {
                    matchedValue
                } else {
                    val token = "ZXQ${maskCounter++}"
                    maskMap[token] = entry.forcedReplacement ?: matchedValue
                    token
                }
            }
        }

        // BƯỚC A2: Khóa các từ viết hoa toàn bộ >= 2 ký tự (nếu bật keepAllCaps)
        if (isKeepAllCaps()) {
            val abbreviationSet = setOf("ATK", "DMG", "DEF", "SPD", "CD", "HP", "LV", "MAX")
            val allCapsRegex = Regex("""\b[A-Z]{2,}\b""")
            workingText = allCapsRegex.replace(workingText) { matchResult ->
                val word = matchResult.value
                if (word.startsWith("ZXQ") || word in abbreviationSet) {
                    word
                } else {
                    val token = "ZXQ${maskCounter++}"
                    maskMap[token] = word
                    token
                }
            }
        }

        // BƯỚC B: Bung từ viết tắt cho phần văn bản còn lại
        for ((abbrRegex, fullWord) in ABBREVIATION_RULES) {
            workingText = abbrRegex.replace(workingText, fullWord)
        }

        return MaskedResult(workingText, maskMap)
    }

    fun unmask(translatedText: String, map: Map<String, String>): String {
        if (map.isEmpty() || translatedText.isBlank()) return translatedText

        var result = translatedText
        val sortedEntries = map.entries.sortedByDescending { it.key.length }

        for ((token, replacement) in sortedEntries) {
            val indexPart = token.removePrefix("ZXQ")
            val flexibleTokenRegex = Regex("""(?i)zxq\s*$indexPart\b""")
            result = flexibleTokenRegex.replace(result) {
                Regex.escapeReplacement(replacement)
            }
        }
        return result
    }
}
