# Blocage de l'instrumentation Android

Aucun démarrage d'émulateur ni commande connectedAndroidTest exécuté dans ce lot. L'audit bêta 17 a constaté deux plantages natifs avec sortie 139 avant disponibilité ADB ; voir ../beta17-audit/RESULTATS.md et ses extraits filtrés. La configuration n'a pas été réinitialisée. Aucun APK installé sur un téléphone.

Les tests instrumentés sont compilés mais ne sont pas déclarés réussis : 16 tests existants et un nouveau test d'alias/restauration, soit 17 tests non exécutés. Les fixtures de DocumentsProvider, permissions SAF et cycle de vie Android nécessitent un environnement fonctionnel ; les tests JVM ne les valident pas.
