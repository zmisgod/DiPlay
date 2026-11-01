"""Decode real JPEGs with the pinned LVGL TJPGD and the firmware's actual callbacks.

Requires Pillow and a C compiler. Unlike callback-only tests, this catches a change
in the decoder's RGB/BGR contract. No decoder implementation is mocked.
"""
import ctypes
import io
import pathlib
import shutil
import subprocess
import tempfile
import unittest
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[1]

class JpegPipelineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        target = pathlib.Path(cls.tmp.name)
        source = ROOT / 'managed_components/lvgl__lvgl/src/libs/tjpgd'
        decoder = target / 'libs/tjpgd'
        decoder.mkdir(parents=True)
        for name in ('tjpgd.c', 'tjpgd.h', 'tjpgdcnf.h'):
            shutil.copy2(source / name, decoder / name)
        # Only LVGL's enable switch is needed by the unmodified standalone decoder.
        (target / 'lv_conf_internal.h').write_text('#define LV_USE_TJPGD 1\n')
        firmware = (ROOT / 'main/main.c').read_text()
        start = firmware.index('typedef struct {\n    const uint8_t *jpeg;')
        callbacks = firmware[start:firmware.index('static uint8_t *decode_frame(')]
        (target / 'test.c').write_text('#include "libs/tjpgd/tjpgd.h"\n' + callbacks + '''
int decode(const uint8_t *jpeg, size_t length, uint16_t *out) {
    uint8_t work[4096];
    jpeg_decode_ctx ctx = {.jpeg=jpeg, .length=length, .rgb=out};
    JDEC decoder;
    JRESULT result = jd_prepare(&decoder, jpeg_input, work, sizeof(work), &ctx);
    if (result != JDR_OK) return result;
    if (decoder.width != 466 || decoder.height != 466) return JDR_FMT1;
    return jd_decomp(&decoder, jpeg_output, 0);
}
''')
        subprocess.run(['cc', '-shared', '-fPIC', '-Wall', '-Werror',
                        str(target / 'test.c'), str(decoder / 'tjpgd.c'),
                        '-o', str(target / 'test.so')], check=True)
        cls.lib = ctypes.CDLL(str(target / 'test.so'))
        cls.lib.decode.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.POINTER(ctypes.c_uint16)]
        cls.lib.decode.restype = ctypes.c_int

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_real_jpeg_primaries_map_tints_and_neutrals_keep_channel_order(self):
        colors = [(255, 0, 0), (0, 255, 0), (0, 0, 255),
                  (190, 215, 242), (255, 255, 255), (96, 96, 96)]
        image = Image.new('RGB', (466, 466))
        for index, color in enumerate(colors):
            x, y = index % 3, index // 3
            image.paste(color, (x*155, y*233, 466 if x == 2 else (x+1)*155, (y+1)*233))
        for subsampling in (0, 2):
            with self.subTest(subsampling=subsampling):
                encoded = io.BytesIO()
                image.save(encoded, format='JPEG', quality=85, subsampling=subsampling)
                data = encoded.getvalue()
                pixels = (ctypes.c_uint16 * (466*466))()
                self.assertEqual(0, self.lib.decode(data, len(data), pixels))
                for index, expected in enumerate(colors):
                    x, y = (index % 3)*155+75, (index // 3)*233+115
                    pixel = pixels[y*466+x]
                    actual = ((pixel >> 11)*255//31, ((pixel >> 5)&63)*255//63, (pixel&31)*255//31)
                    for got, want in zip(actual, expected):
                        self.assertLessEqual(abs(got-want), 12, (expected, actual))
