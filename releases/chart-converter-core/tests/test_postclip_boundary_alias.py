"""Five small invented host controls; no C imports, chart, library or native I/O."""
import copy
import struct
import unittest

from no_chart_converter.country_runtime import postclip_alias as p


LIMITS = p.Limits(16, 100, 4096, 1024)


def frame(entries, query=77):
    return struct.pack('<HI', len(entries), query) + b''.join(
        struct.pack('<IH', k, len(v))+v for k,v in sorted(entries.items()))


def element(points):
    raw = struct.pack('<I', 0x01000000 | len(points)) + b''.join(
        struct.pack('<II', *point) for point in points)
    bbox = (min(x for x,y in points),min(y for x,y in points),
            max(x for x,y in points),max(y for x,y in points))
    return (16, raw, bbox, points)


def fixture(programme=b'LC(OWN00001);', extra=None):
    uid, primary = 0x80000011, 17
    record = p.F.pack(uid,0,100,7,0,120,2,0,0,0)
    programmes = {k:programme for k in p.DRAW}
    if extra:
        programmes.update(extra)
    deriv = dict(originalSourceKey='1'*64, featureSourceDigest='2'*64,
                 profileSHA256='3'*64, sourceId='OWN-INVENTED:f17', primaryUID=primary,
                 authoredUID=uid, role='source_boundary_line',splitPolicy=p.SPLIT_POLICY,
                 programmes={str(k):v.hex()for k,v in programmes.items()})
    sb = dict(uid=uid,kind=2,cellId='OWN-INVENTED',sourceOwnerOrdinal=2,
              sourceOriginalOwnerOrdinal=0,sourceFeatureId=17,
              sourceId='OWN-INVENTED:f17',featureSourceDigest='2'*64,
              originalRecord32Hex=record.hex(),
              primarySourceRelationship=dict(role='source_boundary_line',primaryUID=primary,
                 derivedUID=uid,derivedFromUID=primary,featureSourceDigest='2'*64,
                 originalSourceId='OWN-INVENTED:f17',queryAttrsAliasPrimary=True,
                 originalSourceFeatureId=17,sourceOriginalOwnerOrdinal=0,
                 nativeSourceOwnerOrdinal=2),
              sourceGeometryReceipt=dict(derivedBoundaryLineCarrier=True,
                 sourceGeometryKind='area',geometryRepair=False),
              resolution=dict(sourceRoleDerivation=deriv,
                 selected=dict(effectiveSourceKeySHA256=p.sha(p.canonical(deriv)))))
    owner = dict(uid=uid,cellId='OWN-INVENTED',sourceOwnerOrdinal=2,record=record,
                 role='SOURCE_WINNER_DETAIL',sourceBinding=sb,
                 postclipContext=dict(stage='AFTER_CLIP_AND_FINAL_VALIDATION',
                    validationPassed=True,requestedLevel=5,authoringLastLevel=7))
    outer = [(0,0),(20,0),(20,20),(0,20),(0,0)]
    hole = [(5,5),(5,15),(15,15),(15,5),(5,5)]
    return owner,[element(outer),element(hole)],dict(offset=100,raw=frame(programmes)),sb


def apply(f, **kw):
    return p.separate_boundary_runs(*f, policy=p.POLICY,limits=kw.get('limits',LIMITS))


def runs(elements):
    result, pending = [], []
    for usage, raw, bbox, points in elements:
        if usage == 8:
            if pending:result.append(pending)
            pending=[]
        else:pending.extend(points)
    if pending:result.append(pending)
    return result


def edges(parts):
    return [(a,b)for part in parts for a,b in zip(part,part[1:])]


def invented_existing_writer(rows):
    """Small byte-buffer illustration of the existing writer, not a chart builder."""
    arena,pool,records,dedup = bytearray(),bytearray(),[],{}
    for record,elements in rows:
        fields=list(p.F.unpack(record));fields[1]=len(pool);words=[]
        for usage,raw,bbox,pts in elements:
            if raw not in dedup:dedup[raw]=len(arena);arena.extend(raw)
            words.append((usage<<24)|dedup[raw])
        pool.extend(struct.pack('<I',len(words))+struct.pack('<'+'I'*len(words),*words))
        records.append(p.F.pack(*fields))
    return arena,pool,records


