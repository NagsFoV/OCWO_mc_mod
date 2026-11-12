# 04. Forme de la vapeur et des nuages

## Rappel des décisions

Les nuages sont de **gros conteneurs** gérés par le serveur, pas des voxels. Ils accumulent la vapeur, grossissent selon la quantité stockée, sont poussés par un vent simple, et se vident selon la saturation, l'humidité du biome et le relief.

La question est : sous quelle forme existe la **vapeur** entre le moment où l'eau s'évapore et celui où elle rejoint un nuage ?

## Les options

### 1. Bloc à gravité inversée (comme le sable et le gravier, mais vers le haut)

Le bloc de vapeur monte avec une entité `FallingBlockEntity` inversée, puis se pose en hauteur.

- Avantages : visible, intuitif, réutilise un mécanisme vanilla.
- Inconvénients :
  - chaque bloc qui bouge devient une entité, donc des milliers d'entités pendant une grosse évaporation ;
  - des blocs en l'air gênent la construction, bloquent la lumière et ont des collisions ;
  - ça surcharge la sauvegarde et les mises à jour de chunks ;
  - la vapeur est bloquée par les plafonds, ce qui crée des problèmes dans les grottes et les maisons.
- Verdict : **déconseillé**. C'est le plus coûteux, avec le plus d'effets de bord.

### 2. Une entité par nuage

Chaque nuage est une entité (`Entity`) dont la taille dépend de sa quantité de vapeur.

- Avantages : synchronisation client gratuite, rendu personnalisé facile, on peut interagir avec (commandes, débogage).
- Inconvénients : en vanilla, une entité vit **dans un chunk**. Quand le chunk se décharge, le nuage s'arrête. Or les nuages doivent voyager au-dessus de zones vides de joueurs. Il faudrait contourner ce mécanisme.
- Verdict : possible, mais on se bat contre le moteur.

### 3. Particules

- Avantages : très léger, joli.
- Inconvénients : n'existent que **côté client**. Aucune donnée de jeu, rien n'est sauvegardé, et chaque joueur voit autre chose.
- Verdict : **uniquement pour l'effet visuel** (vapeur qui monte d'un lac, brume).

### 4. Données pures côté serveur (recommandé)

La vapeur et les nuages sont des **données** au niveau du monde, pas des blocs ni des entités.

- **Vapeur** : un nombre dans une grille d'atmosphère grossière (par exemple une case pour 4×4 chunks), sauvegardé avec le monde (`SavedData`). L'évaporation ajoute de la vapeur dans la case, et la vapeur d'une case nourrit le nuage le plus proche ou en crée un nouveau.
- **Nuages** : une liste d'objets serveur (position, quantité de vapeur, taille, forme, vitesse) sauvegardée avec le monde, **indépendante des chunks chargés**. Ils voyagent partout.
- **Client** : le serveur envoie aux joueurs les nuages proches (position, taille, forme) par un paquet réseau. Le client dessine sa propre géométrie de nuage et remplace le rendu des nuages vanilla.
- **Visuel de l'évaporation** : des particules (option 3) au-dessus de l'eau qui s'évapore, côté client seulement.
- Avantages :
  - quelques dizaines ou centaines d'objets seulement, donc très peu coûteux ;
  - la simulation continue dans les zones déchargées ;
  - aucun impact sur la construction ni sur l'éclairage ;
  - totalement sous notre contrôle (fusion, division, forme).
- Inconvénients : il faut écrire le rendu client et la synchronisation réseau nous-mêmes.

## Comparaison

| Critère | Bloc inversé | Entité | Particule | Données serveur |
|---|---|---|---|---|
| Coût serveur | Très élevé | Moyen | Nul | Faible |
| Fonctionne loin des joueurs | Non | Non (sans contournement) | Non | **Oui** |
| Gameplay (pluie contrôlée) | Oui | Oui | Non | **Oui** |
| Gêne la construction et la lumière | Oui | Non | Non | Non |
| Travail de rendu | Aucun | Moyen | Faible | Moyen à élevé |

