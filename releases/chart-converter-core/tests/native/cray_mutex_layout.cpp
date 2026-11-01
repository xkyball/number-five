/* Own compile-only ABI control. No OSS implementations or native calls. */
#include "cray_mutex.h"

template<class A, class B> struct SameType { enum { value = 0 }; };
template<class A> struct SameType<A, A> { enum { value = 1 }; };
static_assert(SameType<decltype(&CRayMutex::Lock), unsigned int (CRayMutex::*)()>::value,
              "Own Lock declaration preserves a full status word");
static_assert(SameType<decltype(&CRayMutex::Unlock), unsigned int (CRayMutex::*)()>::value,
              "Own Unlock declaration preserves a full status word");
static_assert(SameType<decltype(&CRayMutex::TryLock),
                      unsigned int (CRayMutex::*)(unsigned int)>::value,
              "Own TryLock argument and result are full status words");

/* Invented multiple inheritance validates target layout and emits real RTTI.
 * Neither the original NavMutexImpl class nor its metadata is copied here. */
class OwnPrimary {
public:
    virtual ~OwnPrimary();
};
OwnPrimary::~OwnPrimary() {}

class OwnDerived final : public OwnPrimary, private CRayMutex {
public:
    ~OwnDerived() override;
};
OwnDerived::~OwnDerived() {}
static_assert(sizeof(OwnPrimary) == 4, "One ARM32 polymorphic primary base");
static_assert(sizeof(OwnDerived) == 12, "Own derived control has two real bases");
