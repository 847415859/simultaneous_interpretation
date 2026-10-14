package com.yinanyi.app.translate

/** 输入框旁「译」按钮的显示规则。纯函数，方便测试。 */
object A11yRules {
    /** 默认不显示按钮的 App：银行、支付类，和以南译自己。 */
    val DEFAULT_OFF = setOf(
        "com.eg.android.AlipayGphone", "com.unionpay", "cmb.pb", "com.icbc", "com.chinamworld.main",
        "com.android.bankabc", "com.chinamworld.bocmbci", "com.bankcomm.Bankcomm", "com.yinanyi.app",
    )

    private const val MAX_CHARS = 4000

    fun parseOff(csv: String): List<String> = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun isOff(pkg: String, csv: String): Boolean = pkg in DEFAULT_OFF || pkg in parseOff(csv)

    fun addOff(csv: String, pkg: String): String = (parseOff(csv) + pkg).distinct().joinToString(",")

    fun removeOff(csv: String, pkg: String): String = parseOff(csv).filter { it != pkg }.joinToString(",")

    fun hasChinese(text: String): Boolean = text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }

    /** 这个输入框要不要显示「译」：不是密码框、App 没被关掉、里面有中文、不太长。 */
    fun shouldOffer(text: CharSequence?, pkg: String?, password: Boolean, offCsv: String): Boolean {
        if (password || pkg == null || isOff(pkg, offCsv)) return false
        val t = text?.toString() ?: return false
        return t.length <= MAX_CHARS && hasChinese(t)
    }

    /** 按钮上的文字：「译 EN」。 */
    fun pillLabel(langCode: String): String = "译 " + langCode.uppercase()
}
