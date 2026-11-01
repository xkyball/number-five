"""Invented tiny wire fixtures; no real chart coordinates or private indexes."""
import copy
import hashlib
import os
import struct

import pytest

from no_chart_converter.rx5 import region
from no_chart_converter.rx5.load_planning import (
    SourceBinding, authenticate_reader_source, build_load_index, estimate_selected_requests, range_union_bytes,
)
from no_chart_converter.rx5.wire import AuthoredBlock, FEATURE, HEADER_SLOTS, assemble, blocks


def synthetic_chart():
    header = bytearray(240)
    header[:8] = b'\x01RayRX5\0'
    header[56:72] = bytes(range(16))
    feature = FEATURE.pack(1,0,0,0,0,1,1,0,0,0)
    authored = [
        AuthoredBlock('index',0x1101,0,b'',struct.pack('<II',1,0)),
        AuthoredBlock('coordinates',0x1102,0,b'',struct.pack('<III',0x01000001,123,456)),
        AuthoredBlock('empty-index',0x1101,0,b'',b''),
        AuthoredBlock('empty-coordinates',0x1102,0,b'',b''),
        AuthoredBlock('render',0x1103,0,b'',b'SYNTHETIC'),
        AuthoredBlock('additional',0x1104,0,b'',b'SYNTHETIC'),
    ]

    def node(name, level, children, *, body=None):
        refs = [(8*i,children.get(i)) for i in range(4)]
        if body is not None:
            streams = ('empty-index','empty-coordinates','render','additional') if not body else ('index','coordinates','render','additional')
            refs += [(32+8*i,value) for i,value in enumerate(streams)]
        authored.append(AuthoredBlock(name,0x6000+level,1 if body is None else 3,
                                     bytes(32 if body is None else 64),b'' if body is None else body,references=tuple(refs)))

    node('root',0,{0:'empty',1:'route'},body=feature)
    node('empty',1,{},body=b'')
    node('route',1,{1:'shared'})
    node('shared',2,{},body=feature)
    refs = {key:('root' if key==160 else None) for key in HEADER_SLOTS}
    return assemble(bytes(header), authored, refs)


class MetadataOnlyReader(region.Reader):
    def __init__(self, source, allowed):
        self.allowed = allowed
        self.metadata_reads = []
        super().__init__(source)

    def read(self, offset, length):
        assert any(lo <= offset and offset+length <= hi for lo,hi in self.allowed), 'payload read'
        self.metadata_reads.append((offset,length))
        return super().read(offset,length)

    def payload(self, *args):
        raise AssertionError('payload read')

    range = cached = stream = idx = element = payload


@pytest.fixture
def scene(tmp_path, monkeypatch):
    raw, offsets = synthetic_chart()
    source = tmp_path/'synthetic.rx5'
    source.write_bytes(raw)
    # Restore public module globals after this synthetic caller configuration.
    for name in ('SOURCE','SOURCE_BYTES','SOURCE_SHA','STORE'):
        monkeypatch.setattr(region,name,getattr(region,name))
    region.configure(source,tmp_path/'store')
    parsed = blocks(raw)
    allowed = [(0,240)]+[(at,block.data_offset) for at,block in parsed.items()]
    reader = MetadataOnlyReader(source,allowed)
    binding = dict(sha256=hashlib.sha256(raw).hexdigest(),bytes=len(raw),
                   uuidHex=raw[56:72].hex(),stat=region.statkey(reader.before),ctimeNs=reader.before.st_ctime_ns)
    try:
        yield raw,offsets,reader,binding,source
    finally:
        if reader.fd is not None:
            reader.close()


def test_header_only_index_reuses_public_reader(scene):
    raw,offsets,reader,binding,_ = scene
    index = build_load_index(reader,source_binding=binding)
    assert index.tree_nodes == 4
    assert [(p.tile,p.stored_owners) for p in index.packages] == [((0,0,0),1),((1,0,0),0),((2,3,0),1)]
    assert len(reader.metadata_reads) > 1
    assert all(length <= 240 for _,length in reader.metadata_reads)
    assert reader.fd is not None
    assert index.source == SourceBinding.from_mapping(binding)


