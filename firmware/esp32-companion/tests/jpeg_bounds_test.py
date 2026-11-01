"""Exercise the firmware's actual JPEG header guard with a host C compiler."""
import ctypes
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]

class JpegBoundsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        source = (ROOT / 'main/main.c').read_text()
        guard = source[source.index('static bool jpeg_dimensions'):source.index('/* Takes ownership')]
        path = pathlib.Path(cls.tmp.name)
        (path / 'test.c').write_text('#include <stdbool.h>\n#include <stddef.h>\n#include <stdint.h>\n' + guard + '\nbool check(const uint8_t *b, size_t n) { return jpeg_dimensions(b,n); }\n')
        subprocess.run(['cc','-shared','-fPIC','-Wall','-Werror',str(path/'test.c'),'-o',str(path/'test.so')],check=True)
        cls.lib = ctypes.CDLL(str(path/'test.so'))
        cls.lib.check.argtypes = [ctypes.c_void_p,ctypes.c_size_t]
        cls.lib.check.restype = ctypes.c_bool
    @classmethod
    def tearDownClass(cls): cls.tmp.cleanup()
    def check(self,b): return self.lib.check(b,len(b))
    def header(self,width=466,height=466,marker=0xc0):
        return bytes([255,216,255,marker,0,17,8,height>>8,height&255,width>>8,width&255,3,1,0x22,0,2,0x11,1,3,0x11,1])
    def test_expected_dimensions_and_baseline_only(self):
        self.assertTrue(self.check(self.header()))
        for w,h in [(0,466),(466,0),(1920,532),(465,466),(466,65535)]: self.assertFalse(self.check(self.header(w,h)))
        self.assertFalse(self.check(self.header(marker=0xc2)))
    def test_truncation_and_segment_boundaries(self):
        b=self.header()
        for n in range(len(b)): self.assertFalse(self.check(b[:n]),n)
        self.assertTrue(self.check(b[:2]+bytes([255,224,0,4,1,2])+b[2:]))
        self.assertFalse(self.check(b[:2]+bytes([255,224,255,255])+b[2:]))
        self.assertFalse(self.check(b[:2]+bytes([255,224,0,0])+b[2:]))
        self.assertFalse(self.check(bytes([255])*1024))
        self.assertFalse(self.check(b'not a jpeg'))

if __name__ == '__main__': unittest.main()
