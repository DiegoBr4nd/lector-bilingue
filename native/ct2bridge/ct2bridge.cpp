// Puente JNI entre Kotlin (Ct2NativeBridge) y CTranslate2 + SentencePiece.
// Reglas: validar toda entrada, no dejar escapar excepciones C++, no registrar texto.
#include <jni.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>
#include <sys/stat.h>

#include <absl/base/log_severity.h>
#include <absl/log/globals.h>
#include <ctranslate2/logging.h>
#include <ctranslate2/translator.h>
#include <sentencepiece_processor.h>

namespace {

constexpr int kMaxSentenceChars = 1000;
constexpr int kMaxBatch = 64;
constexpr int kMaxThreads = 8;
constexpr int kMaxBeam = 8;
constexpr size_t kMaxDecodingLength = 512;

struct Engine {
    std::unique_ptr<ctranslate2::Translator> translator;
    sentencepiece::SentencePieceProcessor source;
    sentencepiece::SentencePieceProcessor target;
    size_t beam = 1;
};

// Handles válidos: evita usar punteros liberados o inventados.
// Guarda shared_ptr: si unload llega mientras otro hilo traduce, el motor se libera
// cuando esa traducción termina (nunca se usa memoria ya liberada).
std::mutex g_registry_mutex;
std::unordered_map<jlong, std::shared_ptr<Engine>> g_registry;
// Contador monótono: un handle nunca se reutiliza (evita confundir un motor nuevo con uno
// liberado en la misma dirección). 0 queda reservado como inválido.
std::atomic<jlong> g_next_handle{1};

struct InvalidArgument : std::runtime_error { using std::runtime_error::runtime_error; };
// Tipo propio: así un std::logic_error interno de CTranslate2 no se confunde con
// "motor no cargado".
struct InvalidHandle : std::runtime_error { using std::runtime_error::runtime_error; };

void throwJava(JNIEnv* env, const char* cls, const char* msg) {
    if (env->ExceptionCheck()) return;
    jclass c = env->FindClass(cls);
    if (c != nullptr) env->ThrowNew(c, msg);
}

bool fileExists(const std::string& path) {
    struct stat st{};
    return stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

// UTF-16 (Java) → UTF-8 estándar. Sustitutos sueltos → U+FFFD.
std::string toUtf8(JNIEnv* env, jstring s) {
    const jsize len = env->GetStringLength(s);
    std::u16string u(static_cast<size_t>(len), u'\0');
    env->GetStringRegion(s, 0, len, reinterpret_cast<jchar*>(u.data()));
    std::string out;
    out.reserve(u.size() * 3);
    for (size_t i = 0; i < u.size(); ++i) {
        uint32_t cp = u[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < u.size() && u[i + 1] >= 0xDC00 && u[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (u[i + 1] - 0xDC00);
            ++i;
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }
        if (cp < 0x80) {
            out += static_cast<char>(cp);
        } else if (cp < 0x800) {
            out += static_cast<char>(0xC0 | (cp >> 6));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += static_cast<char>(0xE0 | (cp >> 12));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (cp >> 18));
            out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        }
    }
    return out;
}

// UTF-8 → UTF-16 (Java). Secuencias inválidas → U+FFFD.
jstring toJava(JNIEnv* env, const std::string& s) {
    std::u16string u;
    u.reserve(s.size());
    size_t i = 0;
    while (i < s.size()) {
        const auto b0 = static_cast<unsigned char>(s[i]);
        uint32_t cp;
        size_t n;
        if (b0 < 0x80) { cp = b0; n = 1; }
        else if ((b0 & 0xE0) == 0xC0) { cp = b0 & 0x1F; n = 2; }
        else if ((b0 & 0xF0) == 0xE0) { cp = b0 & 0x0F; n = 3; }
        else if ((b0 & 0xF8) == 0xF0) { cp = b0 & 0x07; n = 4; }
        else { u += u'�'; ++i; continue; }  // continuación suelta, C0/C1, F5..FF
        if (b0 == 0xC0 || b0 == 0xC1 || b0 > 0xF4) { u += u'�'; ++i; continue; }
        if (i + n > s.size()) { u += u'�'; break; }
        bool ok = true;
        for (size_t k = 1; k < n; ++k) {
            const auto b = static_cast<unsigned char>(s[i + k]);
            if ((b & 0xC0) != 0x80) { ok = false; break; }
            cp = (cp << 6) | (b & 0x3F);
        }
        // Rechaza formas no más cortas (sobrelargas), > U+10FFFF y sustitutos.
        static constexpr uint32_t kMin[] = {0, 0, 0x80, 0x800, 0x10000};
        if (!ok || cp < kMin[n] || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
            u += u'�'; ++i; continue;
        }
        if (cp >= 0x10000) {
            cp -= 0x10000;
            u += static_cast<char16_t>(0xD800 + (cp >> 10));
            u += static_cast<char16_t>(0xDC00 + (cp & 0x3FF));
        } else {
            u += static_cast<char16_t>(cp);
        }
        i += n;
    }
    return env->NewString(reinterpret_cast<const jchar*>(u.data()), static_cast<jsize>(u.size()));
}

std::shared_ptr<Engine> lookup(jlong handle) {
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    const auto it = g_registry.find(handle);
    if (it == g_registry.end()) throw InvalidHandle("handle inválido");
    return it->second;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_diegobr4nd_lectorbilingue_engine_opus_Ct2NativeBridge_nativeLoad(
        JNIEnv* env, jclass, jstring jModelDir, jint threads, jint beam) {
    try {
        if (jModelDir == nullptr) throw InvalidArgument("modelDir nulo");
        if (threads < 1 || threads > kMaxThreads) throw InvalidArgument("threads fuera de rango (1..8)");
        if (beam < 1 || beam > kMaxBeam) throw InvalidArgument("beam fuera de rango (1..8)");
        const std::string dir = toUtf8(env, jModelDir);
        for (const char* f : {"/model.bin", "/source.spm", "/target.spm"}) {
            if (!fileExists(dir + f)) throw InvalidArgument("falta un archivo del modelo");
        }
        auto engine = std::make_shared<Engine>();
        if (!engine->source.Load(dir + "/source.spm").ok()) throw std::runtime_error("source.spm inválido");
        if (!engine->target.Load(dir + "/target.spm").ok()) throw std::runtime_error("target.spm inválido");

        ctranslate2::models::ModelLoader loader(dir);
        loader.device = ctranslate2::Device::CPU;
        loader.compute_type = ctranslate2::ComputeType::INT8;
        loader.num_replicas_per_device = 1;
        ctranslate2::ReplicaPoolConfig pool;
        pool.num_threads_per_replica = static_cast<size_t>(threads);
        engine->translator = std::make_unique<ctranslate2::Translator>(loader, pool);
        engine->beam = static_cast<size_t>(beam);

        const jlong handle = g_next_handle.fetch_add(1);
        std::lock_guard<std::mutex> lock(g_registry_mutex);
        g_registry.emplace(handle, std::move(engine));
        return handle;
    } catch (const InvalidArgument& e) {
        throwJava(env, "java/lang/IllegalArgumentException", e.what());
    } catch (const std::exception&) {
        throwJava(env, "java/lang/IllegalStateException", "no se pudo cargar el modelo");
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "error nativo desconocido");
    }
    return 0;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_diegobr4nd_lectorbilingue_engine_opus_Ct2NativeBridge_nativeTranslate(
        JNIEnv* env, jclass, jlong handle, jobjectArray jSentences) {
    try {
        if (jSentences == nullptr) throw InvalidArgument("lista nula");
        const jsize n = env->GetArrayLength(jSentences);
        if (n < 1 || n > kMaxBatch) throw InvalidArgument("lote fuera de rango (1..64)");
        const std::shared_ptr<Engine> engine = lookup(handle);

        std::vector<std::vector<std::string>> batch;
        batch.reserve(static_cast<size_t>(n));
        for (jsize i = 0; i < n; ++i) {
            auto js = static_cast<jstring>(env->GetObjectArrayElement(jSentences, i));
            if (js == nullptr) throw InvalidArgument("oración nula");
            if (env->GetStringLength(js) > kMaxSentenceChars) {
                env->DeleteLocalRef(js);
                throw InvalidArgument("oración demasiado larga (máx. 1000)");
            }
            const std::string text = toUtf8(env, js);
            env->DeleteLocalRef(js);
            std::vector<std::string> pieces;
            if (!engine->source.Encode(text, &pieces).ok()) throw std::runtime_error("tokenización");
            pieces.emplace_back("</s>");
            batch.push_back(std::move(pieces));
        }

        ctranslate2::TranslationOptions options;
        options.beam_size = engine->beam;
        options.max_decoding_length = kMaxDecodingLength;
        const auto results = engine->translator->translate_batch(batch, options);
        if (results.size() != static_cast<size_t>(n)) throw std::runtime_error("resultados incompletos");

        jclass stringClass = env->FindClass("java/lang/String");
        if (stringClass == nullptr) return nullptr;
        jobjectArray out = env->NewObjectArray(n, stringClass, nullptr);
        env->DeleteLocalRef(stringClass);
        if (out == nullptr) return nullptr;
        for (jsize i = 0; i < n; ++i) {
            std::string decoded;
            // Span: la sobrecarga con std::vector está obsoleta en SentencePiece v0.2.2.
            const auto& tokens = results[static_cast<size_t>(i)].output();
            if (!engine->target.Decode(absl::MakeConstSpan(tokens), &decoded).ok()) {
                throw std::runtime_error("destokenización");
            }
            jstring js = toJava(env, decoded);
            if (js == nullptr) return nullptr;
            env->SetObjectArrayElement(out, i, js);
            env->DeleteLocalRef(js);
        }
        return out;
    } catch (const InvalidArgument& e) {
        throwJava(env, "java/lang/IllegalArgumentException", e.what());
    } catch (const InvalidHandle&) {
        throwJava(env, "java/lang/IllegalStateException", "motor no cargado");
    } catch (const std::exception&) {
        throwJava(env, "java/lang/IllegalStateException", "error al traducir");
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "error nativo desconocido");
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_diegobr4nd_lectorbilingue_engine_opus_Ct2NativeBridge_nativeUnload(
        JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return;
    std::shared_ptr<Engine> released;
    {
        std::lock_guard<std::mutex> lock(g_registry_mutex);
        const auto it = g_registry.find(handle);
        if (it == g_registry.end()) return;  // ya liberado o inválido: no-op
        released = std::move(it->second);
        g_registry.erase(it);
    }
    // Se destruye aquí, fuera del candado (o al terminar una traducción en curso).
    released.reset();
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_diegobr4nd_lectorbilingue_engine_opus_Ct2NativeBridge_nativeUtf8RoundTrip(
        JNIEnv* env, jclass, jstring text) {
    if (text == nullptr) {
        throwJava(env, "java/lang/IllegalArgumentException", "texto nulo");
        return nullptr;
    }
    try {
        return toJava(env, toUtf8(env, text));
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "error nativo desconocido");
        return nullptr;
    }
}

// Solo para pruebas: decodifica bytes UTF-8 crudos con toJava.
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_diegobr4nd_lectorbilingue_engine_opus_Ct2NativeBridge_nativeUtf8BytesToString(
        JNIEnv* env, jclass, jbyteArray bytes) {
    if (bytes == nullptr) {
        throwJava(env, "java/lang/IllegalArgumentException", "bytes nulos");
        return nullptr;
    }
    const jsize len = env->GetArrayLength(bytes);
    if (len > 4096) {
        throwJava(env, "java/lang/IllegalArgumentException", "demasiados bytes (máx. 4096)");
        return nullptr;
    }
    try {
        std::string raw(static_cast<size_t>(len), ' ');
        if (len > 0) env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte*>(raw.data()));
        if (env->ExceptionCheck()) return nullptr;
        return toJava(env, raw);
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "error nativo desconocido");
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
    // Defensa extra (regla: nunca registrar texto): apaga el registro interno de
    // las librerías. Abseil (usado por SentencePiece) escribe en logcat;
    // CTranslate2 escribe en stderr. Ninguno de los caminos que usamos registra
    // texto del usuario, pero así no depende de eso.
    try {
        absl::SetMinLogLevel(absl::LogSeverityAtLeast::kInfinity);
        ctranslate2::set_log_level(ctranslate2::LogLevel::Off);
    } catch (...) {
        // CT2_VERBOSE inválido en el entorno: no impide cargar la librería.
    }
    return JNI_VERSION_1_6;
}
