import importlib.util
import ipaddress
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.request import urlopen

spec=importlib.util.spec_from_file_location('gps_map_server',Path(__file__).resolve().parents[1]/'server.py')
s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)

def document(n=3):
    return {'serverSessionId':'test','unrelatedSecret':'not public','positionSources':{
        'sources':[{'sourceId':str(i),'label':'GPS '+str(i),'position':{'latitudeDegrees':50,'longitudeDegrees':4},
                    'status':'LIVE','observedAtEpochMs':100000,'gnss':{'private':'not public'}} for i in range(n)],
        'combination':{'status':'LIVE','position':{'latitudeDegrees':50,'longitudeDegrees':4},
                       'observedAtEpochMs':100000,'available':True,'contributors':[{'sourceId':'0','private':'not public'}],
                       'excluded':[{'sourceId':'2','reason':'POSITION_OUTLIER'}]},'total':n,'omitted':0}}

class Tests(unittest.TestCase):
    def test_gps_catalogue_provenance_is_independent_of_helm_overlay(self):
        d=document();d['simulated']=True
        d['positionSources'].update(simulated=False,sourceKind='REAL')
        self.assertFalse(s.normalize(d,100001)['simulated'])
        d['positionSources']['simulated']=True
        self.assertTrue(s.normalize(d,100001)['simulated'])

    def test_sources_whitelist_and_combination(self):
        for n in [1,3,40]:
            value=s.normalize(document(n),100001)
            self.assertEqual(len(value['sources']),n)
            self.assertEqual(value['combination']['excluded'][0]['reason'],'POSITION_OUTLIER')
            self.assertNotIn('private',json.dumps(value))
            self.assertNotIn('unrelatedSecret',value)

    def test_invalid_coordinates_and_truncation(self):
        for pos in [{'latitudeDegrees':float('nan'),'longitudeDegrees':0},{'latitudeDegrees':91,'longitudeDegrees':0},{'latitudeDegrees':True,'longitudeDegrees':0}]:
            self.assertIsNone(s.position(pos))
        self.assertEqual(s.position({'latitudeDegrees':0,'longitudeDegrees':0}),{'latitudeDegrees':0,'longitudeDegrees':0})
        d=document();d['positionSources']['omitted']=4;d['positionSources']['total']=7
        self.assertEqual(s.normalize(d,100001)['omitted'],4)
        d['positionSources']['sources'][1]['sourceId']='0'
        with self.assertRaises(ValueError):s.normalize(d,100001)

    def test_failed_poll_retains_original_clock_but_marks_unavailable(self):
        with tempfile.TemporaryDirectory() as tmp:
            key=Path(tmp)/'key';key.write_text('test-secret-key')
            c=s.Collector(key);c.value=s.normalize(document(),100001)
            with patch.object(c.opener,'open',side_effect=OSError('sensitive error')): c.poll()
            self.assertFalse(c.snapshot()['upstreamAvailable'])
            self.assertEqual(c.snapshot()['sources'][0]['observedAtEpochMs'],100000)
            self.assertNotIn('sensitive',json.dumps(c.snapshot()))

    def test_shared_snapshot_http_no_upstream_poll_and_no_path_escape(self):
        c=s.Collector('/unused');c.value=s.normalize(document(),int(s.time.time()*1000))
        server=s.Server(('127.0.0.1',0),s.handler(c,ipaddress.ip_network('192.168.68.0/22')))
        t=threading.Thread(target=server.serve_forever,daemon=True);t.start()
        base='http://127.0.0.1:'+str(server.server_port)
        try:
            with patch.object(c,'poll',side_effect=AssertionError('HTTP must never poll')):
                for _ in range(5):
                    with urlopen(base+'/api/positions') as r:
                        self.assertEqual(len(json.load(r)['sources']),3)
                        self.assertEqual(r.headers['Cache-Control'],'no-store')
            for path in ['/../server.py','/vendor/LICENSE','/etc/passwd']:
                with self.assertRaises(Exception) as ctx:urlopen(base+path)
                self.assertEqual(ctx.exception.code,404)
                ctx.exception.close()
        finally:server.shutdown();server.server_close();t.join()

if __name__=='__main__':unittest.main()
