// Puente JNI mínimo (Tarea 3): solo comprueba que CTranslate2 y SentencePiece
// enlazan y que la librería carga. La Tarea 4 lo reemplaza por el puente real.
#include <jni.h>

#include <ctranslate2/types.h>
#include <sentencepiece_processor.h>

namespace {

// Referencias temporales: obligan al enlazador a incluir código de ambas librerías.
bool linkCheck() {
  const auto type = ctranslate2::str_to_compute_type("int8");
  sentencepiece::SentencePieceProcessor processor;
  return type == ctranslate2::ComputeType::INT8 && !processor.status().ok();  // sin modelo cargado: status() no es OK
}

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
  if (!linkCheck()) {
    return JNI_ERR;
  }
  return JNI_VERSION_1_6;
}
