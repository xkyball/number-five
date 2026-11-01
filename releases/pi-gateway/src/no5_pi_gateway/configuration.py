"""Strict configuration for the isolated Pi gateway project."""

from __future__ import annotations

from dataclasses import dataclass, field
import fnmatch
from pathlib import Path
import stat
import tomllib
from urllib.parse import urlparse


class ConfigurationError(ValueError):
    """Configuration cannot safely describe a supported upstream source."""


@dataclass(frozen=True, slots=True)
class Calibration:
    """App-facing calibration evidence, never inferred from an echogram frame."""

    revision: int = 0
    valid: bool = False
    sample_period_us: float | None = None
    # The Android V1 schema requires this numeric field even where an upstream
    # supplies only a direct displayed-range scale.  With valid=False this is a
    # schema placeholder, not a measured M0/M1 calibration value.
    sound_speed_mps: float = 1500.0
    depth_reference: str = "TRANSDUCER"
    transducer_offset_m: float | None = None
    keel_offset_m: float | None = None

    def __post_init__(self) -> None:
        if self.revision < 0:
            raise ConfigurationError("calibration.revision must be non-negative")
        if self.sample_period_us is not None and not 0 < self.sample_period_us <= 1000:
            raise ConfigurationError("calibration.sample_period_us must be 0..1000")
        if not 1300 <= self.sound_speed_mps <= 1800:
            raise ConfigurationError("calibration.sound_speed_mps must be 1300..1800")
        if self.depth_reference not in {"TRANSDUCER", "SURFACE", "KEEL"}:
            raise ConfigurationError("unsupported calibration.depth_reference")
        for name, value in (("transducer_offset_m", self.transducer_offset_m),
                            ("keel_offset_m", self.keel_offset_m)):
            if value is not None and not -50 <= value <= 50:
                raise ConfigurationError(f"calibration.{name} must be -50..50")

    def as_contract(self) -> dict[str, object]:
        return {
            "revision": self.revision,
            "valid": self.valid,
            "samplePeriodUs": self.sample_period_us,
            "soundSpeedMps": self.sound_speed_mps,
            "depthReference": self.depth_reference,
            "transducerOffsetM": self.transducer_offset_m,
            "keelOffsetM": self.keel_offset_m,
        }


HELM_FIELDS = frozenset({
    "portRpm", "starboardRpm", "portTrim", "starboardTrim", "portFuel", "starboardFuel",
    "heading", "speed", "depth", "waterTemp", "portVoltage", "starboardVoltage",
    "houseVoltage", "portCoolantC", "starboardCoolantC", "portOilBar", "starboardOilBar",
    "portFuelFlowLph", "starboardFuelFlowLph", "totalFuelFlowLph", "portGear", "starboardGear",
    "latitudeDegrees", "longitudeDegrees", "fuelRemainingLiters", "courseOverGroundDegrees",
    "tripDistanceNm", "tripDurationSeconds", "fuelUsedLiters", "efficiencyNmPerLiter",
})


@dataclass(frozen=True, slots=True)
class HelmFieldMapping:
    """Explicit Signal K snapshot extraction; no path is assumed by the gateway."""

    name: str
    value_pointer: str
    timestamp_pointer: str
    transform: str = "identity"

    def __post_init__(self) -> None:
        if self.name not in HELM_FIELDS:
            raise ConfigurationError(f"unsupported helm field: {self.name}")
        for label, pointer in (("value_pointer", self.value_pointer),
                               ("timestamp_pointer", self.timestamp_pointer)):
            if not pointer.startswith("/"):
                raise ConfigurationError(f"helm field {self.name} {label} must be an RFC6901 pointer")
        if self.transform not in {"identity", "radians_to_degrees", "metres_per_second_to_knots"}:
            raise ConfigurationError(f"unsupported helm transform for {self.name}")


