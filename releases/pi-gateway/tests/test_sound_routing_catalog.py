"""Automatic routing uses an exact DSP-validated marker and immutable content."""
import asyncio
from contextlib import asynccontextmanager
import hashlib
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock
from no5_pi_gateway.audio import CamillaDspClient, CamillaPreset, AudioError, _preset_sound_routing

class SoundRoutingCatalogTest(unittest.TestCase):
    def test_native_marker_only_not_title_filename_or_ambiguous_yaml(self):
        for routing in ('front_stereo_fullrange', 'stereo_4_1', 'immersive_4_1'):
            self.assertEqual(routing, _preset_sound_routing('title: Arbitrary\ndescription: No5 sound v1 / '+routing+'\n'))
        for value in ('title: No5 sound v1 / immersive_4_1\n',
                      'description: No5 sound v1 / unknown\n',
                      'description: "No5 sound v1 / immersive_4_1"\n',
                      ' description: No5 sound v1 / immersive_4_1\n',
                      'description: No5 sound v1 / immersive_4_1\ndescription: No5 sound v1 / stereo_4_1\n',
                      'description: No5 sound v1 / immersive_4_1\n'+'x'*65536):
            self.assertIsNone(_preset_sound_routing(value))

    def test_changed_routing_content_is_rejected_before_set_or_reload(self):
        original='title: Same title\ndescription: No5 sound v1 / immersive_4_1\n'
        candidate=CamillaPreset('safe_id','Same title','/approved/profile.yml','immersive_4_1',hashlib.sha256(original.encode()).hexdigest())
        for changed in (original+'changed: true\n', original.replace('immersive_4_1','stereo_4_1')):
            client=object.__new__(CamillaDspClient)
            client._config=SimpleNamespace(websocket_url='http://fixture.invalid')
            @asynccontextmanager
            async def socket(_): yield object()
            client._socket=socket
            client._request=AsyncMock(return_value=changed)
            with self.assertRaisesRegex(AudioError,'routing changed'):
                asyncio.run(client.apply_preset(None,candidate,'/approved/previous.yml'))
            self.assertEqual(1,client._request.await_count)
