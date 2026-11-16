# Validation finale — bêta 19.2

17 octobre 2026. **BUILD SUCCESSFUL**, passe finale en 2 min 48 s. Travail arrêté après validation automatisée et préparation de la livraison.

## Résultats

| Contrôle | Résultat |
|---|---|
| JVM Debug | 102 tests, 0 échec, 0 erreur, 0 ignoré |
| JVM Release | 102 tests, 0 échec, 0 erreur, 0 ignoré |
| APK Debug | Compilation réussie |
| APK Release | Compilation réussie, non signée |
| APK tests Android Debug | Compilation réussie ; tests non exécutés |
| Lint Debug | 0 erreur, 92 avertissements |
| Lint Release | 0 erreur, 90 avertissements |

Aucun émulateur lancé, aucune installation sur smartphone. Aucun test USB physique. Les avertissements Lint restent présents ; aucun contrôle n’a été désactivé pour cette livraison. Le nombre d’avertissements reste celui de la validation bêta 19.1.

Commande finale, avec dépendances locales et autorisation d’utilisation du cache Gradle hors sandbox :

```sh
JAVA_HOME=/var/home/Snoopy63/.jdks/jbr-21.0.11 ./gradlew --offline --no-daemon --max-workers=2 testDebugUnitTest testReleaseUnitTest assembleDebug assembleRelease assembleDebugAndroidTest lintDebug lintRelease
```

Une première passe complète avait déjà réussi en 3 min 55 s. La passe finale inclut les derniers tests et diagnostics ajoutés pendant cette première validation. Journal complet : `gradle-final.log`. Rapports JVM : `jvm-debug/`, `jvm-release/`. Rapports Lint XML/TXT/HTML archivés ici. Les tests Android ajoutés sont décrits dans `../../README-BETA19.2.md`, mais leur compilation ne démontre pas leur réussite sur Android.

## APK livrée

Fichier : `app/release/bonobo-files-explorateur-1.3.0-beta19.2-unsigned.apk` depuis la racine du projet.

- Package vérifié dans l’APK par aapt : `fr.bonobo.filemanager`.
- VersionName : **1.3.0-beta19.2**, versionCode : **31**.
- Taille : **25 100 918 octets**.
- SHA-256 : `d29d102897d66e8d540fa901a02c9143275b56c2e4f1a1e957325ada039bc539`.
- Copie livrée identique à `app/build/outputs/apk/release/app-release-unsigned.apk` après la passe finale.
- `apksigner verify --verbose` : code retour 1, `DOES NOT VERIFY`, `Missing META-INF/MANIFEST.MF`, attendu pour cette Release non signée.
- Inspection complémentaire : aucune signature JAR et aucun bloc APK Signing Block v2/v3. Voir `unsigned-structure.json` et `apksigner-unsigned-check.txt`.

La première invocation d’apksigner n’a pas trouvé Java dans PATH ; le contrôle a été réexécuté avec le JDK local explicitement placé dans PATH. Ce défaut d’environnement n’est pas utilisé comme preuve d’absence de signature : les résultats ci-dessus proviennent du contrôle exécuté correctement et de l’inspection structurelle.

Métadonnées : `output-metadata.json`, `apk-package.txt`, `APK-SHA256.txt`. Aucun accès ou changement manuel de clé ; signature Debug habituelle conservée, aucune signature Release effectuée. Les anciens APK ne sont pas remplacés par le nouvel artefact nommé.

## Périmètre vérifié

Sept fichiers de production existants corrigés : `FileRepositoryImpl.kt`, `MainViewModel.kt`, `MainScreen.kt`, `OperationDialog.kt`, `TransferEngineFactory.kt`, `SafFiles.kt`, `TransferCoordinator.kt`. Ajout de `DeletionBatch.kt`. Version mise à jour dans `app/build.gradle.kts`. Ajout de deux classes JVM et d’une classe Compose Android ; extension des tests SAF et de leur fournisseur/activité de permission.

`lot-files-sha256.json` indexe les 15 fichiers de code/configuration du lot. Leurs empreintes ont été contrôlées après la validation finale. `production-scope.json` compare l’ensemble de `app/src/main` à l’inventaire conservé avant la correction bêta 19.1 : 183 fichiers identiques, huit fichiers modifiés et deux ajouts. Parmi ces différences, `DashboardScreen.kt` et `HomeFolderShortcut.kt` sont explicitement les changements préexistants de bêta 19.1, pas ceux de ce lot. Aucun fichier de production supprimé par rapport à cette référence.

Le workspace était déjà largement modifié avant ce travail ; aucune remise à zéro. `git diff --check` sur les fichiers suivis concernés par le lot ne signale pas d’espace fautif. Le contrôle global signale des espaces de fin de ligne préexistants dans `AddRemoteDialog.kt` et `NetworkScreen.kt`, laissés hors périmètre.

## Limites et tests terrain obligatoires

Les tests JVM utilisent des fichiers factices et des moteurs simulés. Les nouveaux tests Android couvrent le vrai moteur et un fournisseur synthétique avec identifiants opaques, mais restent non exécutés. Une vraie clé est obligatoire pour valider les droits OEM, la suppression récursive, les deux orientations du double panneau, USB→USB, l’annulation avec clé toujours branchée, les caches, l’éjection et la persistance, le cas ES Explorer, la reconnexion et l’acquittement.

La cause du déplacement incomplet observé en bêta 19.1 n’est pas établie sur appareil. Cette version corrige le refus artificiel de destination SAF et présente les échecs après copie sans succès trompeur. Elle ne promet pas une réparation du fournisseur Android ni une reprise automatique. Les destinations restent créées sous leur nom final ; des dossiers partiels, des entrées dont la création n’a pu être confirmée ou des fichiers dont le nettoyage est refusé peuvent rester. Les noms longs de corbeille locale et les déplacements locaux entre volumes peuvent encore être refusés ; ces causes sont remontées au bilan.

Rapport détaillé : `../../README-BETA19.2.md`. Procédure terrain : `PROCEDURE-TESTEUR.md`. Aucun NAS, publication, autre application, développement FTP/FTPS ou fiche USB. Aucune utilisation de données personnelles.
