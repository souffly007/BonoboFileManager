# Résultats bêta 17

Validation finale du 11 octobre 2026 : **BUILD SUCCESSFUL**. [Journal final](delivery-final.log).

```sh
./gradlew assembleDebug assembleRelease testDebugUnitTest testReleaseUnitTest assembleDebugAndroidTest lintDebug lintRelease --offline --max-workers=1
```

| Contrôle | Résultat |
| --- | --- |
| Compilation Debug | Réussie |
| Compilation Release non signée | Réussie |
| Compilation AndroidTest | Réussie, 16 scénarios présents |
| JVM Debug | 52 tests réussis, 0 échec, 0 erreur, 0 ignoré |
| JVM Release | 52 tests réussis, 0 échec, 0 erreur, 0 ignoré |
| Lint Debug | 0 erreur, 92 avertissements |
| Lint Release | 0 erreur, 90 avertissements |
| Exécution Android | Non exécutée : quatre plantages de l’émulateur, code 139 |
| USB physique / ES Explorer PRO | Non exécuté |
| NAS réel / smartphone | Aucun accès NAS ; aucune installation ni instrumentation sur smartphone |

Les résultats JVM définitifs sont archivés dans [jvm-debug](jvm-debug/) et [jvm-release](jvm-release/). Les rapports Lint sont disponibles pour [Debug](lint-results-debug.html) et [Release](lint-results-release.html). Les 48 tests JVM précédents et les 12 tests Android précédents sont conservés ; 4 tests JVM et 4 tests Android sont ajoutés.

## Artefacts

- [bonobo-files-explorateur-1.3.0-beta17-debug.apk](../../app/release/bonobo-files-explorateur-1.3.0-beta17-debug.apk) — SHA-256 `a75ed87f0c89f96e7489a978e48be6203889cfd72b1936dec765cb3666dbefb5`.
- [bonobo-files-explorateur-1.3.0-beta17-release-unsigned.apk](../../app/release/bonobo-files-explorateur-1.3.0-beta17-release-unsigned.apk) — SHA-256 `b0a8c5a8d508ba87bf4dea477c2a0efffeda1cb0e20d1b5f7e340e7e5483b0a4`.

Debug conserve le package et la signature de développement existants. Release est non signée et n’est pas installable telle quelle. Aucune nouvelle clé de signature d’application n’a été créée. Les APK compilés sont archivés localement uniquement.

## Incidents intermédiaires

- Wrapper sur cache temporaire absent : accès réseau interdit, utilisation du cache Gradle existant.
- Imports d’icônes manquants : correction puis compilation réussie. [Journal](intermediate/build-missing-icons.log).
- Deux délais de lecture dans les anciens tests FTPS locaux : première suite Debug 50/52 ; relance complète Debug 52/52. [Échec](intermediate/build-with-ftps-timeouts.log), [XML intermédiaires](intermediate/debug-tests/), [relance](debug-tests.log).
- Plantage interne Lint `ConcurrentModificationException` dans `GradleDetector` : [journal](intermediate/lint-concurrency-crash.log). Relance avec un seul worker réussie, sans désactiver de contrôle.
- Émulateur isolé 37.2.12 : plantages dans les modes swiftshader_indirect, swiftshader, off et host. [Journaux](intermediate/). Aucun test Android n’a démarré ; aucun résultat de bêta 16 n’est réutilisé comme preuve d’exécution.

Le [rapport détaillé](../../README-BETA17.md) explique les corrections, la conservation de la source après restauration, la corbeille SAF non prise en charge, les limites des déplacements et la procédure de recette.
