"""Own explicit whole-map metadata grouping with intact physical layer blocks.

No geometry union, style/presence/range normalization, native execution or IO.
Repeated physical LayerIDs remain in sequence. Their native equivalence is
pending; parser/writer acceptance alone does not qualify original rendering.
"""
from copy import deepcopy
import hashlib,json
from .atlas import atlas_from_spec

COMPLETE_MAP_METADATA_POLICY='COMPLETE_MAP_METADATA_ORDERED_PHYSICAL_LAYER_CONCATENATION/1'
COMPLETE_MAP_METADATA_FORMAT='no5-own-complete-map-metadata-group/1'
MAX_COMPLETE_MAP_INPUTS=2048  # Explicit own work cap, not a feature/KIND/format limit.

def _complete_need(value,message):
 if not value:raise ValueError(message)
def _complete_canonical(value):return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=False,allow_nan=False).encode()
def _complete_digest(value):return hashlib.sha256(_complete_canonical(value)).hexdigest()
def _complete_metadata(map_document):return {k:deepcopy(v)for k,v in map_document.items()if k!='layers'}

def _complete_group_content(maps,ordered_bindings,source_binding,*,policy,expected_maps_sha256,expected_bindings_sha256,max_input_maps):
 _complete_need(type(policy)is str and policy==COMPLETE_MAP_METADATA_POLICY,'EXPLICIT_COMPLETE_MAP_METADATA_POLICY')
 _complete_need(type(max_input_maps)is int and 1<=max_input_maps<=MAX_COMPLETE_MAP_INPUTS,'EXPLICIT_INPUT_MAP_WORK_CAP')
 _complete_need(type(maps)is list and 1<=len(maps)<=max_input_maps and _complete_digest(maps)==expected_maps_sha256,'BOUND_COMPLETE_ORDERED_MAPS')
 _complete_need(type(ordered_bindings)is list and len(ordered_bindings)==len(maps)and type(source_binding)is dict and source_binding,'COMPLETE_ORDERED_SOURCE_BINDINGS')
 _complete_need(_complete_digest(dict(orderedBindings=ordered_bindings,sourceBinding=source_binding))==expected_bindings_sha256,'EXACT_EXTERNAL_BINDING_HASH')
 common=_complete_metadata(maps[0]);layers=[];origins=[];ids=set();total_features=0;cell=None
 for i,(m,binding)in enumerate(zip(maps,ordered_bindings)):
  _complete_need(type(m)is dict and m.get('format')=='no5-own-plain-generic-attribute-batch/1'and _complete_metadata(m)==common,'COMPLETE_MAP_METADATA_MUST_BE_IDENTICAL')
  _complete_need(type(binding)is dict and set(binding)=={'inputOrdinal','cellId','mapValueSHA256','sourceReference'}and type(binding['inputOrdinal'])is int and binding['inputOrdinal']==i and type(binding['cellId'])is str and binding['cellId']and _complete_digest(m)==binding['mapValueSHA256']and type(binding['sourceReference'])is dict and binding['sourceReference'],'EXACT_ORDERED_MAP_BINDING')
  if cell is None:cell=binding['cellId']
  _complete_need(binding['cellId']==cell,'ONE_ORIGINAL_SOURCE_CELL_PER_GROUP')
  before=_complete_digest(m);atlas_from_spec(dict(format='no5-own-plain-atlas/1',maps=[m]));_complete_need(_complete_digest(m)==before,'PUBLIC_PARSER_MUST_NOT_MUTATE_INPUT_MAP')
  first=len(layers);feature_rows=[]
  for li,layer in enumerate(m['layers']):
   for fi,feature in enumerate(layer['features']):
    sid=feature['source_id'];_complete_need(type(sid)is str and sid and sid not in ids,'NO_FEATURE_ID_RENAME_OR_ALIAS');ids.add(sid)
    feature_rows.append(dict(sourceFeatureId=sid,oldLayerOrdinal=li,oldFeatureOrdinal=fi,newLayerOrdinal=first+li,newFeatureOrdinal=fi,featureValueSHA256=_complete_digest(feature)));total_features+=1
   layers.append(deepcopy(layer))
  origins.append(dict(inputOrdinal=i,firstOutputLayerOrdinal=first,physicalLayerCount=len(m['layers']),completeInputMapSHA256=_complete_digest(m),orderedBinding=deepcopy(binding),features=feature_rows))
 out=deepcopy(common);out['layers']=layers;document=dict(format='no5-own-plain-atlas/1',maps=[out]);before=_complete_digest(document);atlas_from_spec(document);_complete_need(_complete_digest(document)==before,'PUBLIC_PARSER_MUST_NOT_MUTATE_GROUPED_DOCUMENT')
 ledger=dict(format='no5-complete-map-metadata-origin-ledger/1',policy=policy,sourceBinding=deepcopy(source_binding),inputMapsSHA256=expected_maps_sha256,inputBindingsSHA256=expected_bindings_sha256,commonMapMetadataSHA256=_complete_digest(common),cellId=cell,origins=origins,inputMapCount=len(maps),outputMapCount=1,physicalLayerRecords=len(layers),features=total_features,explicitInputMapWorkCap=max_input_maps,physicalLayerPolicy='COPY_EVERY_COMPLETE_LAYER_BLOCK_IN_INPUT_ORDER_INCLUDING_A_B_A',geometryUnions=0,sourceOrAttributeRewrites=0)
 return dict(format=COMPLETE_MAP_METADATA_FORMAT,policy=policy,document=document,sourceLedger=ledger,derivedIdentity=dict(policy=COMPLETE_MAP_METADATA_POLICY,completeOrderedMapsSHA256=expected_maps_sha256,orderedBindingsSHA256=expected_bindings_sha256),nativeQualification='PENDING_REPEATED_PHYSICAL_LAYER_IDS_AND_NORMAL_NATIVE_ADMISSION',nativeEquivalent=False)

