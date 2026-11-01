/* OwnHostABI source. Original Nav/OSS libraries are external and unmodified. */
#include "cray_mutex.h"

/* Own machine-compatible declarations, derived from argument/return registers;
 * these are not recovered authoritative vendor source prototypes. */
extern "C" {
unsigned int OSS_LinuxMutexCreate(void *storage44, const char *name);
unsigned int OSS_MutexDestroy(void *storage44);
unsigned int OSS_MutexLock(void *storage44);
unsigned int OSS_MutexUnlock(void *storage44);
unsigned int OSS_MutexTryLock(void *storage44);
unsigned int OSS_MutexTimedLock(void *storage44, unsigned int timeout);
}

namespace {
no5_cray_mutex_observer observer = nullptr;

void observe(const CRayMutex *object, unsigned int operation, unsigned int status) noexcept {
    if (observer != nullptr) {
        observer(object, operation, status);
    }
}
}

extern "C" void no5_cray_mutex_set_observer(no5_cray_mutex_observer value) noexcept {
    observer = value;
}

struct CRayMutex::Impl {
    /* Zero is fresh output storage, not an initialized pthread representation.
     * Only the genuine OSS create function initializes/registers its mutex. */
    alignas(4) unsigned char storage[44];
};

CRayMutex::CRayMutex() : impl_(nullptr) {
    static_assert(sizeof(Impl) == 44 && alignof(Impl) == 4, "Exact opaque OSS storage");
    Impl *fresh = new Impl{};
    unsigned int status;
    try {
        status = OSS_LinuxMutexCreate(fresh->storage, "no5-owned-CRayMutex");
    } catch (...) {
        /* No complete provider status: registry side effects are not proven
         * reversible across an unexpected unwind. Do not free its storage. */
        __builtin_abort();
    }
    observe(this, no5_cray_mutex_create, status);
    if (status != 0) {
        /* The bound provider's normal nonzero path precedes native init and
         * registration; this specific returned-failure allocation is safe. */
        delete fresh;
        throw no5_cray_mutex_provider_error{no5_cray_mutex_create, status};
    }
    impl_ = fresh;
}

CRayMutex::~CRayMutex() noexcept {
    /* Destruction requires quiescence: no owners, waiters or concurrent users. */
    impl_->storage[40] = 1;
    const unsigned int status = OSS_MutexDestroy(impl_->storage);
    observe(this, no5_cray_mutex_destroy, status);
    if (status != 0) {
        /* Own stricter failure policy: never free a still-registered object or
         * pretend successful cleanup. This is not original exception parity. */
        __builtin_abort();
    }
    delete impl_;
}

unsigned int CRayMutex::Lock() {
    const unsigned int status = OSS_MutexLock(impl_->storage);
    observe(this, no5_cray_mutex_lock, status);
    return 0;  // Observed facade machine word; raw OSS status is separate.
}

unsigned int CRayMutex::Unlock() {
    const unsigned int status = OSS_MutexUnlock(impl_->storage);
    observe(this, no5_cray_mutex_unlock, status);
    return 0;
}

unsigned int CRayMutex::TryLock(unsigned int timeout) {
    const unsigned int status = timeout == 0
        ? OSS_MutexTryLock(impl_->storage)
        : OSS_MutexTimedLock(impl_->storage, timeout);
    observe(this, timeout == 0 ? no5_cray_mutex_try_lock : no5_cray_mutex_timed_lock, status);
    if (status == 0) return 0;
    if (status == 0x203) return 20;
    if (status == 0x205) return 21;
    return 1;
}