def test_repeated_requests_and_shared_streams_are_distinct_costs(scene):
    _,_,reader,binding,_ = scene
    index = build_load_index(reader,source_binding=binding)
    requests = [([3,6,0],[2,3,0]),([3,7,0],[2,3,0]),([3,1,5],[0,0,0])]
    result = estimate_selected_requests(index,requests,source_binding=binding)
    # I=8, C=12; I/C block headers add 32. Two different source nodes alias I/C.
    assert result['freshGeometryPayloadBytes'] == 60
    assert result['freshGeometryPackageBytes'] == 156
    assert result['uniqueSourcePackages'] == 2
    assert result['uniqueSourceGeometryPayloadBytes'] == 40
    assert result['uniqueSourceGeometryPackageBytes'] == 104
    assert result['unionGeometryPayloadBytes'] == 20
    assert result['unionGeometryPackageBytes'] == 52
    assert result['actualIOClaim'] is result['cacheHitClaim'] is False


def test_supplied_none_coarse_and_empty_are_not_reselected(scene):
    _,_,reader,binding,_ = scene
    index = build_load_index(reader,source_binding=binding)
    requests = [([3,0,0],None),([3,0,0],[0,0,0]),([3,0,0],[1,0,0])]
    before = copy.deepcopy(requests)
    result = estimate_selected_requests(index,requests,source_binding=binding)
    assert result['selectedSources'] == [None,[0,0,0],[1,0,0]]
    assert result['noSourceRequests'] == result['explicitEmptyRequests'] == 1
    assert result['freshGeometryPayloadBytes'] == 20
    assert result['freshGeometryPackageBytes'] == 84  # Empty I/C retain headers.
    assert requests == before
    assert result['sourceSelectionChanged'] is False


@pytest.mark.parametrize('spans,expected',[
    ([],0), ([(5,0)],0), ([(10,5),(10,5)],5),
    ([(10,5),(15,5)],10), ([(10,10),(12,2)],10),
    ([(0,4),(2,4),(9,1)],7), ([(12,8),(0,20),(8,8)],20),
])
def test_exact_range_union(spans,expected):
    assert range_union_bytes(spans) == expected


@pytest.mark.parametrize('spans', [[(-1,2)],[(0,-1)],[(True,2)],[(0,1.5)]])
def test_invalid_ranges_fail_closed(spans):
    with pytest.raises(ValueError):
        range_union_bytes(spans)


@pytest.mark.parametrize('limit,within,excess',[(52,True,0),(51,False,1),(0,False,52)])
def test_budget_reports_without_dropping_requests(scene,limit,within,excess):
    _,_,reader,binding,_ = scene
    index = build_load_index(reader,source_binding=binding)
    result = estimate_selected_requests(index,[([1,1,0],[0,0,0])],source_binding=binding,byte_budget=limit)
    assert result['budget'] == dict(basis='freshGeometryPackageBytes',limitBytes=limit,withinBudget=within,excessBytes=excess)
    assert result['requestCount'] == 1 and result['selectedSources'] == [[0,0,0]]


@pytest.mark.parametrize('field', ['sha256','bytes','uuidHex','stat','ctimeNs'])
def test_stale_source_binding_is_refused(scene,field):
    _,_,reader,binding,_ = scene
    index = build_load_index(reader,source_binding=binding)
    stale = copy.deepcopy(binding)
    if field == 'sha256':stale[field] = 'f'*64
    elif field == 'uuidHex':stale[field] = 'f'*32
    elif field == 'stat':stale[field][3] += 1
    elif field == 'ctimeNs':stale[field] += 1
    else:
        stale['bytes'] += 1
        stale['stat'][2] += 1
    with pytest.raises(ValueError):
        estimate_selected_requests(index,[],source_binding=stale)


def test_metadata_source_binding_matches_real_header_and_fd(scene):
    _,_,reader,binding,_ = scene
    for change in ('uuidHex','stat'):
        bad = copy.deepcopy(binding)
        if change == 'uuidHex':bad[change] = 'f'*32
        else:bad['stat'][1] += 1
        with pytest.raises(ValueError):
            build_load_index(reader,source_binding=bad)


