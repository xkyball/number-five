# Optional public S52 provider

This bridge calls the actual public OpenCPN lookup and conditional-symbol
functions with a complete plaintext OSENC cell, its area associations, ATON
arrays and explicit mariner settings. It does not render or capture images.
SOUNDG keeps its original depth samples for the target's sounding programme.
RX5 resource compatibility remains the presentation profile's responsibility;
OpenCPN's private CA sector command needs a separately checked translation.

Use an explicitly selected, checked `no-apps/map` source tree. No Map sources,
third-party binary, fonts or chart data are copied into this package.

```sh
cmake -S PATH_TO_PACKAGE/cache_country/native -B NEW_NATIVE_BUILD \
  -DNO5_MAP_SOURCE=PATH_TO_NO_APPS/map -DCMAKE_BUILD_TYPE=Release \
  -DNO5_HOST_GL=PATH_TO_GLES_LIBRARY -DNO5_HOST_EGL=PATH_TO_EGL_LIBRARY
cmake --build NEW_NATIVE_BUILD --parallel 4 --target no5_s52_provider
```

On macOS the selected EGL/GLES SDK also requires compatible public headers on
the include path (pass `-DCMAKE_CXX_FLAGS=-IHEADER_DIRECTORY`). The Android SDK
emulator libraries have relative install names: set `DYLD_LIBRARY_PATH` to their
directory when invoking the provider. Linux uses its selected EGL/GLES runtime.
The CMake build never changes the external source tree.

```sh
no-chart-converter s52-provider --request REQUEST.json --output NEW_RESPONSE.json \
  --engine ABSOLUTE_NATIVE_EXECUTABLE --assets PATH_TO_NO_APPS/map/chart-engine/src/main/assets \
  --cache EXISTING_PRIVATE_WORK_DIRECTORY
```

Requests use `no5-cache-country-s52-request/1`, an exact profile digest, explicit
settings and fresh cells/feature digests. The wrapper validates plaintext source
hashes and original typed attributes before binding compiled programmes. It
also accepts a pinned package archive and member hashes. The response includes
engine/resource/source identities, both paper and simplified contexts, actual
lookup IDs, resolved priority/category, instructions and expanded CSP names.
Unsupported features, settings or conditionals fail explicitly. No output file
is replaced. The provider itself claims no physical a97 portrayal equivalence.

Programme parsing uses the actual public `_LUP2rules`/`StringToRules` chain.
This preserves the public legacy LNDARE spelling `CS(QUAPOS01;TX(...))` as an
eight-byte CSP name followed by a separate text rule. Target serialization
retains that text and removes only closing syntax beyond its parsed arguments.
Requested symbols which the public parser itself binds to `QUESMRK1` are
serialized using that actual Rule, with explicit `symbolResolutions` provenance.
This is not a claim that the originally requested symbol exists or was rendered.

The focused native parser regression runs against the selected public assets:

```sh
no5_s52_provider --self-test-programmes PATH_TO_NO_APPS/map/chart-engine/src/main/assets
```

It checks legacy CS/text separation, ordinary commands, the public parser's
non-drawing OP handling, optional CA closing syntax and actual symbol binding.
