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
    for _ in range(7):
        if panel_selected() == name: key(23, 1.0); return True
        key(20, 0.6)
    key(4); key(4); return False

def top_diff(a, b):
    # Mean per-pixel difference of the top half of two screenshots (0 = identical, 255 = opposite).
    from PIL import Image, ImageChops
    im = [Image.open(OUT / f'{n}.png').convert('L').crop((0, 0, 1920, 540)) for n in (a, b)]
    return sum(ImageChops.difference(*im).getdata()) / (1920 * 540)

def frames_while_browsing():
    # Twelve arrow presses along the first row, then Luma's own frame statistics: (frames from key presses, janky percent).
    # Waits first so the start-up hint has finished fading. The clock redraws once a second and each ADB key press
    # takes about 2.5 s on a Fire TV, so one frame per elapsed second is subtracted: a static theme leaves about one
    # frame per press, an animated focus many more.
    time.sleep(8); sh('dumpsys', 'gfxinfo', 'com.dhyn.luma', 'reset'); start = time.time()
    for k in [22] * 6 + [21] * 6: key(k, 0.4)
    elapsed = time.time() - start; info = sh('dumpsys', 'gfxinfo', 'com.dhyn.luma')
    total = int(re.search(r'Total frames rendered: (\d+)', info).group(1)); jank = float(re.search(r'Janky frames: \d+ \(([\d.]+)%\)', info).group(1))
    return total - round(elapsed), jank

def top_area_darkness(name):
    # Mean brightness (0-255) of the top area's empty right side, behind where its white title sits.
    from PIL import Image
    return sum(Image.open(OUT / f'{name}.png').convert('L').crop((1300, 300, 1900, 460)).getdata()) / (600 * 160)

def untinted_tiles(name):
    # Second-row tiles (unfocused, page not scrolled): each tile's top strip, above its banner, is the app's own tint.
    # Counts tiles whose strip is still nearly the bare wallpaper, i.e. drawn see-through. Screen = design * 1920/1672.
    from PIL import Image, ImageChops
    k = 1920 / 1672; shot_im = Image.open(OUT / f'{name}.png').convert('RGB')
    wall = Image.open(ROOT / 'app' / 'assets' / 'daylight-wallpaper.png').convert('RGB').resize((1920, 1081))
    pale = 0
    for col in range(7):
        l = 52 + col * 228; box = (int((l + 20) * k), int(662 * k), int((l + 180) * k), int(668 * k))
        d = ImageChops.difference(shot_im.crop(box), wall.crop(box)); diff = sum(sum(px) for px in d.getdata()) / (3 * len(d.getdata()))
        pale += diff < 30
    return pale

def top_bar_overlap():
    # Bounds of dock tiles (screen px) that intrude into the top bar's band (y 20-110) while the page is scrolled.
    return [n.get('content-desc') for n in nodes() if re.search(r'dock \d of', n.get('content-desc', ''))
            and (lambda y, yy: y < 110 and yy > 20)(*map(int, re.findall(r'\d+', n.get('bounds'))[1::2]))]

def prism_shape(name):
    # Prism's round tiles, measured on a screenshot taken with the first app focused. Design space -> screen = 1920/1672.
    # Returns (unfocused tiles whose bounding-square corner shows bare wallpaper, focused circle's top-ring brightness).
    from PIL import Image, ImageChops
    k = 1920 / 1672; im = Image.open(OUT / f'{name}.png').convert('RGB')
    wall = Image.open(ROOT / 'app' / 'assets' / 'prism-wallpaper.png').convert('RGB').resize((1920, 1081))
    def patch(x, y): return (int((x - 3) * k), int((y - 3) * k), int((x + 3) * k), int((y + 3) * k))
    round_ = 0
    for col in range(1, 7):
        cx, cy = 60 + col * 221 + 100.5, 485; box = patch(cx - 80, cy - 80)
        d = ImageChops.difference(im.crop(box), wall.crop(box)); round_ += sum(sum(px) for px in d.getdata()) / (3 * len(d.getdata())) < 20
    ring = sum(im.convert('L').crop(patch(160.5, 385)).getdata()) / len(im.convert('L').crop(patch(160.5, 385)).getdata())
    return round_, ring

def row_diff(a, b):
    # Mean per-pixel difference of the app-row band (screen y 420-1000) of two screenshots.
    from PIL import Image, ImageChops
    im = [Image.open(OUT / f'{n}.png').convert('L').crop((0, 420, 1920, 1000)) for n in (a, b)]
    return sum(ImageChops.difference(*im).getdata()) / (1920 * 580)

def option(name):
    # Steps down an open Luma panel to the row called name and picks it. Returns False (panel closed) if it is not there.
    if name not in texts(): key(4); return False
    for _ in range(6):
        if panel_selected() == name: key(23, 1.0); return True
        key(20, 0.6)
    key(4); return False

