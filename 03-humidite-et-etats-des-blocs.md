# 03. Humidité et états des blocs

## Demande

- L'humidité **ne change pas forcément l'état du bloc**. De la terre sèche ou un peu humide reste de la terre et se comporte comme de la terre.
- Certains blocs **changent d'état au-delà d'un seuil**. Par exemple, une terre trop humide devient de la boue.
- Des blocs pleins qui bloquaient l'eau la laissent passer, comme la cobblestone. Il faut un **effet** quand l'eau en sort.

## Ce que fait Minecraft aujourd'hui

### La terre labourée (`FarmBlock`)

C'est le seul vrai système d'humidité du vanilla.

- Elle a une propriété d'état **`MOISTURE`**, de 0 à 7 (`BlockStateProperties.MOISTURE`). Chaque valeur est un état de bloc distinct, avec sa propre texture (la terre mouillée est plus sombre à partir de 7).
- Elle évolue par **tick aléatoire** (`randomTick`) :
  - s'il y a de l'eau dans un rayon de 4 blocs horizontalement, au même niveau ou un bloc au-dessus (`isNearWater`), ou s'il pleut au-dessus, alors `MOISTURE` passe directement à 7 ;
  - sinon, `MOISTURE` baisse de 1. Arrivée à 0 sans culture dessus, la terre labourée redevient de la terre (`turnToDirt`).
- La croissance des cultures est plus rapide sur une terre humide (`CropBlock.getGrowthSpeed`).
- PhysEx modifie déjà ce comportement (`FarmBlockFluidMixin`) pour que la terre labourée consomme réellement de l'eau.

### Les autres transformations liées à l'eau

| Bloc | Transformation vanilla | Déclencheur |
|---|---|---|
| Terre, terre stérile, terre racinée | → **boue** | Le joueur utilise une fiole d'eau dessus (action manuelle, pas de simulation) |
| Boue | → **argile** | Boue posée sur un bloc au-dessus d'une stalactite de dripstone : elle sèche par tick aléatoire |
| Poudre de béton | → béton | Contact immédiat avec de l'eau |
| Éponge | → éponge mouillée | Absorbe l'eau autour d'elle. L'éponge mouillée sèche dans le Nether (OCWO : voir « Éponges ») |
| Corail | → corail mort | Aucune eau adjacente (vérifiée par tick programmé) |
| Neige, glace | Fondent | Luminosité de bloc supérieure à 11 (tick aléatoire) |
| Canne à sucre | Ne pousse pas | Pas d'eau adjacente au bloc de base |

### Le tick aléatoire

C'est le mécanisme vanilla de toutes ces évolutions lentes.

- Avec `randomTickSpeed = 3`, chaque section de 16×16×16 reçoit 3 ticks aléatoires par tick de jeu. Un bloc donné en reçoit un toutes les **68 secondes en moyenne**, avec une grande variation.
- Seuls les blocs qui déclarent `isRandomlyTicking` sont concernés, ce qui rend le système bon marché.
- Ça ne tourne que dans les chunks proches des joueurs (distance de simulation).

## Deux façons de stocker l'humidité

| | A. Propriété d'état (comme `MOISTURE`) | B. Couche de données séparée (comme la couche fluide de PhysEx) |
|---|---|---|
| Principe | On ajoute une propriété `humidity` aux blocs | On stocke l'humidité dans un tableau par section de chunk, à côté des blocs |
| Coût | Multiplie le nombre d'états de **chaque** bloc concerné. Sur des centaines de blocs vanilla, ça explose la table des états et casse la compatibilité | Environ 2 Ko par section à 16 niveaux, seulement là où il y a de l'humidité |
| Comportement | Le bloc change d'état à chaque variation : mises à jour, envoi au client, nouveau rendu | Le bloc reste identique, comme tu le veux. Seules nos données changent |
| Rendu | Gratuit, avec une texture par état | Il faut envoyer les données au client si on veut un rendu « mouillé » (teinte plus sombre) |

**Recommandation : B.** C'est exactement ta règle : l'humidité vit à côté du bloc et ne le modifie pas. On ne passe par un **changement d'état réel** qu'au-delà d'un seuil (terre → boue), comme le fait déjà la terre labourée quand elle redevient de la terre.

## Seuils de transformation proposés (à valider)

Ce sont des pistes de départ, toutes réglables par un fichier de données.

