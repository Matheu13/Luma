# Regression audit for Luma on a Fire TV device over ADB, using remote key events only
# (screen taps put Android in touch mode and swallow the next arrow). Restores dock order
# and ends on the Nebula theme. Expects a dock of 7 apps and Home on Fire set up.
# Any script error is saved to error.txt with its traceback and counted as a failure.
# Needs LUMA_DEVICE (ADB serial) and ANDROID_SDK_ROOT (or adb on PATH), from the environment or tools/local.json.
# Run: python tools/regression-audit.py   (results in build/audit/)
import json, os, re, shutil, subprocess, sys, time, traceback
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
_local = ROOT / 'tools' / 'local.json'
_cfg = json.loads(_local.read_text()) if _local.exists() else {}
def setting(name): return os.environ.get(name) or _cfg.get(name)
_sdk = setting('ANDROID_SDK_ROOT')
ADB = str(Path(_sdk) / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')) if _sdk else shutil.which('adb')
DEV = setting('LUMA_DEVICE')
if not ADB or not DEV: sys.exit('Set LUMA_DEVICE and ANDROID_SDK_ROOT (or put adb on PATH)')
OUT = ROOT / 'build' / 'audit'; OUT.mkdir(parents=True, exist_ok=True)
results = []

def adb(*a, timeout=40):
    r = subprocess.run([ADB, '-s', DEV, *a], capture_output=True, timeout=timeout)
    if r.returncode: raise RuntimeError(f'adb {" ".join(a)} failed: ' + r.stderr.decode(errors='replace'))
    return r.stdout
def sh(*a): return adb('shell', *a).decode(errors='replace')
def key(k, wait=0.9, long=False):
    sh('input', 'keyevent', *(['--longpress'] if long else []), str(k)); time.sleep(wait)
def fg(): return sh('dumpsys', 'activity', 'activities').split('mResumedActivity:')[-1].splitlines()[0].strip()
def shot(name): (OUT / f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p'))
def record(name, ok, evidence):
    results.append(dict(test=name, status='PASS' if ok else 'FAIL', evidence=evidence))
    (OUT / 'results.json').write_text(json.dumps(results, indent=2)); print(results[-1]['status'], name, '|', evidence, flush=True)
def nodes():
    # uiautomator occasionally fails to reach an idle state right after a transition; retry once.
    for attempt in range(2):
        try:
            sh('uiautomator', 'dump', '/sdcard/a9.xml'); return list(ET.fromstring(sh('cat', '/sdcard/a9.xml')).iter('node'))
        except (RuntimeError, ET.ParseError):
            if attempt: raise
            time.sleep(1.5)
def texts(): return [n.get('text') for n in nodes() if n.get('text')]
def selected():
    for n in nodes():
        if n.get('selected') == 'true' and re.search(r'(dock|app) \d+ of', n.get('content-desc', '')): return n.get('content-desc')
    return ''
def dock():
    return [re.sub(r', dock.*', '', n.get('content-desc')) for n in nodes() if re.search(r'dock \d of \d', n.get('content-desc', ''))]
def root():
    sh('am', 'force-stop', 'com.dhyn.luma'); sh('am', 'start', '-W', '-n', 'com.dhyn.luma/com.dhyn.tvhome.LumaLauncher'); time.sleep(2)
def go_to(prefix):
    for _ in range(9):
        if selected().startswith(prefix): return True
        key(22)
    return False
def tap(text):
    for n in nodes():
        if n.get('text') == text:
            x, y, xx, yy = map(int, re.findall(r'\d+', n.get('bounds'))); sh('input', 'tap', str((x + xx) // 2), str((y + yy) // 2)); time.sleep(1.5); return True
    return False
def move_first_right():
    # Hold OK on dock slot 1 -> Move app -> Right -> OK. Returns False (and backs out) if any step is not as expected.
    first = dock()[0]
    if not selected().startswith(first) and not go_to(first): return False
    key(23, 1.2, long=True)
    if 'Move app' not in texts(): return False
    key(20, 0.8); key(19, 0.8)
    if not any(n.get('selected') == 'true' and n.get('text') == 'Move app' for n in nodes()): key(4); return False
    key(23, 1.0); key(22, 1.0); moving = selected(); key(23, 1.0)
    return moving.startswith('Moving')

def panel_selected():
    return next((n.get('text') for n in nodes() if n.get('selected') == 'true' and n.get('text') and n.get('text') != 'Home tab'), '')
def choose_theme(name):
    # Menu -> Settings (third row) -> Appearance (first row) -> step to the theme -> OK.
    key(82, 1.0); key(20, 0.6); key(20, 0.6)
    if panel_selected() != 'Settings': key(4); return False
    key(23, 1.0); key(23, 1.0)
    for _ in range(6):
        if panel_selected() == name: key(23, 1.0); return True
        key(20, 0.6)
    key(4); key(4); return False

def run():
    root(); record('Cold launch', 'com.dhyn.luma/' in fg(), fg()); shot('01-launch')
    original = dock(); record('Dock read through accessibility nodes', len(original) == 7, ' / '.join(original))
    record('Missing apps not shown', 'Netflix' not in original, 'Netflix absent from dock')

    key(85, 1.5); record('Play/Pause opens nothing', 'Lunar' not in texts() and 'com.dhyn.luma/' in fg(), 'no Appearance dialog after keycode 85')
    key(4, 2.5); record('Back on home stays in Luma', 'com.dhyn.luma/' in fg(), fg())

    # Long Home runs early: after a burst of Home presses, Home on Fire's press-pattern handling sometimes treats ADB's
    # synthetic long press as a short one (a real remote hold is unaffected). One retry; a retry is noted in the evidence.
    for attempt in (1, 2):
        time.sleep(6); key(3, 3, long=True)
        if 'com.amazon.tv.launcher/' in fg(): break
    record('Long Home reaches Amazon launcher', 'com.amazon.tv.launcher/' in fg(), fg() + ('' if attempt == 1 else ' (on retry)'))
    time.sleep(5)  # let Home on Fire settle, so a late redirect cannot reset Luma during the next step

    # Move flow with persistence and restore.
    root(); ok = move_first_right(); root(); after = dock()
    record('Move swaps dock slots 1 and 2', ok and after[:2] == original[1::-1], ' / '.join(after[:3])); shot('02-moved-after-restart')
    ok = move_first_right(); root(); restored = dock()
    record('Move back restores original order', ok and restored == original, ' / '.join(restored))

    # Settings gear (Up -> Home, Right -> Apps, Right -> gear) opens Luma's Settings panel; its second row opens Fire TV Settings.
    root(); key(19); key(22); key(22); key(23, 1.2); items = texts()
    record('Gear opens Luma Settings panel', 'Appearance' in items and 'About Luma' in items, ' / '.join(t for t in items if t))
    key(20, 0.8); key(23, 3); record('Fire TV Settings opens from the panel', 'MainSettingsActivity' in fg(), fg())
    key(4, 2.5); record('Back from Settings returns Luma', 'com.dhyn.luma/' in fg(), fg())

    # Each dock tile opens its app; Home returns to Luma.
    for i, name in enumerate(original):
        root()
        for _ in range(i): key(22, 0.7)
        if not selected().startswith(name): key(22, 0.7)  # first arrow can be swallowed after force-stop
        if not selected().startswith(name): record(f'Dock tile {name} opens app', False, 'could not select tile: ' + selected()); continue
        try: adb('logcat', '-c')  # Fire OS sometimes refuses to clear the log; it is only used for diagnosis below
        except RuntimeError: pass
        key(23, 5); front = fg()
        if 'com.dhyn.luma/' in front:
            # Distinguish "Luma never asked" from "the app started and then crashed".
            log = adb('logcat', '-d', '-t', '400').decode(errors='replace')
            sent = re.search(r'START u0 .*?cmp=([\w.]+)/', log); died = re.search(r'Process ([\w.]+) \(pid \d+\) has died', log)
            front = (f'launch sent to {sent.group(1)}' if sent else 'no launch request seen') + (f'; {died.group(1)} crashed' if died else '')
        record(f'Dock tile {name} opens app', 'com.dhyn.luma/' not in fg(), front)
        key(3, 3); record(f'Home from {name} returns Luma', 'com.dhyn.luma/' in fg(), fg())

    record('Amazon remains OS Home', 'com.amazon.tv.launcher/' in sh('cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME'), 'resolver')

    # Themes through Menu > Settings > Appearance with remote keys, ending on Nebula.
    root()
    for t in ['Lunar', 'Aurora', 'Cinema Shelf', 'Prism', 'Nebula']:
        ok = choose_theme(t); time.sleep(1.5)
        shot('theme-' + t.replace(' ', '-'))
        record(f'Theme {t} renders', ok and 'Lunar' not in texts() and 'com.dhyn.luma/' in fg(), fg() if ok else 'could not reach the theme in the Appearance panel')

    (OUT / 'memory.txt').write_text(sh('dumpsys', 'meminfo', 'com.dhyn.luma'))

if __name__ == '__main__':
    if sh('getprop', 'sys.boot_completed').strip() != '1': sys.exit('device not ready')
    sh('input', 'keyevent', '224'); time.sleep(1)
    (OUT / 'error.txt').unlink(missing_ok=True)
    try:
        run()
    except Exception:
        (OUT / 'error.txt').write_text(traceback.format_exc())
        record('Audit script ran to completion', False, 'stopped with an error; see error.txt: ' + traceback.format_exc().strip().splitlines()[-1])
    print('AUDIT COMPLETE', sum(r['status'] == 'PASS' for r in results), 'of', len(results), 'passed', flush=True)
    sys.exit(0 if all(r['status'] == 'PASS' for r in results) else 1)
