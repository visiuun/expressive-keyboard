"""Adds CLDR search keywords to app/src/main/assets/emoji.txt (4th, '|'-separated column).

Gboard's emoji search matches these keywords ("lol" -> 😂, "party" -> 🎉), not only the
official names. Download the annotation files first:

  https://raw.githubusercontent.com/unicode-org/cldr/main/common/annotations/en.xml
  https://raw.githubusercontent.com/unicode-org/cldr/main/common/annotationsDerived/en.xml

Usage: python tools/add_emoji_keywords.py <en.xml> <en_derived.xml>
"""
import os
import sys
import xml.etree.ElementTree as ET

FE0F = "️"


def load(path):
    out = {}
    for a in ET.parse(path).getroot().iter("annotation"):
        if a.get("type") == "tts" or not a.text:
            continue
        words = [w.strip().lower() for w in a.text.split("|") if w.strip()]
        out.setdefault(a.get("cp").replace(FE0F, ""), []).extend(words)
    return out


def main():
    kw = {}
    for p in sys.argv[1:]:
        for k, v in load(p).items():
            kw.setdefault(k, []).extend(v)
    path = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "emoji.txt")
    lines = open(path, encoding="utf-8").read().split("\n")
    out, hits = [], 0
    for line in lines:
        p = line.split("\t")
        if line.startswith("#") and len(p) == 1 or len(p) < 2:
            out.append(line)
            continue
        p = (p + ["", ""])[:3]
        words = kw.get(p[0].replace(FE0F, ""), [])
        seen, keep = set(p[1].lower().split()), []
        for w in words:
            if w not in seen:
                seen.add(w)
                keep.append(w)
        if keep:
            hits += 1
        out.append("\t".join(p + ["|".join(keep)]))
    open(path, "w", encoding="utf-8", newline="\n").write("\n".join(out))
    print("emoji with keywords:", hits)


if __name__ == "__main__":
    main()
