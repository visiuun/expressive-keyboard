"""Downloads Material Symbols (Rounded) SVGs and converts them to Android vector drawables.

Usage: python -I fetch_symbols.py <res_drawable_dir>
Material Symbols are Apache-2.0 licensed by Google.
"""
import os
import re
import sys
import urllib.request

out = sys.argv[1]
os.makedirs(out, exist_ok=True)

# (symbol name, fill, auto-mirror)
SYMBOLS = [
    ("check", 1, False), ("auto_awesome", 1, False), ("mood", 1, False), ("settings", 1, False),
    ("keyboard_return", 1, True), ("emoji_symbols", 1, False), ("delete", 1, False),
    ("content_paste", 1, False), ("close", 1, False), ("check_circle", 1, False),
    ("backspace", 1, True), ("swap_horiz", 1, False), ("push_pin", 1, False), ("push_pin", 0, False),
    ("back_hand", 1, False), ("palette", 1, False), ("open_in_full", 1, False),
    ("keyboard_arrow_down", 1, False), ("keyboard", 1, False), ("flag", 1, False),
    ("file_open", 1, False), ("emoji_people", 1, False), ("emoji_objects", 1, False),
    ("emoji_nature", 1, False), ("emoji_food_beverage", 1, False), ("emoji_events", 1, False),
    ("download", 1, False), ("directions_car", 1, False), ("chevron_right", 1, True),
    ("chevron_left", 1, True), ("schedule", 1, False), ("search", 1, False), ("send", 1, True),
    ("arrow_forward", 1, True), ("arrow_back", 1, True), ("shift", 0, False), ("shift", 1, False),
    ("shift_lock", 1, False), ("keyboard_hide", 1, False),
    ("mic", 1, False), ("home", 1, False), ("translate", 1, False), ("stop", 1, False),
    ("edit", 1, False), ("graphic_eq", 1, False), ("keyboard_voice", 1, False),
    ("dark_mode", 1, False), ("swipe", 1, False), ("spellcheck", 1, False), ("vibration", 1, False),
    ("info", 1, False), ("text_fields", 1, False), ("space_bar", 1, False), ("interests", 1, False),
    ("tune", 1, False), ("view_agenda", 1, False),
]

URL = "https://raw.githubusercontent.com/google/material-design-icons/master/symbols/web/{n}/materialsymbolsrounded/{n}{f}_24px.svg"

for name, fill, mirror in SYMBOLS:
    url = URL.format(n=name, f="_fill1" if fill else "")
    svg = urllib.request.urlopen(url, timeout=30).read().decode("utf-8")
    m = re.search(r'viewBox="([-\d. ]+)"', svg)
    if m:
        minx, miny, w, h = (float(v) for v in m.group(1).split())
    else:
        minx = miny = 0.0
        w = float(re.search(r'width="([\d.]+)"', svg).group(1))
        h = float(re.search(r'height="([\d.]+)"', svg).group(1))
    paths = re.findall(r'<path[^>]*\sd="([^"]+)"', svg)
    if not paths:
        raise SystemExit(f"no path in {name}")
    body = "\n".join(f'        <path android:fillColor="#FF000000" android:pathData="{d}" />' for d in paths)
    xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Material Symbols Rounded: {name} (fill {fill}) -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="{w:g}" android:viewportHeight="{h:g}"{' android:autoMirrored="true"' if mirror else ''}>
    <group android:translateX="{-minx:g}" android:translateY="{-miny:g}">
{body}
    </group>
</vector>
'''
    fname = f"sym_{name}{'' if fill else '_outline'}.xml"
    with open(os.path.join(out, fname), "w", encoding="utf-8") as fh:
        fh.write(xml)
    print("ok", fname)
