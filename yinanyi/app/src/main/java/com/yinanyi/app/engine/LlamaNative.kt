package com.yinanyi.app.engine

/** llama.cpp 的 JNI 入口，实现在 src/main/cpp/translator.cpp。 */
object LlamaNative {
    init {
        System.loadLibrary("yinanyi_llm")
    }

    external fun load(path: String, threads: Int): Long

    /** system 为空时只发一条 user 消息；返回 UTF-8 字节。 */
    external fun translate(handle: Long, system: String, user: String, maxTokens: Int): ByteArray
    external fun free(handle: Long)
}
