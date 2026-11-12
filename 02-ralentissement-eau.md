# 02. Ralentissement proportionnel au niveau d'eau

## Demande

Le ralentissement vanilla actuel devient celui d'un **bloc d'eau plein** (8 niveaux). Chaque niveau ralentit proportionnellement : un bloc à 1/8 donne 1/8 du ralentissement. On commence par une règle linéaire, qu'on pourra affiner plus tard.

## Comment fonctionne le vanilla (1.21.x, mappings Mojang)

1. **Détection de l'eau** : à chaque tick, `Entity.updateInWaterStateAndDoWaterCurrentPushing()` appelle `updateFluidHeightAndDoFluidPushing(FluidTags.WATER, 0.014)`. La fonction parcourt les blocs que touche la hitbox, calcule la hauteur d'eau de chaque bloc (`FluidState.getHeight`), et en déduit :
   - `wasTouchingWater` : un booléen, vrai dès qu'**un peu** d'eau touche la hitbox ;
   - `fluidHeight[WATER]` : la profondeur d'immersion de l'entité ;
   - la poussée du courant (le flux de l'eau).
2. **Déplacement** : quand `isInWater()` est vrai, `LivingEntity.travel()` passe en mode « eau » (`travelInWater` dans les versions récentes) :
   - frottement horizontal `f = 0.8` (`getWaterSlowDown()`), ou `0.9` en sprint ;
   - accélération réduite à `0.02` ;
   - `Attributes.WATER_MOVEMENT_EFFICIENCY` (Depth Strider) rapproche ces valeurs de celles sur terre ;
   - à la fin du tick, la vitesse est multipliée par `(f, 0.8, f)`.
3. **Conséquence** : le vanilla fonctionne en tout ou rien. 1 niveau d'eau (environ 11 % de hauteur) ralentit exactement comme un bloc plein. Avec l'eau finie de PhysEx, les niveaux 1 à 7 sont des états d'eau « qui coule » vanilla, donc ils ralentissent au maximum.

## Règle proposée

Soit `r` le **taux de remplissage**, entre 0 et 1, et `X_terre` / `X_eau` les valeurs vanilla :

```
r = niveau / 8
X = X_terre + (X_eau - X_terre) × r     (interpolation linéaire)
```

Ça s'applique au frottement horizontal, au frottement vertical, à l'accélération et à la poussée du courant.

**Comment calculer `r` pour une entité qui touche plusieurs blocs.** Deux options :

| Option | Calcul | Avantage | Inconvénient |
|---|---|---|---|
| A. Niveau du bloc | Niveau du bloc **le plus rempli** au niveau des pieds | Colle exactement à la règle « 1/8 de bloc = 1/8 du ralentissement » | Saut brusque quand on passe d'un bloc à l'autre |
| B. Immersion | `fluidHeight / hauteur de l'entité`, plafonné à 1 | Naturel : on est plus freiné quand on a de l'eau jusqu'à la taille. Marche aussi pour les petites entités | S'éloigne de la règle par niveau |

**Recommandation** : l'option A pour la première version, puisque c'est ta règle, en gardant le calcul isolé dans une seule fonction pour pouvoir passer à B sans rien casser.

## Ce qu'il faut aussi décider

Le ralentissement n'est pas le seul comportement en tout ou rien du vanilla :

- **Nage et sprint-nage** : à partir de quel niveau peut-on nager ? Proposition : seulement si l'eau arrive au moins à hauteur des yeux, comme en vanilla.
- **Noyade** : elle dépend des yeux (`isEyeInFluid`), donc elle ne change pas.
- **Dégâts de chute** : le vanilla les annule dans n'importe quelle eau. Proposition : les réduire proportionnellement, et les annuler seulement à partir d'une certaine profondeur.
- **Extinction du feu** sur une entité : elle reste en tout ou rien.
- **Bateaux et objets qui flottent** : leur flottaison dépend de la hauteur d'eau, et PhysEx a déjà un `BoatMixin` à vérifier.
- **Poussée du courant** : à pondérer par `r` elle aussi.

## Où intervenir dans le code

- Un mixin sur `LivingEntity` au moment du calcul du frottement et de l'accélération en eau.
- Un mixin sur `Entity.updateFluidHeightAndDoFluidPushing` pour récupérer le niveau du bloc le plus rempli et pondérer la poussée.
- À vérifier sur la version cible : le code du déplacement a été réorganisé plusieurs fois entre la 1.20 et la 26.x.

## Questions ouvertes

- Option A ou B ?
- Décidé (12/11/2026) : on nage comme en vanilla.
- Décidé (12/11/2026) : la même logique s'applique aux mobs. Aucun mob important ne fait moins d'un bloc de haut, donc pas de cas particulier.
- Les objets au sol et les bateaux : même logique, ou comportement à part ?
