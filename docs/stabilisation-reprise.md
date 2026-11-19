# Stabilisation : état du chantier

Branche `stabilisation` (partie de `eponges`). Un commit par étape, tests verts avant chaque commit (`gradlew.bat :fabric:runGameTest`, plus `:fabric:runGameTestVessels` pour la série des vases communicants). Rien ne va dans `main` sans le feu vert de Valentin en jeu.

## Reprise (19/11/2026)

La session précédente a été coupée sans commit. J'ai retrouvé sur le disque, mélangés dans la copie de travail :
- l'étape 1 complète (matériaux et tests) ;
- une partie de l'étape 2 (registre en unités, nuages, conversion des anciennes sauvegardes, retour de l'humidité de génération quand on mine) ;
- le début de l'étape 3 (règle `ocwo:moisture_evaporation`, accumulateur par zone, appels dans l'absorption et la transmission).

Aucun Gradle ni serveur de test bloqué. Une copie de sauvegarde de tout ce travail est gardée hors du dépôt le temps du chantier. J'ai séparé ces changements pour faire un commit par étape.

## Étapes

| # | Étape | État |
|---|---|---|
| 1 | Moins de matériaux humides | fait, tests verts |
| 2 | Registre d'eau par zone de 4×4 chunks | fait, tests verts |
| 3 | Évaporation à chaque transfert d'humidité | fait, tests verts |
| 4 | Réservoirs abstraits pour les grandes masses d'eau au repos | fait, tests verts |
| 5 | Sources de zone | fait, tests verts |

## Étape 1 : moins de matériaux humides

Valeurs à 0 (capacité, perméabilité, sans stades) pour : roche dense, roche poreuse, béton, brique (et terres cuites), bois, feuillage, organique. Déjà à 0 avant : métal, verre, roche imperméable, neige et glace. Fichiers de la page de classement (`tools/store/materials`) mis à jour avec les mêmes valeurs.

L'ancient debris avait une capacité de 1 par un réglage propre au bloc (fait dans la page le 12/11) : retiré, c'est un minerai.

Tests : `cobblestoneWallSeeps` devient `gravelWallSeeps` ; `basaltIsDirectional` retiré ; nouveau `cobblestoneHoldsNoWater`. 27 tests courants verts, 22 vases verts.

**Vases communicants, cas instable corrigé** : le cas « tunnel de 20 » échouait environ une fois sur deux (déjà le 15/11), selon l'endroit où le test tombait. Le vase de droite pouvait se trouver dans un 3e chunk que le test ne faisait pas tourner : il restait hors de la masse d'eau et tout se figeait. Le test force maintenant ses chunks, comme les scénarios de vidange. Commit à part, avant l'étape 1.

**Page de classement** : sa base n'a pas les nouvelles valeurs des matériaux (la collection `materials` est vide, la page montre donc les anciennes valeurs par défaut) ni le retrait du réglage de l'ancient debris. Les fichiers du dépôt (`tools/store`) et les données du mod ont les bonnes valeurs. Je n'ai pas écrit dans la page sans l'accord de Valentin.

## Étape 2 : registre d'eau par zone

Détail dans `03-humidite-et-etats-des-blocs.md`, « Registre d'eau par zone ». En bref :
- registre par zone de 4×4 chunks, en unités : réserve et nuages ; conversion des anciennes sauvegardes (niveaux → unités) ;
- **fuite 1** : la réserve démarre à 0 (elle démarrait au volume d'eau de la zone, l'eau était comptée deux fois) ;
- **fuite 2** : un bloc qui s'en va rend son humidité de génération à la réserve (le surplus sort en eau dans la case) ; l'humidité restée dans un bloc plein qui n'en tient plus (pierre des anciens mondes) va aussi à la réserve ;
- **fuite 3** : l'eau naturelle ne garde son niveau que si la réserve a vraiment l'eau ; sinon elle baisse vraiment ;
- éponges générées : humidité de génération cohérente (sèche = 0, mouillée = pleine) ;
- commande `/ocwo water ledger` ; réserve et nuages dans F3 et `/ocwo water info`.

Tests : nouveau `CheckLedgerGameTests.zoneTotalStaysConstant` (seul dans son lot) ; `disturbanceWakesLocally` mis à jour (la réserve ne paie que ce qu'elle a, seul dans son lot) ; `mudMinedAndPlacedKeepsWater` compte la réserve.

**Effet en jeu à surveiller** : sans réserve inventée, une tranchée creusée depuis une rivière ou un lac fait vraiment baisser l'eau naturelle, qui se réveille. Dans un vrai monde, ça peut réveiller beaucoup d'eau d'un coup (c'était la raison de la réserve). L'étape 4 (réservoirs abstraits) est là pour ça.

## Étape 3 : évaporation à chaque transfert d'humidité

