"""Small generated RGBA/XML cases; no chart, private resources or fixtures."""
import unittest
from no_chart_converter.cache_country.bitmap_pivots import RGBAImage,normalize_bitmap_pivot_rows

def xml(*pivots, name='TEST0001',width=2,height=1):
    return ('<symbols>'+''.join('<symbol><name>'+name+'</name><bitmap width="'+str(width)+
        '" height="'+str(height)+'"><graphics-location x="0" y="0"/><pivot x="'+str(x)+
        '" y="'+str(y)+'"/></bitmap></symbol>'for x,y in pivots)+'</symbols>').encode()

class PixelPivotTests(unittest.TestCase):
    def setUp(self):
        self.image=RGBAImage(2,1,bytes([1,2,3,255,5,6,7,0]))
        self.row=b'TEST0001,1,0,0,2,1,-2250,-2302\r\n'
    def call(self,row=None,public=None,source=None,**kwargs):
        return normalize_bitmap_pivot_rows(self.row if row is None else row,self.image,
            xml((1,0))if source is None else source,self.image if public is None else public,**kwargs)
    def test_complete_pixel_binding_changes_only_offsets(self):
        out,receipt=self.call();self.assertEqual(out,b'TEST0001,1,0,0,2,1,-1,0\r\n')
        self.assertEqual(len(receipt['changes']),1)
    def test_one_transparent_rgb_byte_difference_is_unproven(self):
        other=RGBAImage(2,1,bytes([1,2,3,255,5,6,8,0]))
        out,r=self.call(public=other);self.assertEqual(out,self.row);self.assertEqual(len(r['unproven']),1)
    def test_same_pixels_different_dimensions_is_unproven(self):
        other=RGBAImage(1,2,self.image.pixels)
        out,r=self.call(public=other,source=xml((0,1),width=1,height=2))
        self.assertEqual(out,self.row);self.assertEqual(r['unproven'][0]['reason'],'PIXELS_OR_DIMENSIONS_DIFFER')
    def test_name_must_match(self):
        out,r=self.call(source=xml((1,0),name='OTHER001'));self.assertEqual(out,self.row)
        self.assertEqual(r['unproven'][0]['reason'],'NO_PUBLIC_BITMAP')
    def test_conflicting_matching_pivots_are_not_first_wins(self):
        out,r=self.call(source=xml((1,0),(0,1)));self.assertEqual(out,self.row)
        self.assertEqual(r['unproven'][0]['reason'],'AMBIGUOUS_MATCHING_BITMAP_PIVOTS')
    def test_identical_public_duplicates_are_allowed(self):
        out,r=self.call(source=xml((1,0),(1,0)));self.assertEqual(len(r['changes']),1)
    def test_already_exact_lexical_row_preserved(self):
        row=b'TEST0001,1.0,0,0,2,1,-1.0,0.00\n';out,r=self.call(row=row)
        self.assertEqual(out,row);self.assertEqual(r['alreadyExact'],['TEST0001'])
    def test_protected_own_asset_kept(self):
        out,r=self.call(protected_names=['TEST0001']);self.assertEqual(out,self.row)
        self.assertEqual(r['protected'],['TEST0001'])
    def test_nonunit_scale_not_silently_normalized(self):
        row=self.row.replace(b',1,0,0,',b',2,0,0,');out,r=self.call(row=row)
        self.assertEqual(out,row);self.assertEqual(r['unproven'][0]['reason'],'NON_UNIT_SCALE')
    def test_invalid_rect_refused_not_padded(self):
        with self.assertRaises(ValueError):self.call(row=b'TEST0001,1,1,0,2,1,0,0\n')
    def test_incomplete_rgba_refused(self):
        with self.assertRaises(ValueError):RGBAImage(2,1,b'1234')
    def test_duplicate_table_name_refused(self):
        with self.assertRaises(ValueError):self.call(row=self.row+self.row)
    def test_incomplete_same_name_variant_is_not_ignored(self):
        source=xml((1,0)).replace(b'</symbols>',
            b'<symbol><name>TEST0001</name><bitmap width="2" height="1">'
            b'<graphics-location x="0" y="0"/></bitmap></symbol></symbols>')
        out,r=self.call(source=source)
        self.assertEqual(out,self.row)
        self.assertEqual(r['unproven'][0]['reason'],'INCOMPLETE_PUBLIC_BITMAP_DEFINITION')
    def test_xml_entity_definitions_refused(self):
        with self.assertRaisesRegex(ValueError,'PUBLIC_BITMAP_XML_ENTITIES_FORBIDDEN'):
            self.call(source=b'<!DOCTYPE symbols [<!ENTITY own "test">]>'+xml((1,0)))
    def test_missing_integral_public_geometry_has_explicit_refusal(self):
        with self.assertRaisesRegex(ValueError,'INTEGRAL_PUBLIC_BITMAP_GEOMETRY_REQUIRED'):
            self.call(source=xml((1,0)).replace(b'width="2"',b''))
    def test_utf16_cannot_bypass_entity_refusal(self):
        text=('<?xml version="1.0" encoding="UTF-16"?>'
              '<!DOCTYPE symbols [<!ENTITY own "TEST0001">]>'+
              xml((1,0)).decode().replace('<name>TEST0001</name>','<name>&own;</name>'))
        with self.assertRaisesRegex(ValueError,'PUBLIC_BITMAP_XML_UTF8_REQUIRED'):
            self.call(source=text.encode('utf-16'))

if __name__=='__main__':unittest.main()
