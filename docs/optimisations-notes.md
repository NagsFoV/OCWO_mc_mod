# Notes sur les optimisations de la simulation de l'eau

Notes techniques pour le document de présentation des optimisations (15/11/2026). État : branche `simulation-complete`. Les chiffres sont ceux des tests automatiques (GameTests), sur le PC de développement. « Moyenne / pire » désigne le temps du code de l'eau par tick : moyenne sur le scénario, et tick le plus long.

## Principe général actuel

**Simulation complète à chaque tick** (décision de Valentin, 15/11/2026). Chaque tick fait en entier tout le travail d'eau dû à ce tick, comme une explosion de TNT : écoulement, pression de toutes les masses d'eau qui ont du travail, et humidité tous les 10 ticks. Rien n'est reporté au tick suivant. Si c'est lourd, c'est le jeu qui ralentit (TPS), pas l'eau.

La quantité de travail se règle d'une seule façon : **la distance de simulation de l'eau** (`ocwo:water_simulation_distance`, en chunks ; -1 par défaut = les chunks qui tournent, c'est-à-dire la distance de simulation du serveur). Au-delà, l'eau est figée sans rien perdre, comme les chunks non tickés en vanilla.

Les optimisations ne servent donc plus à « étaler » le travail : elles servent à **faire moins de travail pour le même résultat**.

---

## 1. Pression : masses d'eau gardées en mémoire (`WaterBodies`, 15/11/2026)

**Problème.** La pression doit trouver, pour une masse d'eau, la surface libre la plus haute et le point libre le plus bas. Avant, chaque déclencheur relançait une exploration de la masse. Pour un lac ou une mer, c'est des milliers ou des dizaines de milliers de blocs à chaque fois. Il fallait donc une limite (1 024 colonnes), et une masse plus grande ne pouvait jamais être déclarée équilibrée. C'est ce qui figeait les vases à côté d'un lac qui se vide.

**Principe.** Chaque masse d'eau rencontrée est construite une fois, entièrement (sans limite, dans la distance de simulation), puis gardée en mémoire et tenue à jour. On ne réexplore jamais toute une masse.

**Structures de données** (classe `WaterBodies`, une par dimension, dans `CustomFluidTicks`) :
- **Colonne** (`Col`) : une suite verticale de blocs pleins et immobiles d'un même fluide (x, z, y du bas, y du haut). C'est l'unité de base : un vase de 12×12 sur 40 de haut fait 144 colonnes au lieu de 5 760 blocs.
- **Index** : table (x, z) → liste des colonnes de cette verticale (souvent une seule). Il permet de retrouver en temps constant la colonne qui contient un bloc.
- **Liens** : chaque colonne connaît ses colonnes voisines reliées (une face ouverte entre deux blocs pleins à la même hauteur). La masse est un graphe de colonnes.
- **Masse** (`Body`) : l'ensemble de ses colonnes, plus deux **tas** (files de priorité) : les surfaces libres de la plus haute à la plus basse, et les points libres du plus bas au plus haut.
- **Liste de travail** : les masses qui ont quelque chose à faire (surfaces écartées d'au moins 2 niveaux, ou cibles à recalculer).
- **Changements en attente** : les positions des blocs modifiés depuis le dernier passage, dans l'ordre (crochets sur `Level.setBlock` et sur la couche fluide).

**Déroulement d'un tick** (`WaterBodies.tick`) :
1. Appliquer les changements en attente (`flush` → `update`), localement.
2. Pour chaque masse de la liste de travail, une fois par tick : prendre la surface la plus haute et le point le plus bas, déplacer un niveau, recommencer tant que l'écart est d'au moins 2 niveaux et que les ouvertures le permettent (débit : hauteur de la colonne × accélération). Chaque déplacement est suivi de l'application locale des changements qu'il a causés.
3. Les masses équilibrées sortent de la liste de travail.

**Mise à jour incrémentale** (`update`, `updateInPlace`) : quand un bloc change, on ne regarde que sa verticale et ses 4 voisines.
- Cas courant : la colonne grandit ou rétrécit d'un bloc à une extrémité. Elle garde son identité, on recalcule seulement ses liens avec les 4 colonnes voisines.
- Cas général (colonne coupée en deux, créée ou disparue) : les colonnes touchées sont remplacées, les nouvelles sont reliées à leurs voisines.
- **Fusion** : un lien qui apparaît entre deux masses les réunit ; la plus petite rejoint la plus grande (coût proportionnel à la petite).
- **Séparation** : quand un lien disparaît, on vérifie en mémoire, par un parcours du graphe des colonnes sans lire le monde, si la masse est toujours d'un seul tenant ; sinon chaque morceau devient une masse.
- De l'eau inconnue touchée par une colonne est explorée et rattachée tout de suite.

**Cibles par bloc plutôt que par colonne** (`evalCell`, `targetHeight`) : une surface ou un point libre est une case (eau non pleine à côté, au-dessus ou au-dessous d'une colonne, case vide remplaçable à côté ou au-dessus, ou haut d'une colonne plein avec de l'air au-dessus). Quand un bloc change, on réévalue seulement ce bloc et ses 6 voisins comme cibles, et on les ajoute aux tas. Les cibles d'une colonne entière ne sont recalculées que pour une colonne neuve ou après une fusion ou une séparation.

**Entrées périmées vérifiées tard** : les tas peuvent contenir des entrées dépassées (eau qui a bougé). Une entrée n'est vérifiée contre le monde que lorsqu'elle arrive en tête du tas ; si elle ne correspond plus, elle est jetée. Les tas sont compactés quand ils deviennent trop gros.

**Chemins et débit** : pour respecter le débit des ouvertures (un tunnel 1×1 laisse passer 1 niveau par tick, ×4 au plus avec l'accélération), chaque déplacement réserve les colonnes du chemin entre la surface et le point bas. Le chemin suit un **arbre couvrant** de la masse (parcours en largeur dans le graphe des colonnes, en mémoire). L'arbre est gardé tant qu'aucun de ses liens ne disparaît.

**Chiffres** :

| Test | Avant (quotas, recherches limitées) | Après |
|---|---|---|
| `midLake` (lac 32×32×8 qui se vide) | 6,7 / 70 ms | 4,3 / 38 ms |
| `midLakeVessel` (vase à côté de ce lac) | 6,0 / 46 ms, vases équilibrés au tick 1 580 | 5,4 / 39 ms, équilibrés au tick 900 |
| `bigLake` (lac 64×64×16 qui se vide) | 30,7 / 84 ms, dont 5,6 de pression | 28,7 / 126 ms, dont 9,8 de pression |
| `seaActivity` (mer 160×160×12, un joueur pose et casse des blocs sous l'eau) | - | 0,8 / 33 ms |
| `VesselsGameTests` (22 cas) | 22/22 (avec la correction de la file) | 22/22, rien reporté à aucun tick, vases un peu plus rapides |

Étapes de la pression sur `bigLake` pendant la mise au point : première version 29,9 ms par tick (chaque changement relançait une vérification de séparation et la reconstruction de l'arbre sur toute la masse) ; colonnes mises à jour sur place : 12,8 ms ; cibles par bloc : 9,8 ms.

**Limites et compromis** :
- Mémoire : une colonne coûte de l'ordre de 100 octets (objet, liens, entrée d'index). Une mer de 100 000 colonnes dans la distance de simulation, c'est environ 10 à 20 Mo.
- Première rencontre d'une très grande masse : la construction coûte environ 25 µs par colonne, en un seul tick (environ 1,2 s mesurées pour 50 000 colonnes). Une mer naturelle de 100 000 colonnes donnerait un tick de 2 à 3 s la première fois.
- `bigLake` reste au-dessus de la cible de 10 ms en moyenne.

**Classes et méthodes** : `WaterBodies` (`tick`, `process`, `flush`, `update`, `updateInPlace`, `build`, `merge`, `checkSplit`, `evalCell`, `targetHeight`, `buildTree`, `path`, `inspect`), `CustomFluidTicks` (`enqueuePressure`, `onBlockChanged`, `samplePressure`, `tick`), `PressureSearch` (règles communes : `isStillFull`, `height`, `flowMultiplier`, `wakeAround`, `pushToSurface`), crochets dans `WorldFluidsMixin.setBlock` et `FluidUtils.setFluid` (couche fluide).

---

## 2. Distance de simulation de l'eau (15/11/2026)

**Problème.** Sans quota, il faut un autre moyen de régler la quantité de travail.

**Principe.** Une seule règle, `ocwo:water_simulation_distance` (chunks autour des joueurs ; -1 = les chunks qui tournent). Au-delà :
- l'écoulement ne tourne pas : un filtre (`RangeGate`) est ajouté au prédicat de chunks des ticks de fluide ;
- l'eau compte comme une paroi pour la pression, et les colonnes qui sortent de la zone sont retirées toutes les 20 ticks (`sweepRange`) ;
- les blocs d'humidité actifs attendent sans être traités.

Rien n'est perdu : l'eau reprend quand la zone revient à portée. Le résultat « dans la zone ou non » est mis en cache par chunk pour le tick (`inRangeChunk`).

**Classes** : `WaterBodies.inRangeChunk`, `CustomFluidTicks.RangeGate`, `MoistureSimulation.tick`.

---

## 3. Écoulement (PhysEx)

**Avant** : au plus 10 000 tâches d'écoulement par tick (`task_limit`) et 1 % de ce nombre en tâches d'égalisation, le reste attendait.

**Maintenant** : toutes les tâches dues au tick, et toutes les tâches d'égalisation présentes au début du passage (celles qu'elles créent sont pour le tick suivant, sinon le passage ne finirait jamais).

**Chiffres** : PhysEx seul sur `bigLake` : 20 à 28 ms en moyenne et 36 à 55 ms au pire tick avec la limite ; 20,2 ms et 107 ms sans limite. La moyenne ne change pas ; ce sont les pics qui montent, parce que plus rien n'est lissé. Un gros changement soudain coûte un tick long : 280 000 tâches d'écoulement en un tick (1,2 s) quand on pose d'un coup une mer de 160×160 dans un test.

**Limite** : c'est aujourd'hui la plus grosse part du coût sur les grands lacs qui se vident. L'écoulement lui-même n'a pas encore été optimisé (code d'origine de PhysEx).

---

## 4. Humidité (`MoistureSimulation`)

**Principe, en place depuis l'étape 1 (12-13/11/2026)** :
- seuls les blocs **actifs** sont simulés (ensemble ordonné : eau qui bouge à côté, bloc poreux posé ou cassé, échantillonnage autour des joueurs) ; un bloc stable sort de l'ensemble ;
- un pas tous les **10 ticks** seulement ;
- humidité stockée en entiers (16 unités par niveau d'eau), dans une couche par section de chunk créée seulement si nécessaire (`MoistureLayer`).

**Historique du coût** :
- budget de 2 ms par pas (13/11) : `wetArea` 3,1 ms en moyenne, dont 0,18 ms d'humidité ;
- quota fixe de 500 blocs par pas (15/11, abandonné) : avec 2 000 blocs, le pire tick montait à 70 ms ;
- tous les blocs actifs à chaque pas (simulation complète) : `wetArea` 2,3 ms en moyenne, dont 0,26 ms d'humidité, 1 152 blocs de boue ; pire tick 157 ms au moment où le bassin est posé d'un coup.

---

## 5. Optimisations plus anciennes encore en place

- **Pression v3, recherche par colonnes (14/11/2026)** : l'idée de la colonne comme unité vient de là (avant : bloc par bloc). Elle est reprise telle quelle dans `WaterBodies`. Avant v3, 12 cas de vases sur 16 restaient bloqués ; après, 16 sur 16.
- **Débit par colonne** : une colonne transmet par tick autant de niveaux qu'elle a de blocs (× l'accélération). Réservation des colonnes du chemin à chaque déplacement.
- **Accélération par la pression (15/11/2026)** : racine carrée de l'écart de hauteur en blocs, plafond 4 (`ocwo:pressure_flow_max`). Un seul niveau par transfert, et seulement si l'écart est d'au moins 2 : pas de dépassement ni de va-et-vient. Vases environ deux fois plus rapides.
- **Vérification lente autour des joueurs** : toutes les secondes, 16 blocs d'eau au hasard près de chaque joueur servent de déclencheurs, pour les masses immobiles mais déséquilibrées (chargées d'avant, par exemple).
- **Aléatoire tiré de la graine du monde** (`FastMath.RANDOM`) : la simulation ne dépend pas d'une source au hasard différente à chaque lancement.

---

## 6. Essayé puis abandonné

| Essai | Date | Pourquoi abandonné |
|---|---|---|
| Pression v2 : recherches bloc par bloc, limitées à 256 blocs | 12/11 | Ne traversait pas les grands vases ; une recherche arrêtée par la limite passait pour « équilibrée ». |
| Budget de temps de 3 ms par tick pour la pression, 2 ms pour l'humidité | 12-13/11 | La vitesse de l'eau dépendait du PC. |
| Mémoire « masse équilibrée » de 20 ticks qui jetait les déclencheurs | 12/11 | Jetait aussi des déclencheurs utiles : petits vases figés (corrigé en v3). |
| Une seule recherche par masse et par tick, les autres déclencheurs remis au tick suivant | 14/11 | Faisait grossir la file (milliers d'entrées qui tournaient). |
| Quotas fixes d'opérations par tick (`pressure_operations_per_tick`, `moisture_blocks_per_step`, `task_limit`), échelle globale en %, budget facultatif en ms | 14-15/11 | Remplacés par la simulation complète (décision de Valentin : pas de calcul reporté). |
| Résultat « trop grande, rien à déplacer » mis de côté 200 ticks, file à deux étages (suites de masses d'abord, un quart du quota gardé) | 15/11 | Corrigeait les vases figés à côté d'un lac (22/22), mais restait un contournement de la limite de recherche ; remplacé par les masses gardées en mémoire. |
| Recalcul de toutes les cibles d'une colonne à chaque changement | 15/11 | Trop coûteux sur un lac qui se vide (milliers de colonnes touchées par tick) ; remplacé par l'évaluation des 7 cases autour du changement. |
| Vérification de séparation et reconstruction de l'arbre après chaque changement | 15/11 | Coût proportionnel à toute la masse à chaque déplacement ; remplacées par la mise à jour sur place, et une vérification seulement quand un lien disparaît. |

---

## 7. Mesurer

- Tests de vases : `gradlew.bat :fabric:runGameTestVessels`.
- Tests de performance, un scénario à la fois (sinon un lac encore en train de se vider fausse le suivant) : `gradlew.bat :fabric:runGameTestPerf "-Docwo.perf=-Dfabric-api.gametest.filter=ocwo-gametest:performance_game_tests_big_lake"` (`mid_lake`, `mid_lake_vessel`, `big_lake`, `wet_area`, `sea_activity`, et les variantes `_no_pressure`).
- En jeu : lignes F3 `ocwo_simulation` (coût par tick, masses d'eau, travail reporté), `/ocwo pressure queue`.

## 8. Pistes si un cas reste trop lourd (sans remettre de calculs en attente)

- Pression tous les 2 ou 4 ticks, mais en entier à chaque fois (comme l'humidité tous les 10 ticks).
- Mers et grands lacs naturels traités comme des réservoirs au niveau de la mer, sans analyse de pression.
- Distance de simulation de l'eau plus petite par défaut.
- Optimiser l'écoulement PhysEx lui-même, aujourd'hui la plus grosse part du coût sur les grands lacs qui se vident.
