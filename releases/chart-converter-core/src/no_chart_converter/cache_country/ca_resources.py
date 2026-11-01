"""Own portable CA-to-native vector authoring kernel.

Reuses the reviewed source numeric and adaptive open-vector algorithms. This
module is stdlib-only and authors inert resource data. It does not execute OEM
code, rasterize, mutate a chart, or select a substitute for an unknown sector.
"""
from __future__ import annotations
import json
from decimal import Decimal
import hashlib
import math
import re
import struct

SOURCE_FORMAT = 'no5-source-ca-glsl-numeric-contract/1'
NUMERIC_POLICY = 'EXACT_DECIMAL_INTEGRAL_DIMENSIONS/1'
SOURCE_PI = 3.1415926535897931160
ROOT_Q = 2147483649
DIMENSIONS = {1:'outlineWidth',3:'arcWidth',6:'arcRadiusMillimetres',7:'sectorRadiusMillimetres'}
ANGLES = {4:'sectorStartDegrees',5:'sectorEndDegrees'}
NUMBER = re.compile(r'[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?\Z', re.ASCII)
SOURCE_LONG = re.compile(r'[ \t\r\n\v\f]*[+-]?\d+\Z', re.ASCII)

class Refusal(ValueError): pass

def need(ok, label):
    if not ok: raise Refusal(label)

def f32(value):
    need(type(value) in (int,float) and math.isfinite(value),'FINITE_NUMERIC_INPUT')
    try: result=struct.unpack('<f',struct.pack('<f',value))[0]
    except (OverflowError,struct.error): raise Refusal('FLOAT32_TRANSPORT_OVERFLOW') from None
    need(math.isfinite(result),'FINITE_FLOAT32_TRANSPORT')
    return result

def bits(value):
    return struct.unpack('<I',struct.pack('<f',value))[0]

def parse_original_ca(raw):
    need(type(raw) is bytes and 0<len(raw)<=65535,'OWN_BOUNDED_RAW_TOKEN')
    need(raw.startswith(b'CA(') and not any(c in raw for c in (b';',b'\x1f',b'\0')),'ONE_CA_TOKEN')
    try: body=raw[3:].decode('ascii')
    except UnicodeDecodeError: raise Refusal('ASCII_SOURCE_TOKEN') from None
    closing=body.endswith(')'); body=body[:-1] if closing else body
    parts=body.split(',');need(len(parts)==8 and all(parts),'EIGHT_EXACT_PARAMETERS')
    names={0:'outlineColorName',2:'arcColorName'}; numeric={}; port={}
    for i,name in names.items():
        need(re.fullmatch(r'[A-Z0-9]{5}',parts[i].strip()) is not None,'SOURCE_COLOR_NAME5')
    for i,name in {**DIMENSIONS,**ANGLES}.items():
        text=parts[i].strip();need(NUMBER.fullmatch(text) is not None,'FINITE_DECIMAL_SOURCE_NUMBER')
        value=Decimal(text);need(value.is_finite(),'FINITE_DECLARED_NUMBER')
        if i in DIMENSIONS:
            need(value>=0 and value==value.to_integral_value() and value<=0x7fffffff,'SUPPORTED_INTEGRAL_DIMENSION_PROFILE')
            numeric[name]=int(value)
            # Sourceport ToLong consumes the untrimmed token and refuses .0.
            literal=parts[i]+(')' if closing and i==7 else '')
            accepted=SOURCE_LONG.fullmatch(literal) is not None
            port[name]=dict(sourceToLongAccepted=accepted,sourceToLongResult=int(value) if accepted else None,
                            sourceResultUnprovedIfRejected=not accepted)
        else:
            value=float(value);need(math.isfinite(value),'FINITE_DECLARED_ANGLE')
            numeric[name]=value
    return dict(format=SOURCE_FORMAT,rawTokenHex=raw.hex(),rawTokenSHA256=hashlib.sha256(raw).hexdigest(),
                exactOriginalParameterLexemes=parts,originalClosingParenthesisPresent=closing,
                closingParenthesisSynthesized=False,numericPolicy=NUMERIC_POLICY,
                outlineColorName=parts[0].strip(),arcColorName=parts[2].strip(),**numeric,
                sourcePortLongConversions=port,
                sourcePortAllLongLexemesAccepted=all(v['sourceToLongAccepted'] for v in port.values()),
                sourceRuntimeExecuted=False,originalA97PaintAccepted=False)

