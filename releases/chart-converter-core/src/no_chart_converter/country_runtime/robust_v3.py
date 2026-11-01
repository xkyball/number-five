from . import robust_v2 as old

"""Additive actual-call measured Kind3 fill continuation; frozen V2 unchanged.

Root authorized the same conditional pixel-model policy for new actual calls.
Primary V2 .1 authoring geometry policy stays first. Only its own finite
conversion refusals permit a freshly measured WHOLE filled-region fallback.
"""

from pathlib import Path

import hashlib, sys

from shapely import set_precision

from shapely.ops import unary_union

from shapely.affinity import translate

TRIGGERS={'ROBUST_GRID_HOLE_OR_POINT1_AUTHORED_PIXEL_CONTINUATION_REQUIRED',
          'FINITE_COMPLETE_LATTICE_ROW_BUDGET','FINITE_HOLELESS_LATTICE_SCOPE',
          'NO_POSITIVE_INTEGER_COMPONENT','UNREPRESENTABLE_COMPONENT_QROUND_POINTS_NOT_COLLINEAR',
          'EXACT_CONSTRAINED_COMPONENT_PARTITION','LATTICE_HULL_CONSERVATIVE'}

class RobustClipper(old.RobustClipper):
    def clip_polygon(self,polygon,cell_id):
        before=len(self.events)
        try:return super().clip_polygon(polygon,cell_id)
        except ValueError as error:
            if str(error)not in TRIGGERS:raise
            trigger=str(error)
        old.need(polygon.is_valid and self.owner_binding and self.owner_binding['cellId']==cell_id,'VALID_ORIGINAL_BOUND_KIND3_FILL')
        reference=unary_union(old.parts(polygon.intersection(self.masks[cell_id]),'Polygon'))
        old.need(reference.is_valid,'VALID_ACTUAL_ORIGINAL_FLOAT_FILL_CLIP')
        variants=((0,0),(-.5,-.5),(-.5,.5),(.5,-.5),(.5,.5));attempts=[];selected=None;proof=None
        for dx,dy in variants:
            candidate=set_precision(set_precision(translate(reference,dx,dy),1,mode='valid_output'),0)
            if not candidate.is_valid:
                attempts.append(dict(phase=[dx,dy],valid=False));continue
            try:receipt=old.projected_model_equal(reference,candidate,self)
            except ValueError as error:
                if str(error)!='AUTHORING_WHOLE_FILLED_PIXEL_MODEL_DIFFERENCE':raise
                attempts.append(dict(phase=[dx,dy],valid=True,modelEqual=False));continue
            selected=candidate;proof=receipt;proof['rootQuantizationPhaseChoice']=[dx,dy];break
        if selected is None:
            error=ValueError('ACTUAL_KIND3_WHOLE_PIXEL_MODEL_CONVERSION_REQUIRED')
            error.receipt=dict(ownerBinding=self.owner_binding,trigger=trigger,attempts=attempts,
                referenceFloatWkbHex=reference.wkb.hex(),referenceFloatWkbSHA256=old.sha(reference),
                authoredEpoch=self.level,authoredLastLevel=self.epoch_last_level,rootOrigin=list(self.origin))
            raise error
        hd=reference.boundary.hausdorff_distance(selected.boundary)if not selected.is_empty else None
        event=dict(policy='own-actual-kind3-measured-whole-filled-pixel-continuation/1',trigger=trigger,
            ownerBinding=self.owner_binding,actualOriginalGeometryInput=self.actual_original_geometry_input,
            referenceFloatWkbHex=reference.wkb.hex(),referenceFloatWkbSHA256=old.sha(reference),
            actualIntegerOutputWkbHex=selected.wkb.hex(),actualIntegerOutputWkbSHA256=old.sha(selected),
            pixelModelReceipt=proof,signedAddedAreaRootUnits2=selected.difference(reference).area,
            signedLostAreaRootUnits2=reference.difference(selected).area,
            discreteBoundaryHausdorffRootUnits=hd,actualBoundaryAuthoringPixels=hd*self.scale if hd is not None else None,
            globalBoundaryPoint1Claim=False,nativeQRegionAAOrA97EqualityClaim=False,
            holesBefore=sum(len(p.interiors)for p in old.parts(reference,'Polygon')),
            holesAfter=sum(len(p.interiors)for p in old.parts(selected,'Polygon')),
            positiveFloatingClipWasOmitted=reference.area>0 and selected.is_empty,
            noU32UnrepresentabilityOrNoPositiveComponentRemovedClaim=True,
            disposition='PIXEL_GRID_MODEL_EMPTY_DISPLAY'if selected.is_empty else proof['disposition'],
            priorIncompleteComponentAttemptEvents=len(self.events)-before,
            additionalLS_LC_BoundaryStrokeImpactNotProven=True,originalQueriesDepthXYZAndCoverageUnchanged=True)
        # Earlier component attempts are diagnostic, never admitted output.
        self.events.append(event);self.reference=reference;self.emitted=selected;self.pixel_model_fallback_used=True
        return selected,event

def wrap_clipper(base,*,epoch_last_level=None,tile_size=256,owner_binding=None):
    return RobustClipper(base,epoch_last_level=epoch_last_level,tile_size=tile_size,owner_binding=owner_binding)

def clip_owner(co,ix,record,*,cell_id,clipper,source_tile=(0,0,0),coordinate_format=2,epoch_last_level=None,owner_binding=None):
    if not isinstance(clipper,RobustClipper):
        clipper=wrap_clipper(clipper,epoch_last_level=epoch_last_level,owner_binding=owner_binding)
    old.need(clipper.owner_binding and clipper.owner_binding['cellId']==cell_id,'ACTUAL_SOURCE_CELL_BOUND')
    old.need(int.from_bytes(record[:8],'little')==clipper.owner_binding['uid'],'ACTUAL_SOURCE_UID_BOUND')
    clipper.actual_original_geometry_input=dict(coordinateBytes=len(co),coordinateSHA256=hashlib.sha256(co).hexdigest(),
        indexBytes=len(ix),indexSHA256=hashlib.sha256(ix).hexdigest(),record32Hex=record.hex(),
        sourceOwnerOrdinal=clipper.owner_binding['sourceOwnerOrdinal'],kind=record[28])
    return old.clip_owner(co,ix,record,cell_id=cell_id,clipper=clipper,source_tile=source_tile,
        coordinate_format=coordinate_format,epoch_last_level=epoch_last_level,owner_binding=owner_binding)

validate_final_owner=old.validate_final_owner
