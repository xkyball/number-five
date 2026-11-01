"""Volatile, owner-bound Mac forwarding; the configured local source wins."""
from dataclasses import replace
import glob
import hashlib
import json
from pathlib import Path
import time
import uuid


class ForwardingConflict(ValueError):
    pass


class ForwardingLease:
    TTL_S = 60
    UPSTREAM = 'http://127.0.0.1:18788'

    def __init__(self, policy_path=None, *, clock=time.monotonic):
        self.clock = clock
        self.policy_path = None if policy_path is None else Path(policy_path)
        self.policy = None
        if self.policy_path is not None:
            self.policy = json.loads(self.policy_path.read_text())
            if (self.policy.get('schema') != 'no5.sonar-forwarding-policy/1'
                    or self.policy.get('target') not in {'lab','pi'}
                    or type(self.policy.get('local_source_configured')) is not bool):
                raise ValueError('invalid forwarding policy; no route admitted')
        self.owner = None
        self.deadline = 0.
        self.revision = 0
        self.reason = None

    def conflict(self):
        if self.policy is None:
            return 'forwarding is not provisioned'
        if self.policy['local_source_configured']:
            return 'a local physical source is configured'
        for filename, expected in self.policy.get('protected_sha256', {}).items():
            try:actual=hashlib.sha256(Path(filename).read_bytes()).hexdigest()
            except OSError:return 'local source configuration requires review'
            if actual != expected:return 'local source configuration changed'
        if any(glob.glob(pattern) for pattern in self.policy.get('physical_device_globs', [])):
            return 'a local Pico device is present'
        return None

    def snapshot(self):
        conflict = self.conflict()
        if self.owner is not None and (self.clock() >= self.deadline or conflict):
            self.owner = None;self.deadline = 0.;self.revision += 1
            self.reason = conflict or 'forwarding lease expired'
        return {'schema':'no5.sonar-forwarding/1','enabled':self.policy is not None,
                'owner':self.owner,'revision':self.revision,'active':self.owner is not None,
                'remaining_s':max(0.,self.deadline-self.clock()),'conflict':conflict,
                'reason':self.reason,'upstream':self.UPSTREAM,
                'target':None if self.policy is None else self.policy['target']}

    def acquire(self, payload):
        current=self.snapshot()
        if not isinstance(payload,dict) or set(payload)!={'owner','ttl_s','expected_revision'}:
            raise ForwardingConflict('owner, ttl_s and expected_revision are required')
        try:owner=str(uuid.UUID(payload['owner']))
        except (ValueError,TypeError,AttributeError):raise ForwardingConflict('invalid owner') from None
        if (type(payload['ttl_s']) is not int or payload['ttl_s']!=self.TTL_S
                or type(payload['expected_revision']) is not int
                or payload['expected_revision']!=self.revision):
            raise ForwardingConflict('lease duration or revision mismatch')
        if current['conflict']:raise ForwardingConflict(current['conflict'])
        if self.owner not in {None,owner}:raise ForwardingConflict('another owner holds this route')
        self.owner=owner;self.deadline=self.clock()+self.TTL_S;self.revision+=1;self.reason=None
        return self.snapshot()

    def release(self, owner):
        self.snapshot()
        if self.owner is not None and self.owner!=owner:
            raise ForwardingConflict('another owner holds this route')
        self.owner=None;self.deadline=0.;self.revision+=1;self.reason='owner released forwarding'
        return self.snapshot()

    def source_config(self, original):
        if not self.snapshot()['active']:return original
        return replace(original,producer='m1',upstream_base_url=self.UPSTREAM,source_name='mac-physical-sonar')
