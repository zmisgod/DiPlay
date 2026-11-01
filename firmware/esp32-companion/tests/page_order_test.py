"""Exercise firmware page order in both directions without changing wire IDs."""
import ctypes,pathlib,subprocess,tempfile,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
class PageOrderTest(unittest.TestCase):
 def test_swipe_and_wheel_cycles_are_inverse(self):
  with tempfile.TemporaryDirectory() as tmp:
   p=pathlib.Path(tmp)
   (p/'test.c').write_text('#include "page_order.h"\nint next(int p){return companion_next_page(p);}\nint prev(int p){return companion_previous_page(p);}\n')
   subprocess.run(['cc','-shared','-fPIC','-Wall','-Werror','-I',str(ROOT/'main'),str(p/'test.c'),'-o',str(p/'test.so')],check=True)
   lib=ctypes.CDLL(str(p/'test.so'))
   page=0;forward=[]
   for _ in range(6):forward.append(page);page=lib.next(page)
   self.assertEqual([0,2,1,0,2,1],forward)
   for page in range(3):self.assertEqual(page,lib.prev(lib.next(page)))
   source=(ROOT/'main/main.c').read_text()
   self.assertIn('change_page(companion_next_page(page))',source)
   self.assertIn('change_page(companion_previous_page(page))',source)
   self.assertIn('p->valueint<0?companion_next_page(page):p->valueint',source)
if __name__=='__main__':unittest.main()
