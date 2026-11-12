"""OCWO: pre-classification of every Minecraft block into an OCWO material.

Input : blocks-26.3-raw.json (written by the game itself: gradlew :fabric:runBlockDump)
Output: blocks-26.3.json (compact) =
  {
    "version": "26.3",
    "materials": [{"id", "label", "description"}],
    "rules": {rule_id: description},
    "blocks": [[block_id, material_id, rule_id, confidence, flags], ...]
  }
confidence: "H" (high: explicit list or vanilla tag), "M" (medium: shape or name), "L" (low: sound or fallback)
flags: "F" = made of water (fixed water that travels with the item: mud, ice, snow, wet sponge...)

Run: py -3 classify.py
"""
import collections
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, 'blocks-26.3-raw.json')
OUT = os.path.join(HERE, 'blocks-26.3.json')

MATERIALS = [
    ('exclu', 'Exclu', "Air, fluides, blocs techniques : hors système d'humidité"),
    ('creux', 'Creux', "L'eau y passe comme dans l'air (couche fluide), sans humidité : portes, clôtures, barreaux, rails, panneaux…"),
    ('plante', 'Plante', "Végétation non pleine. 0/0 veut dire pas d'humidité dans la plante, pas étanche : l'eau liquide peut partager sa case (elle appartient à la case et reste quand on casse la plante)"),
    ('culture', 'Culture', "Cultures : l'eau est dans la plante et dans le fruit (décision du 11/11)"),
    ('terre', 'Terre et sols', 'Terre, herbe, podzol, mycélium, terre labourée, chemin…'),
    ('sable', 'Sable', 'Sable, sable rouge, sable des âmes…'),
    ('gravier', 'Gravier', 'Gravier'),
    ('argile_boue', 'Argile et boue', 'Argile, boue, racines boueuses'),
    ('neige_glace', 'Neige et glace', 'Neige, glace (blocs faits d\'eau)'),
    ('mousse', 'Mousse', 'Blocs et tapis de mousse'),
    ('roche_poreuse', 'Roche poreuse', 'Cobblestone, grès, tuf, calcite, netherrack…'),
    ('roche_dense', 'Roche dense', 'Pierre, deepslate, granite, basalte, minerais, briques de pierre…'),
    ('roche_impermeable', 'Roche imperméable', 'Obsidienne, bedrock, deepslate renforcée'),
    ('brique', 'Briques et terre cuite', 'Briques, terre cuite, briques de boue, briques du Nether'),
    ('beton', 'Béton', 'Béton'),
    ('poudre_beton', 'Poudre de béton', 'Devient du béton au contact de l\'eau'),
    ('bois', 'Bois', 'Bûches, planches, bambou'),
    ('feuillage', 'Feuillage', 'Feuilles'),
    ('organique', 'Organique', 'Champignons géants, citrouille, melon, foin, corail, slime, miel…'),
    ('laine', 'Laine et tissus', 'Laine, tapis'),
    ('eponge', 'Éponge', 'Éponge sèche et mouillée'),
    ('verre', 'Verre', 'Verre plein'),
    ('metal', 'Métal et minéraux', 'Blocs de fer, or, cuivre, diamant…'),
    ('autre', 'Autre (à relire)', 'Non classé automatiquement'),
]
MATERIAL_IDS = {m[0] for m in MATERIALS}

RULES = {
    'id_exclu': 'Liste explicite : air, fluides, blocs techniques',
    'id_list': 'Liste explicite d\'identifiants',
    'tag': 'Tag vanilla',
    'hollow_tag': 'Tag vanilla de bloc creux (portes, clôtures, murets, rails…)',
    'partial_base': 'Dalle, escalier ou muret : matériau du bloc de base',
    'nocollision_plant': 'Sans collision, son de végétal : plante',
    'nocollision': 'Sans collision : creux',
    'not_full': 'Forme non pleine : creux',
    'name': 'Indice dans le nom',
    'sound': 'Son du bloc',
    'fallback': 'Aucune règle : à relire',
}