@dataclass(frozen=True, slots=True)
class SignalKConfig:
    enabled: bool = False
    snapshot_url: str | None = None
    source_name: str = "signalk"
    published_at_pointer: str | None = None
    vessel_time_pointer: str | None = None
    vessel_time_format: str = "hms"
    poll_interval_s: float = 0.25
    fields: tuple[HelmFieldMapping, ...] = ()
    # Optional complete Signal K leaves in the same already configured snapshot.
    # Empty RFC6901 pointers explicitly select the document root.
    position_pointer: str | None = None
    gnss_pointer: str | None = None
    combination_sources: tuple[str, ...] = ()
    combination_verified_fix_clock_sources: tuple[str, ...] = ()
    combination_max_age_ms: int = 5000
    combination_fix_quality_skew_ms: int = 1000
    combination_max_time_skew_ms: int = 1000
    combination_max_separation_metres: float = 30.0

    def __post_init__(self) -> None:
        for name, pointer in (("position_pointer", self.position_pointer),
                              ("gnss_pointer", self.gnss_pointer)):
            if pointer is not None:
                if not isinstance(pointer, str) or len(pointer) > 2_000 or (pointer and not pointer.startswith("/")):
                    raise ConfigurationError(f"helm.{name} must be an RFC6901 pointer")
                # Reject malformed escapes here, before the polling worker starts.
                escaped = pointer.replace("~0", "").replace("~1", "")
                if "~" in escaped:
                    raise ConfigurationError(f"helm.{name} has an invalid RFC6901 escape")
        if self.gnss_pointer is not None and self.position_pointer is None:
            raise ConfigurationError("helm.gnss_pointer requires helm.position_pointer")
        refs = self.combination_sources
        if not isinstance(refs, tuple) or (refs and not 2 <= len(refs) <= 16) or any(
                not isinstance(ref, str) or not ref.strip() or len(ref) > 200 or not ref.isprintable() for ref in refs):
            raise ConfigurationError("helm.combination_sources requires 2..16 explicit source references")
        if len(set(refs)) != len(refs):
            raise ConfigurationError("helm.combination_sources must not repeat a receiver")
        if refs and (self.position_pointer is None or self.gnss_pointer is None):
            raise ConfigurationError("GPS combination requires explicit position and GNSS pointers")
        verified = self.combination_verified_fix_clock_sources
        if (not isinstance(verified, tuple) or any(not isinstance(s, str) or s not in refs for s in verified)
                or len(set(verified)) != len(verified)):
            raise ConfigurationError("GPS combination verified fix clocks must be distinct configured receivers")
        if type(self.combination_max_age_ms) is not int or not 1 <= self.combination_max_age_ms <= 5000:
            raise ConfigurationError("helm.combination_max_age_ms must be 1..5000")
        if type(self.combination_fix_quality_skew_ms) is not int or not 0 <= self.combination_fix_quality_skew_ms <= 1000:
            raise ConfigurationError("helm.combination_fix_quality_skew_ms must be 0..1000")
        if type(self.combination_max_time_skew_ms) is not int or not 0 <= self.combination_max_time_skew_ms <= 5000:
            raise ConfigurationError("helm.combination_max_time_skew_ms must be 0..5000")
        if isinstance(self.combination_max_separation_metres, bool) or not isinstance(self.combination_max_separation_metres, (int, float)) or not 0 < self.combination_max_separation_metres <= 1000:
            raise ConfigurationError("helm.combination_max_separation_metres must be finite and 0..1000")
        if not self.enabled:
            return
        if not self.snapshot_url:
            raise ConfigurationError("helm.snapshot_url is required when helm is enabled")
        if not self.source_name or len(self.source_name) > 200:
            raise ConfigurationError("helm.source_name must contain 1..200 characters")
        parsed = urlparse(self.snapshot_url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ConfigurationError("helm.snapshot_url must be an absolute http(s) URL")
        for name, pointer in (("published_at_pointer", self.published_at_pointer),
                              ("vessel_time_pointer", self.vessel_time_pointer)):
            if not pointer or not pointer.startswith("/"):
                raise ConfigurationError(f"helm.{name} is required when helm is enabled")
        if self.vessel_time_format not in {"hms", "rfc3339_utc"}:
            raise ConfigurationError("helm.vessel_time_format must be hms or rfc3339_utc")
        if not 0.05 <= self.poll_interval_s <= 10:
            raise ConfigurationError("helm.poll_interval_s must be 0.05..10")
        if not self.fields:
            raise ConfigurationError("helm requires at least one explicit field mapping")
        names = [mapping.name for mapping in self.fields]
        if len(names) != len(set(names)):
            raise ConfigurationError("helm field mappings must not repeat a field")


@dataclass(frozen=True, slots=True)
class CamillaDspConfig:
    """CamillaDSP 4.1.3 settings; the WebSocket URL is never guessed."""

    enabled: bool = False
    websocket_url: str | None = None
    expected_version: str = "4.1.3"
    poll_interval_s: float = 0.25
    channel_faders: tuple[tuple[str, int], ...] = ()
    channel_labels: tuple[tuple[str, str], ...] = ()
    # CamillaDSP deliberately exposes only its current config path, not a
    # preset-list API.  An administrator therefore selects the exact local
    # directory and filename pattern.  Every candidate is subsequently read
    # and validated by CamillaDSP before it reaches the app.
    preset_directory: str | None = None
    preset_pattern: str = "preset_*.yml"
    preset_files: tuple[str, ...] = ()
    preset_refresh_s: float = 10.0
    equalizer_state_file: str | None = None
    sound_state_file: str | None = None

    def __post_init__(self) -> None:
        if self.sound_state_file not in {None, "/var/lib/no5-pi-gateway/sound-state.json"}:
            raise ConfigurationError("audio.camilla.sound_state_file must use the managed sound state path")
        if self.equalizer_state_file not in {None, "/var/lib/no5-pi-gateway/master-equalizer.json"}:
            raise ConfigurationError("audio.camilla.equalizer_state_file must use the managed master-equalizer state path")
        if not self.enabled:
            return
        if not self.websocket_url:
            raise ConfigurationError("audio.camilla.websocket_url is required when audio is enabled")
        parsed = urlparse(self.websocket_url)
        if parsed.scheme not in {"ws", "wss"} or not parsed.netloc:
            raise ConfigurationError("audio.camilla.websocket_url must be an absolute ws(s) URL")
        if self.expected_version != "4.1.3":
            raise ConfigurationError("the reviewed CamillaDSP adapter is pinned to expected_version 4.1.3")
        if not 0.1 <= self.poll_interval_s <= 10:
            raise ConfigurationError("audio.camilla.poll_interval_s must be 0.1..10")
        expected_channels = {"MASTER", "SUB", "REAR", "AUX3", "AUX4"}
        mapping = dict(self.channel_faders)
        if set(mapping) != expected_channels:
            raise ConfigurationError("audio.camilla.channel_faders must map MASTER, SUB, REAR, AUX3 and AUX4")
        if any(index < 0 for index in mapping.values()) or len(set(mapping.values())) != len(mapping):
            raise ConfigurationError("audio.camilla.channel_faders must be unique non-negative indexes")
        if mapping["MASTER"] != 0:
            raise ConfigurationError("audio.camilla.channel_faders.MASTER must be CamillaDSP fader 0 (Main)")
        labels = dict(self.channel_labels)
        if len(labels) != len(self.channel_labels) or set(labels) - set(mapping):
            raise ConfigurationError("audio.camilla.channel_labels may only name configured faders once")
        for channel, label in labels.items():
            if not label.strip() or len(label) > 80 or any(ord(char) < 32 or ord(char) == 127 for char in label):
                raise ConfigurationError(f"audio.camilla.channel_labels.{channel} must be short visible text")
        if self.preset_directory is not None:
            directory = Path(self.preset_directory)
            if not directory.is_absolute() or any(part == ".." for part in directory.parts):
                raise ConfigurationError("audio.camilla.preset_directory must be an absolute normalized directory")
            if not self.preset_pattern or "/" in self.preset_pattern or "\\" in self.preset_pattern or len(self.preset_pattern) > 80:
                raise ConfigurationError("audio.camilla.preset_pattern must be a short filename pattern")
            if len(set(self.preset_files)) != len(self.preset_files) or len(self.preset_files) > 64:
                raise ConfigurationError("audio.camilla.preset_files must contain at most 64 unique names")
            for filename in self.preset_files:
                if (not filename or len(filename) > 120 or "/" in filename or "\\" in filename or
                        filename in {".", ".."}):
                    raise ConfigurationError("audio.camilla.preset_files must contain plain filenames")
            if not 1.0 <= self.preset_refresh_s <= 3600.0:
                raise ConfigurationError("audio.camilla.preset_refresh_s must be 1..3600")

    @property
    def fader_indexes(self) -> dict[str, int]:
        return dict(self.channel_faders)

    @property
    def fader_labels(self) -> dict[str, str]:
        configured = dict(self.channel_labels)
        return {channel: configured.get(channel, channel.replace("_", " ").title())
                for channel, _index in self.channel_faders}

    def preset_paths(self) -> tuple[Path, ...]:
        """Return only bounded regular files immediately inside the approved directory."""

        if self.preset_directory is None:
            return ()
        directory = Path(self.preset_directory)
        try:
            directory_stat = directory.stat()
            if not stat.S_ISDIR(directory_stat.st_mode):
                return ()
            entries = list(directory.iterdir())
        except OSError:
            return ()
        result: list[Path] = []
        allowed_names = set(self.preset_files)
        for candidate in entries:
            if (allowed_names and candidate.name not in allowed_names) or (
                    not allowed_names and not fnmatch.fnmatchcase(candidate.name, self.preset_pattern)):
                continue
            try:
                metadata = candidate.lstat()
            except OSError:
                continue
            if not stat.S_ISREG(metadata.st_mode) or metadata.st_size <= 0 or metadata.st_size > 1_048_576:
                continue
            result.append(candidate)
        return tuple(sorted(result, key=lambda value: value.name))


@dataclass(frozen=True, slots=True)
class RaspotifyConfig:
    """The documented librespot ``--onevent`` hand-off, not a network API."""

    enabled: bool = False
    state_file: str | None = None
    poll_interval_s: float = 0.25

    def __post_init__(self) -> None:
        if not self.enabled:
            return
        if not self.state_file or not Path(self.state_file).is_absolute():
            raise ConfigurationError("audio.raspotify.state_file must be an absolute local path")
        if not 0.1 <= self.poll_interval_s <= 10:
            raise ConfigurationError("audio.raspotify.poll_interval_s must be 0.1..10")


@dataclass(frozen=True, slots=True)
class SpotifyConnectConfig:
    """Local contract for the No5-controlled Spotify Connect receiver.

    Both paths are Unix files owned by the local service boundary; neither is
    an address that can be pointed at a remote or app-controlled server.
    """

    enabled: bool = False
    state_file: str | None = None
    control_socket: str | None = None
    poll_interval_s: float = 0.25
    command_timeout_s: float = 3.0
    master_volume_bridge: bool = False

    def __post_init__(self) -> None:
        if not self.enabled:
            return
        if not self.state_file or not Path(self.state_file).is_absolute():
            raise ConfigurationError("audio.spotify_connect.state_file must be an absolute local path")
        if not self.control_socket or not Path(self.control_socket).is_absolute():
            raise ConfigurationError("audio.spotify_connect.control_socket must be an absolute local path")
        if not 0.1 <= self.poll_interval_s <= 10:
            raise ConfigurationError("audio.spotify_connect.poll_interval_s must be 0.1..10")
        if not 0.25 <= self.command_timeout_s <= 5:
            raise ConfigurationError("audio.spotify_connect.command_timeout_s must be 0.25..5")


@dataclass(frozen=True, slots=True)
class AudioFaderLimit:
    """Explicit command safety envelope for one reviewed CamillaDSP fader."""

    min_db: float
    max_db: float
    step_db: float = 1.0

    def __post_init__(self) -> None:
        # AUDIO V1 advertises each configured range directly to HELM. Keep the
        # deployment policy inside the published contract bounds rather than
        # admitting a value Camilla may accept but the V1 client must reject.
        if not -150.0 <= self.min_db <= self.max_db <= 24.0:
            raise ConfigurationError("audio fader limits must be within -150..24 dB")
        if not 0.01 <= self.step_db <= 24.0:
            raise ConfigurationError("audio fader limit step_db must be 0.01..24")


@dataclass(frozen=True, slots=True)
class AudioConfig:
    """CamillaDSP is independently observable from the media source.

    Playback metadata is useful but never proof that the DSP is running.  In
    particular, a quiet/idle Spotify receiver must not make volume, mute or
    DSP diagnostics disappear from the application.
    """

    enabled: bool = False
    source_name: str = "no5-audio"
    # ``raspotify_onevent_file`` is the legacy observation-only receiver.
    # ``spotify_connect`` selects No5's local receiver + Unix control socket.
    media_provider: str = "unavailable"
    # Hardware presence is evidence supplied by the local deployment review,
    # not an inference from a reachable CamillaDSP socket.
    hardware_available: bool | None = None
    fader_limits: tuple[tuple[str, AudioFaderLimit], ...] = ()
    camilla: CamillaDspConfig = field(default_factory=CamillaDspConfig)
    raspotify: RaspotifyConfig = field(default_factory=RaspotifyConfig)
    spotify_connect: SpotifyConnectConfig = field(default_factory=SpotifyConnectConfig)

    def __post_init__(self) -> None:
        if self.enabled and not self.camilla.enabled:
            raise ConfigurationError("audio.enabled requires audio.camilla.enabled")
        if not self.source_name or len(self.source_name) > 200:
            raise ConfigurationError("audio.source_name must contain 1..200 characters")
        if self.media_provider not in {"unavailable", "raspotify_onevent_file", "spotify_connect"}:
            raise ConfigurationError("audio.media_provider is unsupported")
        if self.media_provider == "raspotify_onevent_file" and not self.raspotify.enabled:
            raise ConfigurationError("raspotify_onevent_file requires audio.raspotify.enabled")
        if self.media_provider == "spotify_connect" and not self.spotify_connect.enabled:
            raise ConfigurationError("spotify_connect requires audio.spotify_connect.enabled")
        if self.raspotify.enabled and self.media_provider != "raspotify_onevent_file":
            raise ConfigurationError("audio.raspotify requires media_provider=raspotify_onevent_file")
        if self.spotify_connect.enabled and self.media_provider != "spotify_connect":
            raise ConfigurationError("audio.spotify_connect requires media_provider=spotify_connect")
        limits = dict(self.fader_limits)
        if self.spotify_connect.master_volume_bridge:
            if self.media_provider != "spotify_connect" or not self.spotify_connect.enabled:
                raise ConfigurationError("master_volume_bridge requires the No5 Spotify Connect receiver")
            main = limits.get("MASTER")
            if main is None or main.min_db >= main.max_db or main.max_db > 0:
                raise ConfigurationError("master_volume_bridge requires an explicit Main range ending at or below 0 dB")
        if len(limits) != len(self.fader_limits) or set(limits) - {"MASTER", "SUB", "REAR", "AUX3", "AUX4"}:
            raise ConfigurationError("audio.fader_limits may only name unique No5 CamillaDSP faders")
        # A real, reviewed DSP exposes the same five physical controls to all
        # authenticated clients. There is deliberately no second per-user or
        # ``allow_writes`` permission switch: each actuator must instead have
        # a configured bounded dB range before it can be advertised writable.
        if self.enabled and self.hardware_available is True and set(limits) != {
            "MASTER", "SUB", "REAR", "AUX3", "AUX4"
        }:
            raise ConfigurationError("reviewed audio hardware requires limits for MASTER, SUB, REAR, AUX3 and AUX4")

    @property
    def limits(self) -> dict[str, AudioFaderLimit]:
        return dict(self.fader_limits)


@dataclass(frozen=True, slots=True)
class WeatherConfig:
    """Real Open-Meteo delivery driven only by explicit fresh Signal K position."""

    enabled: bool = False
    source_name: str = "no5-weather"
    snapshot_url: str | None = None
    latitude_value_pointer: str | None = None
    latitude_timestamp_pointer: str | None = None
    longitude_value_pointer: str | None = None
    longitude_timestamp_pointer: str | None = None
    position_stale_after_s: float = 30.0
    refresh_interval_s: float = 300.0
    retry_interval_s: float = 60.0

    def __post_init__(self) -> None:
        if not self.enabled:
            return
        if not self.source_name or len(self.source_name) > 200:
            raise ConfigurationError("weather.source_name must contain 1..200 characters")
        if not self.snapshot_url:
            raise ConfigurationError("weather.snapshot_url is required when weather is enabled")
        parsed = urlparse(self.snapshot_url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ConfigurationError("weather.snapshot_url must be an absolute http(s) URL")
        for name, pointer in (
            ("latitude_value_pointer", self.latitude_value_pointer),
            ("latitude_timestamp_pointer", self.latitude_timestamp_pointer),
            ("longitude_value_pointer", self.longitude_value_pointer),
            ("longitude_timestamp_pointer", self.longitude_timestamp_pointer),
        ):
            if not pointer or not pointer.startswith("/"):
                raise ConfigurationError(f"weather.{name} is required when weather is enabled")
        if not 1.0 <= self.position_stale_after_s <= 600.0:
            raise ConfigurationError("weather.position_stale_after_s must be 1..600")
        if not 60.0 <= self.refresh_interval_s <= 3_600.0:
            raise ConfigurationError("weather.refresh_interval_s must be 60..3600")
        if not 10.0 <= self.retry_interval_s <= 600.0:
            raise ConfigurationError("weather.retry_interval_s must be 10..600")


@dataclass(frozen=True, slots=True)
class SimulationConfig:
    """Opt-in local-only simulation overlay settings.

    Simulation never changes an upstream producer.  The mutable selection is
    persisted separately from TOML so that an operator can switch individual
    display fields without granting write access to Signal K, CamillaDSP or a
    SONAR daemon.
    """

    enabled: bool = False
    state_file: str | None = None
    token_env: str = "NO5_GATEWAY_SIMULATION_TOKEN"
    source_name: str = "no5-pi-gateway-simulator"
    # The local UI may switch only between explicitly provisioned daemon
    # endpoints.  It never accepts an endpoint at runtime and it never
    # synthesises acoustic data itself. M0 is the existing SONAR simulator;
    # M1 is the Pico producer once physically connected.
    sonar_m0_upstream_base_url: str | None = None
    sonar_m1_upstream_base_url: str | None = None

    def __post_init__(self) -> None:
        if not self.enabled:
            return
        if not self.state_file or not Path(self.state_file).is_absolute():
            raise ConfigurationError("simulation.state_file must be an absolute local path when simulation is enabled")
        if not self.token_env:
            raise ConfigurationError("simulation.token_env must not be empty")
        if not self.source_name or len(self.source_name) > 200:
            raise ConfigurationError("simulation.source_name must contain 1..200 characters")
        for name, value in (("sonar_m0_upstream_base_url", self.sonar_m0_upstream_base_url),
                            ("sonar_m1_upstream_base_url", self.sonar_m1_upstream_base_url)):
            if value is None:
                continue
            parsed = urlparse(value)
            if parsed.scheme not in {"http", "https"} or not parsed.netloc or parsed.path not in {"", "/"}:
                raise ConfigurationError(f"simulation.{name} must be an absolute http(s) base URL without a path")


@dataclass(frozen=True, slots=True)
class CamillaGuiAuthConfig:
    """Opt-in browser-session boundary for the existing CamillaGUI backend."""

    enabled: bool = False
    public_origin: str = "https://lysmarine.local"
    session_secret_env: str = "NO5_CAMILLAGUI_SESSION_SECRET"
    cookie_name: str = "no5_camillagui"
    session_max_age_s: int = 900

    def __post_init__(self) -> None:
        if not self.enabled:
            return
        parsed = urlparse(self.public_origin)
        if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or
                parsed.path not in {"", "/"} or parsed.params or parsed.query or parsed.fragment):
            raise ConfigurationError("camilla_gui.public_origin must be an https origin without a path")
        if not self.session_secret_env or len(self.session_secret_env) > 160:
            raise ConfigurationError("camilla_gui.session_secret_env must be a short non-empty name")
        if (not self.cookie_name or len(self.cookie_name) > 80 or
                any(not (char.isascii() and (char.isalnum() or char == "_")) for char in self.cookie_name)):
            raise ConfigurationError("camilla_gui.cookie_name must contain only ASCII letters, digits or underscores")
        # The approved browser session is deliberately short. A new login is
        # required after a maximum of 15 minutes, even on a shared boat LAN.
        if not 60 <= self.session_max_age_s <= 900:
            raise ConfigurationError("camilla_gui.session_max_age_s must be 60..900")


@dataclass(frozen=True, slots=True)
class MapRoutesConfig:
    enabled: bool = False
    resources_url: str = ""
    token_file: str | None = None

    def __post_init__(self) -> None:
        if not self.enabled: return
        parsed = urlparse(self.resources_url)
        if (parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password
                or parsed.query or parsed.fragment or parsed.path.rstrip("/") != "/signalk/v2/api/resources/routes"):
            raise ConfigurationError("map_routes.resources_url must be the explicit Signal K v2 route resources URL")
        if self.token_file and not Path(self.token_file).is_absolute():
            raise ConfigurationError("map_routes.token_file must be absolute")

@dataclass(frozen=True, slots=True)
class LuminaTokenConfig:
    enabled: bool = False
    audience: str = ""
    registry_file: str | None = None
    registry_socket: str | None = None
    allow_legacy_gateway_key: bool = True
    def __post_init__(self) -> None:
        if not self.enabled: return
        parsed = urlparse(self.audience)
        if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or
                parsed.path not in {"", "/"} or parsed.query or parsed.fragment):
            raise ConfigurationError("lumina_tokens.audience must be a canonical https origin")
        if not self.registry_file or not Path(self.registry_file).is_absolute():
            raise ConfigurationError("lumina_tokens.registry_file must be absolute")
        if not self.registry_socket or not Path(self.registry_socket).is_absolute():
            raise ConfigurationError("lumina_tokens.registry_socket must be absolute")

