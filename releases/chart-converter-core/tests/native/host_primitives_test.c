#define _POSIX_C_SOURCE 200809L
#include "no5_mutex.h"
#include <errno.h>
#include <inttypes.h>
#include <semaphore.h>
#include <stdalign.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

/* Own native POSIX consumer. No foreign struct layout, OEM code or ABI. */
enum { WORKERS = 4, ITERATIONS = 5000, MAX_CHECKS = 96 };
struct check_result { const char *name; long actual; long expected; };
static struct check_result results[MAX_CHECKS];
static unsigned checks, created, joined;
static pthread_attr_t thread_attributes;
static pthread_t main_thread;
static struct guarded_mutex {
    uint64_t before[2];
    no5_mutex mutex;
    uint64_t after[2];
} guarded = {
    .before = {UINT64_C(0x51c7ae309b6d428f), UINT64_C(0xef09287463ac5bd1)},
    .after = {UINT64_C(0xa5b801c9d3e6472f), UINT64_C(0x193f6b5e80c2a47d)}
};

static void fail(const char *name, long actual, long expected)
{
    fprintf(stderr, "FAIL %s actual=%ld expected=%ld\n", name, actual, expected);
    fflush(stderr);
    /* No invalid destroy on failure: process teardown reclaims all threads. */
    _Exit(1);
}

static void require(const char *name, long actual, long expected)
{
    if (actual != expected) fail(name, actual, expected);
}

/* Only main writes the result array; worker results are read after join. */
static void record(const char *name, long actual, long expected)
{
    require(name, actual, expected);
    if (checks == MAX_CHECKS) fail("result_capacity", checks, MAX_CHECKS - 1);
    results[checks++] = (struct check_result){name, actual, expected};
}

static struct timespec deadline(clockid_t clock, long milliseconds)
{
    struct timespec value;
    require("clock_gettime", clock_gettime(clock, &value), 0);
    value.tv_nsec += (milliseconds % 1000) * 1000000L;
    value.tv_sec += milliseconds / 1000 + value.tv_nsec / 1000000000L;
    value.tv_nsec %= 1000000000L;
    return value;
}

static void create(pthread_t *thread, void *(*function)(void *), void *argument)
{
    require("pthread_create", pthread_create(thread, &thread_attributes, function, argument), 0);
    ++created;
}

static void join(pthread_t thread, void *expected)
{
    void *result = NULL;
    require("pthread_join", pthread_join(thread, &result), 0);
    require("thread_return_identity", result == expected, 1);
    ++joined;
}

struct foreign_results { int busy, wrong_owner, timed, is_not_main; };

static void *foreign(void *argument)
{
    struct foreign_results *result = argument;
    result->is_not_main = !pthread_equal(pthread_self(), main_thread);
    /* Main owns depth two before create and keeps it through this join. */
    result->busy = no5_mutex_try_lock(&guarded.mutex);
    if (result->busy == 0) require("unexpected_try_release", no5_mutex_unlock(&guarded.mutex), 0);
    result->wrong_owner = no5_mutex_unlock(&guarded.mutex);
    struct timespec end = deadline(CLOCK_REALTIME, 100);
    result->timed = no5_mutex_timed_lock(&guarded.mutex, &end);
    if (result->timed == 0) require("unexpected_timed_release", no5_mutex_unlock(&guarded.mutex), 0);
    return argument;
}

static _Atomic uint64_t counter = ATOMIC_VAR_INIT(0);
static atomic_int inside = ATOMIC_VAR_INIT(0);
static atomic_int violations = ATOMIC_VAR_INIT(0);
struct counter_results { int is_not_main; unsigned increments; };

static void *increment(void *argument)
{
    struct counter_results *result = argument;
    result->is_not_main = !pthread_equal(pthread_self(), main_thread);
    for (unsigned i = 0; i != ITERATIONS; ++i) {
        require("counter_lock", no5_mutex_lock(&guarded.mutex), 0);
        if (atomic_fetch_add(&inside, 1) != 0) atomic_fetch_add(&violations, 1);
        atomic_fetch_add_explicit(&counter, 1, memory_order_relaxed);
        ++result->increments;
        if (atomic_fetch_sub(&inside, 1) != 1) atomic_fetch_add(&violations, 1);
        require("counter_unlock", no5_mutex_unlock(&guarded.mutex), 0);
    }
    return argument;
}

static pthread_cond_t condition;
static int ready, permit, done, payload;
struct condition_results { int is_not_main, payload_seen; };

static void wait_for(int *predicate)
{
    struct timespec end = deadline(CLOCK_MONOTONIC, 1000);
    while (!*predicate) {
        int status = pthread_cond_timedwait(&condition,
            no5_mutex_native_handle(&guarded.mutex), &end);
        /* A timeout and predicate transition may race while reacquiring. */
        if (status != 0 && !(status == ETIMEDOUT && *predicate))
            fail("condition_predicate_deadline", status, 0);
    }
}

