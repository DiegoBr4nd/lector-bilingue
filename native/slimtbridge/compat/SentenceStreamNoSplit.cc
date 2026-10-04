// Reemplazo de slimt/Splitter.cc para lector-bilingue.
//
// slimt parte el texto en oraciones con PCRE2 (Splitter.cc + Regex.cc, derivados de
// browsermt/ssplit). Nosotros ya le pasamos oraciones partidas por :core:text, así que
// no compilamos esos archivos ni PCRE2. Este archivo da las mismas clases (Splitter.hh)
// pero cada texto de entrada es UNA oración, tal cual llega.
//
// Copyright (C) 2026 lector-bilingue. Interfaz tomada de slimt (Copyright (C) 2023 slimt Authors).
// Licencia: GPL-2.0-or-later, como slimt (ver native/third_party/slimt/COPYRIGHT).
#include <cstddef>
#include <stdexcept>
#include <string>
#include <string_view>

#include "slimt/Splitter.hh"

namespace slimt {

// Sin lista de prefijos: no hay nada que cargar. Con datos, se rechaza (no los usamos).
Splitter::Splitter(const std::string& /*prefix_file*/) : Splitter() {
  throw std::runtime_error("ssplit no disponible");
}

void Splitter::load(const std::string& /*fname*/) {
  throw std::runtime_error("ssplit no disponible");
}

void Splitter::load_from_serialized(std::string_view buffer) {
  if (!buffer.empty()) throw std::runtime_error("ssplit no disponible");
}

std::string_view Splitter::operator()(std::string_view* rest) const {
  std::string_view all = *rest;
  *rest = std::string_view();
  return all;
}

SentenceStream::SentenceStream(std::string_view text, const Splitter& splitter,
                               splitmode mode, bool verify_utf8)
    : SentenceStream(text.data(), text.size(), splitter, mode, verify_utf8) {}

SentenceStream::SentenceStream(const char* data, size_t size,
                               const Splitter& splitter, splitmode mode,
                               bool /*verify_utf8*/)
    : cursor_(data), stop_(data + size), mode_(mode), splitter_(splitter),
      status_(0) {}

int SentenceStream::status() const { return status_; }

const std::string& SentenceStream::error_message() const {
  return error_message_;
}

// Entrega todo el texto como una sola oración (una vez). Texto vacío: ninguna.
bool SentenceStream::operator>>(std::string_view& snt) {
  if (cursor_ == nullptr || cursor_ >= stop_) return false;
  snt = std::string_view(cursor_, static_cast<size_t>(stop_ - cursor_));
  cursor_ = stop_;
  return true;
}

bool SentenceStream::operator>>(std::string& snt) {
  std::string_view view;
  if (!(*this >> view)) return false;
  snt.assign(view.data(), view.size());
  return true;
}

}  // namespace slimt
