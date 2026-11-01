"""No real device or archive is started by these guard tests."""
import importlib.util
from pathlib import Path
import pytest
spec=importlib.util.spec_from_file_location('volume_launch',Path(__file__).resolve().parents[1]/'tools/launch_on_volume.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
def test_exact_mounted_identity_and_contained_relative_directory_are_required(tmp_path):
    info={'Mounted':True,'VolumeUUID':'expected','MountPoint':str(tmp_path)}
    assert m.validate(info,tmp_path,'sonar/live','expected')==Path('sonar/live')
    for bad in ({**info,'Mounted':False},{**info,'VolumeUUID':'other'},{**info,'MountPoint':'/other'}):
        with pytest.raises(ValueError):m.validate(bad,tmp_path,'sonar/live','expected')
    for path in ('/absolute','../escape','sonar/../../escape'):
        with pytest.raises(ValueError):m.validate(info,tmp_path,path,'expected')
    outside=tmp_path.parent/'volume-outside';outside.mkdir();(tmp_path/'link').symlink_to(outside,target_is_directory=True)
    with pytest.raises(ValueError):m.validate(info,tmp_path,'link/state','expected')
