import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('capture', Path(__file__).with_name('analyze-navigation-capture.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CaptureAnalysisTest(unittest.TestCase):
    def test_roundtrip_repeated_unknown_and_malformed_fields(self):
        body = b'\x00\x05\xff\xff\x01' * 2 + b'\xff'
        fields = module.parameters(body, 0x52ff)
        self.assertEqual([65535, 65535], [p['id'] for p in fields[:2]])
        self.assertEqual('truncated_header', fields[2]['error'])
        self.assertEqual('01', fields[0]['hex'])

    def test_archive_integrity_and_action_markers(self):
        with tempfile.TemporaryDirectory() as tmp:
            raw = struct.pack('>HHH', 0x4040, 11, 0x5201) + b'\0\5\0\1\1'
            events = [dict(kind='capture_start'), dict(kind='frame', seq=1, direction='RX', messageId=0x5201,
                bodyBytes=5, csmHex=raw.hex(), sha256=hashlib.sha256(raw).hexdigest()),
                dict(kind='user_marker', seq=2, detail='destination_changed'), dict(kind='capture_end', complete=True)]
            data = ''.join(json.dumps(e) + '\n' for e in events).encode()
            path = Path(tmp) / 'test.zip'
            with zipfile.ZipFile(path, 'w') as z:
                z.writestr('capture/events.jsonl', data)
                z.writestr('export.json', json.dumps({'files': [{'path': 'capture/events.jsonl', 'bytes': len(data)}]}))
            result = module.analyze(path, Path(tmp) / 'out')
            self.assertEqual([], result['issues'])
            self.assertEqual(1, result['navigationFrames'])
            self.assertEqual('destination_changed', result['markers'][0]['detail'])

    def test_bad_hash_and_unclosed_session_are_not_reported_as_complete(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'bad.zip'
            with zipfile.ZipFile(path, 'w') as z:
                z.writestr('export.json', '{}')
                z.writestr('capture/events.jsonl', json.dumps(dict(kind='frame', seq=2, csmHex='00', sha256='wrong')) + '\n')
            result = module.analyze(path, Path(tmp) / 'out')
            self.assertTrue(any('SHA-256' in item for item in result['issues']))
            self.assertTrue(any('sequence gap' in item for item in result['issues']))
            self.assertFalse(result['sessions'][0]['cleanEnd'])


if __name__ == '__main__':
    unittest.main()
