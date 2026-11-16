# 06. Pression de l'eau

**Statut : modèle validé par Valentin le 12/11/2026. Version 3 de l'algorithme le 14/11/2026, quotas déterministes et accélération le 15/11/2026, simulation complète à chaque tick le 15/11/2026, monde au repos et réservoirs de zone le 16/11/2026 (branche `eau-au-repos`), à tester en jeu.** Code : `PressureSearch` (recherches et transfert), déclenchement dans `ClassicFlowTask.pressure`, file de pression et vérification lente dans `CustomFluidTicks`.

## Monde au repos et réservoirs de zone (16/11/2026, branche `eau-au-repos`)

**Pourquoi** : dans un vrai monde, la simulation complète coûtait de 740 à 820 ms par tick. Rien n'y est jamais à l'équilibre pour l'eau finie et la pression (rivières en pente, aquifères, berges qui absorbent), et tout se réveillait en chaîne. Décision de Valentin : le monde est **au repos par défaut**, comme dans Minecraft, et la simulation ne réagit qu'aux perturbations.

**Eau au repos** (`WaterRest`, `WakeLayer`) :
- L'eau que personne n'a dérangée est au repos : l'écoulement ne lui programme aucune tâche, la pression la traite comme une paroi, l'humidité ne l'absorbe pas, les vérifications autour des joueurs l'ignorent.
- Un bloc modifié qui compte pour l'eau (fluide changé, bloc plein ou passage modifié) réveille l'eau de ce bloc et de ses 6 voisins ; l'eau qui bouge modifie ses voisins, donc le réveil s'étend aussi loin que l'eau bouge.
- La dernière étape de la génération (colonnes de bulles, formes des blocs au chargement d'un chunk) ne réveille rien.
- **Rendormissement** : une eau réveillée qui n'a pas bougé pendant 100 ticks, sans écoulement prévu, sans masse de pression en déséquilibre à côté et sans bloc voisin en train de s'imbiber, se remet au repos (balayage déterministe toutes les 20 ticks).
- L'eau qu'un joueur ou l'écoulement a modifiée reste de l'eau « réelle », même rendormie : elle compte pour la pression et peut se vider. Seule l'eau **naturelle** (jamais modifiée depuis la génération) au repos est inerte.
- Sauvegardé par chunk ; un chunk sans information a toute son eau au repos. Un chunk d'un monde existant où les joueurs sont déjà allés garde son eau en mouvement réveillée la première fois que cette version le voit.
- Règle `ocwo:water_rest` (activée), commandes `/ocwo water info <rayon>` et `/ocwo water rest <rayon>`.

**Réservoirs de zone** (`WaterReserves`, option choisie par Valentin) : quand l'écoulement prend de l'eau à de l'eau naturelle (au repos ou non) ou lui en verse, l'eau naturelle garde son niveau et la réserve de sa zone (4×4 chunks, sauvegardée) paie ou reçoit la différence. La réserve démarre au volume d'eau de la zone ; vide, l'eau naturelle baisse vraiment. Conservation exacte : eau visible plus réserves. Une tranchée creusée depuis une rivière se remplit sans vider la rivière. Affichée dans F3 et dans `/ocwo water info`.

**Mesures** (monde régénéré à la graine de « New World test », 21×21 chunks autour du joueur, temps moyen par tick du serveur entier / 1 % des pires ticks) :

| Situation | Simulation complète sans repos | Monde au repos |
|---|---|---|
| Monde neuf, tout activé | 740 à 820 ms / 1,4 à 2,4 s | 3,9 à 4,0 ms / 5 à 6 ms |
| Même chose, humidité coupée | 406 ms | 3,3 ms |
| Bassin de 24×12 posé sur du sable et de la terre | - | 4,1 à 4,2 ms / 6 ms |
| Tranchée et fosse au bord d'une rivière | - (1re version du repos : 222 puis 494 ms, 111 000 blocs réveillés) | 4,4 ms / 6 à 7,5 ms ; 6 puis 40 blocs réveillés, la réserve de la zone paie (129 336 → 129 307 niveaux) |

Monde existant (copie de « New World test » après les sessions de Valentin) : 167 à 210 ms, parce qu'environ 30 000 à 38 000 blocs y étaient déjà en mouvement et restent réveillés. Choix proposé à Valentin : tout mettre au repos au premier chargement, ou `/ocwo water rest <rayon>` zone par zone. Les tests en jeu se font de préférence dans un nouveau monde.

**Tests** : eau générée immobile, perturbation locale, conservation sans repos, rendormissement, réservoir avec conservation exacte (eau visible + réserve).

## Simulation complète à chaque tick (15/11/2026, branche `simulation-complete`)

**Décision de Valentin** : rien n'avance tant que la simulation du tick n'est pas finie, comme une explosion de TNT. Chaque tick fait en entier tout le travail d'eau dû à ce tick ; s'il est lourd, le jeu ralentit. L'eau garde sa vitesse physique (niveaux par tick, accélération ×4) : un grand vase met toujours du temps à s'équilibrer, mais c'est de l'écoulement, pas du calcul reporté. **L'ampleur se règle par la distance de simulation de l'eau**, pas par des quotas. Si un cas reste trop lourd, on simplifie le système plutôt que de remettre des calculs en attente.

**Ce qui a changé** :
- Écoulement (PhysEx) : toutes les tâches dues au tick (plus de `task_limit`), et toutes les tâches d'égalisation en file au début du passage.
- Pression : chaque masse d'eau qui a du travail est traitée à chaque tick, sans quota ni réessai différé, avec une analyse complète (plus de limite de colonnes).
- Humidité : tous les blocs actifs à chaque pas de 10 ticks.
- Règles supprimées : `task_limit`, `pressure_search_limit`, `pressure_operations_per_tick`, `moisture_blocks_per_step`, `water_simulation_scale`, `water_time_budget_ms`.
- Règles ajoutées : `ocwo:water_pressure` (activée ; remplace `pressure_search_limit 0` pour couper la pression) et `ocwo:water_simulation_distance` en chunks (-1 par défaut = les chunks qui tournent, donc la distance de simulation du serveur). Au-delà, l'eau est figée sans rien perdre et compte comme une paroi pour la pression.
- Avertissement dans le journal quand l'eau prend plus de 1 s dans un tick (sans couper le tick).
- `/ocwo pressure queue` décrit désormais les masses d'eau suivies (nombre, colonnes, travail du dernier tick, travail reporté, plus grandes masses).

**Comment la pression reste abordable sans limite** (`WaterBodies`) :
- Chaque masse d'eau rencontrée est **gardée en mémoire d'un tick à l'autre** : ses colonnes de blocs pleins, les liens entre colonnes voisines, et un tas des surfaces libres (de la plus haute à la plus basse) et un tas des points libres (du plus bas au plus haut).
- Quand un bloc change, seul son voisinage est mis à jour : la colonne concernée est re-mesurée (le plus souvent sur place, en gardant son identité), ses liens sont recalculés, et les 7 cases autour sont réévaluées comme cibles. Pas de nouvelle exploration de la masse.
- Fusion : un lien qui apparaît entre deux masses les réunit (la plus petite rejoint la plus grande). Séparation : quand un lien disparaît, on vérifie en mémoire (sans lire le monde) si la masse est toujours d'un seul tenant, et on la coupe sinon.
- Les entrées des tas sont vérifiées contre le monde seulement quand elles arrivent en tête ; les entrées périmées sont alors jetées.
- Les chemins entre la surface haute et le point bas suivent un arbre couvrant de la masse, gardé tant qu'aucun lien de l'arbre ne disparaît ; il sert à respecter le débit des ouvertures.

**Tests** : les 22 tests de vases passent, avec à chaque tick un contrôle que rien n'est reporté (`lastDeferred` = 0) ; 14 tests courants ; contrôle des mixins sans problème. Vases un peu plus rapides : 5×5×40 en 1 400 ticks (1 460 avant), 9×9 en 2 960 (3 170), 12×12 en 5 210 (5 670), vase à côté d'un lac qu'on remplit en 900 (960).

**Mesures** (moyenne / pire tick du code de l'eau, chaque scénario lancé seul) :

| Scénario | Avant (quotas, branche `pression-file`) | Après (simulation complète) |
|---|---|---|
| Lac 32×32×8 qui se vide (`midLake`) | 6,7 / 70 ms | 4,3 / 38 ms |
| Vase à côté de ce lac (`midLakeVessel`) | 6,0 / 46 ms, vases équilibrés au tick 1 580 | 5,4 / 39 ms, vases équilibrés au tick 900 |
| Lac 64×64×16 qui se vide (`bigLake`) | 30,7 / 84 ms (pression 5,6) | 28,7 / 126 ms (pression 9,8) |
| PhysEx seul, lac 64×64×16 | 20 à 28 / 36 à 55 ms | 20,2 / 107 ms |
| Bassin humide 48×48 (`wetArea`) | 6,9 / 39 à 57 ms | 2,3 / 157 ms (premiers ticks) |
| Mer 160×160×12 avec un joueur qui construit (`seaActivity`) | - | 0,8 / 33 ms |

**Cas encore lourds** (à décider avec Valentin) :
- Le grand lac de 64×64×16 qui se vide : 29 ms en moyenne, au-dessus de la cible de 10 ms, et des pires ticks au-dessus de 100 ms. L'essentiel vient de l'écoulement PhysEx sans limite (20 ms seul), la pression ajoute environ 10 ms.
- Les gros changements soudains (poser d'un coup un bassin de 48×48, ou une mer) font un tick très long : jusqu'à 1,2 s pour 280 000 tâches d'écoulement ou 50 000 colonnes de pression à construire. C'est le principe « comme une explosion de TNT », mais une mer naturelle découverte pour la première fois coûtera aussi un tick long (environ 25 µs par colonne : 2 à 3 s pour une mer de 100 000 colonnes dans la distance de simulation).
- Simplifications possibles : pression tous les 2 ou 4 ticks (en entier) ; mers et grands lacs naturels traités comme des réservoirs au niveau de la mer sans analyse de pression ; distance de simulation de l'eau plus petite par défaut.

## Vases figés à côté d'un grand plan d'eau (15/11/2026, branche `pression-file`)

**Signalé par Valentin** : dans son monde, des vases ne s'équilibraient pas et la file de pression ne se vidait pas.

**Cause, reproduite par les tests** : un lac plus grand que la limite de recherche (72×72 dans le test, donc plus de 1 024 colonnes) ne peut jamais être déclaré équilibré. Chaque vérification lente autour du joueur, chaque mouvement d'eau dans le lac et chaque réessai relançait une recherche de plusieurs milliers de blocs, qui prenait tout le quota du tick. Avec un lac qu'on remplit, 10 des 22 tests de vases restaient figés, même ceux qui n'étaient pas à côté du lac.

**Correction** :
- Nouveau résultat « trop grande, rien à déplacer » : le déclencheur est abandonné (pas de réessai) et les colonnes parcourues sont laissées tranquilles pendant 200 ticks.
- Les suites de masses qu'on peut équilibrer passent d'abord, avec un quart du quota gardé pour les nouveaux déclencheurs ; une masse trop grande qui bouge encore (un lac qu'on remplit) passe à son tour avec les nouveaux déclencheurs, pas devant.
- Le souvenir « équilibré » pour la vérification lente passe de 20 à 100 ticks.
- Diagnostic en jeu : `/ocwo pressure queue` (opérateurs) résumait la file (depuis la simulation complète, elle décrit les masses d'eau suivies) (entrées par type, résultats des 200 derniers ticks, quota utilisé, entrées les plus anciennes avec la taille de leur masse).

**Tests** : vase à côté d'un lac de 72×72 avec un joueur ; même chose pendant qu'on remplit le lac ; file qui se vide après l'équilibre. Avant : 10 tests sur 22 figés. Après : 22 sur 22 ; le vase près du lac qu'on remplit s'équilibre en 960 ticks, comme seul.

## Quotas déterministes et accélération (15/11/2026)

**Décision de Valentin (14/11/2026)** : la simulation est prioritaire. Le jeu n'avance pas tant que le travail d'eau du tick n'est pas fait ; si ça rame, on réduit l'ampleur de la simulation (comme la redstone ou les ticks programmés de Minecraft). **Le TPS baisse plutôt que l'eau.**

**Quotas fixes par tick, plus aucune coupure en millisecondes** :
- Pression : `ocwo:pressure_operations_per_tick` (2 048 par défaut). Un élément pris dans la file compte 1, chaque bloc d'eau parcouru par une recherche compte 1, chaque niveau déplacé compte 1. La file est traitée dans l'ordre (premier arrivé, premier servi), et ce qui dépasse passe au tick suivant dans le même ordre.
- Écoulement : `ocwo:task_limit` (existant, 10 000 tâches par tick ; l'égalisation en fait 1 %).
- Humidité : `ocwo:moisture_blocks_per_step` (500 blocs tous les 10 ticks, voir 03 ; branche `humidite`).
- Échelle globale : `ocwo:water_simulation_scale` en pourcentage (100 par défaut) multiplie tous ces quotas.
- Facultatif : `ocwo:water_time_budget_ms` (0 = désactivé par défaut) remet une limite en millisecondes pour les serveurs qui préfèrent ralentir l'eau plutôt que le jeu (pression, égalisation, humidité).
- Si le travail d'eau d'un tick dépasse 1 seconde, un avertissement est écrit dans le journal, avec le nombre d'opérations faites. Le tick n'est jamais interrompu.
- L'aléatoire de la simulation (directions de l'écoulement, vérification lente autour des joueurs) vient d'une source tirée de la graine du monde, et non plus d'une source au hasard à chaque lancement.

**Équité entre masses d'eau** (bug trouvé en passant aux quotas) : une grande masse d'eau qui reçoit de l'eau en continu (un lac qu'on remplit) relançait une recherche à chaque tick et prenait tout le quota, si bien que les autres masses ne bougeaient plus. Désormais :
- chaque masse d'eau a droit à une seule recherche par tick ;
- un déclencheur venu d'une eau qui a bougé est abandonné si une recherche de sa masse a eu lieu après lui (elle en a déjà tenu compte) ;
- seul le déclencheur qui poursuit le travail d'une masse (eau déplacée : tick suivant ; bloquée : plus tard) est gardé.

Les masses passent donc chacune à leur tour.

**Accélération par la pression** (plafond choisi par Valentin : 4) : une ouverture laisse passer, par tick et par bloc d'ouverture, autant de niveaux que la racine carrée de la différence de hauteur en blocs (1 bloc ×1, 4 blocs ×2, 9 ×3, 16 et plus ×4). Le plafond est réglable par la règle de jeu `ocwo:pressure_flow_max` (4 par défaut, 1 = pas d'accélération). Chaque transfert déplace toujours un seul niveau, et seulement si la différence est d'au moins 2 : l'eau ne peut pas dépasser l'équilibre ni faire de va-et-vient. Une recherche peut faire jusqu'à 32 × plafond transferts, et chaque niveau compte dans le quota. Cela concerne la pression seulement, sans effet visible pour l'instant.

**Tests de vases** (19 cas : les 16 de la v3, plus « grande différence avec accélération », « petite différence » et la loi d'accélération ; contrôle anti-oscillation ajouté sur tous les cas sans versement) :

| Cas | v3 (budget 3 ms) | Quotas, sans accélération | Quotas + accélération ×4 |
|---|---|---|---|
| 1×1, h10 | 40 | 40 | 40 |
| 1×1, h40 | 160 | 160 | 80 |
| 3×3, h10 | 290 | 280 | 230 à 440 |
| 3×3, h20 | 630 | 650 | 350 |
| 3×3, h40 | 1 360 | 1 360 | 530 |
| 5×5, h20 | 1 750 | 1 770 | 940 à 980 |
| 5×5, h40 | 3 780 | 3 760 | 1 470 |
| 9×9, h20 | 5 850 | 5 770 | 3 200 à 3 410 |
| 12×12, h20 | 10 130 | 10 280 | 5 720 à 5 760 |
| 5×5, tunnel 1×2 / 2×2 / 3×3 | 950 / 630 / 380 | 960 / 660 / 380 | 500 / 350 / 210 |
| 5×5, tunnel long de 1 / 20 | 1 690 / 1 850 | 1 720 / 1 850 | 930 / 1 000 à 1 040 |
| 3×3 et 9×9 remplis progressivement | 740 / 5 920 | 1 030 / 5 920 | 750 / 5 920 |
| Petite différence (5×5, 6 et 4 blocs) | - | 200 | 200 (inchangé) |

Toutes les réussites gardent l'eau exacte au niveau près. Les vases remplis progressivement sont limités par la vitesse de versement du test.

**Reproductibilité** : deux lancements du même test donnent le même résultat à 10 ou 20 ticks près (3×3 h10 seul : 220 puis 230 ; 9×9 seul : 3 230 puis 3 210). Le reste de l'écart vient de l'outil de test, qui place chaque test à des coordonnées tirées au hasard (bords de chunks différents), et qui fait tourner plusieurs tests en même temps dans le même monde : ils se partagent alors le quota. La graine du monde de test est fixe (0). Dans un monde donné, à la même position, plus rien ne dépend de la vitesse du PC. L'égalité au tick près n'a pas pu être vérifiée, faute de pouvoir fixer la position des tests.

**Performance** (lac 32×32×8 vidé, lancé seul ; cibles 10 ms en moyenne et 50 ms au pire tick) :

| | Moyenne | Dont pression | Pire tick |
|---|---|---|---|
| Avant (v3, budget 3 ms) | 6,2 ms | 3,7 ms | 47 ms |
| Quotas + accélération, 3 lancements | 5,9 à 6,6 ms | 3,2 à 3,7 ms | 49 à 61 ms |
| PhysEx seul, pour comparer | 2,6 à 2,9 ms | - | 12 à 78 ms |

La moyenne est inchangée. Le pire tick est à la limite des 50 ms ; il varie beaucoup d'un lancement à l'autre, même avec PhysEx seul. Lac 64×64×16 : 26,9 ms (dont 5,0 de pression), contre 22 à 28 ms pour PhysEx seul.

Le lot de performance complet lance les lacs l'un après l'autre dans le même monde, dans un ordre qui change : un lac encore en train de se vider fausse la mesure du suivant. Il faut lancer chaque lac seul : `gradlew.bat :fabric:runGameTestPerf "-Docwo.perf=-Dfabric-api.gametest.filter=ocwo-gametest:performance_game_tests_mid_lake"`.

## Version 3 : recherche par colonnes (14/11/2026)

**Problème signalé par Valentin (13/11/2026)** : les vases communicants recommençaient à bloquer, parfois sur de petits vases (débloqués en rajoutant un peu d'eau) et presque toujours sur les grands (plus de 3×3, plusieurs dizaines de hauteur) ; le panneau de débogage disait « en attente » indéfiniment.

**Causes confirmées par les tests** :
1. Les recherches de la v2 parcouraient les blocs un par un, vers le haut ou vers le bas en priorité, avec une limite de 256 blocs. Dans un grand vase, il faut traverser des couches entières (25 à 144 blocs chacune) avant d'atteindre l'autre vase : la limite était atteinte avant.
2. Une recherche arrivée à sa limite sans rien trouver était prise pour « équilibrée » : la masse d'eau était ignorée pendant 20 ticks et ses déclencheurs abandonnés.
3. Même sur de petits vases : un déclencheur arrivé pendant ces 20 ticks était jeté, puis l'eau se stabilisait (plus aucun déclencheur). C'est ce qui bloquait le vase de 1×1.

**Correction (même modèle validé)** :
- La masse d'eau est parcourue par **colonnes** : une suite verticale de blocs pleins compte pour un seul nœud. Un vase de 12×12 sur 40 de haut fait 144 nœuds au lieu de 5 760 blocs, donc la recherche couvre entièrement les grandes masses. Une seule recherche trouve à la fois la surface la plus haute et le point libre le plus bas, puis enchaîne jusqu'à 32 transferts d'un niveau, chacun revérifié.
- La règle de jeu `ocwo:pressure_search_limit` compte désormais des colonnes (1 024 par défaut).
- Une recherche incomplète n'est **jamais** « équilibrée » : elle est reprise plus tard (délai croissant jusqu'à 64 ticks, 12 essais).
- Le souvenir « masse équilibrée » ne sert plus qu'à filtrer la vérification lente autour des joueurs ; un déclencheur venu d'une eau qui a bougé est toujours traité, et il efface ce souvenir.
- Une recherche par masse d'eau et par tick : les autres déclencheurs de la même masse attendent le tick suivant (sans compter d'échec).
- Débit : une colonne transmet autant de niveaux par tick qu'elle a de blocs (tunnel de 1 de haut : 1 niveau par tick et par colonne ; de 2 de haut : 2).
- Surface libre : un bloc plein n'en est une que s'il a de l'air ou un bloc remplaçable au-dessus (plus sous un plafond).

**Tests en série** (`VesselsGameTests`, lancés à part avec `gradlew.bat :fabric:runGameTestVessels`, chaque cas seul dans son lot) : deux vases joints par un tunnel, l'un rempli, l'autre vide ; succès quand les deux surfaces restent à un niveau près pendant 100 ticks et que l'inspection (celle du panneau de débogage) dit « équilibrée » ; échec si rien ne bouge pendant 200 ticks alors que c'est déséquilibré ; conservation vérifiée.

| Cas (largeur, hauteur, tunnel, longueur) | Avant (v2) | Après (v3) |
|---|---|---|
| 1×1, h10, tunnel 1×1, l5 | bloqué au tick 60 | équilibré au tick 40 |
| 1×1, h40 | équilibré (230) | équilibré (160) |
| 3×3, h10 | équilibré (300) | équilibré (290) |
| 3×3, h20 | équilibré (670) | équilibré (630) |
| 3×3, h40 | bloqué (1 120) | équilibré (1 360) |
| 5×5, h20 | bloqué (1 230) | équilibré (1 750) |
| 5×5, h40 | bloqué (1 220) | équilibré (3 780) |
| 9×9, h20 | bloqué (1 970) | équilibré (5 850) |
| 12×12, h20 | bloqué (2 230) | équilibré (10 130) |
| 5×5, h20, tunnel 1×2 / 2×2 / 3×3 | bloqués (690 / 450 / 260) | équilibrés (950 / 630 / 380) |
| 5×5, h20, tunnel long de 1 / 20 | bloqués (1 160 / 1 530) | équilibrés (1 690 / 1 850) |
| 3×3 et 9×9 remplis progressivement | 3×3 équilibré, 9×9 bloqué | équilibrés (740 / 5 920) |

Avant : 12 cas sur 16 bloqués. Après : 16 sur 16 équilibrés. Les grands vases prennent du temps parce que le tunnel de 1×1 ne laisse passer qu'environ 1 niveau par tick (12×12 : environ 1 400 niveaux à faire passer).

**Performance après correction** : lac 32×32×8 vidé, 6,2 ms par tick en moyenne (dont 3,7 ms de pression), 47 ms au pire tick (cibles 10 et 50) ; lac 64×64×16, 25,2 ms (dont 4,5 de pression), contre 22,2 ms pour PhysEx seul.

Non fait : le tunnel sous un lac de 64×64×20 demandé dans la série (les couloirs sous un lac des tests courants couvrent ce cas en plus petit), et une analyse de masse mise en cache sur plusieurs ticks (la recherche par colonnes a suffi).

## État de l'implémentation (v2)

**Ce qui n'allait pas dans la v1** (constaté par Valentin : U bloqué, couloir sous le lac rempli à 2 blocs sur 3, eau qui monte plus vite entre 1 et 2 qu'entre 0 et 1) :
- La recherche de surface « vers le haut en priorité » contenait un arrêt anticipé faux : elle s'arrêtait dès qu'elle devait redescendre (par exemple d'un couloir vers son ouverture avant de remonter dans le lac). Elle ne trouvait alors que des surfaces à la hauteur du point à remplir, sans écart, donc sans transfert.
- Chaque bloc plein ayant de l'air au-dessus pompait directement depuis la surface : une pièce se remplissait couche par couche dans le désordre, la couche du haut plus vite (10 points de pompage) que celle du bas (un seul front à l'ouverture), et le débit ne dépendait pas de la taille de l'ouverture.

**Algorithme v2** (`PressureSearch.step`) :
1. **Surface haute** : recherche « meilleur d'abord » vers le haut, à travers les blocs pleins (non tombants) du même fluide. Elle ne s'arrête jamais à la première surface trouvée : elle garde la plus haute dans la limite `ocwo:pressure_search_limit`.
2. **Point bas** : recherche symétrique vers le bas, qui garde le point libre le plus bas : bloc non plein, ou cellule vide sur le côté ou au-dessus d'un bloc plein (jamais en dessous : la chute reste à l'écoulement classique). **Une pièce se remplit donc couche par couche, du bas vers le haut.**
3. **Transfert** d'un niveau de la surface haute vers le point bas, si l'écart dépasse 1 niveau. Conservation stricte.
4. **Débit limité par l'ouverture** : chaque bloc plein transmet au plus 1 niveau par tick (`CAPACITY_PER_TICK`). Un transfert réserve tous les blocs de son chemin ; un bloc saturé est bloqué pour les recherches suivantes du même tick. Une ouverture de 1 bloc laisse passer environ 1 niveau par tick, une de 2 blocs environ 2.
5. **File de pression persistante** : un point de départ qui a fait bouger de l'eau est rejoué au tick suivant, ainsi que le bloc plein voisin du point bas rempli. Il est abandonné à l'équilibre. S'il est bloqué, il est réessayé avec un délai croissant (2, 4, 8… ticks), puis abandonné après 6 échecs. Budget : 1 point de départ par tranche de 100 de la règle `task_limit` (100 par tick par défaut).
6. Après chaque transfert, tous les blocs d'eau en contact avec les deux extrémités sont réveillés. La vérification lente (16 blocs au hasard par joueur et par seconde) met les masses d'eau stables dans la file.
7. L'eau déplacée par un bloc posé au fond d'une masse d'eau remonte à la surface (`PressureSearch.pushToSurface`), voir `05-problemes-connus.md`.

**Tests automatiques** (GameTest de Fabric, serveur sans affichage, `fabric/src/gametest`) : U à jonction 1×2, couloir 1×10×3 sous un lac avec ouverture à hauteur 1, à hauteur 2, et avec une marche derrière l'ouverture, verre posé au fond d'un lac. Chaque test vérifie l'état final et, à chaque tick, que la quantité d'eau totale ne change pas. Deux lancements séparés, depuis le dossier `mod\ocwo` :

- **Tests courants** (environ 40 secondes) : les 5 scénarios de pression plus la vérification de l'éclairage avec des clôtures immergées.

  ```
  gradlew.bat :fabric:runGameTest
  ```

- **Tests de performance** (environ 7 minutes, lancés à part) : un lac vidé par le fond dans un sous-sol, avec ou sans pression, chaque scénario seul dans son lot.

  ```
  gradlew.bat :fabric:runGameTestPerf
  ```

Le résultat s'affiche à la fin : « All N required tests passed », ou la liste des tests échoués avec la raison. Chaque scénario écrit aussi des lignes `[OCWO-TEST]` dans le journal (ticks, quantités d'eau, temps mesurés). Valentin a accepté l'EULA de Minecraft pour ce serveur de test le 12/11/2026.