| Bloc | Seuil haut | Devient | Retour quand ça sèche |
|---|---|---|---|
| Terre, terre stérile, terre racinée | stade « mouillé » atteint (validé le 12/11/2026) | Boue | Boue → terre sous un seuil bas (hystérésis, pour éviter les allers-retours) |
| Boue | sèche longtemps | Terre (ou argile si chaud ?) | - |
| Herbe | saturée | Reste de l'herbe, mais l'eau ressort en surface | - |
| Sable | jamais | - | Très perméable, il stocke peu |
| Cobblestone, roche fissurée | saturée longtemps | Version moussue (cobblestone moussue) | Optionnel |
| Terre labourée | Remplacer le calcul de `MOISTURE` par notre humidité | `MOISTURE` = humidité ramenée sur l'échelle 0 à 7 | Garde le comportement vanilla |

Points de conception :
- Les transformations se font **par tick aléatoire ou par la simulation lente**, jamais en direct, pour ne pas surcharger le serveur.
- Les propriétés de chaque matériau (capacité, perméabilité, seuils, transformation) vont dans des **fichiers JSON de datapack** et des **tags de blocs**. Ça permet de régler sans recompiler et d'ajouter la compatibilité avec d'autres mods.

## Eau qui sort des blocs pleins (cobblestone, etc.)

Un bloc plein perméable et saturé laisse ressortir l'eau sur une face libre. Effets possibles, à cumuler :

| Effet | Description | Coût |
|---|---|---|
| Gouttes sous le bloc | Le vanilla le fait déjà : il fait goutter un bloc qui a de l'eau au-dessus (particules `DRIPPING_WATER` dans `ClientLevel`, côté client ; la condition exacte est à vérifier sur la version cible). On le réutilise et on l'étend aux faces latérales | Très faible, client seulement |
| Filet d'eau sur la face | Particules ou rendu qui coule le long de la face, plus dense selon le débit | Faible |
| Son | Ruissellement léger, avec une chance faible | Très faible |
| Teinte mouillée | Le bloc paraît plus sombre quand il est humide | Il faut envoyer l'humidité au client |
| Flaque au pied | La simulation pose réellement de l'eau en dessous ou à côté (niveau 1) : c'est la source qui naît | C'est la mécanique de base des sources |
| Mousse | À long terme, la cobblestone devient moussue | Faible |

**Recommandation** pour une première version : gouttes et filet en particules, plus la vraie flaque posée par la simulation. La teinte mouillée viendra plus tard, avec la synchronisation client.

## Où va l'eau souterraine

**Le problème** : avec seulement une capacité maximale par bloc, l'eau ne fait que descendre. Les blocs se saturent les uns après les autres, puis l'eau ressort dans les grottes, qui se remplissent par le bas. Et comme l'eau est conservée, toute l'eau partie au fond manque en surface : à terme, la surface s'assèche et le cycle s'arrête.

**Proposition, inspirée des vraies nappes phréatiques** :

1. **Un fond quasi imperméable.** La perméabilité baisse avec la profondeur : la deepslate laisse passer presque rien et la bedrock rien du tout. L'eau s'arrête au-dessus et forme une **nappe**.
2. **Un écoulement latéral dans la nappe.** Dans une zone saturée, l'eau se déplace aussi sur les côtés, vers le plus bas. Elle finit par ressortir là où la nappe rencontre la surface, au flanc d'une vallée ou d'une falaise : ce sont les **sources**, qui alimentent les rivières. C'est exactement le but du mod.
3. **Un monde généré à l'équilibre.** À la génération, la roche sous la nappe est déjà saturée, et les grottes situées sous la nappe sont déjà inondées (le vanilla le fait en partie avec ses aquifères). La pluie ne remplit donc pas un réservoir vide pendant des siècles : la nappe est déjà pleine, et l'eau en plus ressort en sources.
4. **Les grottes au-dessus de la nappe** ne sont que traversées : gouttes au plafond, petits filets, ruisseaux souterrains qui descendent vers la nappe. Elles ne se remplissent pas, sauf dans les cuvettes fermées, ce qui donne des lacs souterrains.

**Équilibre visé** : pluie → infiltration → nappe → sources → rivières → océan → évaporation → pluie. Si une région reçoit plus d'eau qu'elle n'en évacue, sa nappe monte jusqu'à la surface et crée un marais ou un lac. C'est un comportement naturel, pas un bug.

### Chaleur des profondeurs (idée de Valentin, 12/11/2026)

