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
| 3 | Évaporation à chaque transfert d'humidité | à faire |
| 4 | Réservoirs abstraits pour les grandes masses d'eau au repos | à faire |
| 5 | Sources de zone | à faire |

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

## Choix faits seul

- **Poudre de béton** : gardée humide (capacité 1), alors qu'elle n'est pas dans la liste. Sans humidité, elle ne pourrait plus devenir du béton au contact de l'eau.
- **Neige** : la liste dit qu'elle garde l'humidité, mais elle n'en avait déjà pas (bloc fait d'eau, avec la glace). Je ne l'ai pas changée : ce serait ajouter un matériau humide, pas en retirer.
- **Anciennes réserves** : gardées telles quelles à la conversion (en unités). Elles contiennent l'eau comptée deux fois, mais aussi de la vraie eau versée dans l'eau naturelle, et on ne peut pas séparer les deux. À confirmer : les remettre à 0 dans les mondes existants.
- **Ordre de paiement** : quand l'écoulement prend à de l'eau naturelle, la réserve de la zone paie d'abord (comme avant).
- **Mesure de l'humidité de génération rendue** : ce qu'un bloc tiendrait au repos à sa place (même calcul que l'humidité de génération), plafonné à ce qu'il tient vraiment.
