# 05. Problèmes connus à corriger

## 1. Éclairage cassé quand on vide de grandes étendues d'eau (PhysEx)

**Symptôme observé par Valentin** : quand on vide une grande étendue d'eau dans PhysEx, l'éclairage devient faux.

**Pourquoi l'eau touche à l'éclairage** : l'eau atténue la lumière du ciel (elle n'est pas transparente comme l'air). Chaque fois qu'un bloc passe de l'eau à l'air, le moteur de lumière doit recalculer la lumière autour. Vider un lac change des milliers de blocs en quelques ticks.

**Pistes trouvées en lisant le code de PhysEx 2.0 (à confirmer en jeu)** :

1. **File de lumière saturée.** PhysEx pose ses blocs par `Level.setBlock(..., flags, 16)` (`FluidUtils.setFluid`). Chaque changement ajoute une mise à jour au moteur de lumière, qui n'en traite qu'un nombre limité par tick. Lors d'un gros vidage, la file prend du retard, et le client affiche des zones sombres ou trop claires en attendant.
2. **Eau stockée dans la couche fluide.** Quand l'eau est dans un bloc creux (porte, barrière, herbe...), PhysEx l'écrit dans sa couche séparée (`FluidLayer.setFluidState`) **sans changer l'état du bloc**. Le moteur de lumière ne voit aucun changement et ne recalcule rien. Si la lumière dépend de cette eau, elle reste fausse.
3. **Ordre des paquets client.** Les changements de blocs partent vers le client tout de suite (`UPDATE_CLIENTS`), mais la lumière recalculée arrive plus tard dans un autre paquet. Si le client reconstruit le rendu de la section entre les deux, il garde l'ancienne lumière.
4. **Mixins de rendu.** PhysEx modifie la copie des sections pour le rendu (`SectionCopyFluidMixin`, `RenderSectionRegionFluidMixin`). Une erreur à cet endroit pourrait aussi produire un affichage faux sans que la lumière serveur soit fausse.

**Comment on le vérifiera sur ta machine** :
- Reproduire : un lac de 30×30×5 environ, qu'on vide d'un coup.
- Regarder si l'éclairage se corrige tout seul après quelques secondes (piste 1 ou 3) ou s'il reste faux (piste 2 ou 4).
- Quitter et recharger le monde : si la lumière est correcte au rechargement, c'est un problème de mise à jour (pistes 1 et 3), pas de stockage.
- Comparer avec la couche fluide désactivée (`extraFluidLayerEnabled = false` dans `boot.jsonc`) pour isoler la piste 2.

**Pistes de correction** (selon la cause) : regrouper les changements par section et déclencher la lumière une fois par lot, notifier explicitement le moteur de lumière pour la couche fluide, et retarder la reconstruction du rendu client jusqu'à l'arrivée de la lumière.

**Résultat des tests automatiques (12/11/2026)** : on vide un lac par le fond, puis on vérifie la lumière du ciel côté serveur dans tout le volume vidé (elle doit valoir 15, puisque le lac est ouvert sur le ciel).
- Lac de 64×64×16 : 0 cellule fausse sur 61 535 après stabilisation. Pendant que l'eau coule encore à côté, quelques cellules (38 sur 33 529) sont à 14 au lieu de 15, et elles sont corrigées au tick suivant : c'est le simple retard du moteur de lumière, qui rattrape tout seul.
- Variante avec des clôtures immergées (eau dans la couche fluide) : 0 cellule fausse sur 1 773.

**Conclusion** : côté serveur, la lumière est juste une fois l'eau stabilisée. Les pistes 1 (file de lumière saturée, sauf comme simple retard passager) et 2 (couche fluide) sont écartées. La suite se joue **côté client** (pistes 3 et 4 : ordre des paquets, mixins de rendu).

**À vérifier en jeu par Valentin** : vider un lac, attendre, puis quitter et recharger le monde. Si la lumière est juste au rechargement, c'est bien un problème de mise à jour côté client. L'écran F3 affiche aussi la lumière client et la lumière serveur du bloc visé (en solo) : si elles diffèrent à un endroit sombre, c'est confirmé.

Les tests restent en place (`PressureLightGameTests` et les tests de performance) pour éviter que le problème revienne côté serveur.

## 2. Eau qui tombe

Chez Flowing Fluids, l'eau qui tombe se disperse en l'air, avec un rendu chaotique. Chez PhysEx, elle forme une colonne unie, proche du vanilla : **c'est ce comportement qu'on garde**. À surveiller : ne pas le casser en modifiant le moteur.

## 3. À surveiller : vapeur produite par la lave naturelle