Détail dans `03-humidite-et-etats-des-blocs.md`, « Évaporation ». Règle `ocwo:moisture_evaporation` (2 %), facteur de biome (0,5 froid, 1 tempéré, jusqu'à 3,5 chaud et sec), accumulateur fractionnaire par zone sauvegardé, unités entières versées dans les nuages.

Pertes d'eau trouvées hors liste et branchées sur les nuages : terre labourée qui boit l'eau, éponge mouillée et seau d'eau dans le Nether. Règle des dimensions (correction de Valentin) : Nether → nuages de la zone Overworld aux coordonnées ×8 ; End → nuages d'une zone Overworld tirée au hasard entre le point d'apparition et un portail de l'End activé (point d'apparition si aucun portail connu ; les portails sont retenus quand on les active).

Tests : nouveau `CheckEvaporationGameTests` (seul dans son lot). Les tests d'humidité comptent l'eau évaporée à l'endroit où elle a quitté le sol (suivi réservé aux tests). Le monde de test est un désert : 7 % par transfert dans les tests.

**Restes connus** : la règle `ocwo:water_hot_fluid_evaporation` (eau contre la lave) existe mais n'est utilisée nulle part. La glace et la neige (eau fixe) ne sont pas encore dans le registre.

## Étape 4 : réservoirs abstraits

Détail et mesures dans `06-pression.md`, « Réservoirs abstraits ». Une grande masse d'eau naturelle au repos (au moins 8 blocs, règle `ocwo:reservoir_min_blocks`) paie avec sa propre surface ce que l'écoulement ou la pression lui prend, quand la réserve ne peut pas : sa surface baisse d'un niveau à la fois, régulièrement, sans rien réveiller. Les transferts de pression passent maintenant aussi par la réserve et les réservoirs.

**Seuil de 8 blocs mesuré : il tient.** Pour un grand lac, 8 ou 64 donnent le même résultat. Sous le seuil, une masse coule normalement et ne coûte presque rien. La vraie limite est ailleurs : la recherche regarde au plus 4 096 blocs de surface (64×64), donc pour un océan la baisse reste locale autour de la sortie.

Tests : nouveau `CheckReservoirGameTests` (seul dans son lot) ; scénarios de performance `reservoirTrench8`, `reservoirTrench64`, `reservoirTrenchOff` (`gradlew.bat :fabric:runGameTestPerf`).

**Fuite corrigée en passant** (trouvée par le test de registre) : une tâche d'écoulement qui faisait plusieurs mouvements depuis le même bloc d'eau naturelle faisait payer le deuxième deux fois, et l'égalisation pouvait créer de l'eau. Elles paient maintenant exactement le changement décidé.

## Étape 5 : sources de zone

Détail dans `03-humidite-et-etats-des-blocs.md`, « Sources de zone ». Un bloc saturé qui suinte donne son niveau au débouché de son amas saturé : la case libre la plus basse sous ou à côté de l'amas, jamais au-dessus du bloc (recherche bornée à 256 blocs, ordre fixe). La zone enregistre ce débouché et le réutilise sans nouvelle recherche tant qu'il est libre et touche le sol humide.

Test : nouveau `CheckZoneSourceGameTests` (seul dans son lot) : l'eau sort au pied du mur, une recherche pour 60 niveaux sortis.

## Choix faits seul

- **Poudre de béton** : gardée humide (capacité 1), alors qu'elle n'est pas dans la liste. Sans humidité, elle ne pourrait plus devenir du béton au contact de l'eau.
- **Neige** : la liste dit qu'elle garde l'humidité, mais elle n'en avait déjà pas (bloc fait d'eau, avec la glace). Je ne l'ai pas changée : ce serait ajouter un matériau humide, pas en retirer.
- **Anciennes réserves** : gardées telles quelles à la conversion (en unités). Elles contiennent l'eau comptée deux fois, mais aussi de la vraie eau versée dans l'eau naturelle, et on ne peut pas séparer les deux. À confirmer : les remettre à 0 dans les mondes existants.
- **Ordre de paiement** : quand l'écoulement prend à de l'eau naturelle, la réserve de la zone paie d'abord (comme avant).
- **Mesure de l'humidité de génération rendue** : ce qu'un bloc tiendrait au repos à sa place (même calcul que l'humidité de génération), plafonné à ce qu'il tient vraiment.
- **Évaporation partout** : l'évaporation s'applique à chaque transfert, sous terre aussi. La question ouverte du document 01 (« seulement pour l'eau qui voit le ciel ? ») n'est pas tranchée : à décider.
- **Transferts concernés** : absorption et transmission. Le suintement (un bloc saturé rend un niveau d'eau) n'évapore rien : c'est déjà de l'eau qui sort.
- **Réservoirs : ordre de paiement** : la réserve de la zone d'abord, puis la masse d'eau elle-même. L'eau versée dans de l'eau naturelle va toujours à la réserve (la surface d'un lac ne remonte pas toute seule).
- **Réservoirs : quels blocs baissent** : les blocs de surface les plus hauts, à égalité les plus proches de la sortie, jamais le bloc qui perd l'eau lui-même, seulement des blocs d'eau simples au repos (pas d'algues ni de blocs waterloggés).
- **Réservoirs : la pression aussi** : sans ça, la pression vidait l'eau naturelle réveillée pour de vrai et le réveil gagnait tout le lac.
- **Sources : « tant qu'il touche la zone »** compris comme « tant que le débouché touche un bloc du sol humide » (saturé ou non). Avec « saturé », le débouché se perdait à chaque niveau sorti (le bloc qui venait de suinter n'est plus saturé) : 17 recherches pour 53 niveaux au lieu d'une.
- **Sources : à quels blocs sert un débouché** : ceux de la même zone de 4×4 chunks, à 8 blocs au plus, pas plus bas que lui. On ne vérifie pas que le bloc fait partie du même amas (ce serait refaire la recherche) : deux amas voisins à moins de 8 blocs peuvent partager un débouché.
- **Sources : seuil de l'amas** : le même que l'ancien suintement (bloc saturé qui reçoit encore, sauf s'il retient l'eau ou change d'état à ce stade).