Résultats des tests courants (12/11/2026) : les 5 scénarios passent, conservation comprise. Contrôle : avec la pression désactivée, les 5 échouent (couloirs bloqués à 7/8, U non rempli, eau restée dans le verre), ce qui reproduit les bugs vus en jeu.

### Performance (mesurée le 12/11/2026)

Le temps mesuré est celui de notre code d'eau par tick : écoulement PhysEx, égalisation, pression et déplacement par les blocs posés. Les compteurs (`OcwoProfiler`) ne sont actifs que pendant les tests de performance, ils ne coûtent rien en jeu normal.

| Scénario | Code de l'eau, moyenne | dont pression | Pire tick | Tick serveur, moyenne / pire |
|---|---|---|---|---|
| Lac 32×32×8 (8 192 blocs), PhysEx seul | 2,6 ms | - | 21 ms | 2,9 / 55 ms |
| Lac 32×32×8, avec pression | **9,2 ms** | 3,2 ms | **45 ms** | 9,8 / 47 ms |
| Lac 64×64×16 (65 536 blocs), PhysEx seul | 23,3 ms | - | 51 ms | 24,9 / 70 ms |
| Lac 64×64×16, avec pression | 28,1 ms | 3,1 ms | 58 ms | 29,6 / 74 ms |

