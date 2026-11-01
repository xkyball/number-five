"""Invented tiny geometry and metadata only; no chart-derived coordinates."""
import struct

from no_chart_converter.rx5.wire import AuthoredBlock, FEATURE, assemble, encode_dictionary


def chart(*, coverage=False, fallback=False):
    header = bytearray(240)
    header[:8] = b'\1RayRX5\0'
    header[56:72] = bytes(range(16))
    struct.pack_into('<Q', header, 104, 256)
    base = 1073807360
    a, b, c, d = (base, base), (base+65536, base), (base+65536, base+65536), (base, base+65536)
    coordinates = b''.join(struct.pack('<I', 0x01000004) + b''.join(struct.pack('<II', *p) for p in triangle)
                           for triangle in [(a,b,c,a), (a,c,d,a)])
    indices = struct.pack('<III', 2, 8 << 24, (8 << 24) | 36)
    program = b'AC(SYNTHETIC);'
    render = struct.pack('<HI', 1, 0) + struct.pack('<IH', 196608, len(program)) + program
    feature = FEATURE.pack(1, 0, 0, 0, 0, 1, 3, 0, 0, 0)
    blocks = [AuthoredBlock('index', 0x1101, 0, b'', indices),
              AuthoredBlock('coords', 0x1102, 0, b'', coordinates),
              AuthoredBlock('render', 0x1103, 0, b'', render),
              AuthoredBlock('query', 0x1104, 0, b'', bytes(14)),
              AuthoredBlock('source', 0x30, 0, b'', b'<rx5><source_chart_info id="SYNTHETIC"/></rx5>', next_name='coverage' if coverage else None),
              AuthoredBlock('dictionary', 8, 16, bytes(24), encode_dictionary([''])),
              AuthoredBlock('presentation', 65, 0, b'', b'OWN SYNTHETIC RESOURCE'),
              AuthoredBlock('documents', 7, 16, bytes(24), encode_dictionary([])),
              AuthoredBlock('info', 2, 0, b'', b'<rx5><chart_info name="SYNTHETIC"/></rx5>')]
    if coverage:
        q=2147483649
        lo,hi=q//2-q//360,q//2+q//360
        ring=[(lo,lo),(hi,lo),(hi,hi),(lo,hi),(lo,lo)]
        blocks.extend([
            AuthoredBlock('coverage-index',0x1105,0,b'',struct.pack('<II',1,18<<24)),
            AuthoredBlock('coverage-coords',0x1106,0,b'',struct.pack('<I',len(ring))+b''.join(struct.pack('<II',*p)for p in ring)),
            AuthoredBlock('coverage',0x34,2,bytes(32),feature,references=((0,'coverage-index'),(8,'coverage-coords'),(16,None),(24,None))),
        ])
    # One productive L13 leaf and its routing ancestors, at invented equatorial geometry.
    tile = (13, 4096, 4096)
    for level in range(14):
        if level == 13:
            refs = tuple([(i*8, None) for i in range(4)] + [(32+i*8, n) for i,n in enumerate(('index','coords','render','query'))])
            blocks.append(AuthoredBlock(f'node{level}', 0x6000+level, 3, bytes(64), feature, references=refs))
        else:
            slot = ((tile[1] >> (12-level)) & 1) | (((tile[2] >> (12-level)) & 1) << 1)
            refs = tuple((i*8, f'node{level+1}' if i == slot else None) for i in range(4))
            if fallback and level==12:
                refs+=tuple((32+i*8,n) for i,n in enumerate(('index','coords','render','query')))
                blocks.append(AuthoredBlock(f'node{level}',0x6000+level,3,bytes(64),feature,references=refs))
            else:
                blocks.append(AuthoredBlock(f'node{level}', 0x6000+level, 1, bytes(32), b'', references=refs))
    refs = {136:'info',144:'source',160:'node0',168:'dictionary',184:'presentation',200:'documents'}
    return assemble(bytes(header), blocks, refs)[0]
