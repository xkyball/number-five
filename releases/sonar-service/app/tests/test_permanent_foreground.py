"""Moving pixels of another foreground app must never qualify as sonar."""
import importlib.util
import io
import json
import os
from pathlib import Path
import numpy as np
from PIL import Image
import pytest

path=Path(os.environ.get('SONAR_VERIFY_CANDIDATE',Path(__file__).parents[1]/'tools/verify_permanent_chain.py'))
spec=importlib.util.spec_from_file_location('foreground_chain',path)
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
HELM='mResumedActivity: ActivityRecord{123 u0 com.no5.helm/.MainActivity t1}'
HOME='mResumedActivity: ActivityRecord{456 u0 com.no5.home/.HomeActivity t2}'
HELM_WINDOW='mCurrentFocus=Window{f023209 u0 com.no5.helm/com.no5.helm.MainActivity}'
ANR_WINDOW='mCurrentFocus=Window{123 u0 Application Not Responding: com.no5.helm}'


def test_only_unique_actual_HELM_is_accepted():
    assert m.foreground_helm(HELM+'\n'+HELM.replace('mResumedActivity','ResumedActivity'))['package']=='com.no5.helm'
    for wrong in [HOME,HELM+'\n'+HOME,'','mResumedActivity: ActivityRecord{u0 com.no5.helm/.OtherActivity}']:
        with pytest.raises(RuntimeError,match='foreground'):m.foreground_helm(wrong)


@pytest.mark.parametrize('wrong', [ANR_WINDOW, 'mCurrentFocus=null', '',
    HELM_WINDOW+'\nmCurrentFocus=Window{abc u0 com.no5.home/com.no5.home.HomeActivity}',
    'mCurrentFocus=Window{abc u0 com.no5.helm/com.no5.helm.OtherActivity}'])
def test_resumed_HELM_behind_error_or_foreign_window_cannot_qualify(wrong):
    assert m.foreground_helm(HELM)['package']=='com.no5.helm'
    with pytest.raises(RuntimeError,match='window focus'):m.focused_helm_window(wrong)


def test_actual_HELM_window_is_required():
    assert m.focused_helm_window(HELM_WINDOW)['component']=='com.no5.helm/com.no5.helm.MainActivity'


def encoded_plot():
    a=np.zeros((1200,2000,3),dtype=np.uint8);a[400:420,500:520]=200
    b=io.BytesIO();Image.fromarray(a).save(b,format='PNG');return b.getvalue()


@pytest.mark.parametrize('before,after,page_before,page_after,accepted',[
    (HELM,HELM,'SONAR','SONAR',True),
    (HOME,HOME,'SONAR','SONAR',False),
    (HELM,HOME,'SONAR','SONAR',False),
    (HELM,HELM,'HELM','SONAR',False),
    (HELM,HELM,'SONAR','AUDIO',False),
])
def test_capture_checks_real_foreground_and_page_on_both_sides(monkeypatch,before,after,page_before,page_after,accepted):
    activity=iter([before,after]);pages=iter([page_before,page_after]);png=encoded_plot();calls=[]
    def command(args):
        calls.append(args)
        if args[-3:]==['activity','activities']:raise AssertionError('unreachable malformed fixture')
        if args[-2:]==['activity','activities']:return next(activity).encode()
        if args[-2:]==['dumpsys','window']:return HELM_WINDOW.encode()
        if args[-2:]==['screencap','-p']:return png
        raise AssertionError('unexpected command')
    monkeypatch.setattr(m,'command',command)
    def preferences(adb):
        calls.append(['committed_preferences'])
        return {'page':next(pages)}, {'method':'committed_atomic_file_inode'}
    monkeypatch.setattr(m,'read_view_preferences',preferences)
    if accepted:
        _,view,data=m.capture_guest_sonar(['adb'])
        assert data==png and view['actual_HELM_foreground_verified'] and view['SONAR_page_before_after_verified']
        assert len(calls)==7 and not view['plot_flat']
        assert view['actual_HELM_window_focus_verified']
    else:
        with pytest.raises(RuntimeError):m.capture_guest_sonar(['adb'])


@pytest.mark.parametrize('windows',[(ANR_WINDOW,HELM_WINDOW),(HELM_WINDOW,ANR_WINDOW)])
def test_error_dialog_before_or_after_picture_rejects_even_moving_sonar(monkeypatch,windows):
    focused=iter(windows)
    def command(args):
        if args[-2:]==['activity','activities']:return HELM.encode()
        if args[-2:]==['dumpsys','window']:return next(focused).encode()
        if args[-2:]==['screencap','-p']:return encoded_plot()
        raise AssertionError('unexpected command')
    monkeypatch.setattr(m,'command',command)
    monkeypatch.setattr(m,'read_view_preferences',lambda adb:({'page':'SONAR'},{}))
    with pytest.raises(RuntimeError,match='window focus'):m.capture_guest_sonar(['adb'])