**Le cas à couvrir** : les joueurs creusent des puits et laissent l'eau couler au fond. Sans garde-fou, un lac vidé dans un puits serait perdu pour la surface. Valentin veut une dimension écologique cadrée, qui ne devienne pas le centre du jeu, et propose :
- une température qui augmente à mesure qu'on descend, pour que l'eau profonde s'évapore et remonte ;
- un cycle artificiel : l'eau qui arrive sur la bedrock est transférée dans les nuages.

**Synthèse proposée : un « gradient géothermique »** qui réunit les deux idées.
- Au-dessus d'une certaine profondeur (par exemple y = 0, le début de la deepslate), l'eau souterraine ne s'évapore pas. Les grottes proches de la surface, dont les grottes luxuriantes, gardent leur eau.
- En dessous, l'eau liquide et l'humidité s'évaporent d'autant plus vite qu'on est profond. Le maximum est au contact de la bedrock.
- La vapeur produite ne traverse pas les blocs : elle va **directement dans la case d'atmosphère au-dessus** (4×4 chunks). C'est le cycle artificiel, mais justifié par la chaleur, et sans aucun calcul de remontée bloc par bloc.
- Visuel : un peu de vapeur en particules au-dessus de l'eau profonde, ce qui signale au joueur qu'elle s'évapore.
- Résultat : un lac vidé dans un puits finit par retomber en pluie sur la même région. La « catastrophe » se répare toute seule, à une vitesse réglable.
- Le vanilla n'a pas de température qui monte avec la profondeur (elle ne change qu'au-dessus de y = 80) : on l'ajoute dans notre fonction de température.

**Alternative écartée pour l'instant** : une évaporation lente de l'eau des grottes vers l'atmosphère. Elle empêcherait aussi l'accumulation, mais elle assécherait peu à peu les grottes isolées, comme les grottes luxuriantes sous un désert.

## Rendu visuel des stades d'humidité, à moindre coût

Objectif : voir qu'un bloc est sec, humide ou trempé, sans alourdir le jeu.

- **Peu de stades visibles** : on ramène l'humidité à 4 stades d'affichage (sec, humide, mouillé, saturé), soit 2 bits par bloc. La simulation garde sa précision, seul l'affichage est grossier.
- **Seulement les blocs visibles** : on n'envoie au client que les stades des blocs qui ont une face exposée (à l'air ou à l'eau). L'intérieur du terrain n'est jamais transmis.
- **Envoi seulement au changement de stade** : la simulation étant lente, un bloc change rarement de stade. Les changements sont regroupés par section de chunk.
- **Assombrissement au moment de la construction du maillage** : quand le client construit le maillage d'une section de chunk, on multiplie la couleur des sommets des faces humides (plus sombre, légèrement bleuté). C'est payé une seule fois par reconstruction, **rien par image**. Ça marche avec n'importe quel pack de textures, sans nouvelle texture ni nouvel état de bloc. PhysEx touche déjà à ce code (`SectionCopyFluidMixin`, `RenderSectionRegionFluidMixin`).
- **Compatibilité** : Sodium remplace la construction du maillage, donc il faudra un module de compatibilité dédié. Les shaders (Iris) respectent en général la couleur des sommets.
- **Complément gratuit** : les gouttes en particules pour les blocs saturés (voir plus haut).

Alternatives écartées : une texture par stade (multiplie les états de bloc) et une teinte via `BlockColor` (ne s'applique qu'aux faces prévues pour la teinte, comme l'herbe : il faudrait réécrire les modèles vanilla, ce qui entre en conflit avec les packs de ressources).

## Classification des blocs par matériau (en cours, 12/11/2026)

**Principe** : chaque bloc reçoit un **matériau** (environ 24) qui porte ses propriétés d'eau. On ne règle bloc par bloc que les exceptions.

**Deux sortes d'eau dans un bloc, à ne pas confondre** :
- **Humidité retenue** : variable, elle dépend de l'état du monde. Quand on mine le bloc, elle est libérée dans le monde selon la règle des destructions (bloc d'eau, vapeur ou nuage).
- **Eau fixe** : les blocs faits d'eau (boue, glace, neige, éponge mouillée). Cette quantité voyage avec l'objet quand on le ramasse.

**Plantes : pas d'humidité ne veut pas dire étanche (12/11/2026)**. Deux propriétés séparées :
- l'**humidité** absorbée dans la matière du bloc : 0 pour toutes les plantes, algues comprises (matériau « Plante » à 0/0) ;
- le **partage de la case avec de l'eau liquide** : il vient du jeu (propriété waterlogged ou couche fluide de PhysEx), pas du classement. L'eau appartient à la case, pas à la plante, et elle reste quand on casse la plante.

