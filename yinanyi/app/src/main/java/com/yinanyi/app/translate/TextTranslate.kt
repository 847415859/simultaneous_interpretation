package com.yinanyi.app.translate

import com.yinanyi.app.Term
import org.json.JSONArray
import org.json.JSONObject

/** 输入翻译的目标语言。zh/en 是给界面和模型提示词用的名字，menu 是系统选择菜单里的名字。 */
data class TargetLang(val code: String, val zh: String, val en: String, val native: String, val menu: String)

object Langs {
    val all = listOf(
        TargetLang("en", "英语", "English", "English", "译成英文"),
        TargetLang("ja", "日语", "Japanese", "日本語", "译成日文"),
        TargetLang("ko", "韩语", "Korean", "한국어", "译成韩文"),
        TargetLang("fr", "法语", "French", "Français", "快速翻译"),
        TargetLang("de", "德语", "German", "Deutsch", "快速翻译"),
        TargetLang("es", "西班牙语", "Spanish", "Español", "快速翻译"),
        TargetLang("ru", "俄语", "Russian", "Русский", "快速翻译"),
        TargetLang("pt", "葡萄牙语", "Portuguese", "Português", "快速翻译"),
        TargetLang("it", "意大利语", "Italian", "Italiano", "快速翻译"),
        TargetLang("th", "泰语", "Thai", "ไทย", "快速翻译"),
        TargetLang("vi", "越南语", "Vietnamese", "Tiếng Việt", "快速翻译"),
        TargetLang("id", "印尼语", "Indonesian", "Bahasa Indonesia", "快速翻译"),
        TargetLang("ar", "阿拉伯语", "Arabic", "العربية", "快速翻译"),
        TargetLang("tr", "土耳其语", "Turkish", "Türkçe", "快速翻译"),
    )

    /** 读别人发来的外文时的目标：中文。 */
    val chinese = TargetLang("zh", "中文", "Chinese", "中文", "译成中文")

    /** 下拉里一直排在前面的三种。 */
    val common = all.take(3)

    fun byCode(code: String): TargetLang = all.firstOrNull { it.code == code } ?: all.first()
}

/**
 * 语气。[hint] 是给模型看的英文要求（内置）或用户写的原话（自定义），[example] 是同一句话的示例译法。
 * [scope] 为 all 或某个语言代码，只有自定义语气会限定语言。
 */
data class ToneDef(
    val id: String,
    val name: String,
    val hint: String,
    val example: String,
    val builtin: Boolean,
    val scope: String = "all",
) {
    fun appliesTo(lang: String) = scope == "all" || scope == lang
}

object Tones {
    const val DEFAULT_ID = "casual"
    const val MAX_CUSTOM = 20
    const val MAX_NAME = 6
    const val MAX_RULE = 100

    val builtin = listOf(
        ToneDef("casual", "口语", "casual, natural, everyday spoken style, like chatting with friends", "Thanks for the help!", true),
        ToneDef("polite", "礼貌", "polite and courteous, but not stiff", "Thank you so much for your help.", true),
        ToneDef("formal", "正式", "formal written register, proper and professional", "I sincerely appreciate your assistance.", true),
        ToneDef("concise", "简洁", "as short and direct as possible, drop pleasantries", "Thanks.", true),
        ToneDef("humor", "幽默", "light-hearted and playful, a little witty, without changing the meaning", "Thanks, you absolute lifesaver!", true),
        ToneDef("warm", "热情", "enthusiastic and positive, with natural exclamations", "Thank you so much, that was amazing!", true),
        ToneDef("tactful", "委婉", "tactful and indirect, soften the wording", "Your help would be much appreciated.", true),
        ToneDef("business", "商务", "professional workplace communication, clear and respectful", "Thank you for your support.", true),
    )


