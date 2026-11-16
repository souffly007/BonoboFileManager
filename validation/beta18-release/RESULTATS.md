# Validation de préparation Release — bêta 18

Date : 11 octobre 2026. Préparation uniquement ; aucune fonctionnalité ajoutée, aucun lot 3 commencé.

## Version et provenance

- Namespace et applicationId : **fr.bonobo.filemanager**.
- Version préparée : **1.3.0-beta18 / versionCode 28**.
- L’APK Release signée archivée `app/release/bonobo files explorateur v1.30-beta16.apk` contient beta16 / code 26, malgré son nom de fichier. Sa signature V2 a été vérifiée en lecture seule.
- L’APK Release bêta 17 archivée contient beta17 / code 27 ; les métadonnées de compilation antérieures et la configuration du projet indiquaient aussi 27. Le code 28 dépasse donc toutes ces valeurs vérifiées.
- La dernière distribution connue localement est la bêta 16 signée ; aucune consultation d’un service de distribution externe n’a eu lieu. Une version distribuée hors de ces archives n’est pas observable ici.
- minSdk 26, targetSdk 35 ; Debug conserve `.adaptive` et `-debug`. Aucun signingConfig Release ajouté, aucun keystore consulté ou modifié.

## Intégration des lots

PLAN-BETA18.md, README-BETA18-LOT1.md, README-BETA18-LOT2.md et leurs résultats ont été relus. Les 222 fichiers du snapshot final du lot 2 correspondent exactement au projet avant préparation. Après préparation, **seul app/build.gradle.kts change dans ce snapshot**, uniquement versionName et versionCode ; tous les fichiers app/src restent identiques.

Les protections du lot 1 (reçus de restauration, alias sûrs, refus de doublons/conflits, relecture d’intégrité réseau avant suppression) et le gestionnaire du lot 2 (coordination séquentielle, panneau commun, progression, annulation différée, service et journal d’interruption sans reprise) restent dans app/src/main. Les explorateurs local et réseau utilisent TransferPanel ; ManagedTransferService figure dans le manifeste principal. Aucun moteur remplacé, aucun contournement SAF, aucune migration de données ou de signature introduit. La compilation Release valide leur présence dans cette variante, sans garantir leur comportement terrain.

## Contrôles réellement exécutés

```sh
./gradlew testDebugUnitTest testReleaseUnitTest assembleRelease lintDebug lintRelease --rerun-tasks --offline --max-workers=1
```

**Code 0, BUILD SUCCESSFUL, 3 min 19 s ; 104 tâches exécutées.** Les tests ont été réexécutés, pas seulement repris du cache.

| Contrôle | Résultat |
| --- | --- |
| JVM Debug | 88 tests, 0 échec, 0 erreur, 0 ignoré |
| JVM Release | 88 tests, 0 échec, 0 erreur, 0 ignoré |
| Compilation Release | Réussie, APK non signée |
| Lint Debug | 0 erreur, 92 avertissements |
| Lint Release | 0 erreur, 90 avertissements |
| Comparaison Lint avec lot 2 | 0 diagnostic ajouté/retiré dans les deux variantes |
| Package/version APK via aapt | fr.bonobo.filemanager / 1.3.0-beta18 / 28 |
| Alignement APK | zipalign -c -P 16 4 : code 0 |
| Signature APK | apksigner refuse la vérification, comme attendu pour l’APK non signée : Missing META-INF/MANIFEST.MF |
| Android instrumenté | 20 tests NON EXÉCUTÉS ; compilés au lot 2, pas recompilés comme APK instrumentation ici |
| USB physique, service Android, rotation, permissions SAF | NON EXÉCUTÉS |
| SMB/SFTP/WebDAV réel et mise à jour signée | NON EXÉCUTÉS |

Le blocage natif de l’émulateur avec code 139 demeure documenté dans [le lot 2](../beta18-lot2/BLOCAGE-ANDROID.md). Aucune nouvelle tentative, aucune réinitialisation. Les fixtures JVM utilisent uniquement des données temporaires isolées et HTTP loopback. Aucun accès au NAS, installation sur smartphone ou publication.

L’avertissement d’assemblage concernant les bibliothèques natives libandroidx.graphics.path.so et libdatastore_shared_counter.so non dépouillées de leurs symboles n’empêche pas la compilation ; elles sont empaquetées telles quelles. Les avertissements Lint existants ne sont pas supprimés pour cette préparation. checkReleaseBuilds=false reste inchangé ; lintRelease a été explicitement exécuté.

## Livrable et preuves

APK : [bonobo-files-explorateur-1.3.0-beta18-release-unsigned.apk](../../app/release/bonobo-files-explorateur-1.3.0-beta18-release-unsigned.apk), 25 084 534 octets.

SHA-256 : `9db2437162dac046edbbb89ddcfa464f537bbd1a88fc1d741c81596c324a854b`.

Cette APK ne peut pas être installée telle quelle : la signature personnelle sera réalisée par l’utilisateur dans Android Studio. La génération signée reconstruira la même variante depuis les sources préparées.

Preuves : gradle-validation.log, XML JUnit jvm-debug/ et jvm-release/, rapports Lint XML/HTML/TXT, TESTS-JVM.json, APK-BADGING.txt, APK-SIGNATURE.txt, APK-SHA256.txt, output-metadata.json, snapshots source-before/source-after et INTEGRATION.txt. Les archives bêta 16/17 et leurs métadonnées n’ont pas été écrasées.

## Limites inchangées et régressions

Aucune régression détectée par les tests JVM ou les diagnostics Lint comparés. Aucun changement fonctionnel effectué ici. Les limites documentées des lots 1/2 restent : refus des destinations SAF indéterminées ; absence de transaction atomique réseau entre vérification et suppression ; suppression récursive potentiellement partielle ; dépendance WebDAV à un ETag fort et au respect d’If-Match ; annulation pouvant attendre un appel bloqué ; journal de résumé sans reprise automatique ; erreurs par racine de dossier ; profondeur maximale 128. Les copies de corbeille restent conservées, les anciennes restaurations sans reçu ne sont pas toujours identifiables. Le conflit d’accès USB imposé par une application tierce n’est pas promis résolu.

La recette Android/USB/réseau et la compatibilité effective de mise à jour avec la signature personnelle restent à vérifier. Voir [procédure testeur](PROCEDURE-TESTEUR.md) et [signature Android Studio](SIGNATURE-ANDROID-STUDIO.md).

## Fichiers livrés dans cette préparation

- app/build.gradle.kts : deux valeurs de version uniquement.
- app/release/bonobo-files-explorateur-1.3.0-beta18-release-unsigned.apk : nouvelle archive non signée.
- README-BETA18.md : point d’entrée de préparation.
- validation/beta18-release/ : rapport, procédures et preuves automatisées.

Arrêt après préparation et validation automatisée. Aucune APK signée produite et aucun lot suivant commencé.
