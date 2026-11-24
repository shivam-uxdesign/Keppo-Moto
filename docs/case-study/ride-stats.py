#!/usr/bin/env python3
"""
Real numbers for the case study, from the app's own export.

On the phone: Profile > Beta tools > Export all rides, save the ZIP, copy it to the computer
(or `adb pull` it from Downloads). Then:

    python3 docs/case-study/ride-stats.py path/to/ridetrack-export-N-rides.zip

Writes case-study-stats.json next to the ZIP. It holds totals and one showcase ride's
speed and lean trace. Places are removed: the route is kept only as a shape, in metres from
an anchor, with the first and last kilometre cut off so home or work can't be read from it.
Send that JSON in the chat. The ZIP itself stays with you.
"""
import json, math, sys, zipfile, collections, os

def load(zpath):
    rides, moments, logs = [], collections.defaultdict(list), collections.defaultdict(str)
    with zipfile.ZipFile(zpath) as z:
        for n in z.namelist():
            folder = os.path.dirname(n)
            if n.endswith('.json') and os.path.basename(n) != 'moments.json':
                try:
                    r = json.loads(z.read(n))
                    if 'stats' in r: r['_folder'] = folder; rides.append(r)
                except Exception: pass
            elif n.endswith('moments.json'):
                try: moments[folder] = json.loads(z.read(n))
                except Exception: pass
            elif n.endswith('moments-log.txt'):
                logs[folder] = z.read(n).decode('utf-8', 'replace')
    return rides, moments, logs

def hav(a, b):
    R = 6371000.0
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * R * math.asin(math.sqrt(h))

def showcase(r, points=600, trim_m=1000):
    s = [p for p in r.get('samples', []) if p.get('lat') is not None and p.get('lon') is not None]
    if len(s) < 50: return None
    # Distance along the ride, to cut the first and last kilometre.
    d = [0.0]
    for a, b in zip(s, s[1:]): d.append(d[-1] + hav((a['lat'], a['lon']), (b['lat'], b['lon'])))
    keep = [i for i in range(len(s)) if trim_m <= d[i] <= d[-1] - trim_m]
    if len(keep) < 50: return None
    step = max(1, len(keep) // points)
    keep = keep[::step]
    o = s[keep[0]]
    k = math.cos(math.radians(o['lat']))
    out = []
    for i in keep:
        p = s[i]
        out.append({
            'x': round((p['lon'] - o['lon']) * 111320 * k), 'y': round((p['lat'] - o['lat']) * 110540),
            't': round((d[i] - d[keep[0]]) / 1000, 3),
            'kmh': None if p.get('speedMps') is None else round(p['speedMps'] * 3.6, 1),
            'lean': None if p.get('leanDeg') is None else round(p['leanDeg'], 1),
            'longG': None if p.get('longG') is None else round(p['longG'], 2),
            'gear': p.get('gear'), 'rpm': None if p.get('rpm') is None else round(p['rpm']),
        })
    return {'name': r.get('name'), 'date': (r.get('start') or '')[:10], 'distanceKm': round(r['stats'].get('distanceM', 0) / 1000, 1), 'points': out}

def main():
    if len(sys.argv) < 2: print(__doc__); sys.exit(1)
    rides, moments, logs = load(sys.argv[1])
    rides = [r for r in rides if r.get('source') != 'DEMO' and r.get('status') != 'DEMO']
    if not rides: print('No rides found in the ZIP.'); sys.exit(1)
    st = lambda r, k: r['stats'].get(k) or 0
    km = sum(st(r, 'distanceM') for r in rides) / 1000
    moving_h = sum(st(r, 'movingMs') for r in rides) / 3.6e6
    total_h = sum(st(r, 'durationMs') for r in rides) / 3.6e6
    types = collections.Counter()
    kinds = collections.Counter()
    clip_s = 0.0
    for lst in moments.values():
        for m in lst:
            kinds[m.get('kind')] += 1
            for t in (m.get('types') or 'MANUAL').split('+'): types[t or 'MANUAL'] += 1
            clip_s += (m.get('durationMs') or 0) / 1000
    dates = sorted(r.get('start', '')[:10] for r in rides if r.get('start'))
    longest = max(rides, key=lambda r: st(r, 'distanceM'))
    out = {
        'rides': len(rides), 'firstRide': dates[0] if dates else None, 'lastRide': dates[-1] if dates else None,
        'km': round(km, 1), 'hoursRiding': round(total_h, 1), 'hoursMoving': round(moving_h, 1),
        'longestRideKm': round(st(longest, 'distanceM') / 1000, 1),
        'topSpeedKmh': round(max(st(r, 'maxSpeedMps') for r in rides) * 3.6, 1),
        'deepestLeanDeg': round(max(max(abs(st(r, 'maxLeftLeanDeg')), abs(st(r, 'maxRightLeanDeg'))) for r in rides), 1),
        'events': {k: sum(st(r, k) for r in rides) for k in ('brakeEvents', 'accelEvents', 'leanEvents', 'stops', 'leftTurns', 'rightTurns')},
        'breakHours': round(sum(st(r, 'breakMs') for r in rides) / 3.6e6, 1),
        'moments': {'total': sum(kinds.values()), 'byKind': dict(kinds), 'byTrigger': dict(types), 'minutesFilmed': round(clip_s / 60, 1)},
        'ridesWithVoiceLog': sum(1 for v in logs.values() if v),
        'appVersions': sorted({r.get('appVersion') for r in rides if r.get('appVersion')}),
        'showcase': showcase(longest),
    }
    dst = os.path.join(os.path.dirname(os.path.abspath(sys.argv[1])), 'case-study-stats.json')
    with open(dst, 'w') as f: json.dump(out, f, indent=1)
    s = dict(out); s.pop('showcase')
    print(json.dumps(s, indent=1)); print('\nSaved', dst, '(send this file; it has no places in it)')

if __name__ == '__main__':
    main()
