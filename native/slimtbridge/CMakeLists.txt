cmake_minimum_required(VERSION 3.22)
project(slimtbridge LANGUAGES C CXX)

# slimt usa C++20 (inicializadores designados).
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Igual que ct2bridge: el nativo va optimizado y sin assert también en debug.
set(CMAKE_C_FLAGS_DEBUG "-O2 -g0 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O2 -g0 -DNDEBUG")

get_filename_component(REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
set(THIRD_PARTY "${REPO_ROOT}/native/third_party")
set(SLIMT_DIR "${THIRD_PARTY}/slimt")
# Copia parcheada de slimt/ (ver más abajo). Vive en la carpeta de compilación.
set(SLIMT_PATCHED "${CMAKE_CURRENT_BINARY_DIR}/slimt-src")

# Sin rutas de la máquina dentro del binario (builds reproducibles). La copia parcheada
# se nombra aparte para que no aparezca la carpeta con hash de AGP.
add_compile_options(
  "-ffile-prefix-map=${REPO_ROOT}=."
  "-ffile-prefix-map=${SLIMT_PATCHED}=slimt-src"
  "-ffile-prefix-map=${ANDROID_NDK}=ndk"
  -ffunction-sections -fdata-sections)

# --- ruy (multiplicación de enteros de 8 bits en ARM) + cpuinfo ---
# Submódulos de slimt fijados (3rd-party/ruy y su third_party/cpuinfo).
add_subdirectory("${SLIMT_DIR}/3rd-party/ruy" ruy EXCLUDE_FROM_ALL)

# --- SentencePiece (fork browsermt, submódulo de slimt), con protobuf-lite propio ---
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(SPM_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(SPM_USE_BUILTIN_PROTOBUF ON CACHE BOOL "" FORCE)
set(SPM_USE_EXTERNAL_ABSL OFF CACHE BOOL "" FORCE)
add_subdirectory("${SLIMT_DIR}/3rd-party/sentencepiece" sentencepiece EXCLUDE_FROM_ALL)
# Como 3rd-party/CMakeLists.txt de slimt: los include de sentencepiece-static son de
# carpeta (include_directories), no del destino; se pasan a mano a quien lo usa.
get_target_property(SPM_INCLUDES sentencepiece-static INCLUDE_DIRECTORIES)

# --- slimt: copia con parches, sin el divisor de oraciones (sin PCRE2) ---
# El submódulo nunca se modifica. Cada vez que se configura:
#   1. se copia slimt/ a la carpeta de compilación con fin de línea LF (en Windows git
#      saca los submódulos con CRLF; así el resultado es igual en Windows y en Linux);
#   2. se aplican los parches de patches/ con `git apply`.
find_package(Git REQUIRED)
set(SLIMT_PATCHES
  "${CMAKE_CURRENT_LIST_DIR}/patches/0001-posix-memalign.patch"
  "${CMAKE_CURRENT_LIST_DIR}/patches/0002-ruy-selected-bias.patch"
  "${CMAKE_CURRENT_LIST_DIR}/patches/0003-abort-lanza-excepcion.patch"
  "${CMAKE_CURRENT_LIST_DIR}/patches/0004-limites-al-leer-modelo.patch")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${SLIMT_PATCHES})

file(GLOB_RECURSE SLIMT_ORIGINALS RELATIVE "${SLIMT_DIR}"
  "${SLIMT_DIR}/slimt/*.cc" "${SLIMT_DIR}/slimt/*.hh" "${SLIMT_DIR}/slimt/*.h")
foreach(rel IN LISTS SLIMT_ORIGINALS)
  # @ONLY: no hay @VAR@ en estos archivos (comprobado); configure_file solo reescribe
  # si el contenido cambió, así no se recompila todo en cada configuración.
  configure_file("${SLIMT_DIR}/${rel}" "${SLIMT_PATCHED}/${rel}" @ONLY NEWLINE_STYLE LF)
endforeach()
foreach(patch IN LISTS SLIMT_PATCHES)
  # GIT_CEILING_DIRECTORIES: que git no tome el repositorio de afuera (la carpeta de
  # compilación está dentro del repo); así `git apply` trabaja como `patch`.
  execute_process(
    COMMAND "${CMAKE_COMMAND}" -E env "GIT_CEILING_DIRECTORIES=${CMAKE_CURRENT_BINARY_DIR}"
            "${GIT_EXECUTABLE}" apply --whitespace=nowarn "${patch}"
    WORKING_DIRECTORY "${SLIMT_PATCHED}"
    RESULT_VARIABLE patch_result
    ERROR_VARIABLE patch_error)
  if(NOT patch_result EQUAL 0)
    message(FATAL_ERROR "No se pudo aplicar ${patch}: ${patch_error}")
  endif()
endforeach()

set(SLIMT_SOURCES
  Aligned.cc Annotation.cc Batcher.cc Frontend.cc HTML.cc Input.cc Io.cc Model.cc
  Modules.cc QMM.cc Request.cc Response.cc Shortlist.cc Tensor.cc TensorOps.cc
  TextProcessor.cc Transformer.cc Utils.cc Vocabulary.cc XHScanner.cc)
list(TRANSFORM SLIMT_SOURCES PREPEND "${SLIMT_PATCHED}/slimt/")
# Splitter.cc y Regex.cc (PCRE2) no se compilan: los reemplaza este archivo.
list(APPEND SLIMT_SOURCES "${CMAKE_CURRENT_LIST_DIR}/compat/SentenceStreamNoSplit.cc")

add_library(slimt_core STATIC ${SLIMT_SOURCES})
target_include_directories(slimt_core PUBLIC
  "${SLIMT_PATCHED}"
  "${CMAKE_CURRENT_LIST_DIR}/compat/include"
  ${SPM_INCLUDES}
  "${SLIMT_DIR}/3rd-party/sentencepiece/src")
# Opciones de la prueba rápida (Tarea 1): ruy + NEON; sin intgemm, gemmology ni BLAS.
target_compile_definitions(slimt_core PRIVATE SLIMT_HAS_RUY USE_NEON)
# slimt usa -Werror; aquí no: un NDK nuevo con avisos nuevos no debe romper la compilación.
target_compile_options(slimt_core PRIVATE -Wall -Wno-unknown-pragmas -Wno-missing-field-initializers
  -Wno-unused-function -Wno-unused-private-field)
target_link_libraries(slimt_core PUBLIC sentencepiece-static ruy)

add_library(slimtbridge SHARED slimtbridge.cpp)
target_link_libraries(slimtbridge PRIVATE slimt_core)
target_link_options(slimtbridge PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
# Solo se exportan las funciones JNI (JNIEXPORT).
set_target_properties(slimtbridge PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
