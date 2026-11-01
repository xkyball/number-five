"""Invented approval documents exercise binding/mutation only, not native visual proof."""
import hashlib
import json
import struct

import pytest

from fixtures import chart
from no_chart_converter.rx5.approvals import apply_allowlist
from no_chart_converter.rx5.pruning import native_tree
from no_chart_converter.rx5.wire import blocks


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def approval(tmp_path, raw, *, disposition='IDENTICAL_WITHIN_PROVED_CHILD_DOMAIN'):
    tree=native_tree(raw)
    by_tile={tile:at for at,tile in tree.items()}
    child=by_tile[(13,4096,4096)];parent=by_tile[(12,2048,2048)]
    evidence={}
    for name in ('visualCertificate','admissionCertificate','querySemanticReport'):
        path=tmp_path/(name+'.json')
        data=json.dumps({'kind':'SYNTHETIC_BINDING_TEST_ONLY','name':name}).encode()
        path.write_bytes(data)
        evidence[name]={'path':path.name,'bytes':len(data),'sha256':sha(data)}
    row={**evidence,'tile':[13,4096,4096],'approved':True,
         'sameWindowFillAndHaloApproved':True,'nonACFootprintsAndAdmissionApproved':True,
         'querySemanticDisposition':disposition,'nativeSourceGateDisposition':'OBSERVABLY_EQUIVALENT',
         'childNodeOffset':child,'parentNodeOffset':parent,'parentChildSlot':0,
         'expectedChildRefHex':struct.pack('<Q',child).hex(),
         'fallbackVectorNodeOffset':parent,'fallbackTile':[12,2048,2048]}
    document={'format':'no5-bound-visual-leaf-prune-allowlist/1',
              'status':'APPROVED_FOR_SCOPED_PHYSICAL_A97_TEST',
              'chart':{'bytes':len(raw),'sha256':sha(raw)},'rows':[row]}
    path=tmp_path/'allowlist.json';path.write_text(json.dumps(document))
    return path,sha(path.read_bytes())


def test_v23_allowlist_binds_proofs_and_complete_inverse(tmp_path):
    raw=chart(fallback=True)
    path,pin=approval(tmp_path,raw)
    output,report=apply_allowlist(raw,path,pin,title='SYNTHETIC V23')
    assert report['actualLeafPrunes']==1
    assert report['sourceTreeNodes']-report['outputTreeNodes']==1
    assert report['sourcePrefixInverseSHA256']==sha(raw)
    assert report['externalProofMathIndependentlyRecomputed'] is False
    chart_info=blocks(output)[struct.unpack_from('<Q',output,136)[0]].payload
    assert b'SYNTHETIC V23' in chart_info
    (tmp_path/'querySemanticReport.json').write_bytes(b'CHANGED')
    with pytest.raises(ValueError,match='EXACT_EXTERNAL_APPROVAL_EVIDENCE'):
        apply_allowlist(raw,path,pin)


def test_v23_refuses_query_loss_override_and_wrong_source(tmp_path):
    raw=chart(fallback=True)
    path,pin=approval(tmp_path,raw,disposition='DIFFERENT_EXPLICITLY_RECORDED_IN_ACCEPTED_REPORT')
    with pytest.raises(ValueError,match='NO_NAUTICAL_LOSS_OVERRIDE'):
        apply_allowlist(raw,path,pin)
    path,pin=approval(tmp_path,raw)
    with pytest.raises(ValueError,match='APPROVAL_BOUND_TO_EXACT_SOURCE'):
        apply_allowlist(raw+b'CHANGED',path,pin)
    with pytest.raises(ValueError,match='EXPLICIT_REVIEWED_ALLOWLIST_PIN'):
        apply_allowlist(raw,path,'0'*64)
