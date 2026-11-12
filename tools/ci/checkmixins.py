"""Check PhysEx/OCWO mixin targets against the Minecraft 26.3 jar using javap.

Usage: python checkmixins.py <java src root> <minecraft jar> <javap path>
Reports: unknown target classes, unknown method names in the target class,
INVOKE target descriptors whose owner/name/descriptor does not exist, shadow names not found.
"""
import os, re, subprocess, sys

src, jar, javap = sys.argv[1], sys.argv[2], sys.argv[3]

def check_jar(jar, javap):
    """Stops at once with a clear message when the Minecraft jar cannot be read."""
    if not os.path.isfile(jar):
        print(f'Minecraft jar unreadable: {jar} does not exist')
        sys.exit(2)
    out = subprocess.run([javap, '-cp', jar, 'net.minecraft.world.level.Level'], capture_output=True, text=True)
    if out.returncode != 0:
        print(f'Minecraft jar unreadable: javap cannot read net.minecraft.world.level.Level from {jar}')
        print(out.stderr.strip()[:500])
        sys.exit(2)


check_jar(jar, javap)
cache = {}


def members(cls):
    """Return (set of 'name', set of 'name+desc') for class cls (dotted), including superclasses."""
    if cls in cache:
        return cache[cls]
    out = subprocess.run([javap, '-p', '-s', '-cp', jar, cls], capture_output=True, text=True)
    names, sigs = set(), set()
    if out.returncode != 0:
        cache[cls] = None
        return None
    lines = out.stdout.splitlines()
    header = lines[1] if len(lines) > 1 else ''
    parents = []
    m = re.search(r' extends ([\w.$<>, ?]+?)(?: implements |\s*\{|$)', header)
    if m:
        parents += [p.strip() for p in m.group(1).split(',')]
    m = re.search(r' implements ([\w.$<>, ?]+?)(?:\s*\{|$)', header)
    if m:
        parents += [p.strip() for p in m.group(1).split(',')]
    parents = [re.sub(r'<.*', '', p) for p in parents if p]
    for i, l in enumerate(lines):
        if l.strip().startswith('descriptor:'):
            decl = lines[i - 1]
            d = l.strip().split(':', 1)[1].strip()
            mm = re.search(r'([\w$<>]+)\(', decl)
            if mm:
                n = mm.group(1)
                if n == cls.split('.')[-1]:
                    n = '<init>'
                names.add(n); sigs.add(n + d)
            else:
                fm = re.search(r'([\w$]+);\s*$', decl)
                if fm:
                    names.add(fm.group(1)); sigs.add(fm.group(1) + ':' + d)
        if 'static {}' in l:
            names.add('<clinit>')
    # superclass and interfaces (default methods are called through the receiver's own type)
    for sup in parents:
        if sup == 'java.lang.Object':
            continue
        r = members(sup)
        if r:
            names |= r[0]; sigs |= r[1]
    cache[cls] = (names, sigs)
    return cache[cls]


imports_re = re.compile(r'^import ([\w.]+);', re.M)
problems = 0
for root, _, files in os.walk(src):
    for f in files:
        if not f.endswith('.java'):
            continue
        text = open(os.path.join(root, f), encoding='utf-8').read()
        code = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
        code = re.sub(r'//[^\n]*', '', code)
        m = re.search(r'@Mixin\(\{?([^)}]*)\}?\)', code)
        if not m:
            continue
        imps = {i.split('.')[-1]: i for i in imports_re.findall(code)}
        pkg = re.search(r'^package ([\w.]+);', code, re.M).group(1)

        def resolve(simple):
            parts = simple.split('.')
            base = imps.get(parts[0])
            if not base:
                return None
            return base + ''.join('$' + p for p in parts[1:])

        targets = []
        for t in m.group(1).split(','):
            t = t.strip().replace('.class', '')
            if t:
                full = resolve(t)
                targets.append(full)
                if not full or members(full) is None:
                    print(f'{f}: target class not found: {t}'); problems += 1
        tmembers = [members(t) for t in targets if t and members(t)]
        # method = "..."
        for meth in re.findall(r'method\s*=\s*"([^"]+)"', code):
            name = meth.split('(')[0]
            for tm in tmembers:
                if '(' in meth:
                    ok = meth in tm[1]
                else:
                    ok = name in tm[0]
                if not ok:
                    print(f'{f}: method not found in target: {meth}'); problems += 1
        # INVOKE targets
        for tgt in re.findall(r'target\s*=\s*"(L[^";]+;[^"]+)"', code):
            mm = re.match(r'L([^;]+);([\w$<>]+)(\(.*)', tgt)
            if not mm:
                continue
            owner, name, desc = mm.group(1).replace('/', '.'), mm.group(2), mm.group(3)
            r = members(owner)
            if r is None:
                if owner.startswith('net.minecraft'):
                    print(f'{f}: INVOKE owner not found: {owner}'); problems += 1
                continue
            if name + desc not in r[1]:
                print(f'{f}: INVOKE target not found: {tgt}'); problems += 1
        # shadows
        for sh in re.findall(r'@Shadow[^;{]*?\s([\w$]+)\s*(?:\(|;|=)', code, re.S):
            if sh in ('final', 'abstract', 'static', 'private', 'protected', 'public'):
                continue
            if not any(sh in tm[0] for tm in tmembers):
                print(f'{f}: shadow not found: {sh}'); problems += 1
print('problems:', problems)
sys.exit(1 if problems else 0)
