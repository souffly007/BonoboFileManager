# Validation et livraison — bêta 19.3

21 octobre 2026. Passe finale : **BUILD SUCCESSFUL in 5m 16s**. Version **1.3.0-beta19.3**, versionCode **32**.

| Contrôle | Résultat final |
|---|---|
| JVM Debug | 123 tests, 0 échec, 0 erreur, 0 ignoré |
| JVM Release | 123 tests, 0 échec, 0 erreur, 0 ignoré |
| APK Debug | Compilation réussie |
| APK Release | Compilation réussie ; non signée |
| APK AndroidTest Debug | Compilation réussie ; tests non exécutés |
| Lint Debug | 0 erreur, 88 avertissements |
| Lint Release | 0 erreur, 86 avertissements |
| USB physique après éjection/reconnexion | Non testé |

Commande finale, avec le JDK local et accès autorisé au cache Gradle existant hors sandbox :

```sh
JAVA_HOME=/var/home/Snoopy63/.jdks/jbr-21.0.11 ./gradlew --offline --no-daemon --max-workers=2 testDebugUnitTest testReleaseUnitTest assembleDebug assembleRelease assembleDebugAndroidTest lintDebug lintRelease
```

Journal final : [gradle-final.log](gradle-final.log). Rapports archivés dans `jvm-debug/`, `jvm-release/` et les fichiers `lint-results-*.xml/txt/html`. Aucun contrôle Lint désactivé ; les avertissements restent présents. Les suites existantes sont complétées par **21 tests JVM** de fiabilité. Les tests du vrai adaptateur Android et de son fournisseur privé restent **compilés uniquement**, sans lancement d'émulateur ni installation sur smartphone.

## APK livrée

[Télécharger la Release non signée](../../app/release/bonobo-files-explorateur-1.3.0-beta19.3-unsigned.apk).

- Package : `fr.bonobo.filemanager` ; versionName `1.3.0-beta19.3`, versionCode `32`, vérifiés par aapt.
- Taille : **25,133,686 octets**.
- SHA-256 : `2dadb6f7b11d94efb2399672e876ade3af72a114f19374fca0f94669f051f401`.
- Copie livrée identique octet pour octet à `app/build/outputs/apk/release/app-release-unsigned.apk`.
- `apksigner verify --verbose` : code retour **1**, `DOES NOT VERIFY`, attendu pour cette Release non signée.
- Inspection ZIP et structure : aucune signature JAR ni bloc APK Signing Block. Aucun appel de signature Release.

Preuves : [checks.json](checks.json), [APK-SHA256.txt](APK-SHA256.txt), [apk-package.txt](apk-package.txt), [apksigner-unsigned-check.txt](apksigner-unsigned-check.txt), [output-metadata.json](output-metadata.json). Les anciens APK et les métadonnées de livraison précédentes ne sont pas remplacés.

## Périmètre et passages intermédiaires

[code-sha256.json](code-sha256.json) indexe les **20 fichiers de code/configuration** du lot, avec contrôle de leurs empreintes après la compilation finale. Le workspace était déjà largement modifié ; aucune remise à zéro ni tentative de réattribuer les changements préexistants. Les empreintes de `FileRepositoryImpl`, `TransferEngineFactory` et `OperationDialog` restent identiques à l'archive bêta 19.2. Les configurations de signature ne sont pas changées ; la compilation Debug utilise le mécanisme Gradle habituel.

Le premier lancement Gradle a rencontré le cache hors sandbox en lecture seule, puis l'accès autorisé a permis la compilation. Une erreur de compilation du nouveau journal (résolution de `entries` dans `Properties.apply`) a été corrigée. Une passe intermédiaire a ensuite échoué sur une indentation signalée par Lint ; le bloc a été simplifié et les contrôles relancés sans suppression de règle. Deux passes complètes réussies ont précédé la dernière : l'une avant le renforcement du test d'écriture partielle, l'autre avant l'ajout du reçu d'intention couvrant une création sans URI retournée. Ces journaux sont conservés séparément. **Les résultats du tableau proviennent de la passe finale avec ces corrections.**

## Limites et tests physiques restants

Le test de 2 147 483 649 octets est un flux généré avec puits et relecture synthétiques, en mémoire bornée : ni fichier de 2 Gio matérialisé par SAF ni support USB réel. Une petite copie factice matérialisée est également testée. Les fournisseurs simulés ne prouvent pas le comportement OEM ou la persistance du matériel.

Restrictions de sécurité livrées : publication SAF progressive par création et seconde écriture, sans renommage remplaçant ; renommage SAF refusé en production faute de fournisseur qualifié ; source conservée pour toute destination SAF, amovible ou non identifiable avec déplacement indiqué incomplet ; aucun nettoyage automatique de dossier SAF, même apparemment vide. Les créations sans identité retournée restent explicitement non confirmées et à examiner, sans suppression par nom.

Le détail des corrections et des limites est dans [README-BETA19.3.md](../../README-BETA19.3.md). La [procédure physique](PROCEDURE-TESTS-PHYSIQUES.md) impose des fichiers factices, une source de référence conservée, l'éjection sûre, la reconnexion, l'actualisation des références, puis la relecture complète et SHA-256 de chaque destination. Aucun succès de flush/fsync n'est présenté comme cette preuve. Le problème ES Explorer reste séparé, sans correction revendiquée.

Aucun NAS réel, smartphone installé, publication, autre application modifiée, développement FTP/FTPS ni fiche USB. **Travail arrêté après validation automatisée, vérification de l'artefact non signé et archivage de la livraison.**
