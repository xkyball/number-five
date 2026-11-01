"""Counterexamples demonstrate that a plausible picture cannot pass as a joint."""
import copy, sys, unittest
from unittest.mock import patch
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'src'))
import build123d as b
from mechanical_function import cylinder, screw_geometry, continuous_escape, root_paths, nominal_shaft_present, cable_grip, directional_witness, prismatic_escape
from electrical_function import pin_types, critical_errors, value
from plausibility_common import CONTRACT, overall, current
from common import OUT, read
from verify import reconstruct

def box(x,y,z,w,d,h):return b.Solid.make_box(w,d,h,b.Plane(origin=(x,y,z)))

class MechanicalCounterexamples(unittest.TestCase):
    def joint(self,length=9):
        base=cylinder(0,0,0,5,10).cut(cylinder(0,0,1,1.15,9.1))
        plate=box(-5,-5,10,10,10,2).cut(cylinder(0,0,9.9,1.7,2.2))
        screw=dict(axis=[0,0,12],direction=-1,length=length)
        return screw,base,plate

    def test_direct_screw_has_real_bearing_support_and_engagement(self):
        f,base,plate=self.joint();self.assertEqual(screw_geometry(f,base,[plate],7)['status'],'PASS')

    def test_short_screw_is_rejected(self):
        f,base,plate=self.joint(3);self.assertIn('INSUFFICIENT_ENGAGEMENT',screw_geometry(f,base,[plate],7)['issues'])

    def test_blind_bottom_is_not_ignored(self):
        f,base,plate=self.joint(12);self.assertIn('BLIND_BOTTOM_OVERRUN',screw_geometry(f,base,[plate],7)['issues'])

    def test_missing_head_support_is_rejected(self):
        f,base,plate=self.joint();plate=plate.cut(cylinder(0,0,9.9,3.1,2.2))
        self.assertIn('NO_HEAD_BEARING',screw_geometry(f,base,[plate],7)['issues'])

    def test_support_gap_is_rejected(self):
        f,base,plate=self.joint();base=base.moved(b.Location((0,0,-1)))
        self.assertIn('NO_POSITIVE_AREA_SUPPORT',screw_geometry(f,base,[plate],7)['issues'])

    def test_declared_target_without_pilot_is_not_a_connection(self):
        f,base,plate=self.joint();base=box(-5,-5,0,10,10,10)
        self.assertIn('NO_COAXIAL_PILOT',screw_geometry(f,base,[plate],7)['issues'])

    def test_declared_length_is_compared_with_actual_export(self):
        f,base,plate=self.joint()
        short=cylinder(0,0,12,1.45,3,-1)
        self.assertFalse(nominal_shaft_present(f,short)['passed'])

    def nut_fixture(self,shoulder):
        base=box(-6,-6,0,12,12,4)
        base=base.cut(box(-3,-3,-.1,6,6,3.1 if shoulder else 4.2))
        base=base.cut(cylinder(0,0,-.1,1.7,4.2))
        moving={'module':box(-4,-4,5,8,8,2),'nut':box(-2.5,-2.5,1,5,5,2),
                'bolt':cylinder(0,0,1,1.4,6)}
        return moving,{'printed_base':base}

    def test_module_bolt_nut_escape_together_from_open_pocket(self):
        moving,fixed=self.nut_fixture(False)
        self.assertTrue(continuous_escape(moving,fixed,[0,0,10])['escape_proved'])

    def test_load_bearing_shoulder_blocks_same_nut_group(self):
        moving,fixed=self.nut_fixture(True)
        self.assertFalse(continuous_escape(moving,fixed,[0,0,10])['escape_proved'])

    def test_unproved_edges_do_not_anchor_a_graph(self):
        self.assertNotIn('MODULE',root_paths([('MODULE','LOOSE_NUT')],'BASE'))

    def grip_fixture(self,gap=.6,bore=3.8):
        axis=b.Plane(origin=(-1,0,0),z_dir=(1,0,0))
        clearance=b.Solid.make_cylinder(2.4,14,axis);cutout=b.Solid.make_cylinder(bore/2,14,axis)
        parts={'lo':box(0,-12,-5,12,24,5).cut(clearance,box(1,-4.2,-3,10,8.4,3.1)),
               'hi':box(0,-12,gap,12,24,5-gap).cut(clearance,box(1,-4.2,gap-.1,10,8.4,3.1)),
               'jlo':box(1.2,-4,-3,9.6,8,3).cut(cutout),
               'jhi':box(1.2,-4,gap,9.6,8,3).cut(cutout)}
        datum=dict(virtual_cable_origin=[0,0,0],virtual_cable_length_mm=12,nominal_cable_mm=4,lower_carrier='lo',upper_carrier='hi',jaws=['jlo','jhi'])
        return parts,datum,dict(required_nominal_interference_mm=.1,required_closing_gap_mm=.3)

    def test_adjustable_jaws_have_nominal_contact_and_travel(self):
        self.assertTrue(cable_grip(*self.grip_fixture())['passed'])

    def test_jaws_bottoming_before_clamping_are_rejected(self):
        self.assertFalse(cable_grip(*self.grip_fixture(gap=0))['passed'])

    def test_old_oversize_bore_cannot_pass_new_cable_contract(self):
        self.assertFalse(cable_grip(*self.grip_fixture(bore=4.4))['passed'])

    def cage_fixture(self):
        moving={'connector':box(0,0,1,10,20,8)}
        fixed={'floor':box(-2,-2,-2,14,24,3),'rear':box(-2,-2,1,14,1.7,10),
               'left':box(-2,-2,1,1.7,24,10),'right':box(10.3,-2,1,1.7,24,10),
               'stop':box(-.1,20.3,1,10.2,1.7,10),'cap':box(-2,-2,11,14,24,2)}
        return moving,fixed

    def test_removed_connector_stop_exposes_axial_escape(self):
        moving,fixed=self.cage_fixture()
        self.assertTrue(directional_witness(moving,fixed,[0,1,0])['blocked'])
        del fixed['stop']
        self.assertTrue(continuous_escape(moving,fixed,[0,35,0])['escape_proved'])

    def test_unfastened_keeper_lifts_and_releases_connector(self):
        moving,fixed=self.cage_fixture()
        self.assertTrue(directional_witness(moving,fixed,[0,0,1])['blocked'])
        cap=fixed.pop('cap');stop=fixed.pop('stop')
        self.assertTrue(prismatic_escape({'cap':cap,'stop':stop},{**fixed,**moving},35)['escape_proved'])
        self.assertTrue(continuous_escape(moving,fixed,[0,0,35])['escape_proved'])