@dataclass(frozen=True, slots=True)
class GatewayConfig:
    bind_host: str = "127.0.0.1"
    port: int = 8789
    producer: str = "disabled"  # disabled | m0 | m1
    upstream_base_url: str | None = None
    source_name: str = "no5-sonard"
    declared_cadence_hz: float = 8.0
    stale_after_s: float = 5.0
    reconnect_delay_s: float = 1.0
    status_poll_s: float = 1.0
    upstream_timeout_s: float = 4.0
    command_token_env: str = "NO5_GATEWAY_COMMAND_TOKEN"
    audio_gateway_key_hash_env: str = "NO5_GATEWAY_HEADER_KEY_SHA256"
    calibration: Calibration = field(default_factory=Calibration)
    helm: SignalKConfig = field(default_factory=SignalKConfig)
    map_routes: MapRoutesConfig = field(default_factory=MapRoutesConfig)
    audio: AudioConfig = field(default_factory=AudioConfig)
    weather: WeatherConfig = field(default_factory=WeatherConfig)
    simulation: SimulationConfig = field(default_factory=SimulationConfig)
    camilla_gui: CamillaGuiAuthConfig = field(default_factory=CamillaGuiAuthConfig)
    lumina_tokens: LuminaTokenConfig = field(default_factory=LuminaTokenConfig)

    def __post_init__(self) -> None:
        if self.producer not in {"disabled", "m0", "m1"}:
            raise ConfigurationError("source.producer must be disabled, m0 or m1")
        if self.producer == "disabled":
            if self.upstream_base_url is not None:
                raise ConfigurationError("disabled source must not declare upstream_base_url")
        elif not self.upstream_base_url:
            raise ConfigurationError("m0/m1 source needs upstream_base_url")
        if self.upstream_base_url:
            parsed = urlparse(self.upstream_base_url)
            if parsed.scheme not in {"http", "https"} or not parsed.netloc:
                raise ConfigurationError("upstream_base_url must be an absolute http(s) URL")
            if parsed.path not in {"", "/"}:
                raise ConfigurationError("upstream_base_url must not include a path")
        if not 1 <= self.port <= 65535:
            raise ConfigurationError("gateway.port must be 1..65535")
        if not 0.1 <= self.declared_cadence_hz <= 20:
            raise ConfigurationError("source.declared_cadence_hz must be 0.1..20")
        if not 0.5 <= self.stale_after_s <= 60:
            raise ConfigurationError("source.stale_after_s must be 0.5..60")
        if not 0.1 <= self.reconnect_delay_s <= 60:
            raise ConfigurationError("source.reconnect_delay_s must be 0.1..60")
        if not 0.2 <= self.status_poll_s <= 60:
            raise ConfigurationError("source.status_poll_s must be 0.2..60")
        if not 0.2 <= self.upstream_timeout_s <= 60:
            raise ConfigurationError("source.upstream_timeout_s must be 0.2..60")
        if not self.source_name or len(self.source_name) > 200:
            raise ConfigurationError("source.name must contain 1..200 characters")
        if not self.command_token_env:
            raise ConfigurationError("security.command_token_env must not be empty")
        if not self.audio_gateway_key_hash_env:
            raise ConfigurationError("security.audio_gateway_key_hash_env must not be empty")

    @property
    def source_kind(self) -> str:
        # M0's own status declares synthetic data. M1 is a Pico-host transport;
        # its calibration still gates any physical-depth interpretation.
        return "SIMULATED" if self.producer == "m0" else "REAL"

    @property
    def simulated(self) -> bool:
        return self.source_kind == "SIMULATED"


