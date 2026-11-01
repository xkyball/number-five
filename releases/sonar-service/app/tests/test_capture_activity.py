from no5_sonard_openecho.capture_activity import CaptureActivity


class Backend:
    def __init__(self):self.starts=0;self.ends=0
    def start(self):self.starts+=1
    def close(self):self.ends+=1


def test_activity_is_scoped_and_idempotent():
    b=Backend();a=CaptureActivity(platform='darwin',backend=b)
    a.start();a.start();assert a.active and b.starts==1
    a.close();a.close();assert not a.active and b.ends==1


def test_non_mac_acquisition_does_not_change_power_policy():
    b=Backend();a=CaptureActivity(platform='linux',backend=b)
    a.start();a.close();assert not a.active and b.starts==b.ends==0
