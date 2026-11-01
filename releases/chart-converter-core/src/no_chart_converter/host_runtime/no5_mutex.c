#ifndef _POSIX_C_SOURCE
#define _POSIX_C_SOURCE 200809L
#endif
#include "no5_mutex.h"
#include <errno.h>

static int no5_mutex_is_live(const no5_mutex *mutex)
{
    return mutex != 0 && mutex->live == 1u;
}

no5_mutex_init_result no5_mutex_init(no5_mutex *mutex)
{
    no5_mutex_init_result result = {0, 0};
    pthread_mutexattr_t attributes;
    int status;

    if (mutex == 0) {
        result.status = EINVAL;
        return result;
    }
    if (mutex->live != 0u) {
        result.status = EALREADY;
        return result;
    }
    status = pthread_mutexattr_init(&attributes);
    if (status != 0) {
        result.status = status;
        return result;
    }
    status = pthread_mutexattr_settype(&attributes, PTHREAD_MUTEX_RECURSIVE);
    if (status == 0) {
        status = pthread_mutex_init(&mutex->native, &attributes);
        if (status == 0)
            mutex->live = 1u;
    }
    result.status = status;
    result.attribute_cleanup_status = pthread_mutexattr_destroy(&attributes);
    return result;
}

int no5_mutex_lock(no5_mutex *mutex)
{
    if (!no5_mutex_is_live(mutex))
        return EINVAL;
    return pthread_mutex_lock(&mutex->native);
}

int no5_mutex_try_lock(no5_mutex *mutex)
{
    if (!no5_mutex_is_live(mutex))
        return EINVAL;
    return pthread_mutex_trylock(&mutex->native);
}

int no5_mutex_timed_lock(no5_mutex *mutex, const struct timespec *absolute_deadline)
{
    if (!no5_mutex_is_live(mutex) || absolute_deadline == 0)
        return EINVAL;
    return pthread_mutex_timedlock(&mutex->native, absolute_deadline);
}

int no5_mutex_unlock(no5_mutex *mutex)
{
    if (!no5_mutex_is_live(mutex))
        return EINVAL;
    return pthread_mutex_unlock(&mutex->native);
}

int no5_mutex_destroy(no5_mutex *mutex)
{
    int status;
    if (!no5_mutex_is_live(mutex))
        return EINVAL;
    status = pthread_mutex_destroy(&mutex->native);
    if (status == 0)
        mutex->live = 0u;
    return status;
}

pthread_mutex_t *no5_mutex_native_handle(no5_mutex *mutex)
{
    if (!no5_mutex_is_live(mutex))
        return 0;
    return &mutex->native;
}