class Controls(unittest.TestCase):
    def test_two_rings_hole_and_visible_query_list(self):
        f=fixture();owner,before,ro,sb=f
        source_copy=copy.deepcopy(f)
        after,ledger=apply(f)
        self.assertEqual([x[0]for x in after],[16,8,16])
        self.assertIs(after[0],before[0]);self.assertIs(after[2],before[1])
        for j in (1,2,3):self.assertIs(after[1][j],before[1][j])
        self.assertEqual([e for e in after if e[0]==16],before)
        self.assertEqual(len(edges(runs(before))),9)
        self.assertEqual(edges(runs(after)),edges([e[3]for e in before]))
        self.assertEqual(len(edges(runs(after))),8)
        self.assertEqual((ledger['beforeRawParts'],ledger['afterRawParts']),(2,3))
        self.assertEqual((ledger['beforeRawPoints'],ledger['afterRawPoints']),(10,15))
        self.assertEqual(ledger['newCOBodies'],0)
        self.assertEqual(f,source_copy)
        self.assertFalse(ledger['centroidOrGeneralQueryParity'])

    def test_mixed_anchor_unknown_ls_only_and_default_keep(self):
        cases=[fixture(b'LC(OWN00001);SY(OWN00002);'),
               fixture(extra={0x10092:bytes(16)}),
               fixture(b'CS(OWNPROC);'),fixture(b'LC(X)SY(Y);'),
               fixture(b'LS(SOLD,1,OWNCL);')]
        for f in cases:
            with self.subTest(programme=f[2]['raw']):
                result,ledger=apply(f)
                self.assertIs(result,f[1]);self.assertEqual(ledger['status'],'KEEP')
        f=fixture();result,ledger=p.separate_boundary_runs(*f,limits=LIMITS)
        self.assertIs(result,f[1]);self.assertEqual(ledger['reason'],'DEFAULT_OFF')
        f=fixture();f[3]['primarySourceRelationship']=None
        result,ledger=apply(f)
        self.assertIs(result,f[1]);self.assertEqual(ledger['status'],'KEEP')

    def test_positive_source_binding_and_actual_both_contexts(self):
        f=fixture();f[3]['featureSourceDigest']='4'*64
        with self.assertRaisesRegex(p.Refusal,'DIGEST'):apply(f)
        f=fixture();f[3]['resolution']['selected']['effectiveSourceKeySHA256']='0'*64
        with self.assertRaisesRegex(p.Refusal,'STYLE_KEY'):apply(f)
        f=fixture();f[2]['raw']=frame({0x30000:b'LC(OWN00001);',0x30001:b'LC(OWN00003);'})
        with self.assertRaisesRegex(p.Refusal,'BOTH_RO_CONTEXTS'):apply(f)
        f=fixture();f[2]['offset']=99
        with self.assertRaisesRegex(p.Refusal,'RO_OFFSET'):apply(f)
        f=fixture();f[3]['primarySourceRelationship']['derivedUID']=float(f[0]['uid'])
        with self.assertRaisesRegex(p.Refusal,'UID_CHAIN'):apply(f)
        f=fixture();f[3]['primarySourceRelationship']['sourceOriginalOwnerOrdinal']=False
        with self.assertRaisesRegex(p.Refusal,'ORDINAL'):apply(f)

    def test_late_stage_explicit_limits_and_repeat_refuse(self):
        f=fixture();f[0]['postclipContext']['validationPassed']=False
        with self.assertRaisesRegex(p.Refusal,'POSTCLIP'):apply(f)
        with self.assertRaisesRegex(p.Refusal,'STRICT_POSITIVE'):
            apply(fixture(),limits=p.Limits(True,100,4096,1024))
        with self.assertRaisesRegex(p.Refusal,'ALIAS_POINT'):
            apply(fixture(),limits=p.Limits(16,12,4096,1024))
        with self.assertRaisesRegex(p.Refusal,'OUTPUT_INDEX'):
            apply(fixture(),limits=p.Limits(16,100,4096,12))
        with self.assertRaisesRegex(p.Refusal,'ALIAS_FRAME'):
            apply(fixture(),limits=p.Limits(16,100,100,1024))
        f=fixture();after,_=apply(f);f=(f[0],after,f[2],f[3])
        with self.assertRaisesRegex(p.Refusal,'DOUBLE_APPLICATION'):apply(f)

    def test_actual_arena_alias_offset_and_all_fi_rebases(self):
        f=fixture();after,ledger=apply(f)
        other=[(p.F.pack(100+i,0,200+i,8,0,1,2,0,0,0),[element([(30+i,30),(40+i,40)])])for i in range(2)]
        a_arena,a_i,a_fi=invented_existing_writer([(f[0]['record'],f[1])]+other)
        b_arena,b_i,b_fi=invented_existing_writer([(f[0]['record'],after)]+other)
        self.assertEqual(a_arena,b_arena)
        self.assertEqual(len(b_i)-len(a_i),4)
        for i in range(3):
            self.assertEqual(a_fi[i][:8]+a_fi[i][12:],b_fi[i][:8]+b_fi[i][12:])
            self.assertEqual(p.F.unpack(b_fi[i])[1]-p.F.unpack(a_fi[i])[1],0 if i==0 else 4)
        joined=p.join_serialized_owner(ledger,pre_emit_record=f[0]['record'],final_record=b_fi[0],
            index_pool=b_i,arena=b_arena,arena_name='OWN-INVENTED-ARENA',tile=[5,1,2])
        self.assertEqual(joined['indexBytes'],16)
        self.assertEqual(joined['postclipAliasLedgerSHA256'],p.sha(p.canonical(ledger)))
        self.assertEqual(joined['aliasOffsets'][0]['actualCOOffset'],len(f[1][0][1]))
        # Correct frame bytes at a DIFFERENT arena offset still fail the alias join.
        extra=len(b_arena);wrong_arena=b_arena+f[1][1][1];wrong_i=bytearray(b_i)
        struct.pack_into('<I',wrong_i,8,(8<<24)|extra)
        with self.assertRaisesRegex(p.Refusal,'MASK_NEXT_OFFSET'):
            p.join_serialized_owner(ledger,pre_emit_record=f[0]['record'],final_record=b_fi[0],
                index_pool=wrong_i,arena=wrong_arena,arena_name='OWN-INVENTED-ARENA',tile=[5,1,2])
        bad=bytearray(b_fi[0]);bad[12]^=1
        with self.assertRaisesRegex(p.Refusal,'FI_ONLY_INDEX'):
            p.join_serialized_owner(ledger,pre_emit_record=f[0]['record'],final_record=bytes(bad),
                index_pool=b_i,arena=b_arena,arena_name='OWN-INVENTED-ARENA',tile=[5,1,2])


if __name__=='__main__':unittest.main()
