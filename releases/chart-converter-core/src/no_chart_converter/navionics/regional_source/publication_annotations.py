"""Explicit opt-in plans for derived publication annotation pick-sites.

The original area and ordered typed attributes remain a separate source record.
This pure planner never changes them, writes a file or enables a native route.
"""
from dataclasses import dataclass
import hashlib
import json

from no_chart_converter.navionics.display_control_wire import encode_display_control


def _sha256(value):
    raw = (json.dumps(value, sort_keys=True, separators=(',', ':'),
                      ensure_ascii=True, allow_nan=False) + '\n').encode()
    return hashlib.sha256(raw).hexdigest()


@dataclass(frozen=True)
class PublicationSourceRetention:
    source_id: str
    feature_sha256: str
    geometry_sha256: str
    ordered_attributes_sha256: str


@dataclass(frozen=True)
class PublicationAnnotation:
    """An explicitly derived point; it does not replace its source area."""

    source_id: str
    xy: tuple
    name: str
    information: str
    min_level: int
    max_level: int
    display_control: bytes
    native_class: int = 201
    native_kind: int = 40
    physical_layer: int = 16


@dataclass(frozen=True)
class PublicationAnnotationPlan:
    enabled: bool
    annotations: tuple
    omitted_no_text: tuple
    retained_sources: tuple


def _publication_text(feature):
    attributes = feature.get('attributes')
    if type(attributes) is not list:
        raise ValueError('ordered source attributes must be a list')
    fields = []
    for attr in attributes:
        if type(attr) is not dict:
            raise ValueError('typed source attribute must be an object')
        key = attr.get('acronym')
        if key not in ('PUBREF', 'INFORM'):
            continue
        value = attr.get('value')
        if attr.get('type') != 'utf8' or type(value) is not str:
            raise ValueError('publication text must retain its typed utf8 value')
        if value.strip():
            fields.append((key, value))
    if not fields:
        return None
    references = [value for key, value in fields if key == 'PUBREF']
    name = ('Publication: ' + references[0] if references else
            'Nautical publication information') + ' (derived)'
    labels = dict(PUBREF='Publication reference (PUBREF)', INFORM='Information (INFORM)')
    lines = ['Nautical publication information']
    lines.extend(labels[key] + ': ' + value for key, value in fields)
    lines.extend(['', 'Derived information pick site for the associated chart area.'])
    return name, '\n'.join(lines)


def plan_publication_annotations(features, anchors_by_source_id=None, *,
                                 enabled=False, min_level=None, max_level=None,
                                 display_rows=None):
    """Return a finite authoring plan, without activating generic writer support.

    enabled=True, a complete explicit feature range, display rows and signed-I32
    world anchors are required to author anything. Anchor projection/interior
    qualification and complete source retention are caller responsibilities.
    Missing/blank PUBREF and INFORM produce an explicit omitted disposition.
    The disabled path is a no-op and does not inspect or transform features.
    """
    if type(enabled) is not bool:
        raise ValueError('enabled must be an explicit bool')
    if not enabled:
        return PublicationAnnotationPlan(False, (), (), ())
    display = encode_display_control(min_level, max_level, display_rows)
    if not isinstance(features, (list, tuple)):
        raise ValueError('explicit finite source feature sequence required')
    if type(anchors_by_source_id) is not dict:
        raise ValueError('explicit world anchors keyed by source_id required')
    annotations, omitted, retained = [], [], []
    seen = set()
    for feature in features:
        if type(feature) is not dict or feature.get('classAcronym') != 'M_NPUB':
            raise ValueError('original M_NPUB feature required')
        source_id = feature.get('sourceId')
        if type(source_id) is not str or not source_id or source_id in seen:
            raise ValueError('distinct nonempty source_id required')
        seen.add(source_id)
        geometry = feature.get('geometry')
        if type(geometry) is not dict or geometry.get('kind') != 'area':
            raise ValueError('original publication area required')
        text = _publication_text(feature)
        retained.append(PublicationSourceRetention(source_id, _sha256(feature),
            _sha256(geometry), _sha256(feature['attributes'])))
        if text is None:
            omitted.append(source_id)
            continue
        xy = anchors_by_source_id.get(source_id)
        if not isinstance(xy, (list, tuple)) or len(xy) != 2 or any(
                type(v) is not int or not -(1 << 31) <= v < (1 << 31) for v in xy):
            raise ValueError('publication anchor must be two signed-I32 world values')
        annotations.append(PublicationAnnotation(source_id, tuple(xy), *text,
                                                min_level, max_level, display))
    if any(key not in seen for key in anchors_by_source_id):
        raise ValueError('anchor does not join a supplied source feature')
    return PublicationAnnotationPlan(True, tuple(annotations), tuple(omitted), tuple(retained))
