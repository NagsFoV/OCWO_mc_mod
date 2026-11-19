# 01. Vision et décisions

## Objectif

Un cycle de l'eau complet et crédible, où les **rivières et les cascades se forment et existent naturellement**. La génération de terrain vanilla ne le permet pas.

Le cycle visé est le suivant :

océans, lacs et sols humides → évaporation → nuages poussés par le vent → pluie ou neige → humidité du sol et de la roche → suintement et sources → rivières et cascades → retour à l'océan.

## Principe clé

**Rien ne se perd, rien ne se crée, tout se transforme.** La quantité totale d'eau (liquide, humidité, vapeur, neige, glace) est conservée. A priori, **seule la génération des chunks crée de l'eau**. Aucun autre mécanisme n'en crée ni n'en détruit.

## Décisions validées

| Sujet | Décision | Date |
|---|---|---|
| Base technique | **Fork de PhysEx-Reborn** (Sasai_Kudasai_BM), à traiter comme le point de départ de notre propre moteur | 2026-11-11 |
| Flowing Fluids | Écarté : son eau qui tombe se disperse en l'air avec un rendu chaotique | 2026-11-11 |
| Eau qui tombe | On garde le comportement de PhysEx : une **colonne unie**, proche du vanilla | 2026-11-11 |
| Chargeurs de mods | **NeoForge et Fabric** | 2026-11-11 |
| Version cible | **Dernière version stable de Minecraft Java**. Au 11/11/2026, c'est probablement la 26.3 (« Wilderness Bound », sortie en septembre 2026), à confirmer au lancement | 2026-11-11 |
| Licence | **GPLv3**, imposée par le fork. Le code devra être public | 2026-11-11 |
| Humidité des blocs | Chaque bloc a une humidité. Il absorbe l'eau (pluie, eau voisine) et la transmet à ses voisins. Dans un bloc d'air, l'eau redevient un bloc d'eau | 2026-11-08 |
| Matériaux | Chaque matériau a une capacité de rétention et une vitesse de transmission différentes (la terre stocke beaucoup, la roche pleine presque rien) | 2026-11-08 |
| Blocs pleins | Certains blocs qui bloquaient l'eau la laissent maintenant passer (par exemple la cobblestone), avec un effet visuel quand l'eau en sort | 2026-11-11 |
| État des blocs | L'humidité ne change pas forcément l'état du bloc. Certains blocs changent d'état au-delà d'un seuil (terre trop humide → boue) | 2026-11-11 |
| Ralentissement | Le ralentissement vanilla correspond à un bloc d'eau plein. Chaque niveau ralentit proportionnellement (1/8 de bloc = 1/8 du ralentissement) | 2026-11-11 |
| Simulation | Elle tourne lentement, pas à chaque tick | 2026-11-08 |
| Nuages | De gros conteneurs (pas des voxels) qui accumulent la vapeur et grossissent selon la quantité stockée. Forme personnalisable | 2026-11-09 |
| Vent | Un système de vent simple | 2026-11-09 |
| Vidage des nuages | Selon la saturation, l'humidité du biome et l'altitude du sol : plus le sol est haut, plus il pleut facilement, pour alimenter les rivières et les cascades. **Pas de calcul de versants** (remplace la décision du 09/11) | 2026-11-11 |
| Nuages par zone | Les nuages sont une quantité d'eau nuageuse par case de **4×4 chunks** (pour commencer), avec un maximum qui dépend du biome. Transitions douces : diffusion, maximum mélangé, rendu interpolé | 2026-11-11 |
| Température | Base : la **température vanilla** du biome, réduite avec l'altitude. On y ajoute un modificateur selon l'heure (courbe fonction du temps de la journée) et un modificateur quand il pleut. Cette température modifiée est calculée par **une fonction à part**, utilisée seulement par nos systèmes et pour le choix pluie ou neige. La couleur de l'herbe et des feuilles garde la température vanilla. **Pas de saisons** (éventuel addon plus tard) | 2026-11-11 |
| Neige | Simplifiée : elle ralentit la vitesse et le taux de transmission de l'humidité vers le bloc en dessous. Une couche de neige ou de glace déjà au maximum transmet directement l'humidité aux blocs adjacents | 2026-11-09 |
| Plafond de neige | Pour l'instant, on garde le plafond vanilla de l'accumulation (règle de jeu `snowAccumulationHeight`, un bloc au maximum), au lieu d'un maximum par biome. Il ne concerne que les précipitations : un joueur peut toujours poser de la neige à la main au-delà. Quand la couche est au plafond, l'excédent de neige qui tombe est transmis au bloc en dessous (règle du 11/11). On évite ainsi une accumulation infinie sans forcer de fonte | 2026-11-11 |
| Neige qui transmet | Quand la neige transmet son eau au bloc en dessous, la couche diminue d'autant (conservation). La transmission dépend de l'humidité minimale à partir de laquelle ce bloc peut absorber de la neige | 2026-11-11 |
| Conservation de l'eau | Principe clé : rien ne se perd, rien ne se crée. Seule la génération des chunks crée de l'eau | 2026-11-11 |
| Eau des cultures et de la nourriture | Les cultures et la nourriture contiennent de l'eau, même stockées. Une partie est dans le fruit, une autre dans la plante : la part de la plante est libérée dès la récolte, celle du fruit quand il est détruit ou consommé | 2026-11-11 |
| Eau détruite hors de l'overworld | Le Nether et l'End ne génèrent ni eau ni pluie. L'eau détruite dans une autre dimension est transférée dans l'overworld. Nether : à la position correspondante (même rapport que les portails). End : à un endroit au hasard dans les zones de l'overworld situées entre le spawn et le portail de l'End | 2026-11-12 |
| Eau détruite ou consommée | Ce qui est **consommé par un joueur** (nourriture, potion...) va directement dans le nuage de la case où il se trouve. Ce qui est **détruit par le feu ou la lave** devient de la vapeur (exemple validé : le pain). Ce qui est **détruit autrement** dans le monde libère sa quantité d'eau sous forme de bloc d'eau. Quand la conversion n'est pas exacte (un reste plus petit que l'unité indivisible du nouvel état), on arrondit et le reste va directement dans le nuage de la case | 2026-11-12 |
| Architecture multi-loader | Modèle **MultiLoader** : un module commun (simulation, humidité, nuages, vapeur, mixins) et deux petits modules Fabric et NeoForge. Pas d'Architectury. On commence par Fabric, NeoForge vient une fois le moteur stable | 2026-11-12 |
| Portail de l'End de référence | On mémorise les portails de l'End activés. À chaque transfert, on en tire un au hasard, et l'eau tombe entre le spawn et ce portail. Idée pour plus tard : renvoyer plutôt cette eau vers les zones qui en manquent | 2026-11-12 |
| Mécanismes vanilla | Aucune source infinie, quelle qu'elle soit (PhysEx le fait déjà). Les autres mécanismes deviennent des blocs ou objets avec une capacité et des règles de transfert, donc de simples réglages de valeurs : le chaudron absorbe mais ne transmet rien autour ni en dessous ; la dripstone récupère l'eau du bloc au-dessus et la rejette par sa pointe ; l'éponge, le seau, la fiole, la poudre de béton, la glace qui fond suivent la même logique et la règle générale des destructions | 2026-11-12 |
| Nom du mod | **Overly Complicated Water Overhaul** (OCWO) | 2026-11-12 |
| Stockage du projet | Dépôt git public sur GitHub : https://github.com/NagsFoV/OCWO_mc_mod, géré par Claude (remplace « tout sur le PC »). Les références locales (clone de PhysEx-Reborn, skdslib) n'y sont pas publiées | 2026-11-12 |
| Eau par bloc et par objet | Chaque bloc miné et chaque objet contient une quantité fixe d'eau ou d'humidité | 2026-11-11 |
| Végétation | Les plantes hors cultures (arbres, herbe, fleurs...) ne consomment pas d'eau, n'en contiennent pas et ne dépendent pas de l'humidité, pour ne pas contraindre le terraforming par des réactions en chaîne. Seules exceptions : les cultures et la nourriture (qui contiennent de l'eau, voir plus bas), la canne à sucre et le corail, comme en vanilla | 2026-11-11 |
| Eau souterraine | Quatre mécanismes complémentaires (détail dans `03-humidite-et-etats-des-blocs.md`) : fond quasi imperméable (deepslate, bedrock) qui crée une nappe ; écoulement latéral de la nappe vers des sources ; monde généré à l'équilibre (roche et grottes sous la nappe déjà saturées) ; gradient géothermique : sous y ≈ 0, l'eau s'évapore d'autant plus vite qu'elle est profonde et passe directement dans la case d'atmosphère au-dessus. L'aspect écologique reste cadré : les dégâts des joueurs se réparent d'eux-mêmes | 2026-11-12 |
| Vapeur active | La vapeur passive (évaporation naturelle) reste abstraite et va directement dans les nuages. L'eau chauffée par une source de chaleur active devient un vrai fluide vapeur qui monte, se disperse dans les 3×3 blocs au-dessus et, à densité minimale à l'air libre, rejoint le nuage de la case. 1 niveau d'eau = 1 bloc de vapeur de densité 4 (sur 8). Détail dans `04-vapeur-et-nuages.md` | 2026-11-12 |
| Condensation de la vapeur | Une vapeur piégée longtemps dans un endroit froid redevient de l'eau (4 unités de densité = 1 niveau d'eau) | 2026-11-12 |
| Brûlure par la vapeur | Une vapeur dense brûle les entités qui la traversent | 2026-11-12 |
| Rendu de la vapeur | La vapeur gêne la vue. Elle est rendue par des particules opaques, d'autant plus nombreuses qu'elle est dense, dans le style de Minecraft. Quand la tête du joueur est dans la vapeur, un brouillard blanc s'affiche à l'écran (comme sous l'eau ou dans la neige poudreuse), avec une distance de vue qui dépend de la densité | 2026-11-12 |
| Seau : ramassage | Deux modes au choix par la règle de jeu `ocwo:bucket_area_pickup` : AREA (par défaut, `true`) complète le seau avec les blocs d'eau connectés autour, sur la même couche ; COLUMN (`false`, comportement PhysEx) ne prend que le bloc visé et celui du dessous. Le seau universel (shift + clic droit) suit la même règle | 2026-11-12 |
| Pression | Modèle de la charge hydraulique validé : l'eau va de la surface libre la plus haute vers le point libre le plus bas d'une même masse connectée. Vases communicants, fontaines, puits artésiens. L'air n'est pas géré. Voir `06-pression.md` | 2026-11-12 |
| Tests en jeu | Validés en jeu par Valentin : les cascades (y compris celles qui se rencontrent) et les éponges | 2026-11-12 |
| Tests en jeu : pression v2 | Validée en jeu par Valentin (hors couloir sous un lac, pas encore testé). Défauts d'affichage avec des shaders notés dans `05-problemes-connus.md`, non prioritaires | 2026-11-12 |
| Rendu d'un bloc plein | Un bloc à 8 niveaux s'affiche à pleine hauteur quand l'eau ne peut pas monter dans le bloc du dessus (bloc ni air, ni remplaçable, ni waterloggable). Sinon, 8/9 de hauteur comme en vanilla. La même règle s'applique à la hauteur logique (entités, bateaux) et au rendu (demande de Valentin, remplace la décision précédente du même jour) | 2026-11-12 |
| Évaporation de l'humidité | **Partout, même sous terre** : chaque transfert d'humidité (absorption, transmission) en perd une part qui monte aux nuages de la zone, 2 % en biome tempéré, moins au froid, plus au chaud et sec (règle `ocwo:moisture_evaporation`). Remplace la proposition « seulement pour l'eau qui voit le ciel ». Détail dans `03-humidite-et-etats-des-blocs.md`, « Évaporation » | 2026-11-19 |
| Éponges (règles v2) | Une éponge est un **seau** de 0 à 64 niveaux (128 étirée), sans échange avec le sol, qui sèche lentement au chaud et au sec ; les éponges voisines se partagent leur eau ; l'objet garde son eau. L'éponge compressée et l'éponge étirée ne tiennent que sous condition (bloc solide de chaque côté de l'axe ; élément collant à chaque bout). Une éponge ne pousse jamais un autre bloc : elle est écrasée ou compressée. Détail dans `03-humidite-et-etats-des-blocs.md`, « Éponges » | 2026-11-19 |
| Fusion des nuages | Les nuages fusionnent quand ils se rencontrent : un grand nuage qui couvre 100 blocs² vaut mieux que 100 petits. On garde en réserve l'idée de redécouper plus tard les grands nuages en plusieurs petits | 2026-11-11 |

