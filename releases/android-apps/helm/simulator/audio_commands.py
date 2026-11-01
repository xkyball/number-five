"""Local-only asynchronous AUDIO contract. No hardware or backend connection."""
from collections import OrderedDict
import copy
import threading


class AudioCommands:
    CAPACITY = 64
    HISTORY = 256

    def __init__(self, audio):
        self.audio = audio
        self.lock = threading.Lock()
        self.pending = [None] * self.CAPACITY
        self.head = self.size = self.next_id = 0
        self.results = OrderedDict()
        self.requests = {}
        self.watermarks = {}
        self.chain_owner = None
        self.chain = set()
        self.last_revision = None
        self.closed = False
        self.wake = threading.Event()
        self.worker = None

    @staticmethod
    def key(request):
        command = request['command']
        if command['type'] in ('fader', 'mute'):
            return request['sessionId'], command['type'], command['channel']
        if command['type'] == 'transport' and command['action'] == 'SEEK':
            return request['sessionId'], 'seek'
        return None

    def trim(self):
        terminal = [identifier for identifier, job in self.results.items()
                    if job['receipt']['status'] not in ('ACCEPTED', 'RUNNING')]
        for identifier in terminal[:-self.HISTORY]:
            job = self.results.pop(identifier)
            self.requests.pop(job['request']['requestId'])

    def submit(self, request):
        session, request_id, counter, expected, _ = self.audio.validate_envelope(request)
        with self.lock:
            old = self.requests.get(request_id)
            if old is not None:
                if old['request'] != request:
                    raise ValueError('Request ID belongs to a different command')
                return copy.deepcopy(old['receipt'])
            if counter <= self.watermarks.get(session, 0):
                raise ValueError('Evicted request cannot be replayed')
            if session not in self.watermarks and len(self.watermarks) >= 256:
                raise ValueError('Producer capacity reached')
            self.watermarks[session] = counter
            self.next_id += 1
            receipt = dict(version=1, kind='audio-command-result', commandId=str(self.next_id),
                           sessionId=session, serverSessionId=request['serverSessionId'],
                           requestId=request_id, status='ACCEPTED', reason='NONE',
                           acceptedAtEpochMs=int(self.audio.wall_epoch_ms()), replacementId=None)
            job = dict(request=copy.deepcopy(request), receipt=receipt)
            self.results[receipt['commandId']] = self.requests[request_id] = job
            state = self.audio.get()[1]
            if self.closed or request['serverSessionId'] != state.get('serverSessionId') or expected != state.get('revision'):
                receipt.update(status='REJECTED', reason='SOURCE_OR_REVISION_CHANGED', state=state)
            else:
                key = self.key(request)
                replaced = False
                if key is not None:
                    for age in range(self.size - 1, -1, -1):
                        position = (self.head + age) % self.CAPACITY
                        previous = self.pending[position]
                        previous_key = self.key(previous['request'])
                        if previous_key is None:
                            break
                        if previous_key == key:
                            previous['receipt'].update(status='SUPERSEDED', reason='NEWER_TARGET', replacementId=receipt['commandId'])
                            self.pending[position] = job
                            replaced = True
                            break
                if not replaced:
                    if self.size == self.CAPACITY:
                        receipt.update(status='REJECTED', reason='BUSY', state=state)
                    else:
                        self.pending[(self.head + self.size) % self.CAPACITY] = job
                        self.size += 1
                self.wake.set()
            self.trim()
            return copy.deepcopy(receipt)

    def read(self, identifier):
        with self.lock:
            job = self.results.get(identifier)
            return copy.deepcopy(job['receipt']) if job is not None else None

    def process_one(self):
        with self.lock:
            if self.closed or not self.size:
                return False
            job = self.pending[self.head]
            self.pending[self.head] = None
            self.head = (self.head + 1) % self.CAPACITY
            self.size -= 1
            job['receipt']['status'] = 'RUNNING'
        # Execution is outside the admission lock; a delayed write cannot block POST.
        request = copy.deepcopy(job['request'])
        try:
            current = self.audio.get()[1]
            if (request['sessionId'] == self.chain_owner and current.get('revision') == self.last_revision
                    and request['expectedRevision'] in self.chain):
                request['expectedRevision'] = current['revision']
            ack = self.audio.command(request)
            with self.lock:
                if ack['status'] == 'VERIFIED':
                    if self.chain_owner != request['sessionId']:
                        self.chain.clear()
                    self.chain_owner = request['sessionId']
                    self.chain.add(job['request']['expectedRevision'])
                    self.last_revision = ack['state']['revision']
                    if len(self.chain) > self.HISTORY:
                        self.chain = {job['request']['expectedRevision']}
                job['receipt'].update(status='APPLIED' if ack['status'] == 'VERIFIED' else 'REJECTED',
                                      reason='NONE' if ack['status'] == 'VERIFIED' else 'BACKEND_REJECTED', state=ack['state'])
                self.trim()
        except Exception:
            with self.lock:
                job['receipt'].update(status='UNKNOWN', reason='COMPLETION_UNKNOWN')
                self.trim()
        return True

    def start(self):
        def run():
            while not self.closed:
                self.wake.wait(.1)
                self.wake.clear()
                while self.process_one():
                    pass
        self.worker = threading.Thread(target=run, name='no5-sim-audio-goals', daemon=True)
        self.worker.start()

    def close(self):
        with self.lock:
            self.closed = True
            while self.size:
                job = self.pending[self.head]
                self.pending[self.head] = None
                self.head = (self.head + 1) % self.CAPACITY
                self.size -= 1
                job['receipt'].update(status='CANCELLED', reason='CLOSED')
            self.trim()
        self.wake.set()
        if self.worker is not None:
            self.worker.join(timeout=3)
