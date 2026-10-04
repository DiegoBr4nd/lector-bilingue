// Reemplazo del Export.hh que genera el CMake de slimt (generate_export_header).
// slimt va como biblioteca estática dentro de libslimtbridge.so y no exporta nada:
// SLIMT_EXPORT queda vacío. Solo se exportan las funciones JNI del puente.
#pragma once
#define SLIMT_EXPORT