Trois cas, vérifiés dans le code actuel :
- **plantes waterloggables** (sea pickle, petite feuille-goutte…) : de 1 à 8 niveaux d'eau dans leur case, via la couche fluide ;
- **plantes aquatiques** (algue, herbier) : elles n'existent que dans une case d'eau pleine. Si on retire de l'eau de leur case, la plante est détruite et remplacée par l'eau qui reste (`FluidUtils.applyFluidToBlock`). Une algue ne peut pousser que dans une case d'eau pleine, ce qui l'empêche de créer de l'eau (`KelpBlockMixin`, correctif de PhysEx) ;
- **plantes terrestres** (herbe, fleurs…) : l'eau occupe leur case grâce à la couche fluide, et elles restent en place au lieu d'être arrachées comme en vanilla.

**Propriétés d'un matériau (proposées)** : capacité (niveaux d'eau retenus), perméabilité (vitesse de transmission, 0 = étanche), stades visibles (assombrissement), changement d'état (bloc cible, stade, retour au séchage), « retient l'eau » (laine, éponge), eau libérée quand on le mine ou le détruit.

**Suintement (décidé le 12/11/2026)** : un bloc saturé (humidité au maximum et qui en reçoit encore) rend le surplus sous forme d'eau liquide, 1 niveau à la fois, dans une case libre à côté ou en dessous (jamais au-dessus), à une vitesse liée à sa perméabilité. C'est le mécanisme qui fait naître les sources et les rivières (plafonds de grotte qui gouttent, falaises qui coupent la nappe). **Règle unique** : tout matériau perméable saturé suinte, sauf s'il a un changement d'état prévu à ce stade (la terre devient de la boue) ou s'il est marqué « retient l'eau ». Pas de case « suintement » par matériau.

**Réglages par bloc (décidé le 12/11/2026)** : en général, une règle unique par matériau. Certains blocs spéciaux ont leur propre réglage, par exemple une **perméabilité différente selon la face** : le bambou (une face étanche, une autre non), le basalte (colonnes), et plus généralement les blocs orientés (bûches, piliers), avec une perméabilité « le long de l'axe » et une autre « en travers ».

