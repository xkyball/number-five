"""Loopback-only synthetic fixture for offscreen browser checks."""
import ipaddress
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from server import Collector,Server,handler
c=Collector('/not-used')
s=Server(('127.0.0.1',0),handler(c,ipaddress.ip_network('127.0.0.0/8')))
print(s.server_port,flush=True)
s.serve_forever()
