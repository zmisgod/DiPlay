"""Compile the actual RGB conversion callback and check color order and write bounds."""
import ctypes,pathlib,subprocess,tempfile,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
class Rgb565Test(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.tmp=tempfile.TemporaryDirectory();p=pathlib.Path(cls.tmp.name)
  s=(ROOT/'main/main.c').read_text();callback=s[s.index('static int jpeg_output('):s.index('static uint8_t *decode_frame(')]
  (p/'test.c').write_text('''#include <stdint.h>
#include <stddef.h>
typedef struct {void *device;} JDEC;
typedef struct {unsigned left,right,top,bottom;} JRECT;
typedef struct {const uint8_t *jpeg;size_t length,offset;uint16_t *rgb;} jpeg_decode_ctx;
'''+callback+'''
int convert(uint16_t *out, uint8_t *in,unsigned left,unsigned right,unsigned top,unsigned bottom) {
 jpeg_decode_ctx ctx={.rgb=out};JDEC jd={.device=&ctx};JRECT rect={left,right,top,bottom};
 return jpeg_output(&jd,in,&rect);
}
''')
  subprocess.run(['cc','-shared','-fPIC','-Wall','-Werror',str(p/'test.c'),'-o',str(p/'test.so')],check=True)
  cls.lib=ctypes.CDLL(str(p/'test.so'));cls.lib.convert.argtypes=[ctypes.POINTER(ctypes.c_uint16),ctypes.POINTER(ctypes.c_uint8),ctypes.c_uint,ctypes.c_uint,ctypes.c_uint,ctypes.c_uint]
 @classmethod
 def tearDownClass(cls):cls.tmp.cleanup()
 def test_color_order_and_right_bottom_boundary(self):
  output=(ctypes.c_uint16*(466*466))();rgb=(ctypes.c_uint8*9)(0,0,255,0,255,0,255,0,0)
  self.assertEqual(1,self.lib.convert(output,rgb,463,465,465,465))
  self.assertEqual([0xf800,0x07e0,0x001f],list(output)[-3:]);self.assertEqual(0,output[0]);self.assertEqual(0,output[-4])
 def test_outside_and_inverted_rect_do_not_write(self):
  output=(ctypes.c_uint16*(466*466))();rgb=(ctypes.c_uint8*9)(*([255]*9))
  for rect in [(465,466,0,0),(0,0,465,466),(4,3,0,0),(0,0,4,3)]:
   self.assertEqual(0,self.lib.convert(output,rgb,*rect));self.assertFalse(any(output))
if __name__=='__main__':unittest.main()