FIXED_WATER = {'minecraft:mud', 'minecraft:ice', 'minecraft:packed_ice', 'minecraft:blue_ice', 'minecraft:frosted_ice',
               'minecraft:snow', 'minecraft:snow_block', 'minecraft:powder_snow', 'minecraft:wet_sponge'}

EXCLU = {'air', 'cave_air', 'void_air', 'water', 'lava', 'bubble_column', 'barrier', 'light', 'structure_void',
         'structure_block', 'jigsaw', 'command_block', 'chain_command_block', 'repeating_command_block',
         'moving_piston', 'fire', 'soul_fire', 'end_portal', 'nether_portal', 'end_gateway', 'test_block',
         'test_instance_block', 'water_cauldron', 'lava_cauldron', 'powder_snow_cauldron'}

ID_LISTS = {
    'terre': ['dirt', 'coarse_dirt', 'rooted_dirt', 'grass_block', 'podzol', 'mycelium', 'farmland', 'dirt_path',
              'crimson_nylium', 'warped_nylium'],
    'sable': ['sand', 'red_sand', 'suspicious_sand', 'soul_sand', 'soul_soil'],
    'gravier': ['gravel', 'suspicious_gravel'],
    'argile_boue': ['clay', 'mud', 'muddy_mangrove_roots'],
    'neige_glace': ['snow_block', 'powder_snow', 'ice', 'packed_ice', 'blue_ice', 'frosted_ice'],
    'mousse': ['moss_block', 'pale_moss_block'],
    'roche_impermeable': ['obsidian', 'crying_obsidian', 'bedrock', 'reinforced_deepslate'],
    'roche_poreuse': ['cobblestone', 'mossy_cobblestone', 'cobbled_deepslate', 'tuff', 'calcite', 'dripstone_block',
                      'netherrack', 'magma_block', 'sandstone', 'chiseled_sandstone', 'cut_sandstone',
                      'smooth_sandstone', 'red_sandstone', 'chiseled_red_sandstone', 'cut_red_sandstone',
                      'smooth_red_sandstone', 'end_stone'],
    'brique': ['bricks', 'mud_bricks', 'packed_mud', 'nether_bricks', 'red_nether_bricks', 'cracked_nether_bricks',
               'chiseled_nether_bricks'],
    'organique': ['pumpkin', 'carved_pumpkin', 'jack_o_lantern', 'melon', 'hay_block', 'dried_kelp_block',
                  'honey_block', 'honeycomb_block', 'slime_block', 'nether_wart_block', 'warped_wart_block',
                  'shroomlight', 'brown_mushroom_block', 'red_mushroom_block', 'mushroom_stem', 'sculk', 'target',
                  'bee_nest', 'beehive', 'cactus', 'bamboo', 'sugar_cane', 'kelp_plant'],
    'eponge': ['sponge', 'wet_sponge'],
    'metal': ['iron_block', 'gold_block', 'diamond_block', 'emerald_block', 'lapis_block', 'redstone_block',
              'netherite_block', 'raw_iron_block', 'raw_gold_block', 'raw_copper_block', 'coal_block',
              'amethyst_block', 'budding_amethyst', 'quartz_block'],
    'bois': ['bamboo_block', 'stripped_bamboo_block', 'bamboo_mosaic'],
}

# vanilla (and convention) tags, checked in this order
TAG_RULES = [
    ('minecraft:crops', 'culture'),
    ('minecraft:leaves', 'feuillage'),
    ('minecraft:logs', 'bois'),
    ('minecraft:planks', 'bois'),
    ('minecraft:wool', 'laine'),
    ('minecraft:wool_carpets', 'laine'),
    ('minecraft:concrete_powders', 'poudre_beton'),
    ('minecraft:concrete', 'beton'),
    ('minecraft:terracotta', 'brique'),
    ('minecraft:glazed_terracotta', 'brique'),
    ('minecraft:ice', 'neige_glace'),
    ('minecraft:sand', 'sable'),
    ('minecraft:dirt', 'terre'),
    ('minecraft:coral_blocks', 'organique'),
    ('minecraft:ores', 'roche_dense'),
    ('minecraft:stone_bricks', 'roche_dense'),
    ('minecraft:base_stone_overworld', 'roche_dense'),
    ('minecraft:base_stone_nether', 'roche_dense'),
    ('minecraft:copper', 'metal'),
]