def restore_input_maps(result):
 """Invert only physical block concatenation; check every complete map hash."""
 _complete_need(type(result)is dict and result.get('format')==COMPLETE_MAP_METADATA_FORMAT,'EXACT_GROUP_RESULT')
 sealed=deepcopy(result);seal=sealed.pop('resultSHA256');_complete_need(type(seal)is str and _complete_digest(sealed)==seal,'EXACT_FINAL_GROUP_ENVELOPE_SEAL')
 document=result['document'];_complete_need(document['format']=='no5-own-plain-atlas/1'and len(document['maps'])==1,'ONE_GROUPED_OUTPUT_MAP')
 m=document['maps'][0];common=_complete_metadata(m);ledger=result['sourceLedger'];_complete_need(_complete_digest(common)==ledger['commonMapMetadataSHA256'],'LITERAL_COMMON_MAP_METADATA')
 restored=[];at=0
 for i,origin in enumerate(ledger['origins']):
  count=origin['physicalLayerCount'];_complete_need(type(origin['inputOrdinal'])is int and origin['inputOrdinal']==i and type(count)is int and count>=1 and type(origin['firstOutputLayerOrdinal'])is int and origin['firstOutputLayerOrdinal']==at,'CONTIGUOUS_COMPLETE_PHYSICAL_LAYER_BLOCKS')
  old=deepcopy(common);old['layers']=deepcopy(m['layers'][at:at+count]);_complete_need(len(old['layers'])==count and _complete_digest(old)==origin['completeInputMapSHA256'],'EXACT_COMPLETE_INPUT_MAP_INVERSE');restored.append(old);at+=count
 _complete_need(at==len(m['layers'])and len(restored)==ledger['inputMapCount']and _complete_digest(restored)==ledger['inputMapsSHA256'],'NO_ADDED_MISSING_OR_REORDERED_PHYSICAL_LAYERS')
 return restored

def verify_complete_map_metadata_group(result,maps,ordered_bindings,source_binding,*,policy,expected_maps_sha256,expected_bindings_sha256,max_input_maps=MAX_COMPLETE_MAP_INPUTS):
 expected=_complete_group_content(maps,ordered_bindings,source_binding,policy=policy,expected_maps_sha256=expected_maps_sha256,expected_bindings_sha256=expected_bindings_sha256,max_input_maps=max_input_maps)
 actual=deepcopy(result);seal=actual.pop('resultSHA256');_complete_need(type(seal)is str and _complete_digest(actual)==seal and actual==expected,'SOURCE_BOUND_COMPLETE_GROUP_ENVELOPE_AND_CONTENT')
 _complete_need(restore_input_maps(result)==maps,'EXACT_ALL_ORIGINAL_PHYSICAL_LAYERS_AND_FEATURES_INVERSE')
 return dict(status='PASS_EXACT_LAYER_BLOCK_CONCATENATION',inputMaps=len(maps),features=expected['sourceLedger']['features'],nativeEquivalent=False)

def group_complete_map_metadata(maps,ordered_bindings,source_binding,*,policy,expected_maps_sha256,expected_bindings_sha256,max_input_maps=MAX_COMPLETE_MAP_INPUTS):
 """Merge only identical whole-map metadata; every layer record stays intact."""
 out=_complete_group_content(maps,ordered_bindings,source_binding,policy=policy,expected_maps_sha256=expected_maps_sha256,expected_bindings_sha256=expected_bindings_sha256,max_input_maps=max_input_maps)
 out['resultSHA256']=_complete_digest(out)
 _complete_need(restore_input_maps(out)==maps,'EXACT_GROUP_BLOCK_INVERSE_BEFORE_RETURN')
 return out
