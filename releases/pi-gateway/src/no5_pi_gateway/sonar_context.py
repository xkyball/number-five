"""Strict atomic Sonard display evidence; never guess an envelope from legacy bytes."""
from __future__ import annotations

from dataclasses import dataclass,replace
import json
import math
import re
import struct
import uuid

from .protocol import ProtocolError,parse_echogram_frame
from .sonar_contract import MAX_SIGNED,SOURCES
from .sonar_published import PublishedPing,parse_record,matches_frame

PREFIX=struct.Struct('<4sHHII')
MAX_METADATA_BYTES=4096
MAX_MESSAGE_BYTES=PREFIX.size+MAX_METADATA_BYTES+2088
SCHEMA='no5.sonard-echogram-context/1'
ROOT_FIELDS={'schema','selected_source','publication_sequence','published_ping'}
ROW_FIELDS=set(PublishedPing.__dataclass_fields__)
WINDOW_FIELDS={'schema','ping_id','capture_sample_count','display_sample_count',
               'display_start_sample','display_end_sample_exclusive','capture_range_m',
               'display_range_m','tx_reference_verified','reason','raw_samples_modified',
               'detector_uses_full_capture'}
UNSIGNED=re.compile(r'(?:0|[1-9][0-9]*)\Z',re.ASCII)
SIGNED=re.compile(r'(?:0|[1-9][0-9]*|-[1-9][0-9]*)\Z',re.ASCII)


@dataclass(frozen=True,slots=True)
class AtomicEchogram:
    frame: object
    publication: PublishedPing
    selected_source: str
    sequence: int
    original_m1: bytes

    @property
    def context(self):return self.publication.session_id,self.publication.segment_id

    @property
    def epoch(self):
        row=self.publication
        return self.context,row.display_generation,row.selection_generation,self.selected_source


def _decimal(value,*,signed=False,minimum=0):
    if not isinstance(value,str)or len(value)>20 or not(SIGNED if signed else UNSIGNED).fullmatch(value):
        raise ProtocolError('atomic context requires a canonical decimal string')
    result=int(value)
    # The wider host wire remains valid, but these values cannot enter Android
    # Long IDs/positive timestamps. Reject explicitly; never wrap or round.
    if not minimum<=result<=MAX_SIGNED:
        raise ProtocolError('atomic integer unsupported by Android V1')
    return result


def _uuid(value):
    if not isinstance(value,str)or len(value)!=36:
        raise ProtocolError('atomic context UUID missing')
    try:canonical=str(uuid.UUID(value))
    except ValueError as error:raise ProtocolError('invalid atomic context UUID')from error
    if canonical!=value:raise ProtocolError('atomic context UUID is not canonical')
    return value


def _json(raw):
    def pairs(items):
        result={}
        for name,value in items:
            if name in result:raise ProtocolError('duplicate atomic context JSON key')
            result[name]=value
        return result
    def invalid(_):raise ProtocolError('non-finite atomic context JSON number')
    def finite(text):
        value=float(text)
        if not math.isfinite(value):invalid(text)
        return value
    try:return json.loads(raw.decode('utf-8'),object_pairs_hook=pairs,
                          parse_constant=invalid,parse_float=finite)
    except(UnicodeError,ValueError,RecursionError)as error:
        raise ProtocolError('atomic context must be strict UTF-8 JSON')from error


def parse_atomic_echogram(data:bytes)->AtomicEchogram:
    if not PREFIX.size<=len(data)<=MAX_MESSAGE_BYTES:
        raise ProtocolError('atomic echogram message exceeds bounds')
    magic,version,flags,metadata_len,m1_len=PREFIX.unpack_from(data)
    if magic!=b'N5EC'or version!=1 or flags!=0:
        raise ProtocolError('unsupported atomic echogram prefix')
    if(not 1<=metadata_len<=MAX_METADATA_BYTES or not 42<=m1_len<=2088 or
            len(data)!=PREFIX.size+metadata_len+m1_len):
        raise ProtocolError('atomic echogram lengths disagree')
    split=PREFIX.size+metadata_len;root=_json(data[PREFIX.size:split])
    if not isinstance(root,dict)or set(root)!=ROOT_FIELDS or root['schema']!=SCHEMA:
        raise ProtocolError('unsupported atomic context schema or fields')
    selected=root['selected_source']
    if not isinstance(selected,str)or selected not in SOURCES:
        raise ProtocolError('invalid atomic selected source')
    sequence=_decimal(root['publication_sequence'],minimum=1)
    original=data[split:];frame=parse_echogram_frame(original)
    if frame.observed_at_epoch_ms<=0 or frame.ping_id>MAX_SIGNED or struct.unpack_from('<H',original,38)[0]!=0:
        raise ProtocolError('atomic M1 ID, timestamp or reserved field unsupported by Android V1')
    row=root['published_ping']
    if not isinstance(row,dict)or set(row)!=ROW_FIELDS:
        raise ProtocolError('invalid atomic publication fields')
    row=dict(row);row['ping_id']=_decimal(row['ping_id'])
    row['time_utc_ns']=_decimal(row['time_utc_ns'],signed=True,minimum=1)
    context=_uuid(row['session_id']),_uuid(row['segment_id'])
    for field in('display_generation','selection_generation','noise_revision','sample_period_ns'):
        if type(row[field])is not int or not 1<=row[field]<(1<<32):
            raise ProtocolError('invalid atomic generation or sampling integer')
    if row['sample_period_ns']>1_000_000:
        raise ProtocolError('atomic sampling interval unsupported by Android V1')
    if type(row['sample_count'])is not int or not 1<=row['sample_count']<=16384:
        raise ProtocolError('atomic capture sample count outside bounds')
    window=row['display_window']
    if not isinstance(window,dict)or set(window)!=WINDOW_FIELDS:
        raise ProtocolError('invalid atomic display-window fields')
    window=dict(window);window['ping_id']=_decimal(window['ping_id'])
    for field in('capture_sample_count','display_sample_count'):
        if type(window[field])is not int or not 1<=window[field]<=16384:
            raise ProtocolError('invalid atomic display sample count')
    for field in('display_start_sample','display_end_sample_exclusive'):
        if type(window[field])is not int or not 0<=window[field]<=16384:
            raise ProtocolError('invalid atomic display window boundary')
    reason=window['reason']
    try:reason_bytes=reason.encode('utf-8')if isinstance(reason,str)else b''
    except UnicodeError as error:raise ProtocolError('invalid atomic window UTF-8')from error
    if not 1<=len(reason_bytes)<=128:
        raise ProtocolError('invalid atomic window reason')
    row['display_window']=window
    publication=parse_record(row,context=context,strict_window_reason=False)
    bound=replace(frame,host_context_hint=context)
    if not matches_frame(publication,bound,context):
        raise ProtocolError('atomic evidence does not match original M1 header')
    if publication.actual_source is not None and selected not in{'all',publication.actual_source}:
        raise ProtocolError('atomic actual source does not match selected source')
    return AtomicEchogram(bound,publication,selected,sequence,original)