## Recommandation

Option 4 pour la logique (vapeur et nuages), et option 3 pour le visuel de l'évaporation.

## Variante simplifiée proposée par Valentin (11/11/2026)

**Idée** : au lieu de nuages individuels, on compte une seule valeur d'« eau nuageuse » par biome, avec un maximum. Chaque biome peut pleuvoir indépendamment. Si un biome sec comme le désert dépasse son maximum (par exemple parce que les joueurs y font évaporer beaucoup d'eau), il distribue l'excédent aux biomes voisins.

**Le problème** : dans Minecraft, un biome n'est pas une zone unique. C'est un type (plaines, désert...) attribué à chaque petite case du monde, et le même type se répète à des milliers d'endroits sur une carte infinie. Une valeur par type de biome serait donc **globale** : de l'eau évaporée dans une plaine ferait pleuvoir dans toutes les plaines du monde. On perdrait la pluie locale, l'effet du relief, et la notion de « biome voisin » n'aurait pas de sens.

**Adaptation proposée** : appliquer la même idée **par case de la grille d'atmosphère** (par exemple 4×4 chunks), au lieu de l'appliquer par type de biome :
- chaque case stocke une seule valeur d'eau nuageuse ;
- son maximum dépend du biome dominant de la case : bas pour un désert, élevé pour une jungle ;
- au-delà du maximum, ou s'il y a du vent, l'excédent passe aux cases voisines ;
- la pluie se décide case par case, selon la saturation, le biome et le relief ;
- le client dessine les nuages à partir de ces valeurs : plus la valeur est élevée, plus la couverture nuageuse est dense.

**Ce qu'on y gagne** : plus de nuages individuels à gérer. La fusion devient automatique, le coût est fixe et la sauvegarde est simple. **Ce qu'on y perd** : plus de nuages distincts avec leur propre forme qui voyagent. On peut les réintroduire plus tard comme couche purement visuelle.

### Transitions douces entre les cases

Pour éviter que la grille se voie (carrés nets, changements brusques) :
- **Diffusion** : à chaque pas de simulation, chaque case échange une fraction de la différence avec ses voisines. Les valeurs s'égalisent doucement.
- **Maximum mélangé** : on calcule le maximum d'une case à partir de plusieurs points du terrain (moyenne des biomes présents), pas seulement du biome dominant. Une case à cheval entre désert et forêt a un maximum intermédiaire.
- **Interpolation côté client** : le rendu mélange les valeurs des cases voisines selon la position (interpolation bilinéaire) et les fait évoluer progressivement dans le temps. On ne voit ni les bords des cases ni de saut d'un instant à l'autre.
- **Bord de pluie** : on décide de la pluie colonne par colonne, à partir de la valeur interpolée, avec un peu de bruit pour casser les lignes droites, et avec un seuil de déclenchement plus haut que le seuil d'arrêt, pour éviter qu'elle clignote.

### Vent

Le vent n'attire rien. C'est une **direction et une vitesse** (globales et lentement variables, pour commencer) qui déplacent à chaque pas une partie de l'eau nuageuse de chaque case vers la case située sous le vent. Le débordement d'une case au-dessus de son maximum, lui, se répartit sur toutes ses voisines.

## Vapeur « active » : un vrai fluide (idée de Valentin, 12/11/2026)

**Principe** : deux sortes de vapeur.
- **Passive** (évaporation naturelle des lacs, des sols, des profondeurs) : jamais simulée bloc par bloc. Elle va directement dans la case d'atmosphère, comme prévu plus haut.
- **Active** : l'eau chauffée par une source de chaleur active (feu, feu de camp, lave, four allumé, bloc de magma, tout bloc très chaud) devient un **fluide vapeur** réel, présent dans le monde, qu'on peut contenir et utiliser. Elle pourra servir plus tard de ressource, par exemple avec Create.

