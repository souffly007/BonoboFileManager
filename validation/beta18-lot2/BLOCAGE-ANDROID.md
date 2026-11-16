# Instrumentation Android non exécutée

Aucun démarrage d'émulateur, connectedAndroidTest ou installation sur téléphone dans ce lot. Le blocage natif avec code 139 est documenté dans ../beta17-audit/RESULTATS.md ; aucune nouvelle tentative ni réinitialisation de configuration.

20 tests Android sont compilés mais NON EXÉCUTÉS : les 17 du lot 1 et trois nouveaux cas :

- TransferPanelTest.panelKeepsThemeColorsAndShowsCancellationAsPending : thèmes, pourcentage explicitement limité à la copie, annulation encore active, détails.
- SafIntegrationTest.localTransferReportsActualBytesAndSeparateVerificationPhase : comptage réel sans relecture doublée, fichier de test créé en buffers bornés.
- SafIntegrationTest.cancelledMoveDuringVerificationNeverEntersSourceDeletion : annulation avant suppression et source intacte.

Les 17 tests précédents sont listés dans les résultats des audits/lots antérieurs. La compilation ne valide ni le service dataSync/WakeLock/notification, ni les permissions SAF, ni le cycle de vie Android. Ces essais restent nécessaires sur un émulateur dédié fonctionnel, puis des données synthétiques de laboratoire. Aucun succès Android n'est annoncé.