**Outils** (dossier `tools`) :
- `blocks-26.3-raw.json` : tous les blocs de la 26.3 (1 286), extraits du jeu lui-même (`gradlew :fabric:runBlockDump`), avec leurs tags vanilla, leur forme, leur son, leur dureté, etc.
- `classify.py` : pré-classement automatique par matériau. Il produit `blocks-26.3.json` (79 Ko), qui donne pour chaque bloc son matériau, la règle qui l'a classé, un indice de confiance (H : liste ou tag vanilla, M : forme ou nom, L : son ou aucune règle) et l'indicateur « eau fixe ».
- Résultat du premier passage : 725 blocs en confiance haute, 519 moyenne, 42 basse (dont 13 non classés : bloc d'os, TNT, générateurs, froglights…). Le matériau « creux » regroupe 481 blocs (portes, clôtures, panneaux, rails, lits…) où l'eau passe comme dans l'air, sans humidité.
- Prochaine étape : un outil d'édition (page web) pour que Valentin corrige le classement et règle les valeurs des matériaux.

## Implémentation, étape 1 (branche `humidite`, 12/11/2026)

**Données** (`tools/export_datapack.py`) : le classement (pré-classement + modifications faites dans la page, relues depuis son stockage dans `tools/store`) est converti en fichiers de données du mod, rechargeables avec `/reload` :
- `data/ocwo/ocwo_materials/<matériau>.json` : capacité, perméabilité, stades visibles, « retient l'eau », changement d'état (bloc cible, stade d'entrée, stade de retour, blocs concernés) et la liste des blocs (`#tag` accepté, pour les autres mods) ;
- `data/ocwo/ocwo_block_settings/<bloc>.json` : réglages propres à un bloc (capacité, perméabilité, perméabilité le long de l'axe et en travers, par face, eau fixe).
- Valeurs par défaut de la page tant que Valentin n'a pas tout relu. Réglages ajoutés par défaut : basalte et basalte poli (le long de l'axe 4, en travers 0), bloc de bambou (3 / 0).
- Le changement d'état de la terre ne concerne que la terre, la terre stérile et la terre racinée (pas l'herbe, le podzol ni la terre labourée).

**Stockage** (`MoistureLayer`) : l'humidité de chaque bloc, par section de chunk (tableau créé seulement si la section en contient), en **unités entières : 1 niveau d'eau = 16 unités**, pour une conservation exacte. Sauvegardée avec le chunk, pas encore envoyée aux clients (ce sera l'étape 2, pour l'assombrissement).

**Simulation par ticks aléatoires** (`MoistureSimulation`, 16/11/2026, validé par Valentin) : comme la pousse des cultures, à chaque tick, `ocwo:moisture_random_ticks` blocs (3 par défaut) sont tirés au hasard dans chaque section de chunk qui a une couche d'humidité, dans les chunks qui tournent et dans la distance de simulation de l'eau. Le tirage utilise l'aléatoire du monde : seuls l'écoulement et la pression restent déterministes. Chaque action d'un bloc est tirée selon la perméabilité de son matériau : en moyenne, le sable laisse toujours passer l'eau plus vite que la terre, la pierre presque pas, les matériaux étanches jamais. L'absorption depuis l'eau est lente et bornée : au plus un niveau par passage, avec la probabilité de la perméabilité. Le coût est fixe et petit, quelle que soit la quantité d'eau. L'eau naturelle au repos n'est pas absorbée (voir 06, « Monde au repos »).

**Humidité de génération** (16/11/2026) : une section sans couche d'humidité est à son équilibre, calculé à partir de la profondeur et du biome, sans rien enregistrer :
- saturé sous le niveau de la mer ;
- au-dessus : sec dans les biomes sans précipitations (désert, savane…), mouillé dans les marais, humide ailleurs ;
- jamais au stade qui changerait le bloc (la terre reste de la terre), jamais sous le stade qui le ferait revenir (la boue reste de la boue), et au moins son eau fixe.

Une section reçoit sa couche, remplie avec cet équilibre (sans créer ni perdre d'eau), dès qu'un de ses blocs est dérangé (bloc posé ou cassé, eau qui bouge à côté). Un bloc à son équilibre, entouré de blocs à leur équilibre et sans eau en mouvement à côté, ne fait rien et ne réveille rien. Un bloc posé par un joueur arrive sec (plus son eau fixe). Les chunks déjà générés en profitent aussi pour les sections qu'ils n'avaient jamais enregistrées.

Un pas d'un bloc :
1. **absorption** : prend 1 niveau d'eau à un bloc d'eau voisin, avec une probabilité liée à la perméabilité de la face ;
2. **transmission** aux voisins poreux, selon l'écart de remplissage et la plus faible des deux perméabilités (par face, ou par axe pour les blocs orientés), avec un biais vers le bas ;
3. **suintement** (règle unique) : un bloc saturé qui reçoit encore (de l'eau ou un voisin saturé au-dessus ou sur le côté) donne 1 niveau d'eau à une case libre en dessous ou à côté, jamais au-dessus, sauf s'il « retient l'eau » ou change d'état à ce stade ;
4. **changement d'état** : terre → boue au stade « mouillé », boue → terre sous « humide » (hystérésis), poudre de béton → béton de la même couleur, éponge → éponge mouillée. L'humidité est conservée à travers le changement.

Les blocs deviennent actifs quand l'eau bouge à côté d'eux, quand un bloc poreux est posé ou cassé, et par un échantillonnage autour des joueurs (eau immobile contre un bloc poreux). Quand un bloc perd sa capacité (cassé, remplacé), son humidité est rendue en eau sur place ou à côté. Ce qui ne trouve pas de place reste stocké et sera rendu plus tard : rien n'est jamais perdu.

**Eau fixe** (blocs faits d'eau qui retiennent de l'humidité : boue ; l'éponge mouillée n'en a plus depuis le 18/11/2026, voir « Éponges »), comptabilité symétrique : quand on casse le bloc, l'objet emporte min(humidité, eau fixe) et le reste est libéré en eau ; quand on pose le bloc, son eau fixe est ajoutée à la case. L'eau fixe vaut 2 niveaux, le seuil sous lequel la boue redevient de la terre (et l'éponge mouillée de l'éponge) : miner puis reposer ne crée donc jamais d'eau, et la boue posée ne redevient pas aussitôt de la terre. Une boue générée par le monde (mangroves) sans humidité reçoit une fois son eau fixe au premier passage de la simulation (eau de génération, seule création permise). La glace et la neige ne retiennent pas d'humidité : leur eau fixe n'est pas encore utilisée. Les algues, herbiers et colonnes de bulles ne cèdent jamais d'eau à l'absorption (sinon ils seraient détruits).

**Pluie** : pas d'absorption (pas encore de nuages : la pluie vanilla créerait de l'eau). La règle de jeu `ocwo:rain_moisture_test` (désactivée par défaut, **non conservative**) permet d'essayer l'effet de la pluie sur les blocs de surface.

**Règles de jeu** : `ocwo:moisture_simulation` (activée par défaut), `ocwo:rain_moisture_test` (désactivée), `ocwo:moisture_random_ticks` (3), et la distance de simulation de l'eau (voir 06).

**Commande de débogage** : `/ocwo moisture [position]` affiche le matériau, l'humidité (unités et niveaux), le stade, la perméabilité de chaque face et les changements d'état prévus ; `/ocwo moisture set <position> <unités>` (opérateurs) fixe l'humidité pour un essai.

**Performance** (test `wetArea`, lancé avec les tests de performance) : un bassin de 48×48, 2 blocs de profondeur, sur un sol de terre et de cobblestone. Sur 2 000 ticks : notre code d'eau prend 3,1 ms par tick en moyenne, dont 0,18 ms pour l'humidité, et 39 ms au pire tick (cibles : 10 et 50 ms). À la fin, 1 150 blocs de terre sont devenus de la boue.

Avec les quotas fixes (15/11/2026), mesuré le même jour sur le même PC (chargé) pour comparer : avant, 6,0 ms en moyenne, 42 à 45 ms au pire tick ; après, 6,9 ms (dont 0,1 ms pour l'humidité), 39 à 57 ms au pire tick, 1 120 blocs de boue. Avec 2 000 blocs par passage, le pire tick montait à 70 ms : d'où la valeur de 500. Depuis la simulation complète (tous les blocs actifs à chaque pas) : 2,3 ms en moyenne, 1 152 blocs de boue ; le pire tick (157 ms) est au moment où le bassin est posé d'un coup. Avec les ticks aléatoires (16/11/2026), un bassin de 24×12 posé sur du sable et de la terre coûte 4,1 ms par tick pour tout le serveur ; après 2 minutes, le sable est humide (22/48 unités) et la terre encore sèche (15/96). Test `sandSoaksFasterThanDirt` : sable 77 % plein, terre 20 %, eau exacte.

**Tests automatiques** (`CheckMoistureGameTests`, dans les tests courants) : mur de gravier qui suinte (cobblestone jusqu'au 19/11/2026), laine qui retient, obsidienne et cobblestone étanches, terre sous un bassin qui devient boue. Chaque test vérifie à chaque tick que l'eau liquide plus l'humidité ne change pas. Les tests de pression sont désormais construits en obsidienne : avec de la pierre, une partie de l'eau passait dans les murs (comportement normal, mais qui faussait ces tests de pression).

## Éponges (18/11/2026, branche `eponges`, à tester en jeu)

Règles de Valentin. L'eau est conservée exactement dans chaque cas : eau liquide plus humidité des blocs.

**L'éponge stocke l'eau** : rien n'est détruit comme en vanilla. L'eau absorbée devient l'humidité de l'éponge (matériau `eponge`, contenance 64 niveaux, soit 8 blocs d'eau pleins). L'éponge aspire tout de suite :
- quand on la pose ;
- quand un bloc voisin change.

Elle prend l'eau reliée jusqu'à 6 blocs de distance, sur 64 cases au plus, jusqu'à être pleine. Elle paraît mouillée dès qu'elle tient 1 niveau.

Elle garde son eau quand un piston la pousse ou la tire. Plus généralement, tout bloc déplacé par un piston emporte son humidité.

Une éponge cassée, brûlée ou détruite par une explosion rend son eau autour d'elle. Elle n'a plus d'eau fixe : l'objet éponge mouillée ne transporte rien.

Dans le Nether, une éponge mouillée sèche et son eau s'évapore, comme en vanilla.

**Écrasement** : une éponge mouillée poussée par un piston contre un bloc qui ne bouge pas est écrasée. C'est le cas quand le piston ne pourrait pas pousser : bloc fixe, ou plus de 12 blocs à pousser. Une éponge sèche bloque le piston, comme en vanilla.
- L'éponge rend toute son eau dans les cases libres autour d'elle, les plus proches d'abord, vers le bas en premier. L'eau ne va jamais dans le bloc contre lequel elle est pressée.
- Sans place autour, l'eau passe par la tête du piston, qui peut contenir de l'eau (elle peut ensuite passer dans le corps du piston).
- Ce qui ne trouve aucune place reste dans la case de la tête, en humidité. Il en sort plus tard, comme pour tout bloc qui ne retient plus d'eau.
- L'éponge se casse et tombe au sol en objet éponge sèche.

**Double éponge** (`ocwo:compressed_sponge`) : deux éponges pressées ensemble contre un bloc qui ne bouge pas, mouillées ou non.
- Chacune rend la moitié de son eau, en niveaux entiers ; le reste est gardé.
- Elles deviennent un seul bloc à la place de la deuxième, et la tête du piston prend la place de la première.
- C'est une éponge essorée : elle n'aspire rien tant qu'elle est compressée. Sa contenance de 128 niveaux sert seulement à garder ce qui reste.
- Quand la tête du piston s'en va, elle se sépare en deux éponges, qui se partagent son eau et réaspirent normalement. L'eau déjà présente dans la case libérée va dans l'éponge posée là.
- Cassée, elle donne deux éponges et rend son eau.

**Éponge étirée** (`ocwo:stretched_sponge`) : un piston collant sorti a une éponge devant sa tête. De l'autre côté, l'éponge est tenue par un élément collant :
- un bloc de slime ou de miel ;
- la tête d'un autre piston collant tournée vers elle ;
- la face d'un piston collant rentré tourné vers elle.

Quand le piston se rétracte, l'éponge ne bouge pas : elle s'étire sur deux blocs, sa case et celle de la tête. Chaque moitié contient autant qu'une éponge, donc l'éponge étirée tient deux fois plus, et elle aspire tout de suite.

Elle redevient une éponge :
- quand le piston la repousse en un seul bloc (dans la case la plus éloignée du piston) ;
- quand une moitié est détruite : l'autre moitié redevient l'éponge, et la moitié détruite ne donne pas d'objet.

Elle rejette alors le surplus autour d'elle, au-delà de 64 niveaux. Ce qui ne trouve pas de place suinte plus tard.

**Blocs non poussables** : les deux nouveaux blocs ne peuvent pas être poussés. Ils n'existent pas en objet et n'apparaissent pas en mode serveur seul. Sans ces blocs, l'écrasement fonctionne quand même, mais pas la double éponge ni l'éponge étirée.

**Textures** : les modèles reprennent les textures vanilla de l'éponge et de l'éponge mouillée, sans nouvelle image. Les versions sèche et mouillée suivent l'eau contenue.
- Éponge étirée : la texture est étirée sur 32 pixels le long de l'axe. La moitié la plus haute montre la moitié haute de la texture, l'autre moitié le bas, et la jonction est continue.
- Double éponge : deux textures entières sont écrasées chacune sur 8 pixels le long de l'axe.

Le sens suit l'axe du piston : les modèles sont tournés selon la propriété `facing`.

**Tests automatiques** (`CheckSpongeGameTests`, dans les tests courants) :
- l'éponge stocke ce qu'elle aspire ;
- une éponge poussée garde son eau ;
- écrasement avec place (40 niveaux rendus) et sans place (8 niveaux dans le piston, 12 en attente dans la case) ;
- double éponge, puis séparation ;
- éponge étirée, qui tient 128 niveaux, puis repoussée en un bloc ;
- moitié détruite.

Chaque test vérifie la conservation exacte.

## Stabilisation (19/11/2026, branche `stabilisation`)

### Moins de matériaux humides

Décision de Valentin : seuls les sols meubles et quelques blocs absorbants gardent une humidité. Les autres blocs pleins ne stockent plus rien et ne laissent plus passer l'eau.

- **Gardent l'humidité** : terres (terre, herbe, podzol, terre labourée, nylium…), sable (et sable des âmes), gravier, argile et boue, mousse, laine, éponges, poudre de béton (elle doit pouvoir devenir du béton).
- **Plus aucune humidité** (capacité 0, perméabilité 0) : roches denses et poreuses (pierre, cobblestone, deepslate, basalte, tuf, calcite, grès…), minerais et métaux, béton, briques et terres cuites, verre, bois, feuilles et blocs organiques. Les feuilles restent seulement waterloggables (eau dans le bloc, sans humidité).
- La neige et la glace ne retenaient déjà pas d'humidité (blocs faits d'eau, traités plus tard avec leur eau fixe) : inchangé.

Conséquences : un mur de cobblestone est étanche, comme l'obsidienne. Le test « mur qui suinte » se fait maintenant avec du gravier, et le test du basalte directionnel est retiré (le basalte ne prend plus d'eau). Nouveau test : `cobblestoneHoldsNoWater`.

### Registre d'eau par zone

Chaque zone de 4×4 chunks (64×64 blocs, sur toute la hauteur) a un registre (`WaterReserves`, sauvegardé avec le monde), en unités d'humidité (1 niveau = 16 unités). Il tient deux réserves invisibles :
- **la réserve** (l'eau souterraine) ;
- **les nuages** : l'eau évaporée, en attendant la pluie.

**Conservation exacte**, en unités : eau visible + humidité des blocs + réserves + nuages. Seule la génération du monde crée de l'eau (blocs d'eau et humidité de génération).

Ce qui passe par la réserve :
- **Eau naturelle** (jamais modifiée depuis la génération) : quand l'écoulement lui prend de l'eau ou lui en verse, elle garde son niveau et la réserve paie ou reçoit la différence, tant qu'elle le peut. Quand la réserve ne peut pas payer, l'eau naturelle baisse vraiment et se réveille.
- **Bloc qui s'en va** (miné, remplacé, détruit) : son humidité de génération, ce qu'il tiendrait ici au repos, retourne à la réserve. Seul ce qu'il a absorbé en plus sort en eau dans la case : miner de la terre humide n'inonde pas un tunnel. Les éponges ne sont pas concernées (elles rendent toute leur eau, voir « Éponges »), ni un bloc poussé par un piston.
- **Humidité restée dans un bloc plein qui n'en tient pas** (la pierre d'un monde sauvegardé avant le 19/11/2026, ou un bloc posé sur de la terre mouillée) : elle va à la réserve au lieu de suinter de la pierre.

**Trois fuites fermées** (19/11/2026) :
1. La réserve d'une zone démarrait au volume d'eau de ses chunks chargés : l'eau était comptée deux fois. Elle démarre maintenant à 0.
2. L'humidité de génération d'un bloc miné sortait en eau. Elle retourne maintenant à la réserve.
3. L'eau naturelle gardait son niveau grâce à cette réserve inventée. Elle ne le garde plus que si la réserve a vraiment l'eau.

**Anciennes sauvegardes** : les réserves enregistrées en niveaux sont converties en unités et gardées telles quelles. Elles contiennent l'eau comptée deux fois, mais aussi l'eau vraiment versée dans l'eau naturelle, et on ne peut pas séparer les deux : les remettre à 0 détruirait de la vraie eau. Les éponges générées (monuments) ont aussi une humidité de génération cohérente : sèches pour l'éponge, pleines pour l'éponge mouillée (avant, une éponge sèche sous le niveau de la mer tenait 64 niveaux cachés).

**Commande** `/ocwo water ledger` (opérateurs) : pour la zone où l'on se trouve, l'eau visible, l'humidité des blocs (y compris l'humidité de génération des sections jamais dérangées), la réserve, les nuages et le total en unités, comptés sur les chunks chargés de la zone. Le total ne change que quand de l'eau passe le bord de la zone ou qu'un chunk se charge. La réserve et les nuages sont aussi dans F3 et dans `/ocwo water info`.

**Tests** : `CheckLedgerGameTests.zoneTotalStaysConstant` (un bassin naturel, un mur de terre naturelle dans un biome de plaine, une poche vide, une pierre qui garde de l'humidité ; le joueur mine la terre ; à chaque tick, eau + humidité + réserves + nuages constant ; au début et à la fin, le total de chaque zone touchée, compté comme la commande, constant) et `CheckRestGameTests.disturbanceWakesLocally` (la réserve ne paie que ce qu'elle a, le reste sort vraiment du bassin). Chacun tourne seul dans son lot, parce que le registre est partagé par tout ce qui est dans la zone.

## Questions ouvertes

- La liste des blocs perméables : quels blocs pleins laissent passer l'eau, et à quelle vitesse ?
- Décidé (12/11/2026) : la terre devient de la boue quand elle atteint le stade « mouillé » (le 3e des 4 stades : sec, humide, mouillé, saturé). Reste à fixer le délai éventuel.
- La boue qui sèche redevient-elle de la terre, ou de l'argile dans certaines conditions ?
- Veut-on la teinte mouillée dès la première version ?