def angle_oracle(parsed, rotation_radians):
    """Source 7346..7369: Double expression -> F32 branches -> F32 uniforms."""
    need(parsed.get('format')==SOURCE_FORMAT,'PARSED_CONTRACT')
    need(type(rotation_radians) in (float,int) and math.isfinite(rotation_radians),'FINITE_ROTATION_DOUBLE')
    rotation_degrees=(float(rotation_radians)*180.0)/SOURCE_PI
    raw1=f32(parsed['sectorStartDegrees']+rotation_degrees)
    raw2=f32(parsed['sectorEndDegrees']+rotation_degrees)
    sub1=raw1>360.0;sub2=raw2>360.0
    sr1=f32(raw1-360.0) if sub1 else raw1
    sr2=f32(raw2-360.0) if sub2 else raw2
    forward=sr2>sr1
    sb=sr1;se=sr2 if forward else f32(sr2+360)
    negative=sb<0.0 or se<0.0
    if negative:sb=f32(sb+360.0);se=f32(se+360.0)
    rad1=f32((sb*SOURCE_PI)/180.0);rad2=f32((se*SOURCE_PI)/180.0)
    leg1=f32(((parsed['sectorStartDegrees']-90)*SOURCE_PI)/180.0)
    leg2=f32(((parsed['sectorEndDegrees']-90)*SOURCE_PI)/180.0)
    leg1=f32(leg1+float(rotation_radians));leg2=f32(leg2+float(rotation_radians))
    return dict(startDegreesF32=sb,endDegreesF32=se,startRadiansF32=rad1,endRadiansF32=rad2,
                legBearingRadiansF32=[leg1,leg2],rotationRadiansDouble=float(rotation_radians),
                float32Bits={k:bits(v) for k,v in dict(rawStart=raw1,rawEnd=raw2,sr1=sr1,sr2=sr2,
                    startDegrees=sb,endDegrees=se,startRadians=rad1,endRadians=rad2,leg1=leg1,leg2=leg2).items()},
                branchReceipt=dict(startSubtracted360Once=sub1,endSubtracted360Once=sub2,
                                   endStrictlyGreater=forward,bothAdded360OnceForNegative=negative,
                                   moduloOperations=0,loopOperations=0),
                sourceVBOEquivalenceClaim=False,trigonometricPixelOutputClaim=False)

