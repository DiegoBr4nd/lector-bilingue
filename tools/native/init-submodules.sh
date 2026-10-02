#!/usr/bin/env bash
# Inicia solo los submódulos que necesita el motor (evita CUDA/cutlass/thrust, varios GB).
# No usar `git submodule update --init --recursive`: bajaría todo eso.
# cpu_features no se baja: CTranslate2 solo lo usa en x86_64 y compilamos solo arm64-v8a.
# abseil-cpp va como submódulo propio: SentencePiece v0.2.2 lo bajaría de internet al compilar.
set -euo pipefail
cd "$(dirname "$0")/../.."
git submodule update --init native/third_party/CTranslate2 native/third_party/sentencepiece \
  native/third_party/abseil-cpp
git -C native/third_party/CTranslate2 submodule update --init \
  third_party/ruy third_party/spdlog
git -C native/third_party/CTranslate2/third_party/ruy submodule update --init third_party/cpuinfo
