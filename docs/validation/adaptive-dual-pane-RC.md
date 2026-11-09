# Candidate : explorateur adaptatif

Date : 9 octobre 2026. Branche : `codex/adaptive-dual-pane-rc`.
Aucune fusion ni promotion en version stable. Modifications laissées dans la branche, sans commit regroupant les travaux préexistants.

## Livrable

APK debug signé : `app/release/bonobo-files-1.3.0-beta14-RC1-adaptive-RC.apk`.
Version : `1.3.0-beta14-RC1-adaptive-RC`.
Identifiant : `fr.bonobo.filemanager.adaptive`, pour une installation séparée de l'application habituelle. Les paramètres et autorisations de cette installation sont indépendants.
SHA-256 : `7742bb22a1563af999244c5aaa651ecbb13c00125c8b00ec848a2dc37f1e2660`.

Le projet comportait de nombreuses modifications locales avant cette mission. Elles sont conservées et intégrées par la compilation ; cette candidate ne représente donc pas un build isolé des seuls changements adaptatifs.

## Changements

- Sous 600 dp de largeur de contenu : un seul panneau et bouton ⇄, disponible également pendant une sélection.
- À partir de 600 dp : deux panneaux côte à côte. La décision utilise la largeur du contenu, indépendamment du type d'appareil.
- Dossiers indépendants ; positions de défilement sauvegardées par panneau ; état conservé dans le ViewModel lors des rotations et recréations d'activité.
- Aucun chargement de dossier déclenché par un changement de largeur ou la bascule ⇄. Initialisation du second panneau à partir de la liste disponible, sauf si le premier est en cours de chargement ou affiche une catégorie.
- Barre supérieure compacte ; paramètres et épinglage dans le menu « Autres actions » ; cibles des boutons Material conservées.
- Noms sur deux lignes en liste et trois en grille ; marges réduites et taille des aperçus limitée selon la largeur. Aperçus de grille réduits à 80 dp sur les écrans de faible hauteur.
- Tri appliqué aux deux listes. Les modes grille/liste et le tri restent communs aux panneaux comme dans l'interface existante.
- Navigation, recherche, création, épinglage, collage local ou réseau, extraction et sauvegarde APK utilisent le panneau actif pour leur destination locale.
- « Copier vers l’autre panneau » dans le menu d'un élément du double explorateur : bascule vers la destination et utilise la copie existante. Une copie réussie rafraîchit les panneaux concernés ; une erreur conserve la copie en attente.
- La destination d'une copie locale est capturée au déclenchement, et son rafraîchissement reste associé à ce panneau même si l'utilisateur bascule pendant l'opération.
- Aucun changement apporté par cette mission aux clients réseau, lecteurs ou thème. Compatibilité minSdk 26 conservée.

## Fichiers modifiés par cette mission

| Fichier | Rôle |
| --- | --- |
| `app/build.gradle.kts` | Suffixes debug pour la candidate isolée |
| `app/src/main/java/fr/bonobo/filemanager/presentation/ui/main/MainScreen.kt` | Disposition adaptative, bascule, menus et destination réseau locale |
| `app/src/main/java/fr/bonobo/filemanager/presentation/ui/main/MainViewModel.kt` | Initialisation du second panneau, tri, recherche et destinations des opérations |
| `app/src/main/java/fr/bonobo/filemanager/presentation/ui/main/FileExplorerPanel.kt` | Clés stables des éléments et action de copie vers l'autre panneau |
| `app/src/main/java/fr/bonobo/filemanager/presentation/ui/main/ExplorerLayout.kt` | Seuil de largeur |
| `app/src/main/java/fr/bonobo/filemanager/presentation/components/FileItemCard.kt` | Lisibilité, aperçu adaptatif et entrée de copie |
| `app/src/test/java/fr/bonobo/filemanager/ExplorerLayoutTest.kt` | Téléphone, paysage étroit, tablette, seuil et fenêtre réduite |
| `app/src/androidTest/java/fr/bonobo/filemanager/AdaptiveExplorerTest.kt` | Test sur appareil de rotation, état et copie réelle |
| `docs/validation/adaptive-dual-pane-RC.md` | Ce récapitulatif |

## Validation réalisée

- `./gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest --offline` : compilation réussie ; **43 tests unitaires, aucun échec ni test ignoré**.
- Test instrumenté `AdaptiveExplorerTest` exécuté directement par `adb shell am instrument` : **1 test réussi** sur smartphone Xiaomi `2412DPC0AG`, écran 1220 × 2712, densité 520 dpi.
- Portrait : un panneau visible ; paysage : deux panneaux visibles ; retour en portrait : seul le panneau actif reste visible ; ⇄ affiche l'autre dossier.
- Grille, tri et sens du tri conservés après rotation et recréation d'activité.
- Dossiers, sélection et presse-papiers conservés après rotation et recréation d'activité ; même instance de ViewModel vérifiée.
- Copie réelle via le menu « Copier vers l’autre panneau » dans deux dossiers privés de la candidate : contenu destination vérifié, source intacte, liste du second panneau rafraîchie, presse-papiers vidé après succès.
- `git diff --check` limité aux fichiers de production modifiés par cette mission : réussi. Les erreurs d'espaces du contrôle global concernent des modifications réseau préexistantes.

Le lanceur `connectedDebugAndroidTest --offline` n'a pas pu s'exécuter : un composant UTP manque dans le cache Gradle. Les APK de test ont été compilés, installés puis le test a été exécuté directement par ADB. Les clics ADB génériques sont interdits par le téléphone ; les actions Compose du test instrumenté fonctionnent.

## Vérifications restant à faire avant stabilisation

- Tablette réelle en portrait/paysage et fenêtre partagée : seules les règles de largeur ont été testées automatiquement ; aucune tablette disponible.
- SMB, SFTP et WebDAV sur des serveurs accessibles, et carte SD réelle : les clients n'ont pas été modifiés, mais ces scénarios n'ont pas été exécutés dans cette mission.
- Transferts volumineux pendant plusieurs rotations : le test couvre la conservation d'une copie en attente et une copie locale réelle de petit fichier, pas un transfert réseau long.
- Vérification visuelle au doigt avec différentes tailles de police, tailles de vignettes et noms très longs, notamment sur tablette.

L'installation debug séparée peut nécessiter l'autorisation d'accès aux fichiers dans les réglages Android pour tester la mémoire partagée et la SD. Le test automatisé utilise les fichiers privés de la candidate.
