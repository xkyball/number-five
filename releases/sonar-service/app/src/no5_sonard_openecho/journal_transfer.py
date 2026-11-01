"""Byte-verified publication of sealed standard Zarr parts to the data volume."""
import hashlib,os,shutil
from pathlib import Path


def file_hash(path):
    digest=hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda:source.read(1024*1024),b''):digest.update(block)
    return digest.hexdigest()


def publish_part(source,destination):
    source=Path(source);destination=Path(destination)
    temporary=destination.with_name(destination.name+'.copying')
    if destination.exists() or temporary.exists():
        raise OSError('RAW destination already exists; preserve both parts for review')
    shutil.copytree(source,temporary)
    files=[p.relative_to(source)for p in source.rglob('*')if p.is_file()]
    for relative in files:
        target=temporary/relative
        if file_hash(source/relative)!=file_hash(target):
            raise OSError('RAW archive transfer hash mismatch')
        with target.open('rb') as handle:os.fsync(handle.fileno())
    for folder,_,_ in os.walk(temporary,topdown=False):
        fd=os.open(folder,os.O_RDONLY)
        try:os.fsync(fd)
        finally:os.close(fd)
    os.rename(temporary,destination)
    fd=os.open(destination.parent,os.O_RDONLY)
    try:os.fsync(fd)
    finally:os.close(fd)
    shutil.rmtree(source)


def recover_verified_part(source,volume,volume_uuid):
    """Recover one orphaned original WAL; caller has verified the mounted UUID."""
    import json,struct,zlib
    from no5_sonard.storage.raw_zarr import RawZarrV2Writer,_PENDING_LEN,_PENDING_CRC
    source=Path(source).resolve();volume=Path(volume).resolve()
    owner=json.loads((source.parent/'OWNER.json').read_text())
    if owner.get('schema')!='no5.capture-journal-owner/1':raise ValueError('unknown journal owner')
    try:os.kill(int(owner['pid']),0)
    except ProcessLookupError:pass
    else:raise OSError('journal still has a live owner; recovery refused')
    attrs=json.loads((source/'.zattrs').read_text());manifest=json.loads((source/'manifest.json').read_text())
    destination=Path(attrs['archive_destination']).resolve()
    if (attrs.get('archive_volume_uuid')!=volume_uuid or destination.name!=source.name
            or not destination.is_relative_to(volume)):
        raise OSError('journal destination/volume identity mismatch')
    pending=source/'_pending.bin'
    if pending.exists():
        data=pending.read_bytes();offset=0
        while offset<len(data):
            if offset+4>len(data):raise OSError('incomplete WAL tail retained for review')
            length=_PENDING_LEN.unpack_from(data,offset)[0];offset+=4
            if length<4 or offset+length>len(data):raise OSError('incomplete WAL record retained for review')
            record=data[offset:offset+length];offset+=length
            if zlib.crc32(record[:-4])&0xffffffff!=_PENDING_CRC.unpack(record[-4:])[0]:
                raise OSError('WAL CRC mismatch; original journal retained')
    writer=RawZarrV2Writer(source,sample_count=manifest['sample_count'],
        sample_period_ns=manifest['sample_period_ns'],chunk_pings=manifest['chunk_pings'])
    writer.close()
    if destination.exists():
        expected={str(p.relative_to(source)):file_hash(p)for p in source.rglob('*')if p.is_file()}
        actual={str(p.relative_to(destination)):file_hash(p)for p in destination.rglob('*')if p.is_file()}
        if actual!=expected:raise OSError('existing archive differs; both originals retained')
        shutil.rmtree(source)
    else:
        publish_part(source,destination)
    return {'destination':str(destination),'raw_rows':writer.count,'raw_samples_modified':False}
