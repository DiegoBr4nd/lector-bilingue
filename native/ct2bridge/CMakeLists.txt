cmake_minimum_required(VERSION 3.22)
project(ct2bridge LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# La puerta se mide con el APK debug: el nativo va optimizado siempre.
set(CMAKE_C_FLAGS_DEBUG "-O2 -g0" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_DEBUG "-O2 -g0" CACHE STRING "" FORCE)
# El toolchain del NDK define CMAKE_<LANG>_FLAGS_DEBUG como variable normal
# ("-fno-limit-debug-info", sin -O), que tapa la del caché: sin estas dos líneas
# compile_commands.json no tiene ningún -O y todo se compila con -O0.
set(CMAKE_C_FLAGS_DEBUG "-O2 -g0")
set(CMAKE_CXX_FLAGS_DEBUG "-O2 -g0")

get_filename_component(REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
set(THIRD_PARTY "${REPO_ROOT}/native/third_party")
# Sin rutas de la máquina dentro del binario (builds reproducibles).
add_compile_options("-ffile-prefix-map=${REPO_ROOT}=." -ffunction-sections -fdata-sections)

# --- CTranslate2: CPU, sin MKL/DNNL/OpenMP/CUDA, con Ruy para ARM ---
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WITH_MKL OFF CACHE BOOL "" FORCE)
set(WITH_DNNL OFF CACHE BOOL "" FORCE)
set(WITH_CUDA OFF CACHE BOOL "" FORCE)
set(WITH_RUY ON CACHE BOOL "" FORCE)
set(OPENMP_RUNTIME NONE CACHE STRING "" FORCE)
set(BUILD_CLI OFF CACHE BOOL "" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory("${THIRD_PARTY}/CTranslate2" ctranslate2 EXCLUDE_FROM_ALL)
# Bionic no tiene pthread_setaffinity_np (error en src/thread_pool.cc): lo damos nosotros.
target_compile_options(ctranslate2 PRIVATE
  "SHELL:-include ${CMAKE_CURRENT_LIST_DIR}/compat/android_pthread_affinity.h")

# --- Abseil (lo necesita SentencePiece v0.2.2) ---
# SentencePiece lo bajaría de internet al configurar (FetchContent) y crearía un
# enlace simbólico dentro de su carpeta. Lo usamos desde un submódulo fijado.
set(ABSL_PROPAGATE_CXX_STD ON CACHE BOOL "" FORCE)
set(ABSL_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(ABSL_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory("${THIRD_PARTY}/abseil-cpp" absl EXCLUDE_FROM_ALL)

# SentencePiece incluye "third_party/absl/...": copiamos los encabezados de Abseil
# a esa forma dentro de la carpeta de compilación (sin enlaces simbólicos).
set(SPM_ABSL_SHIM "${CMAKE_CURRENT_BINARY_DIR}/spm_absl")
file(COPY "${THIRD_PARTY}/abseil-cpp/absl" DESTINATION "${SPM_ABSL_SHIM}/third_party"
  FILES_MATCHING PATTERN "*.h" PATTERN "*.inc")

# --- SentencePiece estático ---
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(SPM_BUILD_TEST OFF CACHE BOOL "" FORCE)
# "none": ni FetchContent ("module") ni find_package ("package"); Abseil ya está arriba.
set(SPM_ABSL_PROVIDER "none" CACHE STRING "" FORCE)
add_subdirectory("${THIRD_PARTY}/sentencepiece" sentencepiece EXCLUDE_FROM_ALL)
# Abseil le llega a sentencepiece-static solo como INTERFACE: sus propios .cc no ven
# "absl/..." (error: 'absl/base/attributes.h' file not found). Lo agregamos aquí.
target_include_directories(sentencepiece-static PRIVATE "${SPM_ABSL_SHIM}" "${THIRD_PARTY}/abseil-cpp")

add_library(ct2bridge SHARED ct2bridge.cpp)
target_include_directories(ct2bridge PRIVATE "${THIRD_PARTY}/sentencepiece/src" "${SPM_ABSL_SHIM}")
target_link_libraries(ct2bridge PRIVATE ctranslate2 sentencepiece-static log)
target_link_options(ct2bridge PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
