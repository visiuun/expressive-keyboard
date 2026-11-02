"""Builds keyboard assets (dictionary, bigram table, emoji list) from raw data.

Usage: python -I build_assets.py <data_dir> <assets_out_dir>
Sources:
  en_50k.txt      - hermitdave/FrequencyWords (OpenSubtitles 2018), CC-BY-SA-4.0
  words_alpha.txt - dwyl/english-words (Unlicense)
  big.txt         - norvig.com corpus (public-domain books)
  eng.tsv         - Tatoeba English sentences, CC-BY 2.0 FR
  emoji-test.txt  - Unicode emoji data
"""
import os
import re
import struct
import sys
from collections import Counter, defaultdict

data, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)

CONTRACTIONS = {
    "I'm": 4300000, "don't": 3500000, "it's": 3300000, "that's": 1700000, "you're": 1500000,
    "can't": 1400000, "I'll": 1200000, "I've": 1000000, "didn't": 950000, "what's": 800000,
    "let's": 700000, "he's": 700000, "there's": 600000, "we're": 650000, "doesn't": 500000,
    "isn't": 450000, "won't": 420000, "they're": 400000, "she's": 400000, "I'd": 380000,
    "you'll": 300000, "wasn't": 300000, "we'll": 250000, "haven't": 220000, "you've": 200000,
    "couldn't": 180000, "wouldn't": 170000, "aren't": 160000, "you'd": 150000, "here's": 150000,
    "who's": 120000, "we've": 110000, "shouldn't": 90000, "hasn't": 60000, "weren't": 60000,
    "they've": 50000, "where's": 50000, "how's": 30000, "they'll": 60000, "it'll": 40000,
    "that'll": 20000, "he'll": 40000, "she'll": 20000, "we'd": 30000, "they'd": 30000,
    "he'd": 30000, "she'd": 20000, "hadn't": 30000, "ain't": 40000, "y'all": 15000,
    "o'clock": 22000, "mustn't": 5000, "needn't": 2000, "'cause": 20000, "ma'am": 30000,
    "could've": 10000, "would've": 12000, "should've": 12000, "there'll": 3000, "what're": 3000,
}
# typed-without-apostrophe forms that should be corrected
OFFENSIVE = set("""fuck fucking fucked fucker motherfucker shit shitty bitch bitches bastard asshole cunt dick
dickhead pussy cock slut whore nigger nigga faggot fag retard twat wanker""".split())
EXTRA_OK = set("""ok okay email emails online app apps lol omg btw wifi internet website smartphone selfie
emoji texting google youtube iphone android hashtag blog vlog podcast""".split())

alpha = set(w.strip() for w in open(os.path.join(data, "words_alpha.txt"), encoding="utf-8"))

freq = {}
for line in open(os.path.join(data, "en_50k.txt"), encoding="utf-8"):
    parts = line.split()
    if len(parts) != 2:
        continue
    w, c = parts[0], int(parts[1])
    if not re.fullmatch(r"[a-z]+", w):
        continue
    if len(w) == 1 and w not in ("a", "i"):
        continue
    if w in alpha or w in EXTRA_OK or c >= 3000:
        freq[w] = c

# casing statistics from corpora (mid-sentence occurrences only)
cap = Counter()
low = Counter()
tok_re = re.compile(r"[A-Za-z]+(?:'[A-Za-z]+)?")


def sentences():
    text = open(os.path.join(data, "big.txt"), encoding="utf-8", errors="ignore").read()
    for s in re.split(r"(?<=[.!?])\s+|\n\s*\n", text):
        yield s, 1
    for line in open(os.path.join(data, "eng.tsv"), encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) == 3:
            yield p[2], 3  # weight conversational data higher


bigrams = Counter()
corpus_uni = Counter()
for s, wgt in sentences():
    toks = tok_re.findall(s)
    if not toks:
        continue
    for i, m in enumerate(tok_re.finditer(s)):
        t = m.group(0)
        if i == 0:
            continue
        prev = s[:m.start()].rstrip()[-1:]
        if prev in "\"'“‘(-:;.!?" or prev == "":
            continue
        if t[0].isupper() and not t.isupper():
            cap[t.lower()] += 1
        elif t.islower():
            low[t] += 1
    lt = ["<s>"] + [t.lower() for t in toks]
    for t in lt[1:]:
        corpus_uni[t] += wgt
    for a, b in zip(lt, lt[1:]):
        bigrams[(a, b)] += wgt

