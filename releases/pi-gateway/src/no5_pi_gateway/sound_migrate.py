"""Administrative, explicit legacy-EQ migration helpers; never a network API.

The provisioner captures the active GetConfigJson/GetConfigFilePath before it
switches to the generated profile, then imports against that profile's actual
GetNo5Sound generation while the gateway is stopped. Original root files and
master-equalizer.json are read only. No private values are baked into recipes.
"""
from __future__ import annotations
import copy
import hashlib
import json
import re

from .equalizer import FREQUENCIES, snapshot as legacy_snapshot
from .sound import SoundError, SoundStore, validate_dsp_state, validate_preset


def _digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def _runtime_generation(entry):
    binding = entry.get("activationBinding")
    if binding is None:
        return entry.get("legacyConfigGeneration")
    fields = {"origin", "sourceSha256", "validationInputSha256", "normalizedConfigSha256", "legacyConfigGeneration", "camillaBinarySha256"}
    if not isinstance(binding, dict) or set(binding) != fields or binding["origin"] != "CAMILLA_VALIDATE_CONFIG_JSON":
        raise SoundError("invalid activation normalization binding")
    for field in fields - {"origin"}:
        if not isinstance(binding[field], str) or re.fullmatch(r"[0-9a-f]{64}", binding[field]) is None:
            raise SoundError("invalid activation normalization hash")
    if binding["sourceSha256"] != entry.get("sourceSha256") or binding["validationInputSha256"] != entry.get("sourceConfigSha256"):
        raise SoundError("activation normalization does not bind approved source/input")
    return binding["legacyConfigGeneration"]


def capture_legacy(config, source_path, migration_manifest, *, alias_binding=None):
    """Bind actual active five-band values to a provisioner's approved profile."""
    if migration_manifest.get("schema") != "no5.sound-profile-migration/1":
        raise SoundError("unsupported profile migration manifest")
    entries = migration_manifest.get("profiles")
    if not isinstance(entries, list) or len(entries) > 64:
        raise SoundError("invalid migration profile list")
    matches = [entry for entry in entries if entry.get("sourcePath") == source_path]
    fingerprint_path = source_path
    if not matches and alias_binding is not None:
        fields = {"sourcePath", "sourceConfigSha256", "canonicalSourcePath"}
        if (not isinstance(alias_binding, dict) or set(alias_binding) != fields or
                alias_binding["sourcePath"] != source_path or alias_binding["sourceConfigSha256"] != _digest(config)):
            raise SoundError("active alias binding does not match fresh captured content")
        fingerprint_path = alias_binding["canonicalSourcePath"]
        matches = [entry for entry in entries if entry.get("sourcePath") == fingerprint_path]
    if len(matches) != 1:
        raise SoundError("active source path is not uniquely approved for migration")
    entry = matches[0]
    # This removes only old gain values from the fingerprint; a foreign
    # topology, frequency, routing or path change fails before any write.
    before = legacy_snapshot(config, fingerprint_path, 0)
    if _runtime_generation(entry) != before["configGeneration"]:
        raise SoundError("active legacy topology differs from provisioned source")
    routing = entry.get("routing")
    preset = {"id": "no5-sound-" + str(routing), "name": str(config.get("title") or "No5 sound")[:100],
              "routingTemplate": routing, "eqEnabled": False, "reserveDb": 12., "eqPoints": [
                  {"id": f"legacy-{frequency}", "frequencyHz": frequency, "gainDb": gain, "q": 1.0, "widthLocked": False}
                  for frequency, gain in zip(FREQUENCIES, before["gainsDb"])], "room": {"amount": 0., "size": .5}}
    validate_preset(preset)
    return {"schema": "no5.sound-live-migration/1", "sourcePath": source_path,
            "destinationPath": entry.get("destinationPath"), "legacyConfigGeneration": before["configGeneration"],
            "sourceConfigSha256": _digest(config), "preset": preset}


def import_legacy(store: SoundStore, capture, new_dsp_state, *, expected_store_sha256):
    """CAS import into a private document; never sends or retries a DSP write."""
    fields = {"schema", "sourcePath", "destinationPath", "legacyConfigGeneration", "sourceConfigSha256", "preset"}
    if not isinstance(capture, dict) or set(capture) != fields or capture["schema"] != "no5.sound-live-migration/1":
        raise SoundError("unsupported captured migration schema")
    preset = validate_preset(capture["preset"])
    observed = validate_dsp_state(new_dsp_state)
    if _digest(store.state) != expected_store_sha256:
        raise SoundError("sound state changed after migration preparation")
    if observed["routingTemplate"] != preset["routingTemplate"] or observed["appliedRevision"] != 0 or observed["preparedRevision"] != 0:
        raise SoundError("new DSP profile is not pristine and routing-matched")
    if observed["preset"]["eqPoints"] or observed["preset"]["room"]["amount"] != 0 or observed["preset"]["eqEnabled"]:
        raise SoundError("new profile already has sound adjustments; preserve and reconcile")
    if store.state["journal"] is not None:
        raise SoundError("cannot migrate over an unresolved preview")
    state = copy.deepcopy(store.state)
    existing = state["presets"].get(preset["id"])
    if existing is not None and existing != preset:
        raise SoundError("migration must not replace a newer user preset")
    binding = state["bindings"].get(observed["configGeneration"])
    if binding is not None and binding != preset["id"]:
        raise SoundError("migration configuration binding already exists")
    state["presets"][preset["id"]] = preset
    state["bindings"][observed["configGeneration"]] = preset["id"]
    if existing is None: state["presetRevision"] += 1
    store.replace(state)
    return {"presetRevision": state["presetRevision"], "presetId": preset["id"], "configGeneration": observed["configGeneration"]}


def state_sha256(store):
    return _digest(store.state)