def evaluate_glsl(parsed, *, rotation_radians, pixels_per_mm, display_size_mm, view_scale_ppm, scamin):
    """Explicit declared-numeric profile + Source GLSL sizing/angle semantics.

The caller supplies actual or explicitly authored display inputs. No defaults
guess device DPI, source SCAMIN or image scale. Pixel output is not generated.
"""
    angle=angle_oracle(parsed,rotation_radians)
    ppmm=f32(pixels_per_mm);display=f32(display_size_mm)
    need(ppmm>0 and display>0 and type(view_scale_ppm) in (int,float) and math.isfinite(view_scale_ppm) and view_scale_ppm>0,'POSITIVE_EXPLICIT_DISPLAY_INPUTS')
    need(type(scamin) is int and -0x80000000<=scamin<=0x7fffffff,'EXPLICIT_SOURCE_SCAMIN_INT32')
    radius=parsed['arcRadiusMillimetres'];leg=parsed['sectorRadiusMillimetres'];width=parsed['arcWidth']
    need(radius>0 and width>0,'POSITIVE_RADIUS_AND_ARC_WIDTH_PROFILE')
    threshold=f32(display/10);cap=f32(radius)>threshold
    need(threshold>0,'NONZERO_FLOAT32_DISPLAY_THRESHOLD')
    factor=1.0
    if cap:
        factor=float(f32(f32(radius)/threshold))
        radius=math.trunc(radius/factor);leg=math.trunc(leg/factor);width=max(math.trunc(width/factor),1)
    rad=f32(f32(radius)*ppmm);leg_rad=f32(f32(leg)*ppmm);arc=f32(f32(width)*ppmm)
    large=display>200
    if large:arc=ppmm
    xscale=f32(1.0);unset=scamin>1e8
    if unset:
        radius_metres=f32(f32(f32(radius)*ppmm)/float(view_scale_ppm))
        need(radius_metres>0,'NONZERO_SOURCE_RADIUS_METRES')
        xscale=f32(1000.0/radius_metres);xscale=f32(max(.5,min(xscale,1.0)))
        rad=f32(rad*xscale);arc=f32(arc*xscale);arc=f32(min(arc,f32(rad/10)))
        leg_rad=f32(leg_rad*xscale)
    outline=f32(parsed['outlineWidth']);outer=f32(arc+outline)
    # Source7408ff configures int wxDash values; DrawDashLine11741ff scales
    # by pen width2 and clamps each length to4. This is not a dash mesh.
    configured=[math.trunc((3.6*ppmm)/3),math.trunc((1.8*ppmm)/3)]
    effective=[f32(max(f32(2*d),4.0)) for d in configured]
    return dict(**angle,radiusPx=rad,arcWidthPx=arc,outlineWidthPx=outline,outerWidthPx=outer,
                legRadiusPx=leg_rad,legWidthPx=2,legDashConfiguredPx=configured,legDashEffectivePx=effective,
                legDashProcessingLengthCapPx=2000,legDashLastEndpointRule='actual endpoint, as Source DrawDashLine',
                legDrawDashLineDerotatesViewport=abs(rotation_radians)>.01,
                legDrawDashLineFullGeometryOrTrigImplemented=False,
                arcColorName=parsed['arcColorName'],sourceOutlineColorName=parsed['outlineColorName'],
                outlineRGBA=[0,0,0,1],arcRGBARequiresActualPaletteBinding=True,
                inputProfile=dict(pixelsPerMMF32=ppmm,displaySizeMMF32=display,viewScalePPMDouble=float(view_scale_ppm),scaminInt32=scamin),
                sizingReceipt=dict(numericPolicy=NUMERIC_POLICY,displayRadiusCapApplied=cap,capFactorDouble=factor,
                                  radiusAfterIntegralCap=radius,sectorRadiusAfterIntegralCap=leg,arcWidthAfterIntegralCap=width,
                                  largeDisplayWidthOverride=large,unsetSCAMINScaleApplied=unset,xscaleF32=xscale),
                actualSourceToLongReturnClaim=False,originalA97PaintAccepted=False)


NATIVE_COORDINATE_FACTOR_BITS = 0x3D1AD42C
NATIVE_COORDINATE_FACTOR = struct.unpack('<f', struct.pack('<I', NATIVE_COORDINATE_FACTOR_BITS))[0]
FORMAT = 'no5-ca-to-native-vector-resource-compatibility/1'

class Refusal(ValueError):
    pass

def need(ok, label):
    if not ok:
        raise Refusal(label)

def f32(x):
    need(type(x) in (float, int) and math.isfinite(x), 'FINITE_FLOAT')
    try:
        y = struct.unpack('<f', struct.pack('<f', x))[0]
    except (OverflowError, struct.error):
        raise Refusal('F32_RANGE') from None
    need(math.isfinite(y), 'F32_FINITE')
    return y

def digest(raw):
    return hashlib.sha256(raw).hexdigest()

def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('ascii')

