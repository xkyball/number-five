"""Pixel-bound Common69 offset normalization; supplied RGBA8, no file/PNG I/O."""
from dataclasses import dataclass
import hashlib
import math
import xml.etree.ElementTree as ET


@dataclass(frozen=True)
class RGBAImage:
    width: int
    height: int
    pixels: bytes

    def __post_init__(self):
        if (type(self.width) is not int or type(self.height) is not int or
                self.width <= 0 or self.height <= 0 or not isinstance(self.pixels, bytes) or
                len(self.pixels) != self.width * self.height * 4):
            raise ValueError('COMPLETE_POSITIVE_RGBA8_IMAGE_REQUIRED')

    def crop(self, x, y, width, height):
        if not (0 <= x < self.width and 0 <= y < self.height and
                0 < width <= self.width - x and 0 < height <= self.height - y):
            raise ValueError('BITMAP_CROP_OUTSIDE_IMAGE')
        return b''.join(self.pixels[((y + j) * self.width + x) * 4:
                                   ((y + j) * self.width + x + width) * 4]
                        for j in range(height))


def parse_bitmap_xml(public_xml):
    """Accept explicit UTF-8 XML without DTDs or entity declarations."""
    try:
        text = public_xml.decode('utf-8-sig')
    except UnicodeDecodeError as error:
        raise ValueError('PUBLIC_BITMAP_XML_UTF8_REQUIRED') from error
    if '\0' in text:
        raise ValueError('PUBLIC_BITMAP_XML_UTF8_REQUIRED')
    if '<!DOCTYPE' in text.upper() or '<!ENTITY' in text.upper():
        raise ValueError('PUBLIC_BITMAP_XML_ENTITIES_FORBIDDEN')
    return ET.fromstring(text)


def normalize_bitmap_pivot_rows(csv, atlas, public_xml, public_bitmap, *, protected_names=()):
    """Return new CSV bytes and factual change/unproven receipts.

    Exact name, dimensions and all RGBA bytes must match. Matching XML variants
    must agree on one bitmap pivot; scale must be exactly 1. Unknowns retain
    their whole original lexical row, and changed rows retain fields 0..5.
    """
    if not isinstance(csv, bytes) or not isinstance(public_xml, bytes):
        raise TypeError('CSV_AND_XML_BYTES_REQUIRED')
    root = parse_bitmap_xml(public_xml)
    symbols, incomplete = {}, set()
    for node in root.iter('symbol'):
        name, bitmap = node.findtext('name'), node.find('bitmap')
        if name is None or bitmap is None:
            continue
        location, pivot = bitmap.find('graphics-location'), bitmap.find('pivot')
        if location is None or pivot is None:
            incomplete.add(name)
            continue
        try:
            item = (int(location.get('x')), int(location.get('y')), int(bitmap.get('width')),
                    int(bitmap.get('height')), int(pivot.get('x')), int(pivot.get('y')))
        except (TypeError, ValueError) as error:
            raise ValueError('INTEGRAL_PUBLIC_BITMAP_GEOMETRY_REQUIRED') from error
        # Validate public-source bounds; never accept image-library padding.
        public_bitmap.crop(*item[:4])
        symbols.setdefault(name, []).append(item)

    protected, seen = frozenset(protected_names), set()
    rows, changes, unproven, exact, protected_rows = [], [], [], [], []
    for line in csv.decode('ascii').splitlines(keepends=True):
        text = line.rstrip('\r\n')
        ending, fields = line[len(text):], text.split(',')
        if len(fields) != 8 or fields[0] in seen:
            raise ValueError('UNIQUE_EIGHT_FIELD_ATLAS_ROWS_REQUIRED')
        name = fields[0]
        seen.add(name)
        try:
            scale, ox, oy = map(float, (fields[1], fields[6], fields[7]))
            x, y, width, height = map(int, fields[2:6])
        except ValueError as error:
            raise ValueError('TYPED_ATLAS_ROW_REQUIRED') from error
        if scale <= 0 or not all(map(math.isfinite, (scale, ox, oy))):
            raise ValueError('FINITE_POSITIVE_ATLAS_SCALE_REQUIRED')
        crop = atlas.crop(x, y, width, height)
        if name in protected:
            protected_rows.append(name)
            rows.append(line)
            continue
        candidates = symbols.get(name, [])
        matched = [item for item in candidates if (width, height) == item[2:4]
                   and crop == public_bitmap.crop(*item[:4])]
        pivots = {(-item[4], -item[5]) for item in matched}
        if scale != 1 or name in incomplete or len(pivots) != 1:
            if scale != 1:
                reason = 'NON_UNIT_SCALE'
            elif name in incomplete:
                reason = 'INCOMPLETE_PUBLIC_BITMAP_DEFINITION'
            elif not candidates:
                reason = 'NO_PUBLIC_BITMAP'
            elif not matched:
                reason = 'PIXELS_OR_DIMENSIONS_DIFFER'
            else:
                reason = 'AMBIGUOUS_MATCHING_BITMAP_PIVOTS'
            unproven.append(dict(name=name, originalRow=text, reason=reason))
            rows.append(line)
            continue
        pivot = next(iter(pivots))
        if (ox, oy) == pivot:
            exact.append(name)
            rows.append(line)
            continue
        new = ','.join(fields[:6] + list(map(str, pivot)))
        changes.append(dict(name=name, originalRow=text, resultRow=new,
            bitmapPivotOffset=list(pivot), matchingPublicBitmaps=[list(item) for item in matched],
            cropRGBASHA256=hashlib.sha256(crop).hexdigest()))
        rows.append(new + ending)
    result = ''.join(rows).encode('ascii')

    def image_binding(image):
        return dict(width=image.width, height=image.height, bytes=len(image.pixels),
                    sha256=hashlib.sha256(image.pixels).hexdigest())

    return result, dict(format='no5-bitmap-pivot-normalization/1',
        policy='EXACT_RGBA8_MATCH_UNIQUE_PUBLIC_BITMAP_PIVOT/1',
        changes=changes, unproven=unproven, alreadyExact=exact, protected=protected_rows,
        counts=dict(rows=len(rows), changed=len(changes), unproven=len(unproven),
                    alreadyExact=len(exact), protected=len(protected_rows)),
        sourceCSV_SHA256=hashlib.sha256(csv).hexdigest(),
        resultCSV_SHA256=hashlib.sha256(result).hexdigest(),
        publicXMLSHA256=hashlib.sha256(public_xml).hexdigest(),
        sourceAtlasRGBA=image_binding(atlas), publicBitmapRGBA=image_binding(public_bitmap),
        privateOrNativeDependencies=False, geometryOrProgrammeChanges=False)