HOLLOW_TAGS = ['minecraft:doors', 'minecraft:trapdoors', 'minecraft:fences', 'minecraft:fence_gates',
               'minecraft:bars', 'minecraft:chains', 'minecraft:rails', 'minecraft:all_signs', 'minecraft:buttons',
               'minecraft:pressure_plates', 'minecraft:beds', 'minecraft:banners', 'minecraft:candles',
               'minecraft:candle_cakes', 'minecraft:flower_pots', 'minecraft:shulker_boxes', 'minecraft:climbable',
               'minecraft:lanterns', 'minecraft:skulls', 'minecraft:cauldrons', 'minecraft:copper_chests',
               'minecraft:copper_golem_statues', 'minecraft:lightning_rods', 'minecraft:wooden_shelves']

PLANT_SOUNDS = {'grass', 'wet_grass', 'crop', 'hard_crop', 'sweet_berry_bush', 'vine', 'cave_vines', 'weeping_vines',
                'twisting_vines', 'roots', 'fungus', 'nether_sprouts', 'small_dripleaf', 'big_dripleaf',
                'hanging_roots', 'glow_lichen', 'spore_blossom', 'azalea', 'flowering_azalea', 'moss_carpet',
                'pink_petals', 'leaf_litter', 'cactus_flower', 'mangrove_roots', 'bamboo_sapling', 'cherry_sapling',
                'stem', 'pointed_dripstone', 'lily_pad', 'dry_grass', 'firefly_bush', 'pale_hanging_moss',
                'eyeblossom', 'sculk_vein', 'nether_wart', 'seagrass', 'coral_plant', 'kelp'}

NAME_RULES = [
    (r'cobble', 'roche_poreuse'),
    (r'sandstone', 'roche_poreuse'),
    (r'tuff', 'roche_poreuse'),
    (r'_bricks?$|bricks_|^bricks', 'brique'),
    (r'terracotta', 'brique'),
    (r'concrete', 'beton'),
    (r'deepslate|blackstone|basalt|stone|granite|diorite|andesite|prismarine|purpur|quartz|end_stone|calcite|dripstone',
     'roche_dense'),
    (r'copper|iron|gold|netherite|diamond|emerald|lapis|amethyst', 'metal'),
    (r'_planks|_log$|_wood$|_stem$|_hyphae$|bamboo', 'bois'),
    (r'glass', 'verre'),
    (r'wool|carpet', 'laine'),
    (r'mushroom|wart|coral|sculk|resin|honey|slime', 'organique'),
    (r'snow|ice', 'neige_glace'),
    (r'mud', 'argile_boue'),
]

SOUND_RULES = {
    'stone': 'roche_dense', 'deepslate': 'roche_dense', 'deepslate_bricks': 'roche_dense',
    'deepslate_tiles': 'roche_dense', 'polished_deepslate': 'roche_dense', 'basalt': 'roche_dense',
    'tuff': 'roche_poreuse', 'tuff_bricks': 'roche_poreuse', 'polished_tuff': 'roche_poreuse',
    'nether_bricks': 'brique', 'mud_bricks': 'brique', 'resin_bricks': 'organique',
    'wood': 'bois', 'nether_wood': 'bois', 'cherry_wood': 'bois', 'bamboo_wood': 'bois',
    'wool': 'laine', 'glass': 'verre', 'sand': 'sable', 'gravel': 'gravier', 'snow': 'neige_glace',
    'copper': 'metal', 'metal': 'metal', 'iron': 'metal', 'amethyst': 'metal', 'coral_block': 'organique',
    'sulfur': 'roche_poreuse', 'cinnabar': 'roche_dense',
}

PARTIAL = re.compile(r'_(slab|stairs|wall)$')


