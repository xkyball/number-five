"""An empirical stability reference cannot silently pass an LCD comparison."""
import json
from pathlib import Path
import sys
import pytest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from verify_depth_rate_archive import evaluation_reference


def test_reference_is_frozen_earlier_and_preserves_lcd_offset(tmp_path):
    path=tmp_path/'ref.json'
    row={'schema':'no5.empirical-depth-reference/1','measured_depth_m':1.252,
         'operator_lcd_m':1.1,'capture_finished_utc_ns':100,
         'all_physical_links':True,'raw_samples_unchanged':True,'wire_sha256':'a'*64}
    path.write_text(json.dumps(row))
    depth,proof=evaluation_reference(path,1.1,101)
    assert depth==1.252 and proof['basis']=='prior_physical_return_stability'
    assert proof['depth_calibrated'] is False
    assert proof['offset_from_lcd_m']==pytest.approx(.152)
    with pytest.raises(ValueError):evaluation_reference(path,1.1,100)
    with pytest.raises(ValueError):evaluation_reference(path,1.,101)
    row['measured_depth_m']=float('nan');path.write_text(json.dumps(row))
    with pytest.raises(ValueError):evaluation_reference(path,1.1,101)


def test_default_remains_the_actual_lcd_reference():
    assert evaluation_reference(None,1.1,101)==(1.1,{'basis':'operator_lcd','depth_calibrated':False})