## Base technique : PhysEx-Reborn

- Dépôt : https://github.com/Sasai-Kudasai-BM/PhysEx-Reborn (GPLv3). Mod d'origine sur CurseForge : https://www.curseforge.com/minecraft/mc-mods/physex
- État actuel : version 2.0.0, Fabric 1.21.11, Java 21, mappings Mojang. Environ 50 fichiers Java et 3 800 lignes.
- Ce qu'on garde comme point de départ : l'eau finie à 8 niveaux, les tâches d'écoulement et d'égalisation, le déplacement de l'eau par les blocs posés, et la couche fluide stockée par chunk (`SparseChunkStorage`, `FluidLayer`), qui sert de modèle pour stocker l'humidité.
- `skdslib.7z`, la bibliothèque de l'auteur livrée dans le dépôt, contient du code source (`net.skds.lib2`, environ 480 fichiers et 50 000 lignes, sans fichier de licence propre). PhysEx n'en utilise qu'une douzaine d'imports (`lib2.utils`, `lib2.mat.FastMath`, `lib2.io` pour la config JSON). **On prévoit de la retirer** et de remplacer ces quelques appels.
- Premier chantier : porter PhysEx de la 1.21.11 vers la version cible.

## Ordre de développement proposé

1. Fork, retrait de skdslib, mise en place de la structure MultiLoader (module commun et module Fabric), portage vers la version cible.
2. Corrections et changements du moteur existant : ralentissement par niveau, éclairage, eau qui tombe.
3. Humidité des blocs, passage de l'eau à travers les blocs pleins, sources par suintement.
4. Changements d'état liés à l'humidité (boue, etc.).
5. Vapeur, nuages, vent et pluie locale.
6. Température et neige.
7. Génération du monde pré-humidifiée.
8. Performances et compatibilité.

## Questions ouvertes

- Mécanismes vanilla : les valeurs exactes (capacité du chaudron, débit de la dripstone, contenu d'un seau, d'une fiole, d'une éponge...), dans les fichiers de données.
- Conservation en multijoueur : que devient l'eau contenue dans l'inventaire d'un joueur qui se déconnecte ? (À traiter plus tard.)
- Plus tard : un objet (« hygromètre ») pour afficher l'humidité. Simple accès à des données qui existent déjà côté serveur, sans impact sur l'architecture.
- Eau souterraine : les réglages chiffrés (profondeur où commence l'évaporation, vitesse au contact de la bedrock, perméabilité de la deepslate).
- Biomes : ils peuvent changer, rarement (commande `/fillbiome`, mods, éditeurs de monde). Le code doit relire le biome et recalculer le maximum des cases de temps en temps, au lieu de le figer.