def _mapping(value: object, label: str) -> dict[str, object]:
    if not isinstance(value, dict):
        raise ConfigurationError(f"{label} must be a TOML table")
    return value


def _string(table: dict[str, object], key: str, default: str | None = None) -> str | None:
    value = table.get(key, default)
    if value is not None and not isinstance(value, str):
        raise ConfigurationError(f"{key} must be a string")
    return value


def _number(table: dict[str, object], key: str, default: float) -> float:
    value = table.get(key, default)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ConfigurationError(f"{key} must be numeric")
    return float(value)


def _integer(table: dict[str, object], key: str, default: int) -> int:
    value = table.get(key, default)
    if isinstance(value, bool) or not isinstance(value, int):
        raise ConfigurationError(f"{key} must be an integer")
    return value


def _source_references(table: dict[str, object], name: str = "combination_sources") -> tuple[str, ...]:
    value = table.get(name, [])
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        raise ConfigurationError(f"helm.{name} must be an array of source references")
    return tuple(value)


def _boolean(table: dict[str, object], key: str, default: bool) -> bool:
    value = table.get(key, default)
    if not isinstance(value, bool):
        raise ConfigurationError(f"{key} must be boolean")
    return value


def _field_mappings(table: dict[str, object]) -> tuple[HelmFieldMapping, ...]:
    values = table.get("fields", [])
    if not isinstance(values, list):
        raise ConfigurationError("helm.fields must be an array of TOML tables")
    result: list[HelmFieldMapping] = []
    for index, raw in enumerate(values):
        entry = _mapping(raw, f"helm.fields[{index}]")
        name = _string(entry, "name")
        value_pointer = _string(entry, "value_pointer")
        timestamp_pointer = _string(entry, "timestamp_pointer")
        if name is None or value_pointer is None or timestamp_pointer is None:
            raise ConfigurationError("each helm.fields item needs name, value_pointer and timestamp_pointer")
        result.append(HelmFieldMapping(name, value_pointer, timestamp_pointer,
                                       _string(entry, "transform", "identity") or ""))
    return tuple(result)