    /** 同一句话「谢谢你的帮助」在各语气下的示例译法，按目标语言给；没有的语言退回英语。 */
    private val EXAMPLES: Map<String, Map<String, String>> = mapOf(
        "en" to mapOf(
            "casual" to "Thanks for the help!", "polite" to "Thank you so much for your help.", "formal" to "I sincerely appreciate your assistance.",
            "concise" to "Thanks.", "humor" to "Thanks, you absolute lifesaver!", "warm" to "Thank you so much, that was amazing!",
            "tactful" to "Your help would be much appreciated.", "business" to "Thank you for your support.",
        ),
        "ja" to mapOf(
            "casual" to "助かった、ありがとう！", "polite" to "お力添えいただき、本当にありがとうございます。", "formal" to "ご支援に深く感謝申し上げます。",
            "concise" to "ありがとう。", "humor" to "命の恩人だよ、ありがとう！", "warm" to "本当にありがとう、最高だよ！",
            "tactful" to "お力添えいただけると大変助かります。", "business" to "ご協力に感謝いたします。",
        ),
        "ko" to mapOf(
            "casual" to "도와줘서 고마워!", "polite" to "도와주셔서 정말 감사합니다.", "formal" to "도움에 깊이 감사드립니다.",
            "concise" to "고마워요.", "humor" to "생명의 은인이야, 고마워!", "warm" to "정말 고마워요, 최고예요!",
            "tactful" to "도와주시면 정말 감사하겠습니다.", "business" to "협조해 주셔서 감사합니다.",
        ),
        "fr" to mapOf(
            "casual" to "Merci pour ton aide !", "polite" to "Merci beaucoup pour votre aide.", "formal" to "Je vous remercie sincèrement de votre concours.",
            "concise" to "Merci.", "humor" to "Merci, tu es un vrai sauveur !", "warm" to "Merci infiniment, c'est génial !",
            "tactful" to "Votre aide serait très appréciée.", "business" to "Merci pour votre soutien.",
        ),
        "de" to mapOf(
            "casual" to "Danke für die Hilfe!", "polite" to "Vielen Dank für Ihre Hilfe.", "formal" to "Ich danke Ihnen aufrichtig für Ihre Unterstützung.",
            "concise" to "Danke.", "humor" to "Danke, du bist ein echter Lebensretter!", "warm" to "Vielen lieben Dank, das war großartig!",
            "tactful" to "Ihre Hilfe wäre sehr willkommen.", "business" to "Vielen Dank für Ihre Unterstützung.",
        ),
        "es" to mapOf(
            "casual" to "¡Gracias por la ayuda!", "polite" to "Muchas gracias por su ayuda.", "formal" to "Le agradezco sinceramente su colaboración.",
            "concise" to "Gracias.", "humor" to "¡Gracias, eres un salvavidas!", "warm" to "¡Muchísimas gracias, ha sido genial!",
            "tactful" to "Se agradecería mucho su ayuda.", "business" to "Gracias por su apoyo.",
        ),
        "zh" to mapOf(
            "casual" to "谢谢你帮忙！", "polite" to "非常感谢您的帮助。", "formal" to "衷心感谢您的协助。",
            "concise" to "谢谢。", "humor" to "你简直是救星，谢了！", "warm" to "太感谢了，你太棒了！",
            "tactful" to "若能得到您的帮助，我将不胜感激。", "business" to "感谢您的支持。",
        ),
    )

    /** 语气 [t] 在目标语言 [lang] 下的示例；自定义语气没有示例，返回空串。 */
    fun exampleFor(t: ToneDef, lang: String): String =
        if (!t.builtin) "" else EXAMPLES[lang]?.get(t.id) ?: EXAMPLES.getValue("en").getValue(t.id)

    fun custom(id: String, name: String, rule: String, scope: String) =
        ToneDef(id, name, rule, "自定义：$rule", false, scope)

