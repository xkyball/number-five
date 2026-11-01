# Own POSIX host runtime source

The package includes `host_runtime/no5_mutex.h`, `no5_mutex.c` and build/lifecycle
instructions. They implement a separate `no5_*` C API using the target
platform's real `pthread_mutex_t`. Compile the source and each consumer together
on the target Linux platform with `-pthread` and the documented POSIX feature
level. Package installation/import performs no native build or runtime loading.

The component provides recursive lock, try-lock, absolute timed-lock, unlock and
destroy operations, returning actual pthread status codes. Initialization also
reports attribute-cleanup status separately. A successful primary initialization
creates a live mutex even if attribute cleanup reports an error; release all
ownership levels and join every user before destroying it.

The optional native handle supports predicate-driven condition waits at exactly
one recursive ownership level. The caller owns condition state, cancellation
cleanup and lifecycle. The packaged README describes these requirements and the
small standalone validation plan.

This is source for an independently authored host component. It defines its own
types and layout. The separate ARM32 C++ facade described below uses genuine OSS
instead of this POSIX component. Neither source component alone establishes reader startup or
chart-format acceptance. Device tests and their evidence are independent of
the Python analysis commands.

The independently authored `tests/native/host_primitives_test.c` is a manual
Linux consumer. From the repository root, compile and run it explicitly:

```sh
build_dir="$(mktemp -d)"
cc -std=c11 -O2 -Wall -Wextra -Werror -pthread \
  -I src/no_chart_converter/host_runtime \
  src/no_chart_converter/host_runtime/no5_mutex.c \
  tests/native/host_primitives_test.c -o "$build_dir/host-primitives"
timeout --kill-after=1s 10s "$build_dir/host-primitives"
```

Check both command exit statuses. After completion, remove the executable and
the temporary directory. This source is not executed by pytest or packaged as
a native executable. It checks recursive ownership, error returns, timed waits,
condition predicates at one ownership level, semaphores, 20,000 protected
increments, canaries, lifecycle and joining all seven created threads. The
test reports its actual target sizes; it does not assert a foreign ABI.

## Own ARMHF CRayMutex facade

`host_runtime/cray_mutex.h` and `cray_mutex.cpp` are independently authored
`OwnHostABI` source for a finite interface to an unmodified reader. They are not
vendor headers, the original SystemFunctions implementation, or a complete
Navionics host. They define a real polymorphic C++ class, generate RTTI/vtables
with the compiler, and delegate mutex operations to the genuine external OSS
provider. No original RTTI, vtable, pthread bytes or other implementation payload
is included. The existing `no5_mutex` implementation is not used as a substitute.

Compile only for little-endian ARM32 hard-float EABI with RTTI, exceptions and normal four-byte
enum words. The header rejects other architectures. The external class has one
vptr and one opaque pointer: size 8, alignment 4. Its only virtual functions are
the complete/deleting destructors; `Lock`, `Unlock` and `TryLock(unsigned int)`
are nonvirtual. Their own unsigned-word source declarations reflect observed
argument/return registers, not recovered authoritative vendor source signatures.

The private allocation is exactly 44 bytes, aligned to 4. Fresh storage is zeroed
before `OSS_LinuxMutexCreate`; zero is not claimed to initialize a native mutex.
Only genuine OSS initializes its real pthread object and registry/name state.
Construction publishes the opaque pointer after a returned zero OSS create status.
The bound provider's normal nonzero path occurs before native initialization and
registration; that failure frees the allocation and throws an own
`no5_cray_mutex_provider_error` with the actual status. An unexpected C++ unwind
from the genuine create call has no complete status or proven reversible registry
state: the adapter aborts without freeing potentially registered storage.
Destruction sets the established
teardown byte, calls genuine `OSS_MutexDestroy`, then frees storage on success.
A nonzero destroy status is observed and aborts rather than freeing a potentially
registered object. These are deliberately stricter own failure rules; there is
no original exception/failure-path parity claim.

The retained genuine create helper does not check every pthread return value.
In particular, its success flag is set before `pthread_mutex_init` and that
call's return is ignored. A zero OSS create status is therefore an observed
provider value, not a standalone proof that native initialization succeeded.
Actual target tests must exercise the real operations and inspect their statuses.
Genuine OSS lock/unlock/timed-lock also have an external disable-flag branch
returning 0 before the pthread operation. The adapter neither changes that flag
nor substitutes its result. Normal OSS/task initialization and observed operation
effects are required; an observer status of 0 alone does not prove pthread entry.

`Lock` and `Unlock` return the observed facade word 0 after the genuine call.
This is not evidence that OSS returned success. Configure the separate own
`no5_cray_mutex_set_observer` before any objects or threads to record every raw
OSS status for a test. Change the observer only at full quiescence. It must not
throw, recurse, lock, or alter results. `TryLock(0)` calls genuine try-lock;
nonzero timeouts call genuine timed-lock. Its result mapping is OSS 0 to 0,
0x203 to 20, 0x205 to 21, and other nonzero to 1. Genuine OSS currently maps both
busy try-lock and expired timed-lock to 0x203, hence facade value 20 for both.
The timeout is passed unchanged: genuine OSS applies its mutable slow-motion
ratio with unsigned 32-bit wrap, interprets milliseconds and builds its own
absolute `CLOCK_REALTIME` deadline. No replacement clock or multiplier is used.

The genuine OSS provider, allocator and compatible C++ runtime must be supplied
externally. Enter through its normal reviewed `OSS_Init` task callback; module
constructors alone do not establish task/TLS/registry state. All users must be
joined, with no ownership or waiters, before destruction. Copy/move and concurrent
lifecycle are unsupported. The remaining reader host callbacks are unimplemented;
there are no success stubs, identity/authentication providers or dummy globals.
A declared lazy-load-only experiment must fail on unsupported symbol use instead
of interpreting symbol availability as complete host or format support.

For compile-only controls, use an existing reviewed ARMHF compiler explicitly:

```sh
arm-linux-gnueabihf-g++ -std=c++11 -Wall -Wextra -Werror -fno-short-enums \
  -frtti -fexceptions -fPIC -c \
  src/no_chart_converter/host_runtime/cray_mutex.cpp -o OWN_BUILD/cray_mutex.o
arm-linux-gnueabihf-g++ -std=c++11 -Wall -Wextra -Werror -fno-short-enums \
  -frtti -fexceptions -fPIC -I src/no_chart_converter/host_runtime \
  -c tests/native/cray_mutex_layout.cpp -o OWN_BUILD/cray_mutex_layout.o
```

Create a fresh `OWN_BUILD` directory first and remove its object files after
inspection. The invented layout control compiles method types and a private
secondary base without implementing OSS or executing original code. Actual
provider/loader/lifecycle and reader calls need separate reviewed target tests.
Python imports, installation and ordinary pytest never compile or load this source.
