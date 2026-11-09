# Résultats — bêta 18 lot 1

Validation exécutée le 11 octobre 2026, données JVM temporaires isolées et serveur HTTP loopback uniquement. Aucune instrumentation, aucun NAS, aucune installation sur téléphone.

Commande terminée avec code 0, **BUILD SUCCESSFUL**, 2 min 18 s :

```sh
./gradlew testDebugUnitTest testReleaseUnitTest assembleDebug assembleRelease assembleDebugAndroidTest lintDebug lintRelease --offline --max-workers=1
```

| Contrôle | Résultat réel |
| --- | --- |
| JVM Debug | 66 tests, 0 échec, 0 erreur, 0 ignoré |
| JVM Release | 66 tests, 0 échec, 0 erreur, 0 ignoré |
| APK Debug | Compilé : app/build/outputs/apk/debug/app-debug.apk |
| APK Release | Compilé non signé : app/build/outputs/apk/release/app-release-unsigned.apk |
| APK instrumentation Debug | Compilé : app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk |
| Lint Debug | 0 erreur, 92 avertissements |
| Lint Release | 0 erreur, 90 avertissements |
| Android instrumenté | 17 tests NON EXÉCUTÉS ; aucune tentative de démarrage supplémentaire |
| Intégration SMB/SFTP sur serveur | NON EXÉCUTÉE ; couverture des invariants par doubles de test du vérificateur partagé |
| WebDAV HTTP local | 4 tests JVM exécutés, compris dans les 66 de chaque variante |

14 nouveaux tests JVM ; les 52 tests existants restent réussis dans chaque variante. Les nombres d'avertissements Lint restent ceux de la bêta 17 ; aucune garantie supplémentaire de comportement terrain ne découle de cette comparaison.

Un premier passage a échoué à la compilation des nouveaux tests : com.sun.net.httpserver indisponible dans la configuration Kotlin Android. Fixture remplacée par un serveur ServerSocket JVM sans dépendance. Le passage final complet ci-dessus est réussi. La compilation Kotlin Debug/Release avait également été exécutée avec succès pendant le développement. Aucun échec restant dans la validation demandée.

Preuves : [journal Gradle](gradle-validation.log), XML JUnit dans jvm-debug/ et jvm-release/, rapports Lint XML/HTML/TXT, [empreintes des APK](APK-SHA256.txt), snapshots source-before.json/source-after.json. Les APK générés restent dans les sorties de compilation ; les archives bêta 17 ne sont pas remplacées. Version et configuration de signature conservées.

Le [blocage Android](BLOCAGE-ANDROID.md) est séparé. Voir [le bilan et les limites](../../README-BETA18-LOT1.md), notamment les destinations SAF indéterminées refusées, les copies pré-journal non identifiables, le manque de transaction atomique SMB/SFTP, les suppressions récursives potentiellement partielles et les résultats de suppression inconnus après coupure réseau. Les erreurs de vérification ne déclenchent pas de suppression des sources ; les données de corbeille restent conservées.

Arrêt après le lot 1 ; pas d'implémentation du lot 2.