    /** 名字是否可用：不能为空，不能和内置或其他自定义重名。返回错误文字，null 表示可以。 */
    fun nameError(name: String, existing: List<ToneDef>, editingId: String?): String? {
        val n = name.trim()
        if (n.isEmpty()) return "请输入名称"
        if (n.length > MAX_NAME) return "名称最多 $MAX_NAME 个字"
        val clash = (builtin + existing).firstOrNull { it.name == n && it.id != editingId }
        return if (clash != null) "已有同名语气「$n」，换一个名字" else null
    }
}

/** 用户对语气的设置：自定义的、隐藏的、排序。存成 JSON。 */
data class ToneConfig(
    val custom: List<ToneDef> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val order: List<String> = emptyList(),
) {
    /** 内置加自定义，按用户排的顺序；没排过的放最后。 */
    fun all(): List<ToneDef> {
        val pool = Tones.builtin + custom
        val rank = order.withIndex().associate { it.value to it.index }
        return pool.withIndex().sortedBy { rank[it.value.id] ?: (1000 + it.index) }.map { it.value }
    }

    /** 面板里显示的：没被隐藏、适用于这个语言。 */
    fun visible(lang: String): List<ToneDef> = all().filter { it.id !in hidden && it.appliesTo(lang) }

    fun find(id: String): ToneDef = all().firstOrNull { it.id == id } ?: Tones.builtin.first()

    fun toJson(): String {
        val c = JSONArray()
        custom.forEach { c.put(JSONObject().put("id", it.id).put("name", it.name).put("rule", it.hint).put("scope", it.scope)) }
        return JSONObject().put("custom", c).put("hidden", JSONArray(hidden.toList())).put("order", JSONArray(order)).toString()
    }

    companion object {
        fun fromJson(s: String?): ToneConfig = runCatching {
            val o = JSONObject(s ?: return ToneConfig())
            val c = o.optJSONArray("custom") ?: JSONArray()
            ToneConfig(
                custom = (0 until c.length()).map { c.getJSONObject(it) }.map {
                    Tones.custom(it.getString("id"), it.getString("name"), it.getString("rule"), it.optString("scope", "all"))
                },
                hidden = o.optJSONArray("hidden")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
                order = o.optJSONArray("order")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            )
        }.getOrDefault(ToneConfig())
    }
}

/** 判断选中的文字是不是中文、屏蔽不该翻译的内容、切分、拼提示词。全部是纯函数，方便测试。 */
object TextRules {
    const val MAX_CHARS = 2000
    private const val OFFLINE_CHUNK = 300

    /** @提及、[表情]、链接、emoji：翻译时原样保留。 */
    val TOKEN = Regex("""@[^\s，。！？、；：,!?;:@]{1,20}|\[[^\[\]\s]{1,12}]|https?://\S+|[\uD83C-\uDBFF][\uDC00-\uDFFF]|[☀-➿]""")

    fun isChinese(text: String): Boolean {
        var han = 0
        var latin = 0
        var other = 0
        for (ch in text) {
            when {
                Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HIRAGANA ||
                    Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.KATAKANA ||
                    Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HANGUL -> other++
                ch.isLetter() -> latin++
            }
        }
        return other == 0 && han > 0 && han * 3 >= latin
    }

    /** 屏蔽：把受保护的内容换成 {1} {2}…，翻译完再换回来。 */
    fun mask(text: String): Pair<String, List<String>> {
        val tokens = ArrayList<String>()
        val masked = TOKEN.replace(text) { m ->
            tokens += m.value
            "{${tokens.size}}"
        }
        return masked to tokens
    }

    /** 换回受保护的内容；模型丢了占位符就把对应内容接在末尾，免得丢失。 */
    fun unmask(text: String, tokens: List<String>): String {
        var out = text
        val lost = ArrayList<String>()
        tokens.forEachIndexed { i, t ->
            val ph = "{${i + 1}}"
            if (out.contains(ph)) out = out.replace(ph, t) else lost += t
        }
        return if (lost.isEmpty()) out else out + " " + lost.joinToString(" ")
    }