def base_id(block_id):
    """'minecraft:stone_brick_slab' -> candidates for its full block."""
    name = block_id.split(':')[1]
    stem = PARTIAL.sub('', name)
    return [stem, stem + 's', stem + '_block', stem + '_planks', stem.replace('_brick', '_bricks'),
            stem.replace('_tile', '_tiles')]


def classify(blocks):
    by_id = {b['id'].split(':')[1]: b for b in blocks}
    id_to_mat = {}
    for mat, ids in ID_LISTS.items():
        for i in ids:
            id_to_mat[i] = mat
    result = {}

    def direct(b):
        name = b['id'].split(':')[1]
        tags = set(b['tags'])
        if name in EXCLU or b['air'] or b['liquid']:
            return 'exclu', 'id_exclu', 'H'
        if name in id_to_mat:
            return id_to_mat[name], 'id_list', 'H'
        if 'minecraft:crops' in tags:
            return 'culture', 'tag', 'H'
        for t in HOLLOW_TAGS:
            if t in tags:
                return 'creux', 'hollow_tag', 'H'
        if b['noCollision'] or b['replaceable']:
            if b['sound'] in PLANT_SOUNDS or 'minecraft:flowers' in tags or 'minecraft:saplings' in tags \
                    or 'minecraft:replaceable_by_trees' in tags or 'minecraft:coral_plants' in tags:
                return 'plante', 'nocollision_plant', 'M'
            return 'creux', 'nocollision', 'M'
        return None

    # pass 1: explicit rules, tags (full blocks first, partial blocks resolved after)
    pending = []
    for b in blocks:
        r = direct(b)
        name = b['id'].split(':')[1]
        if r is None and not PARTIAL.search(name):
            tags = set(b['tags'])
            for t, mat in TAG_RULES:
                if t in tags:
                    r = (mat, 'tag', 'H')
                    break
        if r is None and not b['full'] and not PARTIAL.search(name):
            if b['sound'] in PLANT_SOUNDS:
                r = ('plante', 'nocollision_plant', 'M')
            else:
                r = ('creux', 'not_full', 'M')
        if r is None:
            pending.append(b)
        else:
            result[b['id']] = r

    # pass 2: slabs, stairs, walls -> material of the full block; then names, sounds, fallback
    for b in pending:
        name = b['id'].split(':')[1]
        r = None
        if PARTIAL.search(name):
            for cand in base_id(b['id']):
                full = 'minecraft:' + cand
                if full in result and result[full][0] not in ('creux', 'plante', 'exclu', 'autre'):
                    r = (result[full][0], 'partial_base', 'M')
                    break
        if r is None:
            for pattern, mat in NAME_RULES:
                if re.search(pattern, name):
                    r = (mat, 'name', 'M')
                    break
        if r is None and b['sound'] in SOUND_RULES:
            r = (SOUND_RULES[b['sound']], 'sound', 'L')
        if r is None:
            r = ('autre', 'fallback', 'L')
        result[b['id']] = r
    return result


def main():
    blocks = json.load(open(RAW, encoding='utf-8'))
    result = classify(blocks)
    for mat, _, _ in result.values():
        assert mat in MATERIAL_IDS, mat
    out = {
        'version': '26.3',
        'materials': [{'id': m, 'label': l, 'description': d} for m, l, d in MATERIALS],
        'rules': RULES,
        'blocks': [[b['id'], *result[b['id']], 'F' if b['id'] in FIXED_WATER else ''] for b in blocks],
    }
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(out, f, ensure_ascii=False, separators=(',', ':'))
    mats = collections.Counter(r[0] for r in result.values())
    conf = collections.Counter(r[2] for r in result.values())
    rules = collections.Counter(r[1] for r in result.values())
    print('blocks:', len(blocks), '| size:', os.path.getsize(OUT), 'bytes')
    print('confidence:', dict(conf))
    print('rules:', dict(rules))
    print('materials:', dict(mats.most_common()))


if __name__ == '__main__':
    main()
