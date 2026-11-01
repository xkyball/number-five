# Local WLAN signal

`WifiObserver` is independent of UI Binder, gateway, credentials and audio. It
retains only the application context. Use the same Java8/API29-compatible source
in HOME, HELM, MAP and Control.

```java
// Main thread, when this consumer becomes visible:
WifiObserver.Subscription wifi = WifiObserver.subscribe(context, signal -> {
    // signal.state, signal.rssiDbm (Integer/null), signal.bars (0..4/-1)
});
wifi.refresh(); // Optional explicit onResume refresh, coalesced with the same one-Hz reader.
// Main thread, onPause/onStop or View detachment:
wifi.close();   // Idempotent; do not retain an Activity through an invisible subscription.
```

One process singleton shares a passive WLAN callback and timer between visible
consumers. The first callback is UNKNOWN; a fresh observation follows on the
next main turn, subject to the one-second minimum between reads. The last close
unregisters Android callbacks/receivers, removes the timer and withdraws cached
RSSI. Rapid pause/resume cannot evade the rate limit. Sixteen consumers and
sixteen observed WLAN networks bound retained references; an exceeded network
bound produces UNKNOWN until the visible observation session is restarted.

The model distinguishes OFF (explicitly disabled radio), DISCONNECTED (enabled
radio and no observed WLAN), UNKNOWN (unavailable evidence), and CONNECTED.
CONNECTED can have unknown RSSI; this keeps connection and signal availability
separate. RSSI is the actual platform dBm value, excluding the unavailable -127
sentinel and invalid non-negative values. No replacement dBm is invented.
The common display scale is 0 at/below -100 dBm, 4 at/above -55 dBm, with integer
linear intervals between. Missing RSSI uses bars=-1. This scale is an app display
convention, not a measured throughput or Internet-reachability claim.

`WifiUiText.summary(Context, WifiSignal)` is the common compact state text;
`details(Context, WifiSignal)` adds actual dBm (or explicitly unknown) and the
local-radio scope. Both use the existing No5Text locale, with de/en/af entries
in `translations-wifi-20261017.tsv`. Explicit-language overloads support pure
tests. The integration root regenerates the shared UiTranslations catalogue.

Manifest integration needs only the normal `ACCESS_WIFI_STATE` and
`ACCESS_NETWORK_STATE` permissions. No location/nearby-device request is added.
The observer never scans, enables/disables Wi-Fi, requests a network, reads
SSID/BSSID/IP, or contacts a network endpoint. Multiple connected WLANs use the
strongest actually reported RSSI; no AP identity is retained.

The passive request matches Wi-Fi rather than the default network, so a default
VPN cannot replace the underlying WLAN observation. There is one initial resume
snapshot; later network membership follows the callback, with actual capabilities
read at most once per second outside callback bodies. If callback registration
is unavailable, the bounded visible poll uses snapshots instead. On API29/30,
missing transport RSSI can use `WifiManager.getConnectionInfo().getRssi()`;
API31+ uses the transport information. No location-sensitive field is accessed.

Official Android sources checked on 2026-10-17:

- [ConnectivityManager](https://developer.android.com/reference/android/net/ConnectivityManager): passive callback, normal network-state permission, default-network/VPN distinction, and deprecation of repeated all-network snapshots.
- [WifiInfo](https://developer.android.com/reference/android/net/wifi/WifiInfo): actual RSSI in dBm and separate location-sensitive fields.
- [WifiManager](https://developer.android.com/reference/android/net/wifi/WifiManager#getConnectionInfo()): API31 migration of connection info to network capabilities.
- [NetworkRequest.Builder](https://developer.android.com/reference/android/net/NetworkRequest.Builder): API29-compatible capability removal; `clearCapabilities()` requires API30 and is deliberately unused.
- [Android14 receiver rules](https://developer.android.com/about/versions/14/behavior-changes-14#runtime-receivers-exported): the two protected system-only broadcasts use the system-broadcast exception to export flags, preserving API29 compilation.

Focused check: `python3 ui-state/tools/test_wifi_host.py`. It compiles only these
shared sources with Java8 against actual API29 stubs and runs pure model,
rate-limit, refcount, pause/resume, failure and cancellation tests. Results are
under `ui-state/build/wifi-host/RESULT.json`. It proves no Android callback,
device RSSI, VPN behavior or physical UI integration; those remain integration
checks. The formatter test compiles the actual No5Text with a temporary generated
catalogue under build/, without modifying production UiTranslations.java. It does
not run Gradle, package an APK or touch a device.
