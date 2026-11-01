"""Multiplex unchanged RawZarr writers without padding/truncating raw samples.

Each sibling .zarr is still a standard fixed-geometry original raw archive.
The index ties geometries to one USB segment; Ping IDs retain chronological order.
"""
import json
from no5_sonard.storage.raw_zarr import RawZarrV2Writer


class GeometryArchive:
    def __init__(self, primary, *, eager_sample_counts=()):
        self.primary = primary
        self.path = primary.path
        self.sample_count = primary.sample_count
        self.writers = {(primary.sample_count, primary.sample_period_ns): primary}
        self.attrs = json.loads((self.path/'.zattrs').read_text())
        for n in eager_sample_counts:
            self._writer_for(n, primary.sample_period_ns)
        self._write_index()

    def _write_index(self):
        self.primary._write_json(self.path/'geometry-index.json', {
            'schema': 'no5.raw-geometry-index/1', 'raw_samples_modified': False,
            'segment_id': self.path.stem,
            'geometries': [{'path': w.path.name, 'sample_count': n, 'sample_period_ns': p}
                           for (n,p), w in sorted(self.writers.items())]})

    @property
    def count(self):
        return sum(w.count for w in self.writers.values())

    @property
    def pending_count(self):
        return sum(w.pending_count for w in self.writers.values())

    def _writer_for(self, n, p):
        key=(n,p)
        if key not in self.writers:
            if not 2048 <= n <= 16384 or n%256 or p != 2000:
                raise ValueError('unapproved adaptive raw geometry')
            path = self.path.with_name(f'{self.path.stem}.n{n}.p{p}.zarr')
            self.writers[key] = RawZarrV2Writer(path, sample_count=n, sample_period_ns=p,
                chunk_pings=self.primary.chunk_pings,
                attrs={**self.attrs, 'sample_count': n, 'sample_period_ns': p,
                       'geometry_parent_segment': self.path.stem})
            self._write_index()
        return self.writers[key]

    def append(self, record):
        self._writer_for(record.samples.size,record.sample_period_ns).append(record)

    def flush(self):
        for writer in self.writers.values():
            writer.flush()

    def close(self):
        for writer in self.writers.values():
            writer.close()