def coord(px):
    """Decimal token -> genuine QString.toFloat -> vmul.f32 factor model."""
    value = f32(px / NATIVE_COORDINATE_FACTOR)
    # Avoid relying on exponent syntax in this deliberately narrow fixture.
    # The same exact native Float32 value must survive the fixed decimal text.
    text = format(value, '.24f').rstrip('0').rstrip('.')
    if text in ('', '-0'):
        text = '0'
    need(re.fullmatch(r'-?\d+(?:\.\d+)?', text) is not None, 'FIXED_DECIMAL_SVCT_TOKEN')
    decoded = f32(f32(float(text)) * NATIVE_COORDINATE_FACTOR)
    need(f32(float(text)) == value, 'EXACT_F32_DECIMAL_ROUNDTRIP')
    return text, decoded

def point(x, y):
    a, ax = coord(x)
    b, by = coord(y)
    return a + ',' + b, [ax, by], math.hypot(ax - x, by - y)

def _arc_vertices(radius, start, end, max_error_px):
    """Chord sagitta + endpoint perturbation bounds the ideal centerline.

The cap pi on each angular step is essential for major arcs. Native Float32
vertices and textual round-trips are included in the perturbation term. This
bounds the centerline, not Qt caps/joins/AA, shader pixels or physical display.
"""
    sweep = end - start
    need(0 < sweep <= 2 * math.pi + 1e-6, 'SOURCE_SINGLE_TURN_ARC')
    need(radius > 0 and 0 < max_error_px <= 0.5, 'EXPLICIT_SMALL_ERROR_BUDGET')
    # Half the requested bound is reserved for Float32 coordinate transport.
    step = min(math.pi, 2 * math.acos(max(-1., 1. - min(max_error_px / 2, radius) / radius)))
    need(step > 0, 'REPRESENTABLE_CHORD_STEP')
    n = max(1, math.ceil(sweep / step))
    while True:
        need(n <= 100000, 'OWN_RESOURCE_VERTEX_BOUND')
        ideal = [[radius * math.sin(start + sweep * i / n), -radius * math.cos(start + sweep * i / n)] for i in range(n + 1)]
        values = [point(*p) for p in ideal]
        quantization = max(v[2] for v in values)
        sagitta = radius * (1 - math.cos(sweep / (2 * n)))
        total = sagitta + quantization
        if total <= max_error_px:
            return values, dict(segments=n, vertices=n+1,
                sourceStartRadiansF32=start, sourceEndRadiansF32=end,
                angleSweepRadians=end-start, maxRequestedCenterlineErrorPx=max_error_px,
                chordSagittaBoundPx=sagitta, maxNativeVertexTransportErrorPx=quantization,
                combinedCenterlineHausdorffBoundPx=total,
                proof='For each ideal arc interval <=pi, sagitta bounds arc/chord; linear interpolation of endpoint perturbations adds at most max vertex error.',
                nativeQtCapJoinAntialiasingOrPixelErrorBounded=False,
                arbitraryGlobalSegmentCount=False)
        # More vertices cannot cure a transport floor. Refuse rather than loop.
        need(quantization < max_error_px, 'NATIVE_F32_TRANSPORT_EXCEEDS_ERROR_BUDGET')
        n *= 2

