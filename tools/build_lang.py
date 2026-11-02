"""Builds dictionary + bigram assets for a non-English language.

Usage: python -I build_lang.py <lang> <data_dir> <assets_out_dir>
  <lang>_50k.txt   - hermitdave/FrequencyWords (OpenSubtitles 2018), CC-BY-SA-4.0
  <iso3>.tsv       - Tatoeba sentences, CC-BY 2.0 FR
Output: dict_<lang>.txt (word \\t freq \\t offensive-flag), bigrams_<lang>.bin
"""
import os
import re
import struct
import sys
from collections import Counter, defaultdict

lang, data, out = sys.argv[1], sys.argv[2], sys.argv[3]
ISO3 = {"it": "ita", "es": "spa", "fr": "fra", "de": "deu", "pt": "por"}[lang]
SINGLE = {
    "it": {"e", "è", "a", "o", "i", "ò", "à"},
    "es": {"a", "e", "o", "u", "y"},
    "fr": {"a", "à", "y"},
    "de": set(),
    "pt": {"a", "à", "e", "é", "o", "ó"},
}[lang]
OFFENSIVE = {
    "it": "cazzo cazzi stronzo stronza vaffanculo fanculo puttana troia merda coglione coglioni frocio figa minchia",
    "es": "mierda puta puto cabron cabrón coño joder gilipollas pendejo maricón",
    "fr": "merde putain connard connasse salope enculé pute bordel",
    "de": "scheiße scheisse arschloch fotze hure wichser schlampe",
    "pt": "merda porra caralho puta foda cacete viado",
}[lang].split()

LETTERS = "a-zàáâãäåæçèéêëìíîïñòóôõöøœùúûüýÿß"
word_re = re.compile(rf"^[{LETTERS}]+'?$|^[{LETTERS}]+'[{LETTERS}]+$")
tok_re = re.compile(rf"[{LETTERS}{LETTERS.upper()}]+(?:'[{LETTERS}{LETTERS.upper()}]*)?", re.IGNORECASE)

freq = {}
for line in open(os.path.join(data, f"{lang}_50k.txt"), encoding="utf-8"):
    p = line.split()
    if len(p) != 2:
        continue
    w, c = p[0].lower(), int(p[1])
    if not word_re.match(w):
        continue
    if len(w) == 1 and w not in SINGLE:
        continue
    freq[w] = c

cap = Counter()
low = Counter()
corpus = Counter()
bigrams = Counter()
for line in open(os.path.join(data, f"{ISO3}.tsv"), encoding="utf-8"):
    parts = line.rstrip("\n").split("\t")
    if len(parts) != 3:
        continue
    s = parts[2].replace("’", "'")
    toks = []
    for i, m in enumerate(tok_re.finditer(s)):
        t = m.group(0)
        toks.append(t)
        if i == 0:
            continue
        prev = s[:m.start()].rstrip()[-1:]
        if prev in "\"'“‘«(-:;.!?¿¡" or prev == "":
            continue
        if t[0].isupper() and not t.isupper():
            cap[t.lower()] += 1
        elif t.islower():
            low[t] += 1
    if not toks:
        continue
    lt = ["<s>"] + [t.lower() for t in toks]
    for t in lt[1:]:
        corpus[t] += 1
    for a, b in zip(lt, lt[1:]):
        bigrams[(a, b)] += 1

# drop subtitle junk that never appears in real sentences unless it is very frequent
words = []
for w, c in freq.items():
    if (c < 400 and corpus[w] < 2) or (lang != "de" and c < 3000 and cap[w] > 20 * max(low[w], 1)):
        continue
    disp = w
    if cap[w] >= 3 and cap[w] > 3 * low[w]:
        disp = w[0].upper() + w[1:]
    words.append((disp, c))
# frequent corpus words missing from the subtitle list
have = {w.lower() for w, _ in words}
for w, c in corpus.most_common(40000):
    if w in have or not word_re.match(w) or c < 15 or (lang != "de" and cap[w] > low[w]):
        continue
    if len(w) == 1 and w not in SINGLE:
        continue
    disp = w[0].upper() + w[1:] if (cap[w] >= 3 and cap[w] > 3 * low[w]) else w
    words.append((disp, c * 25))
words.sort(key=lambda x: -x[1])

# drop accent-less misspellings ("perche", "citta") when the accented form is far more common,
# so autocorrect fixes them; genuine pairs like "e"/"è" both stay because both are frequent
import unicodedata


def fold(s):
    return "".join(c for c in unicodedata.normalize("NFD", s.lower()) if unicodedata.category(c) != "Mn")


# subtitles often lose accents, so decide with the well-spelled Tatoeba corpus
groups = defaultdict(list)
for w, c in words:
    groups[fold(w)].append((w, c))
drop = set()
bonus = Counter()
for f, lst in groups.items():
    if len(lst) < 2:
        continue
    plain = [(w, c) for w, c in lst if w.lower() == f]
    accented = [(w, c) for w, c in lst if w.lower() != f]
    if not plain or not accented:
        continue
    top = max(accented, key=lambda x: corpus[x[0].lower()])
    for w, c in plain:
        if corpus[top[0].lower()] >= 5 and corpus[w.lower()] < corpus[top[0].lower()] * 0.1:
            drop.add(w)
            bonus[top[0]] += c
words = sorted(((w, c + bonus[w]) for w, c in words if w not in drop), key=lambda x: -x[1])[:55000]

index = {}
with open(os.path.join(out, f"dict_{lang}.txt"), "w", encoding="utf-8") as f:
    for i, (w, c) in enumerate(words):
        f.write(f"{w}\t{c}\t{1 if w.lower() in OFFENSIVE else 0}\n")
        index.setdefault(w.lower(), i)

START = 0xFFFFF
by_a = defaultdict(list)
for (a, b), c in bigrams.items():
    if c < 2:
        continue
    ia = START if a == "<s>" else index.get(a)
    ib = index.get(b)
    if ia is None or ib is None:
        continue
    by_a[ia].append((c, ib))
rows = []
top_pred = {ia for ia, _ in sorted(((ia, sum(c for c, _ in l)) for ia, l in by_a.items()), key=lambda x: -x[1])[:250]}
for ia, lst in by_a.items():
    lst.sort(reverse=True)
    for c, ib in lst[:(300 if ia in top_pred else 30)]:
        rows.append((ia, ib, min(c * 3, 65535)))
rows.sort()
with open(os.path.join(out, f"bigrams_{lang}.bin"), "wb") as f:
    f.write(struct.pack(">i", len(rows)))
    for ia, ib, c in rows:
        f.write(struct.pack(">iiH", ia, ib, c))
print(lang, "words", len(words), "bigrams", len(rows))
