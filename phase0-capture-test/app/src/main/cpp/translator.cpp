// 用 llama.cpp 跑 Qwen 小模型做逐句翻译的 JNI 封装。
// 每次翻译都清空 KV 缓存，按 system + user 两条消息套用模型自带的聊天模板，贪心解码。

#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "yinanyi-llm"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Translator {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
    const llama_vocab *vocab = nullptr;
    std::string chat_template;
};

std::string to_string(JNIEnv *env, jstring s) {
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jbyteArray to_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()),
                            reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_yinanyi_captest_LlamaNative_load(JNIEnv *env, jobject, jstring path, jint threads) {
    static bool backend_ready = false;
    if (!backend_ready) {
        llama_backend_init();
        backend_ready = true;
    }

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    std::string model_path = to_string(env, path);
    llama_model *model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (model == nullptr) {
        LOGE("failed to load model %s", model_path.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 1024;
    cparams.n_batch = 512;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("failed to create context");
        llama_model_free(model);
        return 0;
    }

    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    auto *t = new Translator();
    t->model = model;
    t->ctx = ctx;
    t->sampler = sampler;
    t->vocab = llama_model_get_vocab(model);
    const char *tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl != nullptr) t->chat_template = tmpl;
    return reinterpret_cast<jlong>(t);
}

/** 返回 UTF-8 字节；出错时返回空数组。 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_yinanyi_captest_LlamaNative_translate(JNIEnv *env, jobject, jlong handle,
                                               jstring system, jstring user, jint max_tokens) {
    auto *t = reinterpret_cast<Translator *>(handle);
    std::string sys = to_string(env, system);
    std::string usr = to_string(env, user);

    llama_memory_clear(llama_get_memory(t->ctx), true);
    llama_sampler_reset(t->sampler);

    // system 为空时只发一条 user 消息（HY-MT 等翻译模型没有 system 提示）
    llama_chat_message msgs[2] = {{"system", sys.c_str()}, {"user", usr.c_str()}};
    llama_chat_message *first = sys.empty() ? &msgs[1] : &msgs[0];
    size_t n_msg = sys.empty() ? 1 : 2;
    const char *tmpl = t->chat_template.empty() ? nullptr : t->chat_template.c_str();
    std::vector<char> buf(sys.size() + usr.size() + 512);
    int32_t n = llama_chat_apply_template(tmpl, first, n_msg, true, buf.data(), static_cast<int32_t>(buf.size()));
    if (n > static_cast<int32_t>(buf.size())) {
        buf.resize(n);
        n = llama_chat_apply_template(tmpl, first, n_msg, true, buf.data(), static_cast<int32_t>(buf.size()));
    }
    if (n < 0) {
        LOGE("chat template failed");
        return to_bytes(env, "");
    }
    std::string prompt(buf.data(), n);

    int32_t n_prompt = -llama_tokenize(t->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                       nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_prompt);
    llama_tokenize(t->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                   tokens.data(), n_prompt, true, true);

    llama_batch batch = llama_batch_get_one(tokens.data(), n_prompt);
    if (llama_decode(t->ctx, batch) != 0) {
        LOGE("prompt decode failed");
        return to_bytes(env, "");
    }

    std::string out;
    for (int i = 0; i < max_tokens; i++) {
        llama_token tok = llama_sampler_sample(t->sampler, t->ctx, -1);
        if (llama_vocab_is_eog(t->vocab, tok)) break;
        char piece[256];
        int32_t len = llama_token_to_piece(t->vocab, tok, piece, sizeof(piece), 0, false);
        if (len > 0) out.append(piece, len);
        batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(t->ctx, batch) != 0) break;
    }
    return to_bytes(env, out);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yinanyi_captest_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    auto *t = reinterpret_cast<Translator *>(handle);
    if (t == nullptr) return;
    llama_sampler_free(t->sampler);
    llama_free(t->ctx);
    llama_model_free(t->model);
    delete t;
}