def compile_resource(scope, evaluated, *, max_centerline_error_px, palette, resource_id):
    """Compile one actual saved Point/CA scope at one explicit source context.

Rotation zero is deliberately enforced: nonzero Source DrawDashLine derotates
around an external viewport center, which this resource fixture does not own.
The original CA bytes and all non-CA programme bytes remain in the receipt.
"""
    need(scope.get('kind') == 1 and scope.get('classCode') == 75, 'AUTHENTIC_LIGHT_POINT_SCOPE')
    need(evaluated.get('rotationRadiansDouble') == 0.0, 'ROTATION_ZERO_FIXTURE_ONLY')
    need(not evaluated.get('legDrawDashLineDerotatesViewport'), 'NO_UNBOUND_VIEWPORT_DEROTATION')
    need(type(resource_id) is int and 0 <= resource_id <= 99999, 'OWN_SYMB_ID_FIVE_DIGITS')
    need(type(max_centerline_error_px) in (int, float) and 0 < max_centerline_error_px <= .5, 'EXPLICIT_SMALL_ERROR_BUDGET')
    rgb = palette.get(evaluated['arcColorName'])
    need(type(rgb) is list and len(rgb) == 3 and all(type(v) is int and 0 <= v <= 255 for v in rgb), 'AUTHENTIC_ARC_PALETTE_RGB')
    identity = dict(source=scope, effectiveSourceDisplay=evaluated,
                    maxCenterlineErrorPx=max_centerline_error_px,
                    compiler=FORMAT, coordinateFactorBits=NATIVE_COORDINATE_FACTOR_BITS)
    name = 'N5' + digest(canonical(identity))[:6].upper()
    need(len(name) == 8 and re.fullmatch('[A-Z0-9]{8}', name), 'OWN_UNIQUE_NAME8')
    radius = float(evaluated['radiusPx'])
    width = float(evaluated['arcWidthPx'])
    outer = float(evaluated['outerWidthPx'])
    need(radius > 0 and 0 < width <= outer and math.isfinite(radius + width + outer), 'EFFECTIVE_RADIUS_WIDTHS')
    start = float(evaluated['startRadiansF32'])
    end = float(evaluated['endRadiansF32'])
    span_degrees = evaluated['endDegreesF32'] - evaluated['startDegreesF32']
    is_circle = span_degrees == 360.0
    points = []
    circles = []
    commands = []
    proofs = []
    def begin(color, pen_width):
        # SP/SW each flush a prior live path in the genuine CommandBuilder.
        commands.extend(['SP' + color, 'SW' + format(f32(pen_width), '.9g'), 'ST0'])
    def polyline(values):
        need(len(values) >= 2, 'PATH_NOT_POINT')
        commands.append('PU' + values[0][0])
        commands.append('PD' + ','.join(p[0] for p in values[1:]))
        points.extend(p[1] for p in values)
    if is_circle:
        token, actual = coord(radius)
        for color, pen in [('A', outer), ('B', width)]:
            begin(color, pen)
            commands.extend(['PU0,0', 'CI' + token])
            circles.append(dict(center=[0,0], radiusPx=actual, penWidthPx=f32(pen), colorAlias=color))
        proofs.append(dict(kind='native_CI_addEllipse', intendedRadiusPx=radius,
            nativeRadiusPx=actual, nativeRadiusTransportErrorPx=abs(actual-radius),
            fullCircleFromSourceDegreeSpan=span_degrees,
            QtEllipseImplementationAndPixelEquivalenceClaim=False))
    else:
        values, proof = _arc_vertices(radius, start, end, max_centerline_error_px)
        for color, pen in [('A', outer), ('B', width)]:
            begin(color, pen)
            polyline(values)
        proofs.append(dict(kind='own_adaptive_screen_PU_PD_arc', **proof))
    # Preserve the Source GLSL dash schedule at rotation zero. Native PU/PD
    # supplies independent open dashes; no world rings/area records are authored.
    leg_receipts = []
    leg_radius = float(evaluated['legRadiusPx'])
    if leg_radius > 0:
        draw, gap = [float(x) for x in evaluated['legDashEffectivePx']]
        need(draw >= 4 and gap >= 4 and evaluated['legWidthPx'] == 2, 'SOURCE_DASH_PARAMETERS')
        need(leg_radius <= evaluated['legDashProcessingLengthCapPx'], 'SOURCE_2000PX_LEG_CAP_REQUIRES_SEPARATE_BINDING')
        begin('A', 2)
        for bearing in evaluated['legBearingRadiansF32']:
            # Source invokes cosf/sinf. Python double trig is explicitly this
            # compiler's own analytic geometry; no claim of ARM-libm bit parity.
            ux, uy = math.cos(bearing), math.sin(bearing)
            offset = 0.
            dashes = []
            while offset < leg_radius:
                until = min(offset + draw, leg_radius)
                a = point(offset * ux, offset * uy)
                b = point(until * ux, until * uy)
                polyline([a,b])
                dashes.append(dict(startDistancePx=offset,endDistancePx=until,
                    nativePoints=[a[1], b[1]],maxVertexTransportErrorPx=max(a[2],b[2])))
                offset += draw + gap
            leg_receipts.append(dict(sourceBearingRadiansF32=bearing,
                sourceLegLengthPx=leg_radius,dashLengthPx=draw,gapLengthPx=gap,
                dashes=dashes,sourceCosfSinfBitParityClaim=False))
    commands.append('SPA')  # flush final edge path using the original SP branch
    svct = ';'.join(commands) + ';'
    need(len(svct.encode('ascii')) <= 65535, 'OWN_BOUNDED_RESOURCE_TEXT')
    need(not any(x in svct for x in ('AA','FP','PM','CA(')), 'SUPPORTED_OPEN_VECTOR_COMMANDS_ONLY')
    extent = max(radius + outer/2, leg_radius+1) + max_centerline_error_px + 1
    units = math.ceil(extent / NATIVE_COORDINATE_FACTOR)
    need(units <= 9999 and 2*units <= 99999, 'SYMD_FIXED_WIDTH_BOUNDS')
    fields = [0,0,2*units,2*units,-units,-units]
    symd = name + 'V' + ''.join(format(x,'05d') for x in fields)
    need(len(symd) == 39, 'EXACT_SYMD39')
    # Dedicated black is derived from the actual Source GLSL hardcoded outline,
    # rather than silently treating Source/native OUTLW(7,7,7) as exact black.
    scrf = 'AN5BLKB' + evaluated['arcColorName']
    fragment = ''.join(tag+'  '+str(len(value))+value+'\n' for tag,value in
        [('SYMB','SY'+format(resource_id,'05d')+'NIL'),('SYMD',symd),('SCRF',scrf),('SVCT',svct)])
    original = bytes.fromhex(scope['canonicalProgramHex'])
    ca = bytes.fromhex(scope['caTokenHex'])
    offset = scope['caTokenByteOffset']
    need(original[offset:offset+len(ca)] == ca, 'SOURCE_CA_REPLACEMENT_EXACT_RANGE')
    target = original[:offset] + ('SY('+name+')').encode('ascii') + original[offset+len(ca):]
    return dict(format=FORMAT,status='HOST_RESOURCE_CANDIDATE_NATIVE_PAINT_PENDING',
        sourceScope=scope,evaluatedSourceGLSL=evaluated,
        compilerPolicy=dict(maxCenterlineErrorPx=max_centerline_error_px,
            targetCoordinates='screen-relative original vector commands, not world coordinates',
            ownApproximation='Adaptive arc chords and explicit Source-schedule dash segments',
            sourcePixelParityClaim=False,originalA97PixelParityClaim=False),
        nativeCoordinateContract=dict(float32FactorBits=NATIVE_COORDINATE_FACTOR_BITS,
            float32Factor=NATIVE_COORDINATE_FACTOR,
            meaning='Literal PU/PD/CI/SymbolCell multiplier in original CommandBuilder/setRect; no physical DPI inference',
            qPenWidth='SW passed directly as Float32 to QPen.setWidthF, never multiplied by coordinate factor',
            pivot='SYMD V pivot[0,0]; Symbol.paint applies negative genuine cell pivot after its rotation',
            symbolRotationContractPending='Candidate SY has no comma; actual modern ClientSymbol/atlas rotation still needs candidate validation'),
        resource=dict(name=name,ownSymbolRecordID=resource_id,SYMD=symd,SCRF=scrf,SVCT=svct,
            common41FragmentASCII=fragment,common41FragmentSHA256=digest(fragment.encode('ascii')),
            boundingExtentUnits=units,symbolType='V',paletteExtension=dict(name='N5BLK',RGB=[0,0,0],source='Source GLSL literal outlineRGBA[0,0,0,1]'),
            authenticArcPalette=dict(name=evaluated['arcColorName'],RGB=rgb),
            installedIntoOriginalCommon41=False,Common44Or45RasterAtlasProduced=False),
        programTransform=dict(originalCAHex=ca.hex(),originalCASHA256=digest(ca),
            originalProgrammeHex=original.hex(),targetProgrammeHex=target.hex(),
            targetProgrammeASCII=target.decode('ascii'),targetProgrammeSHA256=digest(target),
            onlyCAByteRangeReplaced=True,originalCABytesRetained=True,attributeQueryChanged=False,pointXYChanged=False,
            directlyWritingRawCAInto300xx=False),
        centerlineProofs=proofs,legs=leg_receipts,
        actualDecodedVectorBoundsPx=dict(points=len(points),circles=circles,
            minXY=[min((p[k] for p in points),default=0) for k in (0,1)],
            maxXY=[max((p[k] for p in points),default=0) for k in (0,1)]),
        remainingBindings=['Original parser acceptance of newly authored Source41 resource + dedicated black palette overlay',
            'One component native CI/PU-PD draw and pixel read after Root agrees exact small case',
            'Modern a97 ClientSymbol name/rotation and Common44/45 atlas resource integration, not direct Qt Symbol.paint substitution',
            'Runtime display PPMM and dynamic SCAMIN/view-dependent size policy for production',
            'Qt cap/join/AA versus Source strict angular clip/shader alpha; no pixel equivalence',
            'Nonzero viewport rotation/DrawDashLine de-rotation outside this rotation-zero fixture'],
        originalFilesChanged=False,V10Changed=False,nativeCalls=0)


