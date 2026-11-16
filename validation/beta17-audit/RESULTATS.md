# Validation réellement exécutée — audit bêta 17

Date : 11 octobre 2026. Ces résultats complètent les preuves historiques ; ils ne les remplacent pas.

| Contrôle | Résultat de cet audit |
| --- | --- |
| Tests JVM Debug | **52 réussis**, 0 échec, 0 erreur, 0 ignoré |
| Tests JVM Release | **52 réussis**, 0 échec, 0 erreur, 0 ignoré |
| Compilation Debug | **Réussie** |
| Compilation AndroidTest | **Réussie** |
| Tests instrumentés | **0 exécuté / 16 présents** ; blocage de l’environnement avant démarrage des tests |
| Vérification QCOW2, sans réparation | Aucune erreur structurelle détectée ; pas de contrôle du système de fichiers invité |
| Contrôle KVM | Installé et déclaré utilisable, version 12 |
| Empreintes APK archivés | Les deux correspondent exactement à `validation/beta17/RESULTATS.md` |
| Code applicatif, tests, version | Inchangés ; voir [INTEGRITE.json](INTEGRITE.json) |
| Lint et compilation Release | Preuves historiques bêta 17 vérifiées, **non réexécutés** dans cet audit |
| Clé physique / ES Explorer PRO | **Non exécutés** |
| NAS / téléphone / autres projets | Aucun accès NAS, aucune installation ou instrumentation sur téléphone, aucune modification d’un autre projet |

## Commandes et preuves

```sh
./gradlew testDebugUnitTest testReleaseUnitTest --offline --max-workers=1 --rerun-tasks
./gradlew assembleDebug assembleDebugAndroidTest --offline --max-workers=1
```

Les tests JVM ont réellement été relancés, sans résultat de cache : première commande **63 tâches exécutées**, `BUILD SUCCESSFUL`. [Journal JVM](jvm-rerun.log), [XML Debug](jvm-debug/), [XML Release](jvm-release/).

La seconde commande a recompilé les artefacts Debug nécessaires et confirmé AndroidTest : **73 tâches, 5 exécutées et 68 à jour**, `BUILD SUCCESSFUL`. [Journal de compilation](android-build.log). Une compilation réussie n’est pas un test Android réussi.

Les contrôles historiques de Lint restent 0 erreur / 92 avertissements Debug et 0 erreur / 90 avertissements Release. La Release archivée reste non signée ; aucune nouvelle livraison signée n’est produite.

## Essais d’émulateur de cet audit

Même copie isolée API 35 x86_64 sous `/tmp/bonobo-beta15-avd`, binaire Android Emulator 37.2.12. Aucun `wipe-data` ni modification du SDK partagé.

1. TCG, CPU sans accélération, un cœur, swiftshader et Vulkan désactivé : **plantage natif, sortie 139**. Contrôle ADB limité à `emulator-5554`, commande interrompue après son délai (code 124), aucune instrumentation lancée. [Extrait filtré](emulator-tcg-sanitized.log).
2. AVD en lecture seule, deux cœurs, mémoire 2048 demandée, swiftshader, Vulkan/GLDirectMem/GLDMA désactivés : **plantage natif, sortie 139** avant disponibilité ADB. [Extrait filtré](emulator-readonly-sanitized.log).

Les quatre plantages historiques en modes graphiques différents ne sont pas comptés comme de nouvelles tentatives. Le warning de mémoire protégée existe aussi dans le log bêta 16 qui avait démarré avec succès. Il n’établit pas la cause. Le test TCG montre que la désactivation de KVM ne suffit pas à récupérer cet environnement. Aucun core dump exploitable retrouvé pour ce QEMU ; la cause native exacte demeure inconnue.

Les extraits nouveaux ne contiennent ni clé publique ADB, ni chemin de compte utilisateur, ni URI ou contenu de fichier personnel. Les logs bruts anciens d’émulateur nécessitent un nettoyage avant partage.

## Les 16 tests Android impossibles à exécuter

Tous sont bloqués par le même environnement, sans échec applicatif constaté et sans résultat ignoré produit par JUnit : ils n’ont pas démarré.

| Test | État |
| --- | --- |
| `ExampleInstrumentedTest.useAppContext` | Non exécuté — émulateur indisponible |
| `AdaptiveExplorerTest.foldersSelectionAndClipboardSurviveRotationAndRecreation` | Non exécuté — émulateur indisponible |
| `AdaptiveExplorerTest.multipleSelectionCopiesFilesAndFoldersViaToolbar` | Non exécuté — émulateur indisponible |
| `AdaptiveExplorerTest.returningToForegroundRefreshesOpenFolder` | Non exécuté — émulateur indisponible |
| `SafIntegrationTest.trashRestorationFilesFoldersLegacyAndConflicts` | Non exécuté — émulateur indisponible |
| `SafIntegrationTest.groupedMovePartialFailureKeepsFailedSources` | Non exécuté — émulateur indisponible |
| `SafIntegrationTest.usbOperationsAndNameConflicts` | Non exécuté — émulateur indisponible |
| `SafIntegrationTest.readOnlyDisconnectedAndFailedWrites` | Non exécuté — émulateur indisponible |
| `SafIntegrationTest.interruptedMoveKeepsSourceAndRemovesPartialDestination` | Non exécuté — émulateur indisponible |
| `SafIntegrationTest.contentStreamsAndUnsupportedSchemes` | Non exécuté — émulateur indisponible |
| `SafIntegrationTest.silentLossIncorrectCreationAndInaccessibleDestinationKeepSource` | Non exécuté — émulateur indisponible |
| `SafIntegrationTest.repositoryReopenRecursiveCopiesAndMovesBothDirections` | Non exécuté — émulateur indisponible |
| `SafIntegrationTest.cancellationDuringTransferPreservesSource` | Non exécuté — émulateur indisponible |
| `SafIntegrationTest.cancellationInBatchKeepsCurrentAndUnstartedSources` | Non exécuté — émulateur indisponible |
| `SafIntegrationTest.revokedOrInvalidatedPermissionIsExplicitAndCanBeRetried` | Non exécuté — émulateur indisponible |
| `SafIntegrationTest.interruptedProviderWriteAndFailedRecursiveMoveKeepSources` | Non exécuté — émulateur indisponible |

Le fournisseur SAF de test et l’activité tierce synthétique auraient permis de tester révocation, URI invalides, retour au premier plan, écritures perdues, restaurations et transferts sans clé ni NAS. L’environnement n’a pas permis leur exécution.

## Lacunes de couverture à combler après récupération de l’environnement

Les 16 tests présents ne couvrent pas encore la déduplication persistante d’une restauration, l’alias local/SAF de la corbeille, le volume d’origine disparu avec parent recréable, les suppressions concurrentes d’une entrée restaurée, la suppression distante réellement précédée d’une relecture ou une reprise après crash. Les résultats JVM ne prouvent pas ces comportements Android/réseau.

Voir [l’audit](../../AUDIT-BETA17.md) et [le plan bêta 18](../../PLAN-BETA18.md) pour les priorités et critères de validation.