def long_row_checks(before):
    # Aurora's home row takes more than seven apps: add one from All apps, walk to the end, check a fixed-layout
    # theme still shows seven, then remove it. The finally block puts the viewer's home row back whatever happens.
    added = None
    try:
        root(); key(19); key(22); key(23, 1.5)                      # top bar > Apps > OK: All apps
        for _ in range(40):
            name = re.sub(r', app.*', '', selected())
            if name and name not in before: break
            key(22, 0.7)
        key(23, 1.2, long=True); added = name; ok = option('Add to home')
        key(4, 1.0); root()
        for _ in range(10): key(22, 0.6)
        last = selected(); shot('aurora-row-end'); root(); shot('aurora-row-start')
        record('Aurora home row takes an 8th app', ok and last.startswith(name) and 'dock 8 of 8' in last, last)
        d = row_diff('aurora-row-start', 'aurora-row-end')
        record('Aurora row slides to the last app and stops there', 'dock 8 of 8' in last and d > 8, f'row difference {d:.1f}')
        choose_theme('Lunar'); root(); shown = dock()
        record('Fixed themes still show seven', shown == before, ' / '.join(shown))
        # Choosing a dock slot from All apps in a seven-tile theme lists only the seven visible slots.
        key(19); key(22); key(23, 1.5); key(23, 1.2, long=True); option('Add to dock'); slots = [t for t in texts() if t.startswith('Slot ')]
        key(4); key(4); key(4)
        record('Fixed themes offer only seven dock slots', len(slots) == 7, f'{len(slots)} slots offered')
        choose_theme('Aurora')
    finally:
        root(); choose_theme('Aurora')
        if added:
            root()
            for _ in range(10): key(22, 0.6)
            if selected().startswith(added): key(23, 1.2, long=True); option('Remove from home')
        root()
        for _ in range(10): key(22, 0.6)
        last = selected()
        root(); restored = dock()
        record('Aurora remove restores the home row', 'dock 7 of 7' in last and restored == before, ' / '.join(restored))

def daylight_checks():
    # Daylight shows every app: the dock in row 1, the rest below. Starts and ends on row 1 of a fresh Luma.
    shot('theme-Daylight'); b = top_area_darkness('theme-Daylight')
    record('Daylight top area keeps white text readable', b < 120, f'mean brightness {b:.0f}')
    pale = untinted_tiles('theme-Daylight'); record('Daylight tiles keep their full tint', pale == 0, f'{pale} of 7 second-row tiles drawn see-through')
    key(22); shot('daylight-next')
    d = top_diff('theme-Daylight', 'daylight-next'); record('Daylight top area follows focus', d > 8, f'top-half difference {d:.1f}')
    key(21); key(20); shot('daylight-scrolled'); d = top_diff('theme-Daylight', 'daylight-scrolled')
    record('Daylight scrolls down', d > 8, f'top-half difference {d:.1f}')
    over = top_bar_overlap(); record('Daylight scrolled rows clear the top bar', not over, ' / '.join(over) or 'no dock tile under the top bar'); key(19)
    key(20); record('Daylight down reaches lower rows', ', app ' in selected(), selected())
    key(19); record('Daylight up returns to the dock row', ', dock ' in selected(), selected())
    key(20); key(23, 1.2, long=True); items = texts()
    record('Daylight lower row offers Add to dock', 'Add to dock' in items, ' / '.join(t for t in items if t)); key(4)
    key(23, 5); record('Daylight lower-row app opens', 'com.dhyn.luma/' not in fg(), fg())
    key(3, 3); record('Home returns Luma from Daylight', 'com.dhyn.luma/' in fg(), fg())
    # Moving the last dock app down must not swap it with an app outside the dock.
    root(); before = dock(); ok = go_to(before[6])
    if ok: key(23, 1.2, long=True); ok = 'Move app' in texts()
    if ok: key(23, 1.0); key(20, 1.0); key(23, 1.0)
    root(); record('Daylight move stays in the dock row', ok and dock() == before, ' / '.join(dock()))
    total, jank = frames_while_browsing()
    record('Daylight focus animates smoothly', total > 36 and jank < 10, f'{total} frames from 12 presses, {jank}% janky')

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

    # Appearance (Menu > Settings > Appearance) lists all six themes; Menu closes the panels again.
    root(); key(82, 1.0); key(20, 0.6); key(20, 0.6); key(23, 1.0); key(23, 1.0); items = texts(); key(82, 1.0)
    themes = ['Lunar', 'Aurora', 'Cinema Shelf', 'Prism', 'Daylight', 'Nebula']
    record('Appearance lists six themes', all(t in items for t in themes), ' / '.join(t for t in items if t))

    # Themes through Menu > Settings > Appearance with remote keys, ending on Nebula.
    root()
    for t in themes:
        ok = choose_theme(t); time.sleep(1.5)
        shot('theme-' + t.replace(' ', '-'))
        record(f'Theme {t} renders', ok and 'Lunar' not in texts() and 'com.dhyn.luma/' in fg(), fg() if ok else 'could not reach the theme in the Appearance panel')
        if t == 'Daylight' and ok: daylight_checks()
        if t == 'Aurora' and ok: long_row_checks(original)
        if t == 'Prism' and ok:
            rounded, ring = prism_shape('theme-Prism')
            record('Prism tiles are round', rounded == 6, f'{rounded} of 6 unfocused tiles show wallpaper at their corners')
            record('Prism focus is larger with a ring', ring > 200, f'ring brightness {ring:.0f} above the focused circle')
            total, jank = frames_while_browsing(); record('Prism stays static', total <= 24, f'{total} frames from 12 presses on Prism')
    # Back on Nebula: focus moves must stay static there (about one frame per key press plus the clock).
    total, jank = frames_while_browsing()
    record('Other themes stay static', total <= 24, f'{total} frames from 12 presses on Nebula')

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
