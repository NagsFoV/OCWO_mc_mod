"""For every injector with an INVOKE target or a constant, count matching instructions in the target
method bytecode (javap -c) and compare with the requested ordinal.
Usage: python checkinject.py <src root> <jar> <javap>
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
dis_cache = {}


def disasm(cls):
    if cls not in dis_cache:
        out = subprocess.run([javap, '-c', '-p', '-cp', jar, cls], capture_output=True, text=True)
        dis_cache[cls] = out.stdout
    return dis_cache[cls]


def method_bodies(cls, name):
    """Yield (signature line, body text) for each method with this name (incl. <init>)."""
    text = disasm(cls)
    simple = cls.split('.')[-1].split('$')[-1]
    blocks = re.split(r'\n(?=  \S)', text)
    for b in blocks:
        head = b.strip().splitlines()[0] if b.strip() else ''
        if name == '<init>':
            if re.search(r'\b' + re.escape(cls) + r'\(', head):
                yield head, b
        elif re.search(r'[\s.]' + re.escape(name) + r'\(', head):
            yield head, b


problems = 0
for root, _, files in os.walk(src):
    for f in files:
        if not f.endswith('.java'):
            continue
        code = open(os.path.join(root, f), encoding='utf-8').read()
        code = re.sub(r'/\*.*?\*/', '', code, flags=re.S)
        code = re.sub(r'//[^\n]*', '', code)
        if '@Pseudo' in code:
            continue  # a mixin into another mod (Sodium...): not in the Minecraft jar
        m = re.search(r'@Mixin\(\{?([^)}]*)\}?\)', code)
        if not m:
            continue
        imps = {i.split('.')[-1]: i for i in re.findall(r'^import ([\w.]+);', code, re.M)}
        targets = []
        for t in m.group(1).split(','):
            t = t.strip().replace('.class', '')
            if t:
                p = t.split('.')
                targets.append(imps[p[0]] + ''.join('$' + x for x in p[1:]))
        # split on injector annotations
        for inj in re.finditer(r'@(Redirect|Inject|ModifyConstant|ModifyVariable|ModifyArg|ModifyArgs|WrapOperation|ModifyExpressionValue)\((.*?)\)\s*\n\s*(?:public |private |protected )?(?:static )?[\w<>\[\], ?]+ (\w+)\(', code, re.S):
            kind, body, handler = inj.group(1), inj.group(2), inj.group(3)
            meth = re.search(r'method\s*=\s*"([^"]+)"', body)
            if not meth:
                continue
            mname = meth.group(1).split('(')[0]
            # main @At (first one, not inside slice)
            main = re.sub(r'slice\s*=\s*@Slice\(.*', '', body, flags=re.S)
            tgt = re.search(r'target\s*=\s*"(L[^"]+)"', main)
            ordm = re.search(r'ordinal\s*=\s*(\d+)', main)
            const = re.search(r'@Constant\(intValue\s*=\s*(-?\d+)\)', body)
            for cls in targets:
                bodies = list(method_bodies(cls, mname))
                if not bodies:
                    problems += 1
                    print(f'{f}:{handler}: method {mname} NOT FOUND in {cls}')
                    continue
                if tgt:
                    t = tgt.group(1)
                    mm = re.match(r'L([^;]+);([\w$<>]+)(\(.*)', t)
                    if not mm:
                        continue
                    owner, n, d = mm.groups()
                    needle = f'{owner}.{n}:{d}'
                    cnt = sum(b.count(needle) for _, b in bodies)
                    if owner.replace('/', '.') == cls:
                        # calls to the class's own methods are printed by javap without the owner
                        cnt += sum(len(re.findall(r'Method ' + re.escape(n) + ':' + re.escape(d), b)) for _, b in bodies)
                    want = int(ordm.group(1)) if ordm else None
                    status = 'OK' if cnt > (want or 0) else 'FAIL'
                    if status == 'FAIL':
                        problems += 1
                        print(f'{f}:{handler}: {status} {kind} {mname} -> {n} count={cnt} ordinal={want} in {cls}')
                elif const:
                    v = int(const.group(1))
                    pat = {0: 'iconst_0', 1: 'iconst_1', 2: 'iconst_2', 3: 'iconst_3', 4: 'iconst_4', 5: 'iconst_5'}.get(v)
                    cnt = sum(len(re.findall(r'\b' + (pat or f'(?:bipush|sipush|ldc)\\s+#?\\d*\\s*(?://\\s*int\\s+)?{v}') + r'\b', b)) for _, b in bodies)
                    if cnt == 0:
                        problems += 1
                        print(f'{f}:{handler}: FAIL ModifyConstant {v} not found in {mname} of {cls}')
print('problems:', problems)
sys.exit(1 if problems else 0)