    /** 离线用：按行拆开，行内太长再按句子切，每块不超过 [OFFLINE_CHUNK] 字。空行原样保留（块为空串）。 */
    fun linesAndChunks(text: String): List<List<String>> = text.split("\n").map { line ->
        if (line.isBlank()) emptyList() else chunk(line.trim(), OFFLINE_CHUNK)
    }

    fun chunk(line: String, max: Int): List<String> {
        if (line.length <= max) return listOf(line)
        val sentences = line.split(Regex("(?<=[。！？!?.；;])"))
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (s in sentences) {
            if (cur.isNotEmpty() && cur.length + s.length > max) {
                out += cur.toString()
                cur.setLength(0)
            }
            if (s.length > max) {
                s.chunked(max).forEach { out += it }
            } else {
                cur.append(s)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** 太长时只取前 [MAX_CHARS] 字，尽量断在段落或句子末尾。返回（前面一段，剩下的）。 */
    fun head(text: String, max: Int = MAX_CHARS): Pair<String, String> {
        if (text.length <= max) return text to ""
        val window = text.substring(0, max)
        val cut = maxOf(window.lastIndexOf('\n'), window.lastIndexOfAny(charArrayOf('。', '！', '？', '.', '!', '?')))
        val end = if (cut > max / 2) cut + 1 else max
        return text.substring(0, end) to text.substring(end)
    }

    /** 按段落切成每块不超过 [max] 字的几块（分段翻译用）。 */
    fun paragraphs(text: String, max: Int = 1000): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (p in text.split("\n")) {
            if (cur.isNotEmpty() && cur.length + p.length + 1 > max) {
                out += cur.toString()
                cur.setLength(0)
            }
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(p)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out.flatMap { if (it.length > max) chunk(it, max) else listOf(it) }
    }

    // ---------------- 提示词 ----------------

    /** 在线（通用大模型）的系统提示词。 */
    fun systemPrompt(target: TargetLang, tone: ToneDef, terms: List<Term>, text: String, keepTokens: Boolean, alternatives: Boolean = false): String = buildString {
        append("You are a translation engine inside a keyboard helper. Translate the user's text into ${target.en}. ")
        if (tone.builtin) append("Style: ${tone.hint}. ") else append("Follow this style requirement from the user: ${tone.hint}. ")
        append("Output only the translation, with no quotes and no explanations. Keep the line breaks. ")
        if (keepTokens) append("Keep @mentions, [bracketed stickers], emoji, URLs and numbers exactly unchanged. ")
        val hits = glossaryFor(text, terms, target.code == "zh")
        if (hits.isNotEmpty()) {
            append("Glossary, use these translations when the term appears: ")
            append(hits.joinToString("; ") { "${it.first} -> ${it.second}" })
            append(". ")
        }
        if (alternatives) append("Give 2 different alternative translations, each on its own line, without numbering or any other text.")
    }

    /**
     * 术语表是「外文 → 中文」存的。中文译成外文时反过来用：文字里出现了某条的中文，就指定译成它的外文。
     * 目标是中文时按原方向用。
     */
    fun glossaryFor(text: String, terms: List<Term>, toChinese: Boolean = false): List<Pair<String, String>> =
        if (toChinese) terms.filter { text.contains(it.source, ignoreCase = true) }.map { it.source to it.target }
        else terms.filter { text.contains(it.target) }.map { it.target to it.source }

    /** 离线 HY-MT 的提示词（中文和外文互译的官方写法）。 */
    fun hyMtPrompt(text: String, targetZh: String): String = "把下面的文本翻译成$targetZh，不要额外解释。\n\n$text"

    /** 备选说法：取非空行，去掉可能的序号，最多 2 条，并去掉和主译文一样的。 */
    fun parseAlternatives(raw: String, main: String): List<String> = raw.lines()
        .map { it.trim().replace(Regex("^(\\d+[.)、]|[-•*])\\s*"), "").trim().trim('"') }
        .filter { it.isNotEmpty() && it != main.trim() }
        .distinct()
        .take(2)
}