# add common corpus words missing from the subtitle list
for w, c in corpus_uni.most_common(60000):
    if w not in freq and re.fullmatch(r"[a-z]+", w) and w in alpha and c >= 40 and cap[w] <= low[w]:
        freq[w] = c * 20

for frag in "don didn doesn isn wasn couldn wouldn shouldn hasn aren weren ain mustn needn ll ve re".split():
    freq.pop(frag, None)
REAL = {"ill", "well", "were", "wed", "its", "hell", "shell", "shed", "id", "lets", "cause", "maam"}
for k in CONTRACTIONS:
    n = k.lower().replace("'", "")
    if n not in REAL:
        freq.pop(n, None)  # "dont" -> autocorrects to "don't"
if "won" in freq:
    freq["won"] = 150000
words = []
for w, c in freq.items():
    disp = w
    if w == "i":
        disp = "I"
    elif cap[w] >= 5 and cap[w] > 4 * low[w]:
        disp = w.capitalize()
    words.append((disp, c))
for w, c in CONTRACTIONS.items():
    words.append((w, c))
words.sort(key=lambda x: -x[1])
words = words[:60000]

index = {}
with open(os.path.join(out, "dict_en.txt"), "w", encoding="utf-8") as f:
    for i, (w, c) in enumerate(words):
        flag = 1 if w.lower() in OFFENSIVE else 0
        f.write(f"{w}\t{c}\t{flag}\n")
        index[w.lower()] = i

# map corpus tokens to dictionary indices; "<s>" (sentence start) = -1 encoded as 0xFFFFF
START = 0xFFFFF
pairs = []
for (a, b), c in bigrams.items():
    if c < 3:
        continue
    ia = START if a == "<s>" else index.get(a)
    ib = index.get(b)
    if ia is None or ib is None:
        continue
    pairs.append((ia, ib, c))
# keep top-N successors per predecessor to bound size
by_a = defaultdict(list)
for ia, ib, c in pairs:
    by_a[ia].append((c, ib))
rows = []
top_pred = {ia for ia, _ in sorted(((ia, sum(c for c, _ in l)) for ia, l in by_a.items()), key=lambda x: -x[1])[:250]}
for ia, lst in by_a.items():
    lst.sort(reverse=True)
    for c, ib in lst[:(400 if ia in top_pred else 40)]:
        rows.append((ia, ib, min(c, 65535)))
rows.sort()
with open(os.path.join(out, "bigrams_en.bin"), "wb") as f:
    f.write(struct.pack(">i", len(rows)))
    for ia, ib, c in rows:
        f.write(struct.pack(">iiH", ia, ib, c))

# emoji
groups = []
tones = defaultdict(list)
cur = None
TONE = re.compile("[\U0001F3FB-\U0001F3FF]")
for line in open(os.path.join(data, "emoji-test.txt"), encoding="utf-8"):
    if line.startswith("# group:"):
        cur = line.split(":", 1)[1].strip()
        if cur != "Component":
            groups.append((cur, []))
        continue
    if not line.strip() or line.startswith("#") or cur == "Component":
        continue
    m = re.match(r"^[0-9A-F ]+;\s*fully-qualified\s*#\s*(\S+)\s+E(\d+)\.(\d+)\s+(.*)$", line)
    if not m:
        continue
    emo, major, name = m.group(1), int(m.group(2)), m.group(4).strip()
    if major > 16:
        continue
    if TONE.search(emo):
        base = TONE.sub("", emo)
        if len(TONE.findall(emo)) == 1:
            tones[base].append(emo)
        continue
    groups[-1][1].append((emo, name))
with open(os.path.join(out, "emoji.txt"), "w", encoding="utf-8") as f:
    for g, lst in groups:
        f.write(f"#{g}\n")
        for emo, name in lst:
            t = ",".join(tones.get(emo, []))
            f.write(f"{emo}\t{name}\t{t}\n")

print("words", len(words), "bigrams", len(rows), "emoji groups", [(g, len(l)) for g, l in groups])
