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
- **Évaporation partout** : l'évaporation s'applique à chaque transfert, sous terre aussi. **Décision de Valentin (19/11/2026) : on garde ça, même sous terre.** Notée dans le document 01 à la place de la proposition « seulement pour l'eau qui voit le ciel ».
- **Transferts concernés** : absorption et transmission. Le suintement (un bloc saturé rend un niveau d'eau) n'évapore rien : c'est déjà de l'eau qui sort.
- **Réservoirs : ordre de paiement** : la réserve de la zone d'abord, puis la masse d'eau elle-même. L'eau versée dans de l'eau naturelle va toujours à la réserve (la surface d'un lac ne remonte pas toute seule).
- **Réservoirs : quels blocs baissent** : les blocs de surface les plus hauts, à égalité les plus proches de la sortie, jamais le bloc qui perd l'eau lui-même, seulement des blocs d'eau simples au repos (pas d'algues ni de blocs waterloggés).
- **Réservoirs : la pression aussi** : sans ça, la pression vidait l'eau naturelle réveillée pour de vrai et le réveil gagnait tout le lac.
- **Sources : « tant qu'il touche la zone »** compris comme « tant que le débouché touche un bloc du sol humide » (saturé ou non). Avec « saturé », le débouché se perdait à chaque niveau sorti (le bloc qui venait de suinter n'est plus saturé) : 17 recherches pour 53 niveaux au lieu d'une.
- **Sources : à quels blocs sert un débouché** : ceux de la même zone de 4×4 chunks, à 8 blocs au plus, pas plus bas que lui. On ne vérifie pas que le bloc fait partie du même amas (ce serait refaire la recherche) : deux amas voisins à moins de 8 blocs peuvent partager un débouché.
- **Sources : seuil de l'amas** : le même que l'ancien suintement (bloc saturé qui reçoit encore, sauf s'il retient l'eau ou change d'état à ce stade).

## Ce qui reste

**À faire par Valentin** :
- Tester en jeu, de préférence dans un nouveau monde : tranchée depuis un lac (la surface baisse d'un niveau à la fois, le lac reste au repos), terre humide minée (pas d'inondation), sol saturé qui ressort en source au pied d'une pente, `/ocwo water ledger`.
- Jar : celui du commit `9fc4450` (étapes 1 à 5) est dans le profil Modrinth depuis le 19/11/2026 à 02:40, avec l'accord de Valentin. L'ancien (éponges, 19/11 à 00:32) est gardé dans `profiles\Fabric 26.3\ocwo-anciens-jars`.
- Rien dans `main` avant son feu vert en jeu.

**À décider** :
- Évaporation sous terre : **décidé le 19/11/2026, partout, même sous terre** (comportement actuel, rien à changer dans le code).
- Réserves des mondes existants : gardées (décision confirmée par Valentin le 19/11/2026).
- Page de classement : mettre sa base à jour avec les nouvelles valeurs des matériaux.
- Branche : le travail est poussé sur `claude/project-thread-631nn1` (seule branche que cette session pouvait pousser) ; la branche locale s'appelle `stabilisation`.

**Limites connues** :
- Réservoirs : pour un océan, la baisse reste locale aux 64×64 blocs de surface autour de la sortie.
- Sources : un débouché peut servir à deux amas voisins à moins de 8 blocs.
- End : seuls les portails activés après cette version sont connus (sinon, le point d'apparition).
- Règle `ocwo:water_hot_fluid_evaporation` inutilisée ; glace et neige pas encore dans le registre.
- Test `seagrassOnSandSurvives` : échecs rares déjà connus avant ce chantier (14, 15 et 16/11).

## Éponges v2 (19/11/2026, branche `eponges-v2`)

Nouvelles règles de Valentin (son message de 10:50), avec les choix par défaut de l'assistant du projet. Détail dans `03-humidite-et-etats-des-blocs.md`, « Éponges » ; tous les choix réglables sont dans `SpongeRules`. Travail fait sur la branche `eponges-v2`, partie de `stabilisation`, poussée sur `claude/project-thread-631nn1`.

Fait : éponge = seau (plus d'échange avec le sol, séchage au chaud et au sec en un jour de jeu, règle `ocwo:sponge_drying`) ; partage de l'eau entre éponges voisines ; éponge étirée = une seule éponge de 128 niveaux qui absorbe par l'une ou l'autre moitié ; éponge compressée et éponge étirée qui ne tiennent que sous condition ; règles de piston (une éponge ne pousse jamais un autre bloc, écrasement par les 4 côtés, compression, éponge compressée pressée de nouveau ou poussée de côté, éponge étirée repoussée ou étirée de nouveau, la ligne de Valentin) ; objets qui gardent leur eau (composant `ocwo:sponge_water`) ; four ; saut et atterrissage. 25 tests des éponges, 49 tests courants et 22 vases verts.

Choix faits seul (en plus des choix par défaut de l'assistant du projet) :
- **Écrasement sans assez de place** : si l'eau d'une éponge écrasée (ou des deux éponges compressées) ne trouve pas assez de place en partant des 4 côtés, le piston ne pousse pas du tout (comme quand aucun côté n'est libre). Sinon il aurait fallu perdre de l'eau ou la mettre dans la tête du piston, ce qui est interdit.
- **Lecture de la ligne de poussée** : seulement sur l'axe du piston. Une éponge entraînée sur le côté par du slime n'est pas vérifiée (limite connue).
- **Objets** : ~~une éponge étirée ou compressée cassée en mode créatif lâche quand même ses objets~~ remplacé par la règle de Valentin du 19/11 (11:27) : en créatif, la conservation ne s'applique pas, une éponge cassée ne lâche rien et son eau disparaît (test `creativeBreakDeletesWater`). Les éponges mouillées en objet d'avant cette version (sans composant) ne portent pas d'eau : posées, elles sont sèches ; au four, elles ne remplissent plus de seau (avant, le seau se remplissait à partir de rien).
- **Saut** : compte comme saut une chute d'au moins 1 bloc (descendre une dalle ne presse pas l'éponge) ; atterrissage fort au-delà de 3 blocs.
- **Partage** : en proportion de la contenance (une éponge étirée compte double), les éponges compressées n'y participent pas.
- **Cases libérées** : quand une éponge étirée ou compressée change de forme ou casse, l'eau rejetée peut aller dans la case qu'elle vient de libérer (sauf quand un bloc poussé va l'occuper).
- **Mode serveur seul** (sans le mod chez les joueurs) : pas de composant d'objet, une éponge cassée rend son eau autour d'elle comme avant.

Jar : celui du commit `3d3414a` (étapes 1 à 5 et éponges v2) est dans le profil Modrinth depuis le 19/11/2026 à 12:27, avec l'accord de Valentin. Le précédent (`9fc4450`) est gardé dans `profiles\Fabric 26.3\ocwo-anciens-jars`.

### Retours de Valentin du 19/11 à 20:53 (point A, éponges)

- **Blocs « remplacés »** : la cause la plus probable est un décalage entre le serveur et le client. Le client simule le piston avec l'ancien état, avant de recevoir ce que les éponges ont changé : il poussait par exemple la pierre devant une éponge écrasée, et la gardait là où le serveur n'avait rien bougé. Corrigé : ces changements sont envoyés aux joueurs avant le mouvement du piston. Côté serveur, une seule écriture pouvait écraser un bloc (la séparation d'une éponge compressée dans une case avec une plante) : la plante est maintenant détruite avec ses objets, comme par un piston. En corrigeant ça, un test a trouvé une création d'eau (une séparation relancée pendant elle-même) : corrigée. Étirement seulement si l'élément collant est ancré (sinon le groupe est tiré comme en vanilla). Tests : tous les blocs sont encore là après le mouvement (slime entre deux éponges, miel qui pousse une pierre, slime et éponge tirés, étirement qui laisse le reste en place, séparation dans une plante).
- **Trois éponges bloquées** : les deux plus proches du piston sont compressées. Test `threeSpongesPressedAgainstThePiston`.
- **Niveaux entiers** : partout (séchage, partage, absorption, séparation, compression, écrasement, objets) ; un reste de fraction part aux nuages. Le F3, `/ocwo moisture` et l'infobulle affichent le remplissage. L'eau reste rangée avec l'humidité des blocs : ce n'est pas pour le séchage, c'est le stockage par bloc déjà sauvegardé, déplacé par les pistons et compté par le registre.
- Non vérifiable ici : l'affichage côté client (pas de jeu lancé). À regarder en jeu : un piston qui écrase une éponge devant une pierre, et le montage slime entre deux éponges.

## Retours du 19/11 à 20:53, point B (branche `stabilisation-b`, partie de `eponges-v2`)

### 4. Pics de calcul à la génération

Mesuré par un test sans fenêtre sur du vrai terrain (`GenerationGameTests`, `gradlew :fabric:runGameTestGeneration`), avec et sans OCWO. Détail et tableau dans `06-pression.md`, « Génération de grandes zones ».

- **Cause** : le sable et le gravier laissés au-dessus d'une grotte par la génération tombent dès que le chunk tourne ; ils réveillaient l'eau, et la mer inondait la grotte de proche en proche (20 000 à 34 000 tâches d'écoulement pendant un vol de 1 280 chunks).
- **Corrigé** : un chunk neuf naît au repos, sa fin de génération comprise (ticks de bloc de ses 20 premiers ticks et ticks programmés par la génération, même en chaîne ; chutes commencées pendant la génération). La couche « repos » est posée quand le chunk commence à tourner (avant, près d'un joueur, un chunk neuf pouvait compter comme déjà habité et voir son eau en mouvement réveillée). Une masse de pression sortie de la zone simulée est oubliée d'un coup (un tick de 650 ms au déchargement).
- **Chiffres** (vol de 1 280 chunks en 19,5 s, thread principal) : sans OCWO 3 153 et 3 263 ms ; OCWO avant 6 039 ms, 22 ticks de plus de 50 ms, pire 257 ms, notre code environ 3 000 ms ; OCWO après 3 149 à 3 530 ms, 4 à 6 ticks de plus de 50 ms (3 sans OCWO), notre code 167 à 204 ms, notre pire tick 9 à 22 ms. Déchargement : notre code 656 ms avant, 6 ms après.
- **Choix faits seul** : 20 ticks pour la fin de génération d'un chunk (le sable tombe 2 ticks après sa naissance) ; l'eau déplacée par une chute de la génération monte dans sa colonne au repos (sans réveil), elle n'est ni perdue ni créée.
- **Outils de test** : le serveur de test construit un monde normal et tourne à 20 ticks par seconde seulement avec `-Docwo.terrain=true` (lancement `gameTestGeneration`) ; le cadre de test, posé sous terre, ne réveille pas l'eau d'une nappe voisine dans ce lancement. Mesure de chaque partie de notre code dans `OcwoProfiler` (seulement pendant une mesure).
- **Limite** : pas de joueur dans le test, donc rien sur le client.

### 6. Débogage des réservoirs

- **F3** : nouvelle entrée `ocwo_reservoirs` (à cocher avec F3+F6), réservée aux opérateurs en multijoueur (le propriétaire d'un monde solo y a droit). Elle montre l'eau regardée jusqu'à 32 blocs, comme un paiement la verrait : réservoir (lac ou mer) avec son nombre de blocs de surface, ceux qui peuvent baisser et la hauteur de la surface, ou eau ordinaire (trop petite, sans surface, modifiée). Ensuite la zone (réserve, nuages, nombre de sources), la source de zone la plus proche, et l'amas saturé regardé avec sa taille et son débouché.
- **Dans le monde** : `/ocwo debug reservoirs` active ou coupe l'affichage, pour le joueur qui le tape. Il est réservé aux mêmes joueurs. Le serveur envoie les positions à ce joueur seulement, une fois par seconde, tant que c'est actif ; rien n'est envoyé sinon. Particules de poussière, sans code de rendu : bleu au-dessus de la surface des réservoirs à 32 blocs ou moins, orange au-dessus du sol saturé simulé à 24 blocs ou moins, colonne verte aux sources de zone. Au plus 2 048 blocs de surface et 1 024 blocs de sol par envoi.
- **Choix faits seul** : une commande plutôt qu'une touche de débogage (pas de nouvelle combinaison F3 à retenir, et rien à changer côté clavier). Des particules plutôt que des contours (rien à dessiner, compatible Sodium). Le sol saturé à son humidité de génération n'est pas montré : sous le niveau de la mer, il l'est partout.
- **Test** : `reservoirLinesAndView` (un lac naturel de 4×4 est vu comme un réservoir de 16 blocs de surface, un bloc de gravier saturé comme un amas d'un bloc avec son débouché, l'affichage contient les 16 blocs et le gravier, le paquet passe intact, réservé aux opérateurs).
- **Non vérifié ici** : l'affichage côté client (pas de jeu lancé). À regarder en jeu : F3+F6 → `ocwo_reservoirs`, puis `/ocwo debug reservoirs` près d'un lac.

### 7. Assombrissement des blocs humides

Détail dans `03-humidite-et-etats-des-blocs.md`, « Rendu visuel des stades d'humidité », « Ce qui est fait ».

- **Ce qui est assombri** : un bloc de sol humide visible, selon le nombre de stades dont il est plus humide qu'à sa génération (3 crans, de ×0,86 à ×0,64). Un bloc à son humidité de génération garde son aspect vanilla.
- **Envoi** : seulement les blocs visibles des sections touchées par la simulation, avec le chunk puis seulement à chaque changement d'écart, aux joueurs qui voient le chunk.
- **Rendu** : couleur multipliée à la construction du maillage, en vanilla (`ModelBlockRenderer`) et avec Sodium 0.9.2 (`BlockRenderer`, sans dépendance à Sodium). Rien par image.
- **Coût** : pour un bassin de 12×12 qui imbibe la terre pendant 30 s, 13 000 à 15 000 changements d'humidité donnent 700 à 1 100 envois. Notre code d'envoi prend moins de 10 ms en tout. La liste d'un chunk humide se fait en 0,34 ms.
- **Choix faits seul** : l'écart à la génération plutôt que le stade brut. Le stade brut assombrirait presque tout le terrain : toute la terre des biomes pluvieux est humide dès la génération, et tout le sol sous la mer est saturé. Il obligerait aussi à envoyer bien plus de données. Ensuite, un assombrissement gris, sans teinte bleue : Sodium ne donne qu'une luminosité par sommet, et le même rendu des deux côtés me semblait préférable. Enfin, rien de plus clair pour un bloc plus sec qu'à sa génération.
- **Tests** : `wetBlocksSentOnlyWhenVisibleAndChanged` vérifie que seuls les blocs visibles sont envoyés, seulement au changement de stade, avec le bon écart. Il vérifie aussi qu'un bloc découvert est envoyé, qu'un bloc remplacé revient à 0, la liste avec le chunk et l'encodage. `wetSyncCost` sert à la mesure ci-dessus.
- **Non vérifié ici** : l'affichage (pas de jeu lancé). À regarder en jeu, avec et sans Sodium : un bassin posé sur de la terre ou du sable, puis la terre qu'on découvre en creusant à côté.

### Reste du point B

- **5. Boucle lac → humidité → source** : pas encore codé. La règle par défaut couperait aussi les sources de pente de l'étape 5, parce que la pluie ne mouille pas encore le sol. J'ai donc proposé un choix : A, l'eau venue d'un plan d'eau ne sort jamais en source ; ou B, une source sort toujours au moins 1 bloc plus bas que le bloc qui suinte, et jamais dans une eau déjà là. J'attends la réponse.
- **Jar** : celui de `5d055ce` (éponges v2, point A) est construit. Son installation dans le profil a été refusée par le contrôle des permissions, faute d'un « oui » de Valentin dans mon fil. Le profil a toujours `3d3414a`.
