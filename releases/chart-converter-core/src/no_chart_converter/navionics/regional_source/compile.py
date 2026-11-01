"""Retained-IR region to the existing public Plain writer, entirely in memory."""
import hashlib
import io

from no_chart_converter.navionics.atlas import (
    AtlasSpec, atlas_from_spec, atlas_work_profile_from_spec, plan_atlas, write_atlas,
    ATLAS_256_WORK_PROFILE,
)
from no_chart_converter.navionics.batch import plan_sha256

from .adapter import build_region
from .road_integration import mixed_complete_tile_spec


def compile_region(cell,request,*,maximum_plain_bytes,magvar_info205_qualification=None):
    if type(maximum_plain_bytes)is not int or not 1<=maximum_plain_bytes<=64*1024*1024:
        raise ValueError('EXPLICIT_FINITE_PLAIN_BYTE_WORK_BUDGET_REQUIRED')
    result=build_region(cell,request)
    from .magvar_portrayal import rewrite_region_magvar,POINT_RETAINED_POLICY
    has_magvar_area=any(f.get('class_code')==91 and f['kind']==24
        for m in result['document']['maps']for l in m['layers']for f in l['features'])
    has_magvar_line=any(f.get('class_code')==91 and f['kind']==32
        for m in result['document']['maps']for l in m['layers']for f in l['features'])
    if has_magvar_area and 'magvar_portrayal_policy'not in request:
        raise ValueError('MAGVAR_AREA91_WRONG_FILL_REQUIRES_EXPLICIT_PORTRAYAL_POLICY')
    if has_magvar_area or has_magvar_line:
        result=rewrite_region_magvar(result,cell,policy=request.get('magvar_portrayal_policy',POINT_RETAINED_POLICY),
            info205_qualification=magvar_info205_qualification)
    elif magvar_info205_qualification is not None:
        raise ValueError('UNUSED_MAGVAR_INFO205_QUALIFICATION')
    document=result['document']
    if len(document['maps'])>16:
        document.update(format='no5-own-plain-atlas-work-profile/1',work_profile='own_atlas256_host_work_profile_v1')
        profile=ATLAS_256_WORK_PROFILE
    else:profile=None
    if 'roadAuthoring'in result:
        by_map={}
        for occurrence in result['roadAuthoring']['emittedOccurrences']:
            by_map.setdefault(occurrence['mapOrdinal'],[]).append(occurrence['emittedId'])
        maps=[mixed_complete_tile_spec(m,approved_road_emitted_ids=by_map.get(i,[]),
            policy=result['roadAuthoring']['policy'])for i,m in enumerate(document['maps'])]
        spec=AtlasSpec(tuple(maps),work_profile=profile)
    elif profile is not None:spec=atlas_work_profile_from_spec(document)
    else:spec=atlas_from_spec(document)
    plan=plan_atlas(spec,max_output_bytes=maximum_plain_bytes)
    stream=io.BytesIO();emission=write_atlas(spec,stream,expected_plan_sha256=plan_sha256(plan),max_output_bytes=maximum_plain_bytes)
    plain=stream.getvalue()
    if len(plain)!=emission['bytesWritten']or hashlib.sha256(plain).hexdigest()!=emission['emittedStreamSHA256']:
        raise ValueError('PUBLIC_WRITER_IN_MEMORY_EXTENT_AND_SHA_MISMATCH')
    return dict(result,plan=plan,plain=plain,plainSHA256=emission['emittedStreamSHA256'],plainBytes=len(plain),
        sourceDataMustRemainOutsideRepository=True,OriginalProducerApplied=False,NativeProductReady=False)
