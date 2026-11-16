# Reprise bêta 15 — 10 octobre 2026

## État retrouvé et travail conservé

Le dossier comportait déjà de nombreuses modifications, des fichiers ajoutés et des suppressions (licences, APK et site docs). Aucun de ces changements préexistants n'a été annulé. Aucun commit, publication ou transfert externe n'a été réalisé.

Le README et le rapport Lint existants étaient antérieurs aux dernières corrections. Les journaux `/tmp/bonobo-beta15-finish-build.log` et les dates des APK montrent une compilation Debug et AndroidTest achevée, suivie d'une analyse Lint interrompue avant le rapport final. Les XML JVM confirment 48 tests, zéro échec, zéro erreur, zéro ignoré. Ces tests et la compilation de l'APK applicatif n'ont pas été relancés.

Les corrections des huit erreurs Lint étaient déjà dans les sources : destinations cloud mutualisées dans `RemoteDownloadTarget`, garde API 29 et repli pour API 26–28 ; permission `POST_NOTIFICATIONS` contrôlée avant les mises à jour de notification dans les quatre services. `abortOnError=true` était déjà activé.

## Diagnostic et démarrage de l'émulateur

ADB répondait sur l'hôte, sans appareil connecté. Aucun processus QEMU/émulateur n'était actif. Les processus observés dans le bac à sable ne représentent pas ceux de l'hôte ; la vérification a donc été faite hors bac à sable, en lecture seule.

La copie isolée d'AVD créée avant l'interruption a été réutilisée : `/tmp/bonobo-beta15-avd/SyncZen-Recette-API35.avd`. L'AVD original n'a pas été modifié. Aucun wipe, reset, suppression de verrou ou de données, ni redémarrage du serveur ADB.

Une tentative `-gpu swiftshader` a terminé avec code 139 et signal 11. `coredumpctl info 87167` confirme un crash de QEMU headless ; son journal s'arrête après l'initialisation graphique. La pile native disponible ne permet pas d'attribuer la cause exacte. KVM est accessible.

La tentative suivante a démarré en environ 29 secondes avec :

```sh
ANDROID_AVD_HOME=/tmp/bonobo-beta15-avd /var/home/Snoopy63/Android/Sdk/emulator/emulator -avd SyncZen-Recette-API35 -no-window -no-audio -no-snapshot -gpu swangle -feature -Vulkan -port 5554
```

Cette configuration évite le crash observé ; elle ne démontre pas que Vulkan était la cause. Au contrôle final : PID QEMU 88150 actif, `adb -s emulator-5554 get-state` = `device`, `sys.boot_completed` = `1`, nom d'AVD confirmé. L'émulateur est laissé actif.

## Validation réalisée

- Lint incrémental hors ligne : **0 erreur, 92 avertissements**. `lint-resume.log` : succès, 3 tâches exécutées et 28 à jour.
- APK existants installés avec `adb -s emulator-5554 install -r`, sans désinstallation ni effacement de données applicatives.
- Première exécution : 6 tests, 5 échecs. Le contexte Android passe. Le test Compose est perturbé par l'écran d'autorisation du stockage ; le fournisseur SAF ne démarre pas sans `MANAGE_DOCUMENTS`.
- Permissions stockage (`MANAGE_EXTERNAL_STORAGE`) et notifications accordées uniquement à `fr.bonobo.filemanager.adaptive` sur l'émulateur de recette. Retour depuis les paramètres Android, puis reprise ciblée.
- **Test AdaptiveExplorer réussi** dans `android-retry.log` : rotation portrait/paysage, recréation, sélection, presse-papiers, tri et copie entre panneaux. Il n'a pas été réexécuté ensuite.
- Fournisseur synthétique corrigé : protection `MANAGE_DOCUMENTS`, activité de test accordant les URI temporaires depuis l'UID du fournisseur ; composants externes écrits en Java pour ne pas dépendre du runtime Kotlin absent de leur APK autonome ; racine canonique pour les contrôles de descendants.
- **Quatre tests SAF réussis** dans `android-saf-result.log` (`OK (4 tests)`) : copie/renommage/déplacement et conflits, récursion et protection contre les descendants, lecture seule/support absent/écriture refusée, lecture tronquée conservant la source, flux reçus et schémas interdits, refus d'une persistance non offerte.
- Après les corrections de fixture : compilation de l'APK AndroidTest et Lint réussis (`test-fixture-build5.log`). L'APK de production n'a pas été modifié par cette reprise.

Les **six scénarios Android ont donc tous un résultat positif**, réparti entre trois exécutions : contexte dans `android-resume.log`, interface dans `android-retry.log`, SAF dans `android-saf-result.log`. Les échecs intermédiaires sont conservés dans les autres journaux et ne sont pas masqués. Aucun nouveau test miroir de l'implémentation n'a été ajouté.

## Fichiers modifiés pendant la reprise

- `app/src/androidTest/AndroidManifest.xml` : protection du fournisseur et activité d'octroi synthétique.
- `app/src/androidTest/java/fr/bonobo/filemanager/saf/TestDocumentsProvider.java` : remplace la fixture Kotlin, mêmes opérations et simulations.
- `app/src/androidTest/java/fr/bonobo/filemanager/saf/TestGrantActivity.java` : octroi temporaire, exclusivement dans l'APK de tests.
- `app/src/androidTest/java/fr/bonobo/filemanager/saf/SafIntegrationTest.kt` : préparation et attente de la permission temporaire.
- `README-BETA15.md` : résultats et SHA-256 actualisés.
- Ce rapport et journaux dans `validation/beta15/`.

APK applicatif conservé : `app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `8efe9184342da704dcc9330ecdd544611b70e4b2c3bb37dc794915cbabd863e1`.

## Opérations restantes

- Examiner les 92 avertissements Lint ; aucune erreur bloquante restante. Les dépendances et le SDK cible n'ont pas été mis à jour dans cette reprise.
- Validation physique USB/OTG et carte SD : montage/retrait/reconnexion, interruption de copie, capacités réelles des fournisseurs et fichiers partiels éventuels.
- Persistance positive des URI après relance et redémarrage ; le test actuel vérifie le refus de persistance non offerte, pas la persistance positive.
- Vraies intentions interapplications, lecteurs, thèmes et permissions temporaires sur appareils réels.
- Tests réseau SMB/SFTP/HTTPS/cloud dans un environnement de recette explicitement autorisé. Aucun NAS réel ni compte cloud contacté.
- Espaces finaux préexistants dans `AddRemoteDialog.kt` et `NetworkScreen.kt` signalés par `git diff --check`, inchangés.

Aucune donnée utilisateur supprimée. Seuls les fichiers synthétiques créés par les tests sont nettoyés par leurs scénarios ; certains essais interrompus peuvent avoir laissé des fixtures dans le cache de la copie de recette, conservées. Ni smartphone, ni NAS réel, ni AVD original n'ont été utilisés.
