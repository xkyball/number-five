"""Loopback-only instrumented server for the production-client cadence regression.

Run via SimulatorCadenceIntegrationTest. Measurements describe this host, not
Android frames or physical Pi/LAN performance. No dependencies beyond stdlib.
"""
import argparse
import json
from pathlib import Path
import signal
import threading
import time

from .server import Simulation, SimulationServer


class MeasuredSimulation(Simulation):
    def __init__(self):
        self.samples = []
        self.requests = []
        super().__init__(running=False)

    def sample(self):
        super().sample()
        with self.lock:
            self.samples.append({
                'monotonicNs': time.monotonic_ns(),
                'epochMs': self.sample_epoch_ms,
                'timeScale': self.time_scale,
                'elapsedSeconds': self.engine.state.elapsed,
                'threads': threading.active_count(),
            })

    def get(self, kind):
        self.requests.append({'kind': kind, 'monotonicNs': time.monotonic_ns()})
        return super().get(kind)


class MeasuredServer(SimulationServer):
    def __init__(self, address, simulation):
        self.accepted_connections = 0
        super().__init__(address, simulation)

    def process_request(self, request, client_address):
        self.accepted_connections += 1
        super().process_request(request, client_address)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    simulation = MeasuredSimulation()
    server = MeasuredServer(('127.0.0.1', args.port), simulation)

    def stop(_signal, _frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, stop)
    print(f'Cadence fixture at http://127.0.0.1:{server.server_address[1]}', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps({
            'environment': 'Python loopback timing fixture, no Android frame measurement',
            'sampleIntervalMs': server.SAMPLE_INTERVAL * 1000,
            'maxConnectionWorkers': server.MAX_CONNECTIONS,
            'acceptedTcpConnections': server.accepted_connections,
            'samples': simulation.samples,
            'requests': simulation.requests,
        }, indent=2) + '\n')


if __name__ == '__main__':
    main()
