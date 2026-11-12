"""Builds the OCWO block classification page (ocwo-classement.html) from the classification data.

Run after classify.py: py -3 page/build_page.py
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(HERE)

# Proposed default values per material (Valentin can change them in the page).
# capacity: water levels held (1/8 block); permeability: 0 (watertight) to 10 (very fast)
# transform: target block, entry stage, and the stage under which it turns back while drying
DEFAULTS = {
    'exclu': dict(capacity=0, permeability=0, stages=False, retains=False),
    'creux': dict(capacity=0, permeability=0, stages=False, retains=False),
    'plante': dict(capacity=0, permeability=0, stages=False, retains=False),
    'culture': dict(capacity=0, permeability=0, stages=False, retains=False),
    'terre': dict(capacity=6, permeability=4, stages=True, retains=False,
                  transform=dict(target='minecraft:mud', stageIn='mouille', stageOut='humide')),
    'sable': dict(capacity=3, permeability=8, stages=True, retains=False),
    'gravier': dict(capacity=2, permeability=9, stages=True, retains=False),
    'argile_boue': dict(capacity=8, permeability=1, stages=True, retains=False),
    'neige_glace': dict(capacity=0, permeability=0, stages=False, retains=False),
    'mousse': dict(capacity=6, permeability=3, stages=True, retains=False),
    'roche_poreuse': dict(capacity=2, permeability=3, stages=True, retains=False),
    'roche_dense': dict(capacity=1, permeability=1, stages=True, retains=False),
    'roche_impermeable': dict(capacity=0, permeability=0, stages=False, retains=False),
    'brique': dict(capacity=2, permeability=2, stages=True, retains=False),
    'beton': dict(capacity=1, permeability=1, stages=True, retains=False),
    'poudre_beton': dict(capacity=1, permeability=6, stages=False, retains=False,
                         transform=dict(target='(béton de la même couleur)', stageIn='humide', stageOut=None)),
    'bois': dict(capacity=4, permeability=2, stages=True, retains=False),
    'feuillage': dict(capacity=1, permeability=6, stages=False, retains=False),
    'organique': dict(capacity=4, permeability=2, stages=False, retains=False),
    'laine': dict(capacity=8, permeability=5, stages=True, retains=True),
    'eponge': dict(capacity=8, permeability=5, stages=False, retains=True,
                   transform=dict(target='minecraft:wet_sponge', stageIn='sature', stageOut='sec')),
    'verre': dict(capacity=0, permeability=0, stages=False, retains=False),
    'metal': dict(capacity=0, permeability=0, stages=False, retains=False),
    'autre': dict(capacity=0, permeability=0, stages=False, retains=False),
}


def main():
    cls = json.load(open(os.path.join(TOOLS, 'blocks-26.3.json'), encoding='utf-8'))
    raw = {b['id']: b for b in json.load(open(os.path.join(TOOLS, 'blocks-26.3-raw.json'), encoding='utf-8'))}
    tags = sorted({t for b in raw.values() for t in b['tags']})
    tag_index = {t: i for i, t in enumerate(tags)}
    blocks = []
    for bid, mat, rule, conf, flags in cls['blocks']:
        r = raw[bid]
        props = r.get('properties', [])
        orient = 'axis' if 'axis' in props else ('facing' if 'facing' in props else '')
        # W: can hold water in its cell (waterlogged property); A: always in water (seagrass, kelp…)
        if r['waterloggable']:
            flags += 'W'
        elif r['liquidContainer']:
            flags += 'A'
        blocks.append([bid.split(':')[1], mat, rule, conf, flags, orient, [tag_index[t] for t in r['tags']]])
    materials = []
    for m in cls['materials']:
        m = dict(m)
        m['defaults'] = DEFAULTS[m['id']]
        materials.append(m)
    seed = {'version': cls['version'], 'materials': materials, 'rules': cls['rules'], 'tags': tags, 'blocks': blocks}
    data = json.dumps(seed, ensure_ascii=False, separators=(',', ':')).replace('</', '<\\/')
    html = open(os.path.join(HERE, 'template.html'), encoding='utf-8').read().replace('__DATA__', data)
    out = os.path.join(HERE, 'ocwo-classement.html')
    open(out, 'w', encoding='utf-8', newline='\n').write(html)
    print('written', out, os.path.getsize(out), 'bytes;', len(blocks), 'blocks,', len(tags), 'tags')


if __name__ == '__main__':
    main()
