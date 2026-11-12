"""OCWO: generates the mod's moisture data files from the block classification.

Inputs:
  blocks-26.3.json               pre-classification (classify.py)
  page/build_page.py DEFAULTS    default values of the materials
  store/materials/*.json         material values edited in the classification page (if any)
  store/blocks/*.json            per-block edits from the page (material, overrides, fixed water)
  EXTRA_OVERRIDES below          project defaults for special blocks, until Valentin sets them in the page
Outputs (in the common module, reloadable with /reload):
  data/ocwo/ocwo_materials/<material>.json        {capacity, permeability, stages, retains, transform?, blocks: [...]}
  data/ocwo/ocwo_block_settings/<block>.json      {block, capacity?, permeability?, along?, across?, up?, down?, side?, fixed_water?}

Refresh the store first (Claude: ArtifactData list with out_dir=tools/store), then: py -3 export_datapack.py
"""
import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, 'page'))
from build_page import DEFAULTS  # noqa: E402

OUT = os.path.join(HERE, '..', 'mod', 'ocwo', 'common', 'src', 'main', 'resources', 'data', 'ocwo')

# Special blocks with directional permeability (oriented blocks: "along" the axis or facing, "across" it).
# Project defaults until Valentin sets them in the page (the page's own edits win).
EXTRA_OVERRIDES = {
    'basalt': {'along': 4, 'across': 0},
    'polished_basalt': {'along': 4, 'across': 0},
    'bamboo_block': {'along': 3, 'across': 0},
    'stripped_bamboo_block': {'along': 3, 'across': 0},
}

# Fixed water (levels) of blocks made of water that hold moisture: at most the moisture under which the block
# cannot exist (mud turns back into dirt under "humide" = 25 % of 8 levels = 2 levels; wet sponge into sponge
# under "humide" too), so that mining then placing one never creates water. Others (ice, snow): 8, unused for now.
FIXED_WATER_LEVELS = {'mud': 2, 'wet_sponge': 2}

# Blocks a material's state change applies to (03: only dirt-like soils become mud; grass, podzol, farmland… don't).
# Missing material = all its blocks, except the target itself.
TRANSFORM_FROM = {
    'terre': ['minecraft:dirt', 'minecraft:coarse_dirt', 'minecraft:rooted_dirt'],
    'eponge': ['minecraft:sponge'],
}

# Materials without moisture never get a data file entry for their blocks.
NO_MOISTURE = {'exclu', 'creux', 'plante', 'culture', 'autre'}


def load_store(kind):
    out = {}
    for f in glob.glob(os.path.join(HERE, 'store', kind, '*.json')):
        doc = json.load(open(f, encoding='utf-8'))
        out[os.path.splitext(os.path.basename(f))[0]] = doc.get('data', doc)
    return out


def main():
    cls = json.load(open(os.path.join(HERE, 'blocks-26.3.json'), encoding='utf-8'))
    mat_edits = load_store('materials')
    block_edits = load_store('blocks')

    materials = {}
    for m in cls['materials']:
        v = dict(DEFAULTS[m['id']])
        v.update({k: val for k, val in mat_edits.get(m['id'], {}).items() if k in ('capacity', 'permeability', 'stages', 'retains', 'transform')})
        materials[m['id']] = v

    assignment = {m: [] for m in materials}
    settings = {}
    for bid, mat, _rule, _conf, flags in cls['blocks']:
        name = bid.split(':')[1]
        edit = block_edits.get(name, {})
        mat = edit.get('material', mat)
        assignment[mat].append(bid)
        s = dict(EXTRA_OVERRIDES.get(name, {}))
        s.update(edit.get('override') or {})
        fixed = edit.get('fixedWater', FIXED_WATER_LEVELS.get(name, 8) if 'F' in flags else None)
        if fixed:
            s['fixed_water'] = fixed
        if s:
            settings[bid] = s

    mat_dir = os.path.join(OUT, 'ocwo_materials')
    set_dir = os.path.join(OUT, 'ocwo_block_settings')
    for d in (mat_dir, set_dir):
        os.makedirs(d, exist_ok=True)
        for f in glob.glob(os.path.join(d, '**', '*.json'), recursive=True):
            os.remove(f)

    n_mat = 0
    for mid, v in materials.items():
        if mid in NO_MOISTURE and not (v.get('capacity') or v.get('permeability')):
            continue
        doc = {'capacity': v.get('capacity', 0), 'permeability': v.get('permeability', 0),
               'stages': bool(v.get('stages')), 'retains': bool(v.get('retains'))}
        t = v.get('transform')
        if t and t.get('target'):
            doc['transform'] = {k: t[k] for k in ('target', 'stageIn', 'stageOut') if t.get(k)}
            if not doc['transform']['target'].startswith('minecraft:'):
                # page wording "(béton de la même couleur)": the mod replaces "_concrete_powder" by "_concrete"
                doc['transform']['target'] = 'ocwo:same_color_concrete'
            doc['transform']['from'] = TRANSFORM_FROM.get(
                mid, [b for b in sorted(assignment[mid]) if b != t['target']])
        doc['blocks'] = sorted(assignment[mid])
        json.dump(doc, open(os.path.join(mat_dir, mid + '.json'), 'w', encoding='utf-8', newline='\n'), indent=1)
        n_mat += 1

    for bid, s in settings.items():
        ns, path = bid.split(':')
        os.makedirs(os.path.join(set_dir, ns), exist_ok=True)
        json.dump(dict(block=bid, **s), open(os.path.join(set_dir, ns, path + '.json'), 'w', encoding='utf-8', newline='\n'), indent=1)

    print(f'{n_mat} material files, {len(settings)} block setting files, '
          f'{sum(len(assignment[m]) for m in materials if m not in NO_MOISTURE)} blocks with moisture')


if __name__ == '__main__':
    main()
