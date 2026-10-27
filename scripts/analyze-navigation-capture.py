#!/usr/bin/env python3
"""Offline, lossless inspection of DiPlay navigation ZIPs. Python 3 standard library only.
Never treats an unknown integer/byte array as verified coordinates or a route ID.
"""
import argparse
import collections
import hashlib
import json
import pathlib
import struct
import zipfile

NAMES = {
    0x5201: {1: 'routeState', 3: 'currentRoad', 5: 'arrivalEpochSeconds',
             6: 'remainingSeconds', 7: 'remainingMeters', 10: 'distanceToManeuverMeters',
             13: 'currentManeuverIndices'},
    0x5202: {1: 'maneuverIndex', 3: 'maneuverType', 4: 'roadAfterManeuver', 8: 'drivingSide'},
}


def parameters(body, message_id):
    """Preserve order, duplicates, unknown values and malformed tails."""
    offset, result = 0, []
    while offset < len(body):
        if len(body) - offset < 4:
            result.append({'offset': offset, 'error': 'truncated_header', 'remainingHex': body[offset:].hex()})
            break
        length, field = struct.unpack_from('>HH', body, offset)
        if length < 4 or length > len(body) - offset:
            result.append({'offset': offset, 'error': 'invalid_length', 'declaredBytes': length,
                           'remainingHex': body[offset:].hex()})
            break
        value = body[offset + 4:offset + length]
        name = NAMES.get(message_id, {}).get(field)
        item = {'offset': offset, 'id': field, 'name': name or 'unknown', 'bytes': len(value), 'hex': value.hex()}
        if name in ('currentRoad', 'roadAfterManeuver'):
            try:
                item['value'] = value.split(b'\0', 1)[0].decode('utf-8', errors='strict')
            except UnicodeDecodeError:
                item['decodeError'] = 'invalid_utf8'
        elif name == 'currentManeuverIndices' and len(value) % 2 == 0:
            item['value'] = list(struct.unpack('>' + 'H' * (len(value) // 2), value))
        elif name:
            expected = {'routeState': 1, 'arrivalEpochSeconds': 8, 'remainingSeconds': 8,
                        'remainingMeters': 4, 'distanceToManeuverMeters': 4,
                        'maneuverIndex': 2, 'maneuverType': 1, 'drivingSide': 1}[name]
            if len(value) == expected:
                item['value'] = int.from_bytes(value, 'big')
            else:
                item['decodeError'] = f'expected_{expected}_bytes'
        result.append(item)
        offset += length
    return result


def analyze(archive, destination):
    destination = pathlib.Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    summary = {'source': str(archive), 'records': 0, 'navigationFrames': 0, 'messageCounts': {},
               'sessions': [], 'markers': [], 'issues': [],
               'scope': '0x5200..0x52ff full CSM frames; other message IDs/lengths only. No video or file transfer assets.',
               'geometryConclusion': 'Not established. Inspect unknown TLVs; maneuver indices are not route IDs. '
                                     'No Route Sharing capability is enabled by this recorder.'}
    counts = collections.Counter()
    previous = {}
    with zipfile.ZipFile(archive) as source, (destination / 'decoded.jsonl').open('w', encoding='utf-8') as out:
        export = json.loads(source.read('export.json'))
        for entry in export.get('files', []):
            try:
                if source.getinfo(entry['path']).file_size != entry['bytes']:
                    summary['issues'].append(f"{entry['path']}: export length mismatch")
            except KeyError:
                summary['issues'].append(f"{entry['path']}: missing file")
        for name in sorted(source.namelist()):
            if not name.endswith('/events.jsonl'):
                continue
            session = {'path': name, 'cleanEnd': False, 'lastSeq': 0, 'captureStart': None}
            summary['sessions'].append(session)
            with source.open(name) as stream:
                for line_no, line in enumerate(stream, 1):
                    try:
                        event = json.loads(line)
                    except (ValueError, UnicodeDecodeError):
                        summary['issues'].append(f'{name}:{line_no}: invalid/truncated JSON record')
                        continue
                    summary['records'] += 1
                    if 'seq' in event:
                        if event['seq'] != session['lastSeq'] + 1:
                            summary['issues'].append(f'{name}:{line_no}: sequence gap')
                        session['lastSeq'] = event['seq']
                    kind = event.get('kind')
                    if kind == 'capture_start':
                        session['captureStart'] = event
                    if kind == 'capture_end':
                        session['cleanEnd'] = bool(event.get('complete'))
                        if not session['cleanEnd']:
                            summary['issues'].append(f"{name}: {event.get('error')}")
                    if kind == 'user_marker':
                        summary['markers'].append({'capture': name, **event})
                    if 'messageId' in event:
                        counts[f"{event.get('direction')} 0x{event['messageId']:04x} {event.get('messageName')}"] += 1
                    if 'csmHex' in event:
                        try:
                            raw = bytes.fromhex(event['csmHex'])
                            assert hashlib.sha256(raw).hexdigest() == event['sha256'], 'SHA-256 mismatch'
                            assert len(raw) >= 6, 'short CSM'
                            magic, size, mid = struct.unpack_from('>HHH', raw)
                            assert magic == 0x4040 and size == len(raw), 'invalid CSM boundary'
                            assert mid == event['messageId'] and len(raw) - 6 == event['bodyBytes'], 'header mismatch'
                            event['parameters'] = parameters(raw[6:], mid)
                            key = (name, event.get('linkId'), event.get('direction'), mid)
                            before = previous.get(key, [])
                            event['changedParameterPositions'] = [i for i in range(max(len(before), len(event['parameters'])))
                                if (before[i] if i < len(before) else None) !=
                                   (event['parameters'][i] if i < len(event['parameters']) else None)]
                            previous[key] = event['parameters']
                            summary['navigationFrames'] += 1
                        except (ValueError, AssertionError, struct.error) as error:
                            summary['issues'].append(f'{name}:{line_no}: {error}')
                    event['captureFile'] = name
                    out.write(json.dumps(event, ensure_ascii=False) + '\n')
            if not session['cleanEnd']:
                summary['issues'].append(f'{name}: no clean capture_end (live snapshot, process killed, or recording failure)')
    summary['messageCounts'] = dict(sorted(counts.items()))
    (destination / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    return summary


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('zip', type=pathlib.Path)
    parser.add_argument('--out', type=pathlib.Path, required=True)
    args = parser.parse_args()
    result = analyze(args.zip, args.out)
    print(json.dumps({k: result[k] for k in ['records', 'navigationFrames', 'messageCounts', 'issues']}, ensure_ascii=False, indent=2))
