// Puente JNI entre Kotlin (SlimtNativeBridge) y slimt (modelos de Firefox).
// Reglas: validar toda entrada, no dejar escapar excepciones C++, no registrar texto.
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <exception>
#include <fstream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>
#include <sys/stat.h>

#include "slimt/Frontend.hh"
#include "slimt/Model.hh"
#include "slimt/Response.hh"

namespace {

// Mismos topes que NativeBridge.kt (OPUS y Firefox).
constexpr int kMaxSentenceChars = 1000;
constexpr int kMaxBatch = 64;
constexpr int kMaxThreads = 4;
constexpr size_t kMaxConfigBytes = 4096;
constexpr size_t kMaxDirBytes = 4096;
constexpr size_t kMaxFileName = 128;
constexpr int kMaxLayers = 12;
constexpr int kMaxHeads = 16;
constexpr const char* kConfigFile = "slimt.json";

struct InvalidArgument : std::runtime_error { using std::runtime_error::runtime_error; };
struct InvalidHandle : std::runtime_error { using std::runtime_error::runtime_error; };
// slimt.json ausente, mal formado o con valores fuera de rango.
struct InvalidConfig : std::runtime_error { using std::runtime_error::runtime_error; };

// ---------------------------------------------------------------------------------------
// slimt.json: lo escribe nuestro script de modelos (no viene de Mozilla). slimt no lee la
// configuración del modelo, así que capas y cabezas van aquí. Formato estricto:
//   {"model": "<archivo>", "vocabulary": "<archivo>", "shortlist": "<archivo>",
//    "encoder_layers": N, "decoder_layers": N, "heads": N}
// Las 6 claves son obligatorias; no se aceptan otras ni repetidas. Ver README.md.
// ---------------------------------------------------------------------------------------
struct ModelConfig {
    std::string model;
    std::string vocabulary;
    std::string shortlist;
    int encoder_layers = 0;
    int decoder_layers = 0;
    int heads = 0;
};

// Nombre de archivo simple: ^[A-Za-z0-9._-]{1,128}$, sin "..", sin "/".
bool isSafeFileName(const std::string& name) {
    if (name.empty() || name.size() > kMaxFileName) return false;
    if (name.find("..") != std::string::npos || name == ".") return false;
    for (const char c : name) {
        const bool ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
                        (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
        if (!ok) return false;
    }
    return true;
}

class ConfigParser {
public:
    explicit ConfigParser(const std::string& text) : s_(text) {}

    ModelConfig parse() {
        ModelConfig cfg;
        unsigned seen = 0;
        skipWs();
        expect('{');
        skipWs();
        if (peek() == '}') fail();  // objeto vacío: faltan claves
        while (true) {
            skipWs();
            const std::string key = readString();
            skipWs();
            expect(':');
            skipWs();
            unsigned bit = 0;
            if (key == "model") { bit = 1u; cfg.model = readFileName(); }
            else if (key == "vocabulary") { bit = 2u; cfg.vocabulary = readFileName(); }
            else if (key == "shortlist") { bit = 4u; cfg.shortlist = readFileName(); }
            else if (key == "encoder_layers") { bit = 8u; cfg.encoder_layers = readInt(1, kMaxLayers); }
            else if (key == "decoder_layers") { bit = 16u; cfg.decoder_layers = readInt(1, kMaxLayers); }
            else if (key == "heads") { bit = 32u; cfg.heads = readInt(1, kMaxHeads); }
            else fail();                     // clave desconocida
            if ((seen & bit) != 0) fail();   // clave repetida
            seen |= bit;
            skipWs();
            if (peek() == ',') { ++pos_; continue; }
            expect('}');
            break;
        }
        skipWs();
        if (pos_ != s_.size()) fail();       // basura después del objeto
        if (seen != 63u) fail();             // falta alguna clave
        return cfg;
    }

private:
    [[noreturn]] static void fail() { throw InvalidConfig("slimt.json inválido"); }

    char peek() const { return pos_ < s_.size() ? s_[pos_] : '\0'; }

    void expect(char c) {
        if (peek() != c) fail();
        ++pos_;
    }

    void skipWs() {
        while (pos_ < s_.size() &&
               (s_[pos_] == ' ' || s_[pos_] == '\t' || s_[pos_] == '\n' || s_[pos_] == '\r')) {
            ++pos_;
        }
    }

    // Cadena sin escapes ni caracteres de control (ningún valor válido los necesita).
    std::string readString() {
        expect('"');
        std::string out;
        while (true) {
            if (pos_ >= s_.size()) fail();
            const auto c = static_cast<unsigned char>(s_[pos_++]);
            if (c == '"') break;
            if (c == '\\' || c < 0x20 || out.size() >= kMaxFileName) fail();
            out += static_cast<char>(c);
        }
        return out;
    }

    std::string readFileName() {
        std::string name = readString();
        if (!isSafeFileName(name)) fail();
        return name;
    }

    // Entero decimal sin signo, sin ceros a la izquierda, dentro de [lo, hi].
    int readInt(int lo, int hi) {
        const size_t start = pos_;
        int value = 0;
        while (pos_ < s_.size() && s_[pos_] >= '0' && s_[pos_] <= '9') {
            if (pos_ - start >= 3) fail();
            value = value * 10 + (s_[pos_] - '0');
            ++pos_;
        }
        const size_t len = pos_ - start;
        if (len == 0 || (len > 1 && s_[start] == '0')) fail();
        if (value < lo || value > hi) fail();
        return value;
    }

    const std::string& s_;
    size_t pos_ = 0;
};

// Solo archivos normales (lstat: un enlace simbólico no cuenta) directamente en dir.
std::string resolveInside(const std::string& dir, const std::string& name) {
    if (!isSafeFileName(name)) throw InvalidConfig("slimt.json inválido");
    const std::string path = dir + "/" + name;
    struct stat st{};
    if (lstat(path.c_str(), &st) != 0 || !S_ISREG(st.st_mode)) {
        throw InvalidArgument("falta un archivo del modelo");
    }
    return path;
}

ModelConfig readConfig(const std::string& dir) {
    const std::string path = resolveInside(dir, kConfigFile);
    struct stat st{};
    if (lstat(path.c_str(), &st) != 0 || st.st_size <= 0 ||
        static_cast<uint64_t>(st.st_size) > kMaxConfigBytes) {
        throw InvalidConfig("slimt.json inválido");
    }
    std::ifstream in(path, std::ios::binary);
    if (!in) throw InvalidConfig("slimt.json inválido");
    std::string text(static_cast<size_t>(st.st_size), '\0');
    in.read(text.data(), static_cast<std::streamsize>(text.size()));
    if (in.gcount() != static_cast<std::streamsize>(text.size())) throw InvalidConfig("slimt.json inválido");
    return ConfigParser(text).parse();
}

// ---------------------------------------------------------------------------------------
// Motor
// ---------------------------------------------------------------------------------------
struct Engine {
    std::shared_ptr<slimt::Model> model;
    // Un Blocking por hilo (no es seguro usar uno desde varios hilos a la vez).
    std::vector<std::unique_ptr<slimt::Blocking>> services;
    // Una traducción a la vez por motor: los Blocking guardan estado propio.
    std::mutex mutex;
};

std::mutex g_registry_mutex;
// slimt incrementa un contador global (static model_id) sin candado en Model::Model:
// se construye un modelo a la vez.
std::mutex g_model_construct_mutex;
std::unordered_map<jlong, std::shared_ptr<Engine>> g_registry;
// Contador monótono: un handle nunca se reutiliza. 0 queda reservado como inválido.
std::atomic<jlong> g_next_handle{1};

void throwJava(JNIEnv* env, const char* cls, const char* msg) {
    if (env->ExceptionCheck()) return;
    jclass c = env->FindClass(cls);
    if (c != nullptr) env->ThrowNew(c, msg);
}

// UTF-16 (Java) → UTF-8 estándar. Sustitutos sueltos → U+FFFD. (Igual que ct2bridge.)
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

// UTF-8 → UTF-16 (Java). Secuencias inválidas → U+FFFD. (Igual que ct2bridge.)
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
        else { u += u'�'; ++i; continue; }
        if (b0 == 0xC0 || b0 == 0xC1 || b0 > 0xF4) { u += u'�'; ++i; continue; }
        if (i + n > s.size()) { u += u'�'; break; }
        bool ok = true;
        for (size_t k = 1; k < n; ++k) {
            const auto b = static_cast<unsigned char>(s[i + k]);
            if ((b & 0xC0) != 0x80) { ok = false; break; }
            cp = (cp << 6) | (b & 0x3F);
        }
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

// Reparte las oraciones entre los Blocking (oración i → servicio i % n) y las traduce.
// Con 1 servicio todo va en el hilo que llama, en un solo lote.
std::vector<std::string> translateAll(Engine& engine, const std::vector<std::string>& sentences) {
    const size_t n = std::min(engine.services.size(), sentences.size());
    std::vector<std::string> out(sentences.size());
    const slimt::Options options{.alignment = false, .html = false};
    auto work = [&](size_t t) {
        std::vector<std::string> chunk;
        std::vector<size_t> index;
        for (size_t i = t; i < sentences.size(); i += n) {
            chunk.push_back(sentences[i]);
            index.push_back(i);
        }
        auto responses = engine.services[t]->translate(engine.model, std::move(chunk), options);
        if (responses.size() != index.size()) throw std::runtime_error("resultados incompletos");
        for (size_t k = 0; k < index.size(); ++k) out[index[k]] = std::move(responses[k].target.text);
    };
    if (n <= 1) {
        work(0);
        return out;
    }
    std::vector<std::exception_ptr> errors(n);
    std::vector<std::thread> pool;
    pool.reserve(n);
    // Si crear un hilo falla (std::system_error), los hilos ya creados siguen usando
    // out/errors/sentences: hay que unirlos antes de salir. Nunca se destruye un
    // std::thread unible (eso llama a std::terminate). Las partes sin hilo se hacen aquí.
    size_t started = 0;
    try {
        for (; started < n; ++started) {
            pool.emplace_back([&, t = started] {
                try { work(t); } catch (...) { errors[t] = std::current_exception(); }
            });
        }
    } catch (...) {
        // Sin hilo para las partes started..n-1: se traducen en este hilo.
    }
    for (size_t t = started; t < n; ++t) {
        try { work(t); } catch (...) { errors[t] = std::current_exception(); }
    }
    for (auto& th : pool) th.join();
    for (const auto& e : errors) {
        if (e) std::rethrow_exception(e);
    }
    return out;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_diegobr4nd_lectorbilingue_engine_firefox_SlimtNativeBridge_nativeLoad(
        JNIEnv* env, jclass, jstring jModelDir, jint threads) {
    try {
        if (jModelDir == nullptr) throw InvalidArgument("modelDir nulo");
        if (threads < 1 || threads > kMaxThreads) throw InvalidArgument("threads fuera de rango (1..4)");
        const std::string dir = toUtf8(env, jModelDir);
        if (dir.empty() || dir.size() > kMaxDirBytes || dir[0] != '/' ||
            dir.find('\0') != std::string::npos) {
            throw InvalidArgument("modelDir inválido");
        }
        struct stat st{};
        if (stat(dir.c_str(), &st) != 0 || !S_ISDIR(st.st_mode)) throw InvalidArgument("modelDir inválido");

        const ModelConfig cfg = readConfig(dir);
        slimt::Package<std::string> package{
            .model = resolveInside(dir, cfg.model),
            .vocabulary = resolveInside(dir, cfg.vocabulary),
            .shortlist = resolveInside(dir, cfg.shortlist),
            .ssplit = "",
        };
        slimt::Model::Config modelConfig;
        modelConfig.encoder_layers = static_cast<size_t>(cfg.encoder_layers);
        modelConfig.decoder_layers = static_cast<size_t>(cfg.decoder_layers);
        modelConfig.num_heads = static_cast<size_t>(cfg.heads);

        auto engine = std::make_shared<Engine>();
        {
            std::lock_guard<std::mutex> lock(g_model_construct_mutex);
            engine->model = std::make_shared<slimt::Model>(modelConfig, package);
        }
        slimt::Config serviceConfig;
        serviceConfig.cache_size = 0;  // sin caché: no guarda textos entre llamadas
        for (jint t = 0; t < threads; ++t) {
            engine->services.push_back(std::make_unique<slimt::Blocking>(serviceConfig));
        }

        const jlong handle = g_next_handle.fetch_add(1);
        std::lock_guard<std::mutex> lock(g_registry_mutex);
        g_registry.emplace(handle, std::move(engine));
        return handle;
    } catch (const InvalidArgument& e) {
        throwJava(env, "java/lang/IllegalArgumentException", e.what());
    } catch (const InvalidConfig&) {
        throwJava(env, "java/lang/IllegalArgumentException", "slimt.json inválido");
    } catch (const std::exception&) {
        throwJava(env, "java/lang/IllegalStateException", "no se pudo cargar el modelo");
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "error nativo desconocido");
    }
    return 0;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_diegobr4nd_lectorbilingue_engine_firefox_SlimtNativeBridge_nativeTranslate(
        JNIEnv* env, jclass, jlong handle, jobjectArray jSentences) {
    try {
        if (jSentences == nullptr) throw InvalidArgument("lista nula");
        const jsize n = env->GetArrayLength(jSentences);
        if (n < 1 || n > kMaxBatch) throw InvalidArgument("lote fuera de rango (1..64)");
        const std::shared_ptr<Engine> engine = lookup(handle);

        std::vector<std::string> sentences;
        sentences.reserve(static_cast<size_t>(n));
        for (jsize i = 0; i < n; ++i) {
            auto js = static_cast<jstring>(env->GetObjectArrayElement(jSentences, i));
            if (js == nullptr) throw InvalidArgument("oración nula");
            if (env->GetStringLength(js) > kMaxSentenceChars) {
                env->DeleteLocalRef(js);
                throw InvalidArgument("oración demasiado larga (máx. 1000)");
            }
            sentences.push_back(toUtf8(env, js));
            env->DeleteLocalRef(js);
        }

        std::vector<std::string> results;
        {
            std::lock_guard<std::mutex> lock(engine->mutex);
            results = translateAll(*engine, sentences);
        }
        if (results.size() != static_cast<size_t>(n)) throw std::runtime_error("resultados incompletos");

        jclass stringClass = env->FindClass("java/lang/String");
        if (stringClass == nullptr) return nullptr;
        jobjectArray out = env->NewObjectArray(n, stringClass, nullptr);
        env->DeleteLocalRef(stringClass);
        if (out == nullptr) return nullptr;
        for (jsize i = 0; i < n; ++i) {
            jstring js = toJava(env, results[static_cast<size_t>(i)]);
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
Java_io_github_diegobr4nd_lectorbilingue_engine_firefox_SlimtNativeBridge_nativeRelease(
        JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return;
    try {
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
    } catch (...) {
        // Nada que hacer: liberar nunca debe tumbar el proceso.
    }
}