**Comportement proposé**
- La vapeur est un fluide fini avec une densité de 1 à 8 par bloc. **1 niveau d'eau (1/8 de bloc) = 1 bloc de vapeur de densité 4** (validé le 12/11/2026). Une unité de densité vaut donc 1/4 de niveau d'eau. Les densités 5 à 8 restent disponibles pour une vapeur comprimée plus tard, par exemple avec Create. Pour la condensation, il faut 4 unités de densité pour refaire un niveau d'eau : le reste doit être conservé quelque part, par exemple envoyé à l'atmosphère.
- Elle monte. À chaque pas, un bloc de vapeur regarde les 9 blocs au-dessus de lui (3×3). S'il y a de la place, il se divise en parties moins denses qui s'y répartissent.
- Quand une partie atteint la densité minimale à l'air libre, elle est absorbée par l'atmosphère et passe dans le nuage de la case.
- Sous un plafond ou dans un contenant fermé, elle s'accumule et reste utilisable.
- Techniquement, c'est le moteur d'écoulement de PhysEx avec la gravité inversée : ce sont des blocs de fluide, pas des entités, donc on évite les défauts de l'option 1 écartée plus haut.

**Pourquoi ça reste peu coûteux** : la vapeur active n'existe que près des sources de chaleur, donc rarement, et surtout là où les joueurs l'ont voulu.

**Points à cadrer**
- **La lave naturelle** : un lac de lave souterrain touché par de l'eau d'infiltration produirait de la vapeur en continu. Décision : **on ne prévoit rien**. C'est un comportement à surveiller (voir `05-problemes-connus.md`), qui pourra se révéler un problème ou un effet émergent sympa.
- **La condensation** (validée le 12/11/2026) : une vapeur piégée longtemps dans un endroit froid redevient de l'eau, à raison de 4 unités de densité pour 1 niveau d'eau.
- **La brûlure** (validée le 12/11/2026) : une vapeur dense brûle les entités qui la traversent.
- **La gêne de la vision et le rendu** (validés le 12/11/2026) : la vapeur gêne la vue. Elle est rendue par des **particules opaques**, plus nombreuses quand la vapeur est plus dense, ce qui colle au style de Minecraft. Les particules sont générées côté client, seulement près du joueur, avec un plafond de nombre pour ne pas peser sur les performances. Quand la tête du joueur est dans la vapeur, un **brouillard blanc** s'ajoute à l'écran, comme sous l'eau ou dans la neige poudreuse, avec une distance de vue qui dépend de la densité.
- **La lumière et la construction** : la vapeur laisse passer la lumière et ne gêne pas la construction (un fluide est remplaçable).
- **Create** : la machine à vapeur de Create calcule sa puissance à partir de l'eau et de la chaleur, sans fluide vapeur. L'intégration consisterait à déclarer notre vapeur comme fluide standard (transfert de fluides Fabric, capacité de fluide NeoForge) pour qu'elle passe dans les tuyaux et les réservoirs de Create, puis éventuellement à faire consommer cette vapeur par sa chaudière. À étudier plus tard.

## Conséquence sur la pluie

La pluie vanilla est globale (`Level.isRaining`, `isRainingAt`). Avec des nuages locaux, il faudra :
- faire répondre `isRainingAt(pos)` selon la présence d'un nuage qui se vide au-dessus ;
- adapter le rendu de la pluie côté client (elle ne tombe que sous les nuages), ainsi que le son et l'assombrissement du ciel ;
- vérifier tout ce qui lit la météo : la terre labourée, les chaudrons qui se remplissent, la foudre, les mobs (phantoms, tridents)...

C'est le plus gros chantier de compatibilité, en particulier avec les shaders et les autres mods de météo.

## Questions ouvertes

- (Réglées par le passage aux nuages par case de 4×4 chunks : la taille de la grille, le nombre maximum de nuages et leurs règles de fusion n'ont plus lieu d'être.)
- Les réglages chiffrés : le maximum d'eau nuageuse par biome, la vitesse de diffusion entre cases, la force du vent, les seuils de pluie.
- Plus tard : un objet (« hygromètre ») pour voir le contenu d'un nuage. Sans impact sur l'architecture.
- L'altitude des nuages : fixe, ou liée au relief ?
