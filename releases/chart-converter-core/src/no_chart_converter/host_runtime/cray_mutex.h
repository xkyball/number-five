#ifndef NO5_OWN_CRAY_MUTEX_H
#define NO5_OWN_CRAY_MUTEX_H

/* Independently authored, finite ARMHF host ABI. This is not a vendor header.
 * Requires a real genuine OSS provider and its normal OSS_Init task callback.
 * Compile explicitly as ARM32 hard-float C++11+, with RTTI and exceptions.
 */
#if !defined(__arm__) || !defined(__ARM_PCS_VFP) || !defined(__ARMEL__)
#error "This own CRayMutex facade requires little-endian ARM32 hard-float EABI"
#endif

class CRayMutex;

enum {
    no5_cray_mutex_create = 1,
    no5_cray_mutex_destroy = 2,
    no5_cray_mutex_lock = 3,
    no5_cray_mutex_unlock = 4,
    no5_cray_mutex_try_lock = 5,
    no5_cray_mutex_timed_lock = 6
};

/* Own diagnostic interface, separate from the five compatible C++ methods.
 * Configure only before all objects/threads, or after joining and destroying
 * them. Observer calls must not throw, recurse, lock, or alter the status.
 * A facade return word is not evidence of OSS success; record the raw status.
 */
typedef void (*no5_cray_mutex_observer)(
    const CRayMutex *object, unsigned int operation, unsigned int oss_status);
extern "C" __attribute__((visibility("default")))
void no5_cray_mutex_set_observer(no5_cray_mutex_observer observer) noexcept;

/* Own constructor failure type; no claim of original exception-type parity. */
struct no5_cray_mutex_provider_error {
    unsigned int operation;
    unsigned int oss_status;
};

class __attribute__((visibility("default"))) CRayMutex {
public:
    CRayMutex();
    virtual ~CRayMutex() noexcept;
    unsigned int Lock();
    unsigned int Unlock();
    unsigned int TryLock(unsigned int timeout);

    CRayMutex(const CRayMutex &) = delete;
    CRayMutex &operator=(const CRayMutex &) = delete;

private:
    struct Impl;
    Impl *impl_;
};

static_assert(sizeof(unsigned int) == 4, "OSS argument/status words are 32-bit");
static_assert(sizeof(void *) == 4, "The compatible ABI has four-byte pointers");
static_assert(sizeof(CRayMutex) == 8, "CRayMutex is a vptr and one opaque pointer");
static_assert(alignof(CRayMutex) == 4, "CRayMutex ARM32 alignment is four bytes");

#endif
