#ifndef NO5_HOST_RUNTIME_MUTEX_H
#define NO5_HOST_RUNTIME_MUTEX_H

/* Own POSIX source API. Compile every consumer against this header on its
 * target platform; pthread_mutex_t has no fixed size or foreign ABI here.
 * Build with -D_POSIX_C_SOURCE=200809L and -pthread.
 */
#include <pthread.h>
#include <time.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct no5_mutex {
    pthread_mutex_t native;
    unsigned int live;
} no5_mutex;

/* Initialize fresh storage before first use. This initializes our bookkeeping;
 * pthread_mutex_init, not zero bytes, creates the actual native mutex.
 */
#define NO5_MUTEX_STORAGE_INIT {0}

typedef struct no5_mutex_init_result {
    int status;                   /* Primary attribute/native initialization. */
    int attribute_cleanup_status; /* Actual attr_destroy result, if attempted. */
} no5_mutex_init_result;

/* A result with status==0 means the native recursive mutex is live, including
 * when attribute_cleanup_status is nonzero. Check both fields. A live mutex
 * must later be destroyed even if attribute cleanup reported an error.
 * Fresh zero-initialized storage or storage after successful destroy is valid.
 * Reinitializing a live object returns EALREADY without touching native state.
 */
no5_mutex_init_result no5_mutex_init(no5_mutex *mutex);

/* Lock operations return the actual pthread error code, not errno. A null or
 * non-live own object returns EINVAL before any native call. Recursive reentry
 * requires a matching unlock for every successful lock. Try-lock exposes busy;
 * timed-lock uses an absolute CLOCK_REALTIME deadline supplied by the caller.
 */
int no5_mutex_lock(no5_mutex *mutex);
int no5_mutex_try_lock(no5_mutex *mutex);
int no5_mutex_timed_lock(no5_mutex *mutex, const struct timespec *absolute_deadline);
int no5_mutex_unlock(no5_mutex *mutex);

/* Lifecycle requires external synchronization: join all users, release every
 * recursive level and leave no waiters before destroy. Concurrent init/destroy,
 * copying a live object and destroying an owned/waited-on native mutex are not
 * supported. Failed native destroy preserves live state; success permits reuse.
 */
int no5_mutex_destroy(no5_mutex *mutex);

/* For pthread_cond_wait/timedwait at exactly ONE recursive ownership level.
 * NULL means no live own object. Do not initialize, destroy, copy or change the
 * native object through this handle. The caller owns condition predicates,
 * cancellation cleanup, waiters and all condition-variable lifecycle.
 */
pthread_mutex_t *no5_mutex_native_handle(no5_mutex *mutex);

#ifdef __cplusplus
}
#endif
#endif
