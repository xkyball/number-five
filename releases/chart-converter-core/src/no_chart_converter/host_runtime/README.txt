No5 own POSIX mutex source component

Build the C source and its consumer together on the target Linux platform:
  cc -std=c11 -D_POSIX_C_SOURCE=200809L -Wall -Wextra -Werror -pthread \
    no5_mutex.c your_consumer.c -o your_consumer

Start with `no5_mutex mutex = NO5_MUTEX_STORAGE_INIT;`. Call no5_mutex_init,
check BOTH result fields, then use real recursive lock/try/timed/unlock calls.
A primary status of zero makes the mutex live even when attribute cleanup
reported a separate failure. Destroy each live mutex after every user has
joined, no waiters remain, and all recursive ownership levels are released.
This object must not be copied while live or concurrently initialized/destroyed.
A timed deadline is an absolute CLOCK_REALTIME timespec, not a duration or a
foreign timeout tick. Use a normalized deadline; current pthread semantics
remain authoritative. Validity of storage/lifetime cannot be checked from a
pointer. Do not pass uninitialized storage and do not directly modify live.

The native handle supports real pthread condition waits only at ownership
depth one. Condition predicates, their while loop, cancellation cleanup and
condition lifecycle belong to the consumer. Never mutate native mutex setup
through the handle.

This is an independently authored source API using the target pthread headers.
It exports no proprietary class names, old object layouts, guessed typeinfo,
license decisions or placeholder host globals. Compiling/testing it as native
AArch64 demonstrates that own runtime only. ARM32 C++ host compatibility and
original reader/cache startup require separate, still-open bridge contracts.
No compiler, loader, network, device or runtime action happens on package import.

Validation plan (country_zoom_v15 owns the single reviewed Pi harness):
- Real recursive reentry and matching unlocks; foreign recursive unlock EPERM.
- Foreign try-lock EBUSY and genuine timed contention ETIMEDOUT.
- Join, recover ownership, release, destroy and reinitialize.
- Four real threads x5000 protected increments, checked output and joins.
- Predicate-driven condition wait at depth one using this native handle.
- Separate genuine semaphore wait/post and thread lifecycle controls.
- Finite process deadline, no destruction of locked/waited-on POSIX objects,
  checked pthread/clock/join results, and verified temporary build cleanup.