def _channel_faders(table: dict[str, object]) -> tuple[tuple[str, int], ...]:
    raw = table.get("channel_faders", {})
    mapping = _mapping(raw, "audio.camilla.channel_faders")
    entries: list[tuple[str, int]] = []
    for name, index in mapping.items():
        if isinstance(index, bool) or not isinstance(index, int):
            raise ConfigurationError(f"audio.camilla.channel_faders.{name} must be an integer")
        entries.append((name, index))
    return tuple(entries)


def _channel_labels(table: dict[str, object]) -> tuple[tuple[str, str], ...]:
    raw = table.get("channel_labels", {})
    mapping = _mapping(raw, "audio.camilla.channel_labels")
    entries: list[tuple[str, str]] = []
    for name, label in mapping.items():
        if not isinstance(label, str):
            raise ConfigurationError(f"audio.camilla.channel_labels.{name} must be a string")
        entries.append((name, label))
    return tuple(entries)


def _string_array(table: dict[str, object], key: str, label: str) -> tuple[str, ...]:
    value = table.get(key, [])
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        raise ConfigurationError(f"{label}.{key} must be an array of strings")
    return tuple(value)


def _fader_limits(table: dict[str, object]) -> tuple[tuple[str, AudioFaderLimit], ...]:
    raw = table.get("fader_limits", {})
    mapping = _mapping(raw, "audio.fader_limits")
    entries: list[tuple[str, AudioFaderLimit]] = []
    for channel, value in mapping.items():
        limit = _mapping(value, f"audio.fader_limits.{channel}")
        entries.append((channel, AudioFaderLimit(
            min_db=_number(limit, "min_db", -150.0),
            max_db=_number(limit, "max_db", 50.0),
            step_db=_number(limit, "step_db", 1.0),
        )))
    return tuple(entries)