static void *condition_worker(void *argument)
{
    struct condition_results *result = argument;
    result->is_not_main = !pthread_equal(pthread_self(), main_thread);
    require("cond_worker_lock_depth1", no5_mutex_lock(&guarded.mutex), 0);
    ready = 1;
    require("cond_worker_ready_signal", pthread_cond_signal(&condition), 0);
    wait_for(&permit);
    result->payload_seen = payload;
    done = 1;
    require("cond_worker_done_signal", pthread_cond_signal(&condition), 0);
    require("cond_worker_unlock_depth1", no5_mutex_unlock(&guarded.mutex), 0);
    return argument;
}

static sem_t work_token, worker_ready;
struct semaphore_results { int is_not_main, received; };

static int wait_sem(sem_t *semaphore, long milliseconds)
{
    struct timespec end = deadline(CLOCK_REALTIME, milliseconds);
    int status;
    do { status = sem_timedwait(semaphore, &end); } while (status == -1 && errno == EINTR);
    return status == 0 ? 0 : errno;
}

static void *semaphore_worker(void *argument)
{
    struct semaphore_results *result = argument;
    result->is_not_main = !pthread_equal(pthread_self(), main_thread);
    require("sem_worker_ready_post", sem_post(&worker_ready), 0);
    require("sem_worker_wait", wait_sem(&work_token, 1000), 0);
    result->received = 1;
    return argument;
}

static void check_canaries(void)
{
    int intact = guarded.before[0] == UINT64_C(0x51c7ae309b6d428f)
        && guarded.before[1] == UINT64_C(0xef09287463ac5bd1)
        && guarded.after[0] == UINT64_C(0xa5b801c9d3e6472f)
        && guarded.after[1] == UINT64_C(0x193f6b5e80c2a47d);
    record("outer_canaries", intact, 1);
}

