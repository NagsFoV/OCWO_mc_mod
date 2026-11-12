# 06. Pression de l'eau

**Statut : modèle validé par Valentin le 12/11/2026. Version 2 de l'algorithme le 12/11/2026, à tester en jeu.** Code : `PressureSearch` (recherches et transfert), déclenchement dans `ClassicFlowTask.pressure`, file de pression et vérification lente dans `CustomFluidTicks`.

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