def load_config(path: str | Path) -> GatewayConfig:
    """Load a closed, small TOML surface instead of accepting runtime URLs blindly."""

    with Path(path).open("rb") as handle:
        raw = tomllib.load(handle)
    gateway = _mapping(raw.get("gateway", {}), "gateway")
    source = _mapping(raw.get("source", {}), "source")
    security = _mapping(raw.get("security", {}), "security")
    calibration_raw = _mapping(raw.get("calibration", {}), "calibration")
    helm_raw = _mapping(raw.get("helm", {}), "helm")
    map_routes_raw = _mapping(raw.get("map_routes", {}), "map_routes")
    audio_raw = _mapping(raw.get("audio", {}), "audio")
    weather_raw = _mapping(raw.get("weather", {}), "weather")
    simulation_raw = _mapping(raw.get("simulation", {}), "simulation")
    camilla_gui_raw = _mapping(raw.get("camilla_gui", {}), "camilla_gui")
    lumina_tokens_raw = _mapping(raw.get("lumina_tokens", {}), "lumina_tokens")
    camilla_raw = _mapping(audio_raw.get("camilla", {}), "audio.camilla")
    raspotify_raw = _mapping(audio_raw.get("raspotify", {}), "audio.raspotify")
    spotify_connect_raw = _mapping(audio_raw.get("spotify_connect", {}), "audio.spotify_connect")
    calibration = Calibration(
        revision=_integer(calibration_raw, "revision", 0),
        valid=_boolean(calibration_raw, "valid", False),
        sample_period_us=(None if calibration_raw.get("sample_period_us") is None
                          else _number(calibration_raw, "sample_period_us", 0)),
        sound_speed_mps=_number(calibration_raw, "sound_speed_mps", 1500.0),
        depth_reference=_string(calibration_raw, "depth_reference", "TRANSDUCER") or "",
        transducer_offset_m=(None if calibration_raw.get("transducer_offset_m") is None
                             else _number(calibration_raw, "transducer_offset_m", 0)),
        keel_offset_m=(None if calibration_raw.get("keel_offset_m") is None
                       else _number(calibration_raw, "keel_offset_m", 0)),
    )
    return GatewayConfig(
        bind_host=_string(gateway, "bind_host", "127.0.0.1") or "",
        port=_integer(gateway, "port", 8789),
        producer=_string(source, "producer", "disabled") or "",
        upstream_base_url=_string(source, "upstream_base_url"),
        source_name=_string(source, "name", "no5-sonard") or "",
        declared_cadence_hz=_number(source, "declared_cadence_hz", 8.0),
        stale_after_s=_number(source, "stale_after_s", 5.0),
        reconnect_delay_s=_number(source, "reconnect_delay_s", 1.0),
        status_poll_s=_number(source, "status_poll_s", 1.0),
        upstream_timeout_s=_number(source, "upstream_timeout_s", 4.0),
        command_token_env=_string(security, "command_token_env", "NO5_GATEWAY_COMMAND_TOKEN") or "",
        audio_gateway_key_hash_env=_string(
            security, "audio_gateway_key_hash_env", "NO5_GATEWAY_HEADER_KEY_SHA256"
        ) or "",
        lumina_tokens=LuminaTokenConfig(enabled=_boolean(lumina_tokens_raw, "enabled", False), audience=_string(lumina_tokens_raw, "audience", "") or "", registry_file=_string(lumina_tokens_raw, "registry_file"), registry_socket=_string(lumina_tokens_raw, "registry_socket"), allow_legacy_gateway_key=_boolean(lumina_tokens_raw, "allow_legacy_gateway_key", True)),
        calibration=calibration,
        map_routes=MapRoutesConfig(enabled=_boolean(map_routes_raw, "enabled", False),
            resources_url=_string(map_routes_raw, "resources_url", "") or "",
            token_file=_string(map_routes_raw, "token_file")),
        helm=SignalKConfig(
            enabled=_boolean(helm_raw, "enabled", False),
            snapshot_url=_string(helm_raw, "snapshot_url"),
            source_name=_string(helm_raw, "source_name", "signalk") or "",
            published_at_pointer=_string(helm_raw, "published_at_pointer"),
            vessel_time_pointer=_string(helm_raw, "vessel_time_pointer"),
            vessel_time_format=_string(helm_raw, "vessel_time_format", "hms") or "",
            poll_interval_s=_number(helm_raw, "poll_interval_s", 0.25),
            fields=_field_mappings(helm_raw),
            position_pointer=_string(helm_raw, "position_pointer"),
            gnss_pointer=_string(helm_raw, "gnss_pointer"),
            combination_sources=_source_references(helm_raw),
            combination_verified_fix_clock_sources=_source_references(helm_raw, "combination_verified_fix_clock_sources"),
            combination_max_age_ms=_integer(helm_raw, "combination_max_age_ms", 5000),
            combination_fix_quality_skew_ms=_integer(helm_raw, "combination_fix_quality_skew_ms", 1000),
            combination_max_time_skew_ms=_integer(helm_raw, "combination_max_time_skew_ms", 1000),
            combination_max_separation_metres=_number(helm_raw, "combination_max_separation_metres", 30.0),
        ),
        audio=AudioConfig(
            enabled=_boolean(audio_raw, "enabled", False),
            source_name=_string(audio_raw, "source_name", "no5-audio") or "",
            media_provider=_string(audio_raw, "media_provider", "unavailable") or "",
            hardware_available=(None if audio_raw.get("hardware_available") is None
                                else _boolean(audio_raw, "hardware_available", False)),
            fader_limits=_fader_limits(audio_raw),
            camilla=CamillaDspConfig(
                enabled=_boolean(camilla_raw, "enabled", False),
                websocket_url=_string(camilla_raw, "websocket_url"),
                expected_version=_string(camilla_raw, "expected_version", "4.1.3") or "",
                poll_interval_s=_number(camilla_raw, "poll_interval_s", 0.25),
                channel_faders=_channel_faders(camilla_raw),
                channel_labels=_channel_labels(camilla_raw),
                preset_directory=_string(camilla_raw, "preset_directory"),
                preset_pattern=_string(camilla_raw, "preset_pattern", "preset_*.yml") or "",
                preset_files=_string_array(camilla_raw, "preset_files", "audio.camilla"),
                preset_refresh_s=_number(camilla_raw, "preset_refresh_s", 10.0),
                equalizer_state_file=_string(camilla_raw, "equalizer_state_file"),
                sound_state_file=_string(camilla_raw, "sound_state_file"),
            ),
            raspotify=RaspotifyConfig(
                enabled=_boolean(raspotify_raw, "enabled", False),
                state_file=_string(raspotify_raw, "state_file"),
                poll_interval_s=_number(raspotify_raw, "poll_interval_s", 0.25),
            ),
            spotify_connect=SpotifyConnectConfig(
                enabled=_boolean(spotify_connect_raw, "enabled", False),
                state_file=_string(spotify_connect_raw, "state_file"),
                control_socket=_string(spotify_connect_raw, "control_socket"),
                poll_interval_s=_number(spotify_connect_raw, "poll_interval_s", 0.25),
                command_timeout_s=_number(spotify_connect_raw, "command_timeout_s", 3.0),
                master_volume_bridge=_boolean(spotify_connect_raw, "master_volume_bridge", False),
            ),
        ),
        weather=WeatherConfig(
            enabled=_boolean(weather_raw, "enabled", False),
            source_name=_string(weather_raw, "source_name", "no5-weather") or "",
            snapshot_url=_string(weather_raw, "snapshot_url"),
            latitude_value_pointer=_string(weather_raw, "latitude_value_pointer"),
            latitude_timestamp_pointer=_string(weather_raw, "latitude_timestamp_pointer"),
            longitude_value_pointer=_string(weather_raw, "longitude_value_pointer"),
            longitude_timestamp_pointer=_string(weather_raw, "longitude_timestamp_pointer"),
            position_stale_after_s=_number(weather_raw, "position_stale_after_s", 30.0),
            refresh_interval_s=_number(weather_raw, "refresh_interval_s", 300.0),
            retry_interval_s=_number(weather_raw, "retry_interval_s", 60.0),
        ),
        simulation=SimulationConfig(
            enabled=_boolean(simulation_raw, "enabled", False),
            state_file=_string(simulation_raw, "state_file"),
            token_env=_string(simulation_raw, "token_env", "NO5_GATEWAY_SIMULATION_TOKEN") or "",
            source_name=_string(simulation_raw, "source_name", "no5-pi-gateway-simulator") or "",
            sonar_m0_upstream_base_url=_string(simulation_raw, "sonar_m0_upstream_base_url"),
            sonar_m1_upstream_base_url=_string(simulation_raw, "sonar_m1_upstream_base_url"),
        ),
        camilla_gui=CamillaGuiAuthConfig(
            enabled=_boolean(camilla_gui_raw, "enabled", False),
            public_origin=_string(camilla_gui_raw, "public_origin", "https://lysmarine.local") or "",
            session_secret_env=_string(
                camilla_gui_raw, "session_secret_env", "NO5_CAMILLAGUI_SESSION_SECRET"
            ) or "",
            cookie_name=_string(camilla_gui_raw, "cookie_name", "no5_camillagui") or "",
            session_max_age_s=_integer(camilla_gui_raw, "session_max_age_s", 900),
        ),
    )
