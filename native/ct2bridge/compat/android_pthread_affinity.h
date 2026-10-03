// Se incluye a la fuerza (-include) al compilar CTranslate2 para Android.
// Bionic (la libc de Android) no trae pthread_setaffinity_np, que CTranslate2 usa en
// src/thread_pool.cc. Lo implementamos con sched_setaffinity sobre el id del hilo,
// sin modificar el submódulo. Solo se llama si se pide afinidad de hilos (por defecto no).
#pragma once

#if defined(__ANDROID__) && defined(__cplusplus)
#include <pthread.h>
#include <sched.h>
#include <cerrno>

static inline int pthread_setaffinity_np(pthread_t thread, size_t size, const cpu_set_t* set) {
  const pid_t tid = pthread_gettid_np(thread);
  if (tid < 0) {
    return ESRCH;
  }
  return sched_setaffinity(tid, size, set) == 0 ? 0 : errno;
}
#endif