def test_changed_held_source_is_refused(scene):
    _,_,reader,binding,source = scene
    with source.open('ab') as stream:
        stream.write(b'x')
    with pytest.raises(ValueError,match='GENERATION'):
        build_load_index(reader,source_binding=binding)
    # The public Reader's ordinary close is also expected to reject mutation.
    with pytest.raises(ValueError,match='SOURCE_STAT_UNCHANGED'):
        reader.close()
    os.close(reader.fd)
    reader.fd = None


def test_backdated_same_size_rewrite_before_new_reader_is_refused(scene):
    raw,offsets,reader,binding,source = scene
    reader.close()
    before = source.stat()
    altered = bytearray(raw)
    altered[offsets['render']+16] ^= 1
    source.write_bytes(altered)
    os.utime(source,ns=(before.st_atime_ns,before.st_mtime_ns))
    current = region.Reader(source)
    try:
        assert region.statkey(current.before) == binding['stat']
        assert current.before.st_ctime_ns != binding['ctimeNs']
        with pytest.raises(ValueError,match='HELD_FD_BINDING'):
            build_load_index(current,source_binding=binding)
    finally:
        current.close()


def test_explicit_full_hash_authentication_is_separate(scene):
    raw,_,reader,binding,source = scene
    # Public Reader permits the deliberate whole-file hash. MetadataOnlyReader
    # continues to forbid such body reads in build_load_index itself.
    public = region.Reader(source)
    try:
        authenticated = authenticate_reader_source(public,expected_sha256=binding['sha256'],chunk_bytes=240)
        assert authenticated == binding
        assert public.bytes_read >= len(raw)+240
        assert build_load_index(reader,source_binding=authenticated).source == SourceBinding.from_mapping(binding)
        with pytest.raises(ValueError,match='SHA256_MISMATCH'):
            authenticate_reader_source(public,expected_sha256='f'*64)
    finally:
        public.close()


@pytest.mark.parametrize('destination,source',[
    ([2,0,0],[2,3,0]), ([1,1,0],[2,3,0]),
    ([2,1,1],[1,1,0]), ([22,0,0],None), ([1,True,0],None),
])
def test_invalid_requests_are_refused(scene,destination,source):
    _,_,reader,binding,_ = scene
    index = build_load_index(reader,source_binding=binding)
    with pytest.raises(ValueError):
        estimate_selected_requests(index,[(destination,source)],source_binding=binding)


def test_empty_request_and_bounded_work(scene):
    _,_,reader,binding,_ = scene
    index = build_load_index(reader,source_binding=binding)
    assert estimate_selected_requests(index,[],source_binding=binding)['freshGeometryPackageBytes'] == 0
    with pytest.raises(ValueError,match='REQUEST_LIMIT'):
        estimate_selected_requests(index,[([0,0,0],[0,0,0])]*2,source_binding=binding,max_requests=1)
    with pytest.raises(ValueError,match='TREE_BOUND'):
        build_load_index(reader,source_binding=binding,max_nodes=1)


def test_cyclic_metadata_tree_is_refused(scene):
    raw,offsets,reader,binding,source = scene
    reader.close()
    changed = bytearray(raw)
    struct.pack_into('<Q',changed,offsets['root']+16,offsets['root'])
    source.write_bytes(changed)
    region.configure(source,source.parent/'other-store')
    parsed = blocks(changed)
    with_reader = MetadataOnlyReader(source,[(0,240)]+[(at,b.data_offset) for at,b in parsed.items()])
    current = dict(sha256=hashlib.sha256(changed).hexdigest(),bytes=len(changed),uuidHex=changed[56:72].hex(),stat=region.statkey(with_reader.before),ctimeNs=with_reader.before.st_ctime_ns)
    try:
        with pytest.raises(ValueError,match='TREE_BOUND'):
            build_load_index(with_reader,source_binding=current)
    finally:
        with_reader.close()
