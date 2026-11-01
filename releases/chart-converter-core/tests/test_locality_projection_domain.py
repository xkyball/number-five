"""Invented bounded scalar/geometry cases for the native signed32 domain."""
import struct

import pytest

from no_chart_converter.rx5 import locality_empty as empty,locality_parts as parts


def source_fixture(programme=b'AC(TEST);',*,far_world=False):
    unit=parts.Q//32768
    inside=[(unit//4,unit//4),(unit//2,unit//4),(unit//2,unit//2),(unit//4,unit//4)]
    start=parts.Q//2 if far_world else 2*unit
    far=[(start,start),(start+100,start),(start+100,start+100),(start,start)]
    co=bytearray();words=[]
    for pts in(inside,far):
        words.append(24<<24|len(co));co+=struct.pack('<I',0x01000000|len(pts))+b''.join(struct.pack('<II',*p)for p in pts)
    ro=struct.pack('<HI',1,0)+struct.pack('<IH',196608,len(programme))+programme
    rec=parts.F.pack(321,0,0,0,0,42,3,0,0,0)
    return rec,struct.pack('<III',2,*words),bytes(co),ro,(15,0,0)


def test_exactly_one_terminal_separator_is_semantically_eligible():
    for command in(b'AC(TEST)',b'AP(TEST)',b'LS(SOLD,1,TEST)',b'LS(DASH,2,TEST)'):
        assert parts.eligible(3,[(196608,command)])
        assert parts.eligible(3,[(196608,command+b';')])
        assert not parts.eligible(3,[(196608,command+b';;')])
        assert not parts.eligible(3,[(196608,command+b';;'+command)])
    assert not parts.eligible(2,[(196608,b'AC(TEST);')])
    assert not parts.eligible(3,[(196608,b'SY(TEST);')])
    assert not parts.eligible(3,[(196608,b'AC(TEST);'),(65682,struct.pack('<dd',.2,.3))])


def test_partial_full_elements_proved_through29_with_constant_endpoint_work():
    args=source_fixture();result=parts.local_whole_parts(*args)
    row,=result['receipts'];removed,=row['removedParts']
    assert row['retainedPartOrdinals']==[0]and row['originalParts']==2
    assert removed['originalSpanSHA256']==parts.sha(args[2][36:])
    assert [p['level']for p in removed['allDescendantExclusion']]==list(range(15,30))
    assert removed['allDescendantExclusion'][-1]['axisTranslations']==16384
    assert sum(p['actualScalarEvaluations']for p in removed['allDescendantExclusion'])==60
    assert all(p['everyDescendantTranslationCoveredByInterval']and p['signedConversionIntermediatesSafe']for p in removed['allDescendantExclusion'])
    assert result['coordinateBytesChanged']is False and result['renderBytesChanged']is False
    assert result['featureBytes'][:8]+result['featureBytes'][12:]==args[0][:8]+args[0][12:]


def test_far_world_part_is_retained_when_high_zoom_would_overflow_signed32():
    args=source_fixture(far_world=True)
    assert parts.local_whole_parts(*args)['receipts']==[]
    # It is geometrically far, but its old max21 predicate alone is inadequate.
    assert parts.local_whole_parts(*args,max_level=21)['receipts']
    points=[(parts.Q//2,parts.Q//2),(parts.Q//2+100,parts.Q//2+100)]
    assert parts.projection_safety(points,(15,0,0),max_level=21)is not None
    assert parts.projection_safety(points,(15,0,0))is None


def test_whole_owner_rejects_high_zoom_overflow_but_accepts_safe_whole_ring():
    unit=parts.Q//32768
    near=[(2*unit,2*unit),(2*unit+100,2*unit),(2*unit+100,2*unit+100),(2*unit,2*unit)]
    cert=empty.certify(3,[b'AC(TEST);'],[dict(partOrdinal=0,usage=24,rootFlag=1,points=near)],(15,0,0))
    assert cert is not None and cert['projectionErrorBounds'][-1]['level']==29
    assert cert['parts'][0]['nativeProjectionSafety'][-1]['level']==29
    far=[(x+parts.Q//2,y+parts.Q//2)for x,y in near]
    assert empty.certify(3,[b'AC(TEST);'],[dict(partOrdinal=0,usage=24,rootFlag=1,points=far)],(15,0,0))is None


@pytest.mark.parametrize('value',[float(1<<31),float(-(1<<31)-256),float('inf'),float('-inf')])
def test_checked_qround_does_not_wrap_or_simulate_undefined_int_conversion(value):
    assert parts.checked_qt_round(value)is None


def test_negative_half_ties_follow_existing_qt_expression():
    for value in(-17.5,-2.5,-1.5,-.5,.5,1.5,17.5):
        assert parts.checked_qt_round(value)==parts.qt_round(value)
    assert parts.checked_qt_round(-1.5)==-1


def test_interval_encloses_actual_bounded_descendant_translations():
    # Exhaust only tiny invented low-level domains, never14k high-level origins.
    tile=(3,4,4);unit=parts.Q//8
    points=[(4*unit-137,4*unit+83),(5*unit+103,5*unit-59)]
    proof=parts.projection_safety(points,tile,max_level=7)
    assert proof is not None
    for row in proof:
        level=row['level'];scale=row['axisTranslations'];bounds=row['parentRelativeIntegerProjectionBounds']
        for x,y in points:
            for axis,u in enumerate((x,y)):
                base=tile[axis+1]*scale
                for shift in range(scale):
                    actual=parts.qt_round(parts.f32(((2.**level)*(u/parts.Q)-(base+shift))*256.))+256*shift
                    assert bounds[axis]<=actual<=bounds[axis+2]


def test_too_close_to_guard_is_kept_without_a_phase_sample_exception():
    unit=parts.Q//32768
    # Whole far part is only1Root unit past the32-sourcepixel boundary. The
    # arithmetic/stroke support certificate deliberately refuses to trim it.
    edge=(288*parts.Q)//(256*32768)+1
    points=[(edge,unit//4),(edge+2,unit//4),(edge+2,unit//2),(edge,unit//4)]
    args=list(source_fixture());co=bytearray(args[2][:36]);co+=struct.pack('<I',0x01000004)+b''.join(struct.pack('<II',*p)for p in points);args[2]=bytes(co)
    assert parts.local_whole_parts(*args)['receipts']==[]


def test_production_default_scope_keeps_all_line_and_non_ac_candidates_unchanged():
    from no_chart_converter.rx5.locality_apply import _scope_allows
    assert _scope_allows(3,[(196608,b'AC(TEST);'),(196609,b'AC(OTHER);')],'pure_ac_fill')
    for kind,contexts in[(2,[(196608,b'LS(SOLD,1,TEST);')]),
                         (3,[(196608,b'AC(TEST);LS(SOLD,1,TEST);')]),
                         (3,[(196608,b'AP(TEST);')]),
                         (3,[(777,b'AC(TEST);')]),
                         (3,[(196608,b'AC(TEST);'),(65682,struct.pack('<dd',.2,.3))])]:
        assert not _scope_allows(kind,contexts,'pure_ac_fill')
    assert _scope_allows(2,[(196608,b'LS(SOLD,1,TEST);')],'reviewed_kernels')


def test_default_plan_retains_real_proposed_ls_far_part_and_its_original_index():
    from no_chart_converter.rx5.locality_apply import prepare_scoped_plan
    from no_chart_converter.rx5.header import new_plain_vector_header
    from no_chart_converter.rx5.wire import AuthoredBlock,assemble,HEADER_SLOTS
    rec,ix,co,ro,tile=source_fixture(b'LS(SOLD,1,TEST);')
    authored=[AuthoredBlock('i',0x1101,0,b'',ix),AuthoredBlock('c',0x1102,0,b'',co),
              AuthoredBlock('r',0x1103,0,b'',ro),AuthoredBlock('a',0x1104,0,b'',bytes(14))]
    for level in range(15):
        authored.append(AuthoredBlock('n'+str(level),0x6000+level,1,bytes(32),b'',
            references=tuple((q*8,'n'+str(level+1)if q==0 else None)for q in range(4))))
    authored.append(AuthoredBlock('n15',0x600f,3,bytes(64),rec,
        references=tuple((q*8,None)for q in range(4))+tuple((32+q*8,k)for q,k in enumerate(('i','c','r','a')))))
    raw,_=assemble(new_plain_vector_header(bytes(range(16))),authored,{k:('n0'if k==160 else None)for k in HEADER_SLOTS})
    default=prepare_scoped_plan(raw,[tile])
    proposed=prepare_scoped_plan(raw,[tile],command_scope='reviewed_kernels')
    row=default['rows'][0]['plan']
    assert default['commandScope']=='pure_ac_fill'
    assert row['totals']['partsRemoved']==0 and row['owners'][0]['keptOriginalPartOrdinals']==[0,1]
    assert row['compactedBuffers']['features']['sha256']==parts.sha(rec)
    assert row['compactedBuffers']['index']['sha256']==parts.sha(ix)
    assert row['compactedBuffers']['coordinates']['sha256']==parts.sha(co)
    assert proposed['rows'][0]['plan']['totals']['partsRemoved']==1
    assert default['rows'][0]['planSHA256']!=proposed['rows'][0]['planSHA256']
