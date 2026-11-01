"""Read-only, offscreen view of all 31 bodies and actual sliced support/brim."""
import sys, datetime, argparse
from pathlib import Path
from common import ROOT, BASE, read, save, sha
sys.path.insert(0, str(BASE))
import render_upper_bed as view
import numpy as np
from PIL import ImageDraw


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--parts-only', action='store_true',
                        help='Hide sliced supports and brims in the view only.')
    args = parser.parse_args()
    bed_set = read(ROOT / 'print-rest/set.json')
    assert bed_set['body_count'] == 31 and len(bed_set['beds']) == 1
    bed = Path(bed_set['beds'][0]['folder'])
    prep = read(bed / 'preparation.json')
    assert prep['source_sha256'] == sha(ROOT / 'design.json')
    assert len(prep['parts']) == 31
    assert prep['digital'] == 'CANDIDATE_SLICE_PASS_PIXEL_REVIEW_PENDING'
    for part in prep['parts']:
        assert part['stl_sha256'] == sha(part['stl']), part['id']
    gcode = Path(prep['gcode'])
    assert gcode.read_bytes() == (bed / 'plate_1.gcode').read_bytes()
    text = gcode.read_text()
    a, b, w, h, kinds, roles, arcs = view.read_beads(text)
    segments, _, _ = view.parse_gcode(text)
    segments = [s for s in segments if s[2] > 0 and s[3] != 'Custom']
    assert len(segments) == len(a)
    assert np.allclose(a, np.array([s[0] for s in segments]), atol=1e-10)
    assert np.allclose(b, np.array([s[1] for s in segments]), atol=1e-10)
    assert np.all(w > 0) and np.all(h > 0) and set(kinds) == {0, 1, 2}
    original_path_count = len(a)
    if args.parts_only:
        visible = kinds == 0
        a, b, w, h, kinds = (v[visible] for v in (a, b, w, h, kinds))
    out = bed / 'bed-view'
    out.mkdir(exist_ok=True)
    top = float(max(a[:, 2].max(), b[:, 2].max()))
    print('ACTUAL_PATHS', len(a), roles, 'top_mm', top, flush=True)
    result = view.render(a, b, w, h, kinds, [1.1, -1.7, 2.5],
                         (2600, 2100), [[-110, 110], [-110, 110], [0, top]])
    draw = ImageDraw.Draw(result)
    draw.text((72, 36), 'Pi-Box · alle 31 Teile auf einem Bett',
              font=view.font(48, True), fill='#263b48')
    subtitle = ('PI-2L-5 · 220 × 220 mm · Stützen und Haftränder ausgeblendet'
                if args.parts_only else
                'PI-2L-5 · 220 × 220 mm · tatsächliche Druckbahnen aus dem Slicer')
    draw.text((72, 100), subtitle,
              font=view.font(28), fill='#536772')
    x = 75
    legend = ([('#87939e', '31 Druckteile · unveränderte Bettpositionen')]
              if args.parts_only else
              [('#87939e', 'Druckteile'), ('#17a1b8', 'Stützen mit Kontaktlagen'),
               ('#e69d34', 'Haftränder (Brim)')])
    for color, label in legend:
        draw.rounded_rectangle((x, 1960, x + 34, 1994), radius=5, fill=color)
        draw.text((x + 49, 1960), label, font=view.font(28), fill='#2b414d')
        x += int(draw.textlength(label, font=view.font(28))) + 125
    footer = ('Nur die Ansicht wurde geändert. Druckdateien und Druckeinstellungen bleiben unverändert.'
              if args.parts_only else
              'Farben nur zur Unterscheidung. Gedruckt wird alles aus grauem PETG. Druck nicht gestartet.')
    draw.text((72, 2035), footer,
              font=view.font(25), fill='#647883')
    image = out / ('bett-31-teile-ohne-stuetzen.png' if args.parts_only else
                   'bett-31-teile-mit-stuetzen.png')
    result.save(image)
    binding = ('render-binding-parts-only.json' if args.parts_only else
               'render-binding.json')
    save(out / binding, {
        'schema': 'iot.exact-gcode-3d-preview/1', 'revision': 'PI-2L-5',
        'generated_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'body_count': 31, 'bed_mm': [220, 220],
        'source_sha256': sha(ROOT / 'design.json'),
        'preparation_sha256': sha(bed / 'preparation.json'),
        'gcode_sha256': sha(gcode), 'three_mf_sha256': sha(prep['three_mf']),
        'path_count': len(a), 'roles': roles, 'arcs': arcs,
        'source_path_count': original_path_count,
        'visibility': 'model only; support/interface/brim hidden' if args.parts_only else 'all deposited job paths',
        'widths_mm': [float(w.min()), float(w.max())],
        'layer_heights_mm': [float(h.min()), float(h.max())],
        'independent_parser_all_segment_endpoints': 'PASS',
        'renderer': 'Single-thread CPU Z-buffer; all deposited job segments with slicer width and layer height, rectangular bead approximation. No travel paths or startup purge.',
        'image': str(image), 'image_sha256': sha(image),
        'pixel_review': 'PENDING', 'print_files_modified': False,
        'printer_transport': 'NOT_SENT', 'printer_start': 'NOT_REQUESTED',
    })
    print('RENDERED', image, flush=True)


if __name__ == '__main__':
    main()