Un lac de lave souterrain alimenté par de l'eau d'infiltration pourrait produire de la vapeur active en continu dans les grottes. Rien n'est prévu pour l'instant (décision du 12/11/2026). Il faudra observer en jeu si ça pose un problème de performances ou d'équilibre, ou si ça devient un effet émergent intéressant (geysers, grottes embrumées).

## 4. Eau déplacée stockée dans les blocs pleins

**Symptôme observé par Valentin (12/11/2026)** : après avoir posé des blocs de verre au fond d'un lac, il a pu vider de l'eau de ces blocs avec un seau.

**Cause (PhysEx)** : quand on pose un bloc dans l'eau, PhysEx cherche de la place autour (128 blocs au maximum) pour l'eau déplacée. Au fond d'un lac, tout est plein. Pour ne pas détruire l'eau, PhysEx la range dans la couche fluide du bloc posé, quel qu'il soit, verre et pierre compris.

**Correction** : quand il n'y a pas de place autour, l'eau déplacée remonte à la surface de la masse d'eau, dont le niveau monte (`PressureSearch.pushToSurface`).

**Ce qui reste** :
- Le repli de PhysEx (eau gardée dans le bloc) ne sert plus que pour une masse d'eau complètement fermée, sans surface libre. Quand les nuages existeront, ce reste devra aller au nuage de la zone (règle des restes de `01-vision-et-decisions.md`).
- Les blocs posés avant la correction gardent leur eau (on peut la vider au seau).

**À décider** : l'éponge vanilla supprime l'eau qu'elle absorbe, ce qui contredit la conservation. Piste : l'éponge mouillée contient une quantité fixe d'eau, et la sécher au four donne de la vapeur.

## 5. À surveiller : shaders et affichage du niveau 8

Valentin a vu des défauts d'affichage avec des shaders, notamment sur le niveau 8 affiché à pleine hauteur sous un plafond (12/11/2026). Pas prioritaire : à regarder plus tard, ou pas. Piste : les shaders (Iris) ont souvent leur propre rendu de l'eau, qui ne passe pas par nos mixins du `FluidRenderer`.

## 6. Blocs aquatiques qui pourraient créer de l'eau

Les plantes aquatiques (algue, herbier) et les blocs posés « gorgés d'eau » contiennent d'office une case d'eau pleine (8 niveaux). S'ils apparaissent dans une case d'eau partielle, de l'eau est créée. Vérifié dans le code de la 26.3 le 12/11/2026 :

| Cas | Condition dans le jeu | Résultat |
|---|---|---|
| Pousse d'algue | `KelpBlockMixin` (correctif « dupe fix » de PhysEx, bien porté en 26.3) : l'algue ne pousse que dans une case d'eau source pleine | Sûr |
| Pose d'algue ou d'herbier à la main | Le jeu exige une case d'eau pleine (`isFull()`, 8 niveaux) | Sûr |
| Pose d'herbier haut | La case du dessus doit être une eau pleine | Sûr |
| Pose d'un cornichon de mer ou d'un corail à la main | Gorgé d'eau seulement si la case est une eau source pleine ; sinon il est posé sec, et l'eau partielle de la case est déplacée par PhysEx | Sûr |
| Poudre d'os sous l'eau (herbier, coraux) | Ne fait pousser que dans des cases d'eau pleines | Sûr |
| **Poudre d'os sur un herbier** (devient herbier haut) | Le jeu vérifie seulement que le bloc du dessus est de l'eau, quel que soit son niveau | **Crée de l'eau** : jusqu'à 7 niveaux si l'eau du dessus était partielle |
| **Cornichons de mer qui se multiplient** (poudre d'os sur un cornichon posé sur du corail) | Le jeu vérifie seulement que la case voisine est de l'eau, quel que soit son niveau, et y pose un cornichon gorgé d'eau | **Crée de l'eau** : jusqu'à 7 niveaux par cornichon |

**À corriger plus tard** (pas encore codé) : exiger une case d'eau pleine dans ces deux cas, comme le fait déjà le correctif de l'algue.

## 7. À traiter avec les nuages : restes d'humidité de moins d'un niveau

Quand un bloc qui ne peut plus retenir d'humidité (cassé, remplacé) la rend en eau, seuls des niveaux entiers (16 unités) deviennent de l'eau. Le reste, moins d'un niveau, reste stocké dans la case, même devenue de l'air, et y reste pour toujours. Rien n'est perdu, mais cette eau est immobilisée. Quand les nuages existeront, ce reste devra partir au nuage de la zone (règle des restes de `01-vision-et-decisions.md`).

## 8. Couche fluide instable

L'auteur de PhysEx indique lui-même que la couche fluide supplémentaire « peut causer des problèmes de stabilité ». À tester en priorité, puisque notre stockage d'humidité s'en inspire.