SIZING_POLICY = 'OWN_FIXED_PPMM3_DISPLAY300_ROTATION0_NO_SCAMIN_SIZING/1'


def compile_ca_resource(token, *, palette, resource_id, max_centerline_error_px=0.05):
    """Author one generic native definition; no files, pixels, UID or device.

    Integral decimal dimensions follow the established explicit numeric policy.
    Angles preserve their exact represented Float64 values, without modulo or
    nearby-recipe substitution. Caller must separately rasterize, reserve an
    atlas rectangle, validate unchanged old glyphs, and publish coupled pins.
    """
    from .light_sectors import parse_ca
    parsed = parse_ca(token)
    values = parsed['parameters']
    words = [str(value) if i not in (4,5) else format(struct.unpack('<d',bytes.fromhex(value))[0],'.17g')
             for i,value in enumerate(values)]
    canonical_token = 'CA(' + ','.join(words) + ')'
    raw = canonical_token.encode('ascii')
    scope = dict(kind=1,classCode=75,canonicalProgramHex=raw.hex(),
                 caTokenHex=raw.hex(),caTokenByteOffset=0)
    evaluated = evaluate_glsl(parse_original_ca(raw),rotation_radians=0.,
        pixels_per_mm=3.,display_size_mm=300.,view_scale_ppm=1.,scamin=0)
    result = compile_resource(scope,evaluated,max_centerline_error_px=max_centerline_error_px,
                              palette=palette,resource_id=resource_id)
    result['genericSourceCARecipe'] = dict(originalToken=token,canonicalNumericToken=canonical_token,
        parameters=values,sizingPolicy=SIZING_POLICY,sourceFeatureIdentityUsed=False,
        fixedDisplayInputs=dict(pixelsPerMM=3.,displayMM=300.,rotationRadians=0.,
                               viewScalePPM=1.,scaminSizingArgument=0),
        originalA97OrSourcePixelParityClaim=False,atlasPublicationRequired=True)
    return result
