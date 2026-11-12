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
| Éponge | → éponge mouillée | Absorbe l'eau autour d'elle. L'éponge mouillée sèche dans le Nether |
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

**Simulation** (`MoistureSimulation`) : toutes les 10 ticks, seulement sur les blocs « actifs », avec un budget de 2 ms. Un pas d'un bloc :
1. **absorption** : prend 1 niveau d'eau à un bloc d'eau voisin, avec une probabilité liée à la perméabilité de la face ;
2. **transmission** aux voisins poreux, selon l'écart de remplissage et la plus faible des deux perméabilités (par face, ou par axe pour les blocs orientés), avec un biais vers le bas ;
3. **suintement** (règle unique) : un bloc saturé qui reçoit encore (de l'eau ou un voisin saturé au-dessus ou sur le côté) donne 1 niveau d'eau à une case libre en dessous ou à côté, jamais au-dessus, sauf s'il « retient l'eau » ou change d'état à ce stade ;
4. **changement d'état** : terre → boue au stade « mouillé », boue → terre sous « humide » (hystérésis), poudre de béton → béton de la même couleur, éponge → éponge mouillée. L'humidité est conservée à travers le changement.

Les blocs deviennent actifs quand l'eau bouge à côté d'eux, quand un bloc poreux est posé ou cassé, et par un échantillonnage autour des joueurs (eau immobile contre un bloc poreux). Quand un bloc perd sa capacité (cassé, remplacé), son humidité est rendue en eau sur place ou à côté. Ce qui ne trouve pas de place reste stocké et sera rendu plus tard : rien n'est jamais perdu.

**Eau fixe** : quand on casse un bloc fait d'eau (boue, glace, neige, éponge mouillée), sa quantité d'eau fixe part avec l'objet et le reste de l'humidité est libéré. Limite connue : l'eau des objets n'est pas encore suivie (une boue générée par le monde n'a pas d'humidité, l'objet « contient » donc de l'eau qui n'existait pas). Ce sera réglé avec la génération du monde pré-humidifiée et le suivi de l'eau dans les objets.

**Pluie** : pas d'absorption (pas encore de nuages : la pluie vanilla créerait de l'eau). La règle de jeu `ocwo:rain_moisture_test` (désactivée par défaut, **non conservative**) permet d'essayer l'effet de la pluie sur les blocs de surface.

**Règles de jeu** : `ocwo:moisture_simulation` (activée par défaut), `ocwo:rain_moisture_test` (désactivée).

**Commande de débogage** : `/ocwo moisture [position]` affiche le matériau, l'humidité (unités et niveaux), le stade, la perméabilité de chaque face et les changements d'état prévus ; `/ocwo moisture set <position> <unités>` (opérateurs) fixe l'humidité pour un essai.

**Performance** (test `wetArea`, lancé avec les tests de performance) : un bassin de 48×48, 2 blocs de profondeur, sur un sol de terre et de cobblestone. Sur 2 000 ticks : notre code d'eau prend 3,1 ms par tick en moyenne, dont 0,18 ms pour l'humidité, et 39 ms au pire tick (cibles : 10 et 50 ms). À la fin, 1 150 blocs de terre sont devenus de la boue.

**Tests automatiques** (`CheckMoistureGameTests`, dans les tests courants) : mur de cobblestone qui suinte, laine qui retient, obsidienne étanche, terre sous un bassin qui devient boue, basalte directionnel. Chaque test vérifie à chaque tick que l'eau liquide plus l'humidité ne change pas. Les tests de pression sont désormais construits en obsidienne : avec de la pierre, une partie de l'eau passait dans les murs (comportement normal, mais qui faussait ces tests de pression).

## Questions ouvertes

- La liste des blocs perméables : quels blocs pleins laissent passer l'eau, et à quelle vitesse ?
- Décidé (12/11/2026) : la terre devient de la boue quand elle atteint le stade « mouillé » (le 3e des 4 stades : sec, humide, mouillé, saturé). Reste à fixer le délai éventuel.
- La boue qui sèche redevient-elle de la terre, ou de l'argile dans certaines conditions ?
- Veut-on la teinte mouillée dès la première version ?