int main(void)
{
    main_thread = pthread_self();
    no5_mutex_init_result init = no5_mutex_init(&guarded.mutex);
    record("mutex_init", init.status, 0);
    record("mutex_attribute_cleanup", init.attribute_cleanup_status, 0);
    record("native_handle_live", no5_mutex_native_handle(&guarded.mutex) != NULL, 1);
    init = no5_mutex_init(&guarded.mutex);
    record("live_reinit_rejected", init.status, EALREADY);
    record("live_reinit_attribute_cleanup", init.attribute_cleanup_status, 0);
    record("thread_attr_init", pthread_attr_init(&thread_attributes), 0);
    record("thread_stack_256KiB", pthread_attr_setstacksize(&thread_attributes, 256u * 1024u), 0);
    record("thread_guard_native_page", pthread_attr_setguardsize(&thread_attributes, (size_t)sysconf(_SC_PAGESIZE)), 0);

    record("recursive_first_lock", no5_mutex_lock(&guarded.mutex), 0);
    record("recursive_second_lock", no5_mutex_lock(&guarded.mutex), 0);
    record("recursive_same_owner_try", no5_mutex_try_lock(&guarded.mutex), 0);
    record("recursive_release_depth3", no5_mutex_unlock(&guarded.mutex), 0);
    pthread_t peer;
    struct foreign_results foreign_result = {0};
    create(&peer, foreign, &foreign_result);
    join(peer, &foreign_result);
    record("foreign_is_not_main", foreign_result.is_not_main, 1);
    record("foreign_try_busy", foreign_result.busy, EBUSY);
    record("foreign_unlock_not_owner", foreign_result.wrong_owner, EPERM);
    record("foreign_timedlock_deadline", foreign_result.timed, ETIMEDOUT);
    record("recursive_release_depth2", no5_mutex_unlock(&guarded.mutex), 0);
    record("recursive_release_depth1", no5_mutex_unlock(&guarded.mutex), 0);
    struct timespec end = deadline(CLOCK_REALTIME, 100);
    record("timedlock_recovery", no5_mutex_timed_lock(&guarded.mutex, &end), 0);
    record("timedlock_recovery_unlock", no5_mutex_unlock(&guarded.mutex), 0);
    check_canaries();

    pthread_t workers[WORKERS];
    struct counter_results counters[WORKERS] = {{0}};
    for (unsigned i = 0; i != WORKERS; ++i) create(&workers[i], increment, &counters[i]);
    for (unsigned i = 0; i != WORKERS; ++i) {
        join(workers[i], &counters[i]);
        record("counter_is_not_main", counters[i].is_not_main, 1);
        record("counter_worker_increments", counters[i].increments, ITERATIONS);
    }
    record("protected_counter", (long)atomic_load(&counter), WORKERS * ITERATIONS);
    record("mutual_exclusion_violations", atomic_load(&violations), 0);
    record("critical_sections_left", atomic_load(&inside), 0);
    check_canaries();

    pthread_condattr_t condition_attributes;
    record("cond_attr_init", pthread_condattr_init(&condition_attributes), 0);
    record("cond_monotonic_clock", pthread_condattr_setclock(&condition_attributes, CLOCK_MONOTONIC), 0);
    record("cond_init", pthread_cond_init(&condition, &condition_attributes), 0);
    record("cond_attr_destroy", pthread_condattr_destroy(&condition_attributes), 0);
    record("cond_timeout_lock_depth1", no5_mutex_lock(&guarded.mutex), 0);
    end = deadline(CLOCK_MONOTONIC, 100);
    int condition_status;
    do { condition_status = pthread_cond_timedwait(&condition,
        no5_mutex_native_handle(&guarded.mutex), &end); } while (condition_status == 0);
    record("cond_empty_timeout", condition_status, ETIMEDOUT);
    record("cond_timeout_unlock_depth1", no5_mutex_unlock(&guarded.mutex), 0);
    struct condition_results condition_result = {0};
    create(&peer, condition_worker, &condition_result);
    record("cond_main_lock_depth1", no5_mutex_lock(&guarded.mutex), 0);
    wait_for(&ready);
    payload = 42;
    permit = 1;
    record("cond_main_permit_signal", pthread_cond_signal(&condition), 0);
    wait_for(&done);
    record("cond_main_unlock_depth1", no5_mutex_unlock(&guarded.mutex), 0);
    join(peer, &condition_result);
    record("condition_is_not_main", condition_result.is_not_main, 1);
    record("cond_protected_payload", condition_result.payload_seen, 42);
    record("cond_destroy_after_join", pthread_cond_destroy(&condition), 0);

    record("sem_work_init", sem_init(&work_token, 0, 0), 0);
    record("sem_ready_init", sem_init(&worker_ready, 0, 0), 0);
    errno = 0;
    int sem_status = sem_trywait(&work_token);
    int sem_error = errno;
    record("sem_empty_try_return", sem_status, -1);
    record("sem_empty_try_errno", sem_error, EAGAIN);
    record("sem_empty_timedwait_errno", wait_sem(&work_token, 100), ETIMEDOUT);
    struct semaphore_results semaphore_result = {0};
    create(&peer, semaphore_worker, &semaphore_result);
    record("sem_ready_handshake", wait_sem(&worker_ready, 1000), 0);
    record("sem_work_post", sem_post(&work_token), 0);
    join(peer, &semaphore_result);
    record("semaphore_is_not_main", semaphore_result.is_not_main, 1);
    record("sem_token_received", semaphore_result.received, 1);
    int remaining = -1;
    record("sem_work_getvalue", sem_getvalue(&work_token, &remaining), 0);
    record("sem_work_drained", remaining, 0);
    record("sem_ready_getvalue", sem_getvalue(&worker_ready, &remaining), 0);
    record("sem_ready_drained", remaining, 0);
    record("sem_work_destroy_after_join", sem_destroy(&work_token), 0);
    record("sem_ready_destroy_after_join", sem_destroy(&worker_ready), 0);

    record("all_threads_created", created, 7);
    record("all_threads_joined", joined, created);
    record("thread_attr_destroy", pthread_attr_destroy(&thread_attributes), 0);
    record("mutex_destroy_after_all_joined", no5_mutex_destroy(&guarded.mutex), 0);
    record("native_handle_not_live", no5_mutex_native_handle(&guarded.mutex) == NULL, 1);
    record("own_nonlive_lock_guard", no5_mutex_lock(&guarded.mutex), EINVAL);
    record("own_nonlive_destroy_guard", no5_mutex_destroy(&guarded.mutex), EINVAL);
    init = no5_mutex_init(&guarded.mutex);
    record("reuse_init", init.status, 0);
    record("reuse_attribute_cleanup", init.attribute_cleanup_status, 0);
    record("reuse_lock", no5_mutex_lock(&guarded.mutex), 0);
    record("reuse_unlock", no5_mutex_unlock(&guarded.mutex), 0);
    record("reuse_destroy", no5_mutex_destroy(&guarded.mutex), 0);
    check_canaries();

    printf("{\"format\":\"no5-own-host-primitives/1\",\"pass\":true,"
        "\"pid\":%ld,\"pageSize\":%ld,\"pointerBits\":%zu,"
        "\"mutexBytes\":%zu,\"mutexAlignment\":%zu,\"pthreadMutexBytes\":%zu,"
        "\"pthreadConditionBytes\":%zu,\"semaphoreBytes\":%zu,"
        "\"threadsCreated\":%u,\"threadsJoined\":%u,\"counter\":%" PRIu64 ",\"checks\":[",
        (long)getpid(), sysconf(_SC_PAGESIZE), sizeof(void *) * 8,
        sizeof(no5_mutex), alignof(no5_mutex), sizeof(pthread_mutex_t),
        sizeof(pthread_cond_t), sizeof(sem_t), created, joined, atomic_load(&counter));
    for (unsigned i = 0; i != checks; ++i) {
        printf("%s{\"name\":\"%s\",\"actual\":%ld,\"expected\":%ld}",
            i ? "," : "", results[i].name, results[i].actual, results[i].expected);
    }
    puts("]}");
    return ferror(stdout) ? 2 : 0;
}
