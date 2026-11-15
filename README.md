# OCWO : Overly Complicated Water Overhaul

Mod Minecraft Java (26.3, Fabric ; NeoForge prévu) qui vise un cycle de l'eau complet : eau finie, pression, humidité des blocs, vapeur, nuages et pluie, pour que les rivières et les cascades se forment d'elles-mêmes.

OCWO est un **fork modifié de [PhysEx-Reborn](https://github.com/Sasai-Kudasai-BM/PhysEx-Reborn)** de Sasai_Kudasai_BM, sous licence **GPLv3** (voir `LICENSE` et `mod/ocwo/NOTICE.md`).

## État

- Fait : portage de PhysEx vers la 26.3 en projet multi-loader, pression de l'eau (charge hydraulique), seau à deux modes de ramassage, eau déplacée remontée à la surface, rendu de l'eau pleine sous un plafond, tests automatiques.
- En cours : humidité des blocs (classement des 1 286 blocs par matériau).
- Prévu : vapeur, nuages, vent, pluie locale, température, neige, génération du monde pré-humidifiée.

## Structure du dépôt

| Dossier | Contenu |
|---|---|
| `*.md` (racine) | Documents de conception, en français (sommaire ci-dessous) |
| `mod/ocwo/` | Le code du mod : modules `common`, `fabric`, `neoforge` (modèle MultiLoader) |
| `tools/` | Outils : liste des blocs extraite du jeu, pré-classement par matériau, page de classement |

## Compiler et tester

Depuis `mod/ocwo` (Java 25) :

```
gradlew.bat :fabric:build              # jar dans fabric/build/libs
gradlew.bat :fabric:runGameTest        # tests courants (~40 s)
gradlew.bat :fabric:runGameTestPerf    # tests de performance (~7 min)
gradlew.bat :fabric:runBlockDump       # extrait la liste des blocs dans tools/
```

## Débogage en jeu

Les informations d'OCWO sont dans l'écran de débogage du jeu (**F3**), sous forme de lignes comme celles de Minecraft. Trois entrées OCWO apparaissent dans la liste **F3+F6**, où l'on choisit pour chacune « toujours », « avec F3 » ou « jamais » :
- **`ocwo_water_at_head`** (affichée avec F3 par défaut) : l'eau à hauteur des yeux (niveau sur 8, chute, couche fluide), la **pression** (profondeur sous la surface libre la plus haute de la masse d'eau, en blocs et en niveaux) et l'état de la masse (équilibrée, transferts en attente avec la hauteur du point bas, ou analyse incomplète).
- **`ocwo_looking_at`** (affichée avec F3 par défaut) : identifiant et orientation du bloc visé, eau dans la case, matériau, humidité (unités, niveaux, capacité, pourcentage, stade), perméabilité de chaque face, « retient l'eau », eau fixe, changement d'état prévu, simulé en ce moment ou au repos. Si le regard traverse de l'eau, son niveau est affiché aussi.
- **`ocwo_simulation`** (à activer dans F3+F6) : coût du code de l'eau par tick (écoulement, pression, humidité), blocs humides actifs, file de pression, MSPT du serveur, quotas actuels avec l'échelle de simulation, accélération maximale et budget en temps. Réservée aux opérateurs en multijoueur (et au propriétaire d'un monde solo).

Fonctionnement :
- Les données sont calculées par le serveur (la pression et l'humidité n'existent que là). Le client ne les demande que tant que F3 affiche au moins une entrée OCWO, et le serveur ne les envoie, toutes les 10 ticks, qu'à ces joueurs. Quand personne ne les regarde, rien n'est calculé ni mesuré.
- Rien n'est envoyé si la règle de jeu `reducedDebugInfo` est activée.
- Les textes sont traduits (français et anglais) ; les noms des entrées dans F3+F6 restent ceux de Minecraft, qui ne les traduit pas.
- `/ocwo debug` rappelle seulement où trouver ces informations.
- `/ocwo moisture [x y z]` donne le détail de l'humidité d'un bloc dans le chat ; `/ocwo moisture set <x y z> <unités>` la règle pour un essai.

## Documents de conception

| Fichier | Contenu |
|---|---|
| [01-vision-et-decisions.md](01-vision-et-decisions.md) | Objectif, cycle de l'eau, décisions prises, base technique |
| [02-ralentissement-eau.md](02-ralentissement-eau.md) | Ralentissement des entités proportionnel au niveau d'eau |
| [03-humidite-et-etats-des-blocs.md](03-humidite-et-etats-des-blocs.md) | Humidité, changements d'état (terre vers boue), eau qui sort des blocs pleins, classement des blocs |
| [04-vapeur-et-nuages.md](04-vapeur-et-nuages.md) | Forme de la vapeur et des nuages : options et recommandation |
| [05-problemes-connus.md](05-problemes-connus.md) | Problèmes relevés à corriger ou à surveiller |
| [06-pression.md](06-pression.md) | Pression de l'eau : charge hydraulique, vases communicants, tests et performance |

## Conventions

- Principe clé : **rien ne se perd, rien ne se crée, tout se transforme**. Seule la génération des chunks crée de l'eau.
- Unité d'eau commune à tout le système : **1 niveau = 1/8 de bloc**. L'humidité, la vapeur et la neige utilisent la même unité, pour que la quantité totale d'eau soit conservée.
- Chaque document se termine par ses **questions ouvertes**. Une décision validée passe dans `01-vision-et-decisions.md`.
- Les noms de classes Minecraft cités sont ceux de la 26.3 (code non obscurci).