class ElectricalCounterexamples(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.c=read(OUT/'data/electronics.json');cls.k=read(CONTRACT)['electrical']

    def test_active_pin_rules_are_not_all_passive(self):
        types=pin_types(self.c,self.k)
        self.assertEqual(types['U_ADC']['10'],'output');self.assertEqual(types['U_LIMIT']['3'],'input')

    def test_missing_active_pin_rule_is_an_error(self):
        k=copy.deepcopy(self.k);del k['pin_types']['U_ADC']['8']
        with self.assertRaises(ValueError):pin_types(self.c,k)

    def test_swapped_adc_pins_rejected(self):
        c=copy.deepcopy(self.c);adc=next(p for p in c['parts'] if p['ref']=='U_ADC')
        adc['pins']['9'],adc['pins']['10']=adc['pins']['10'],adc['pins']['9']
        self.assertTrue(critical_errors(c,self.k))

    def expected(self,c):
        return {p['ref']+'.'+pin:net if net is not None and not p.get('dnp') else 'NC_'+p['ref']+'_'+pin for p in c['parts'] for pin,net in p['pins'].items()}

    def test_omitted_copper_cuts_rejected(self):
        c=copy.deepcopy(self.c);expected=self.expected(c);c['cuts']=[]
        self.assertTrue(any(e.startswith('SHORT:') for e in reconstruct(c,expected)))

    def test_omitted_top_bridges_rejected(self):
        c=copy.deepcopy(self.c);expected=self.expected(c);c['bridges']=[]
        self.assertTrue(any(e.startswith('OPEN:') for e in reconstruct(c,expected)))

    def test_units_are_not_confused_with_rating(self):
        self.assertAlmostEqual(value(dict(ref='C',value='4.7 uF film/non-polar')),4.7e-6)
        self.assertAlmostEqual(value(dict(ref='C',value='10 uF >=16V')),10e-6)

    def test_open_cannot_become_pass(self):
        self.assertEqual(overall([dict(status='OPEN'),dict(status='PASS')]),'OPEN')

    def test_changed_input_invalidates_evidence(self):
        with patch('plausibility_common.bindings',return_value={'model':'new-hash'}):
            self.assertFalse(current(dict(source_binding={'model':'old-hash'})))

    def test_failed_audit_remains_an_explicit_package_finding(self):
        import run_plausibility
        with patch.object(run_plausibility,'read',return_value={'status':'FAIL','execution_status':'PASS','evidence_binding':{}}),patch.object(run_plausibility,'current',return_value=True),patch.object(Path,'exists',return_value=True):
            self.assertIn('FUNCTIONAL_AUDIT_FAIL',run_plausibility.release_guard())

    def test_open_audit_remains_an_explicit_package_finding(self):
        import run_plausibility
        with patch.object(run_plausibility,'read',return_value={'status':'OPEN','execution_status':'PASS','evidence_binding':{}}),patch.object(run_plausibility,'current',return_value=True),patch.object(Path,'exists',return_value=True):
            self.assertIn('FUNCTIONAL_AUDIT_OPEN',run_plausibility.release_guard())

if __name__=='__main__':unittest.main()