- Cibles : 10 ms par tick en moyenne et 50 ms au pire tick. **Le lac 32×32×8 les respecte** (le test échoue s'il les dépasse). Le lac 64×64×16 est un cas extrême, mesuré sans échec : il est déjà au-dessus avec PhysEx seul (23 ms), le coût vient surtout de l'écoulement d'origine.
- Optimisations de la pression (12/11/2026) : budget de temps de 3 ms par tick, masses d'eau déjà trouvées équilibrées ignorées pendant 20 ticks, recherche du point bas en premier (sans point bas, pas de recherche de surface), file d'attente découpée par tick (on ne parcourt plus toute la file à chaque tick). Avant : 46,5 ms par tick, dont 28,5 ms de pression, sur le grand lac.
- Les lacs ne se vident jamais complètement : il reste des flaques d'1 niveau sur le fond (comportement de PhysEx, l'eau d'un seul niveau ne s'écoule plus).

## Le problème

Dans PhysEx, comme dans le vanilla, un bloc d'eau ne « pousse » pas les autres. Un tunnel creusé au fond d'un grand réservoir ne se remplit qu'à 7 niveaux sur 8, et il reste des poches d'air. L'égalisation ne fait que niveler les blocs voisins : elle ne fait pas monter l'eau.

Une première idée (« un bloc plein avec de l'eau au-dessus pousse vers le côté ») ne marche que pour un tunnel d'un bloc : dès le deuxième bloc du tunnel, le plafond est solide et il n'y a plus d'eau au-dessus.

## Le modèle : la charge hydraulique

La pression d'un bloc ne dépend pas de la colonne juste au-dessus de lui, mais de la **profondeur sous la surface libre de toute la masse d'eau connectée**. C'est la charge hydraulique des physiciens.

**Règle unifiée** : dans une masse d'eau connectée (même fluide), l'eau va de la **surface libre la plus haute** vers le **point libre le plus bas**, tant que l'écart de hauteur dépasse 1 niveau (1/8 de bloc).

- **Hauteur d'un bloc** = y + niveau / 8.
- **Surface libre** = un bloc d'eau non plein, ou un bloc plein dont le bloc du dessus n'est pas le même fluide.
- **Point libre bas** = un bloc d'eau non plein, ou une cellule vide où l'eau peut entrer (air ou bloc remplaçable), au-dessus ou à côté d'un bloc plein de la masse.

## Le mécanisme

1. **Déclenchement** : quand un bloc non plein (ou une cellule libre) touche un bloc plein du même fluide, dans les tâches d'écoulement existantes de PhysEx. Un déclenchement lent (tâche périodique) rattrape les états stables mais déséquilibrés.
2. **Recherche** : parcours en largeur depuis le bloc plein voisin, à travers les blocs pleins du même fluide, dans les 6 directions, borné par la règle de jeu `ocwo:pressure_search_limit` (256 par défaut, 0 = pression désactivée). On retient la surface libre la plus haute.
3. **Transfert** : 1 niveau par mise à jour, retiré à la surface haute et ajouté au point bas. La quantité totale d'eau ne change jamais.
4. **Équilibre** : rien ne bouge quand l'écart est de 1 niveau ou moins.

## Les vases communicants

C'est la conséquence directe du modèle : deux réservoirs reliés par le bas finissent avec leur surface à la même hauteur.

- Un puits relié par le bas à un réservoir se remplit jusqu'au niveau de la surface du réservoir.
- Si le puits est plus bas que la surface du réservoir, l'eau déborde par le haut : c'est une **fontaine**.
- Usages : château d'eau, bassin rempli par le fond, écluses, tuyaux et aqueducs, **puits artésiens** (cohérent avec les nappes du document 03 : une source jaillit là où la nappe est sous pression).
- Le **siphon** (eau qui remonte par-dessus un obstacle avant de redescendre) n'est pas pris en charge.

## Les grandes masses d'eau

Un océan contient des millions de blocs. La recherche est limitée, et on utilise la meilleure surface trouvée dans la limite. Simplification possible plus tard : considérer qu'une masse trop grande a sa surface au niveau de la mer.

## L'air (facultatif, pas implémenté)

Le mod se concentre sur l'eau, pas sur l'air : la gestion de l'air n'est pas nécessaire et peut rester une simplification, comme ailleurs dans Minecraft.

Piste légère pour plus tard : une **poche d'air fermée et petite** (recherche limitée à 16-32 blocs, comme pour la vapeur) reste sèche, sinon elle se remplit. Le code de la pression prévoit déjà un point d'accroche (`canPressureFill`), qui autorise toujours le remplissage pour l'instant.

## Questions ouvertes

- Limite de recherche par défaut (256 ?) et comportement avec les océans.
- Vitesse : 1 niveau par mise à jour, ou plusieurs niveaux selon l'écart de hauteur ?
