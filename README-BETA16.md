# Bonobo Files Explorateur — bêta 16

**Contrôle de livraison : cet APK Debug ne peut pas mettre à jour la bêta 15 Release archivée.** Packages et certificats différents. Aucune nouvelle APK de remplacement n’est construite tant que la signature de l’installation du testeur n’est pas confirmée. Voir [le contrôle de compatibilité](validation/beta16/COMPATIBILITE-SIGNATURE.md).

Préparation Release vérifiée : `fr.bonobo.filemanager`, code 26, version `1.3.0-beta16`, corrections compilées en Release, 48 tests JVM Release réussis, Lint Release sans erreur (90 avertissements). Aucune APK Release signée produite. [Procédure de comparaison du certificat et de signature dans Android Studio](validation/beta16/PREPARATION-RELEASE.md).

Préparée le 11 octobre 2026 pour validation : **1.3.0-beta16-debug**, code **26**, application `fr.bonobo.filemanager.adaptive`.

APK archivé : [bonobo-files-explorateur-1.3.0-beta16-debug.apk](app/release/bonobo-files-explorateur-1.3.0-beta16-debug.apk).

SHA-256 : `1fc1b72ec57149bedd1cbff7cc867b4c54a68f0bcb4979c8457b98b86a9a9314`.

Preuves finales : [compilation](validation/beta16/delivery-build.log), [12 tests Android](validation/beta16/android-delivery.log), [XML JVM](validation/beta16/jvm/), [Lint](validation/beta16/lint-results-debug.html).

## Diagnostic

Les défauts suivants sont établis dans le code de la bêta 15 :

- Une copie SAF était déclarée terminée après `flush` et fermeture des flux, sans relecture du document ni confirmation de sa présence dans le dossier parent. Le déplacement supprimait ensuite la source. Un fournisseur acceptant l’écriture mais perdant les octets pouvait donc provoquer une fausse réussite et une perte de la source. La fixture reproduit maintenant ce comportement.
- `createDocument` était accepté dès qu’il renvoyait une URI non nulle. Un identifiant existant, une création fantôme ou un document inaccessible n’étaient pas contrôlés avant utilisation. Ces trois cas sont désormais simulés.
- L’explorateur écoutait le montage/retrait du média, mais ne relisait pas systématiquement ses dossiers à `ON_RESUME`. Un changement par une autre application pouvait laisser une liste périmée ou masquer la perte d’accès jusqu’à une actualisation manuelle.

**La cause exacte des deux incidents sur la clé réelle n’est pas démontrée.** Nous n’avons ni trace du téléphone, ni URI du testeur, ni accès au fournisseur de cette clé. Le rôle d’ES Explorer PRO, du montage Android, d’un verrou, d’une permission temporaire ou d’un fournisseur défaillant reste une hypothèse. Aucun remount, démontage forcé ou réinitialisation automatique des permissions n’est ajouté.

### Destination, URI et documents

Le sélecteur USB existant peut ouvrir un chemin local de volume lorsque celui-ci est lisible, ou une URI SAF sélectionnée dans Android. L’absence de trace interdit de déterminer lequel le testeur utilisait. Le collage capture le panneau et son dossier de destination ; il ne se sert pas de la base Room pour matérialiser le fichier.

Les deux voies utilisent désormais le même transfert vérifié dans `SafFiles`. Pour SAF, la destination est le document parent avec le paramètre applicatif `bonobo_name`. Le nom ne sert jamais à inventer un identifiant de document ou un chemin système. L’identifiant renvoyé par `DocumentsContract.createDocument` est comparé aux documents présents avant création, puis recherché dans une nouvelle interrogation du parent. L’autorité, le nom et le type doivent correspondre. L’URI effectivement utilisée est reconstruite dans l’arbre autorisé à partir de l’identifiant confirmé par le fournisseur.

### Écriture et confirmation

- Création exclusive côté local ; refus des noms déjà présents côté SAF et refus d’un identifiant renvoyant un document déjà présent. Aucun remplacement volontaire d’un fichier de destination existant.
- Fermeture des flux avec `use`, prise en compte des exceptions de lecture/écriture/fermeture, contrôle de la taille source annoncée quand elle est disponible, points de contrôle d’annulation pendant les transferts et vérifications.
- Relecture de la destination **après fermeture du flux d’écriture** : nombre d’octets et SHA-256 comparés à ceux effectivement lus de la source. Une lecture impossible ou une divergence produit un échec explicite.
- Nouvelle interrogation du dossier parent après écriture. Pour les dossiers, comparaison de l’arborescence et du contenu des fichiers ; contrôle complet supplémentaire avant suppression de la source d’un déplacement.
- La source n’est pas supprimée si la copie, sa relecture ou sa vérification échoue, ni si l’annulation est observée avant la suppression. Si la suppression échoue après copie confirmée, l’opération remonte une erreur.
- Un fichier incomplet nouvellement créé est nettoyé si possible. Un dossier partiel n’est **pas supprimé récursivement automatiquement** : une autre application pourrait y avoir ajouté des documents. L’erreur avertit qu’une destination partielle peut subsister ; la source reste intacte lors d’un échec de copie.
- Le presse-papiers est libéré uniquement après succès du repository. La liste de destination est ensuite relue ; aucun fichier fictif n’est ajouté à l’interface pour simuler une réussite.

### Reprise d’accès

À `ON_RESUME`, le panneau actif et l’autre panneau ouvert sont relus ; les listes périmées sont remplacées par le résultat du stockage. `SecurityException` et les documents indisponibles produisent une erreur. L’écran propose **Réessayer l’accès**, **Sélectionner le dossier USB / OTG** et le retour à l’accueil. Une nouvelle sélection reste volontaire : aucune autorisation persistante n’est supprimée automatiquement.

## Fichiers modifiés pendant cette préparation

Le dépôt contenait déjà de nombreuses modifications de bêta 15. Elles ont été conservées. Cette liste décrit uniquement les interventions de cette session :

| Fichier | Intervention bêta 16 |
| --- | --- |
| `app/build.gradle.kts` | Version 26 / 1.3.0-beta16 après validation des corrections. |
| `app/src/main/java/fr/bonobo/filemanager/util/SafFiles.kt` | Création confirmée, relecture SHA-256, vérification récursive, annulation, protection des déplacements et messages de résultat incertain. |
| `app/src/main/java/fr/bonobo/filemanager/data/repository/FileRepositoryImpl.kt` | Transferts vérifiés pour chemins locaux et SAF ; propagation de l’annulation. |
| `app/src/main/java/fr/bonobo/filemanager/presentation/ui/main/MainViewModel.kt` | Actualisation des panneaux au retour au premier plan. |
| `app/src/main/java/fr/bonobo/filemanager/presentation/ui/main/MainScreen.kt` | Observation de `ON_RESUME`, réessai et sélection USB / OTG depuis l’erreur. |
| `app/src/androidTest/AndroidManifest.xml` | Activité synthétique de changement d’application, uniquement dans l’APK de tests. |
| `app/src/androidTest/java/fr/bonobo/filemanager/AdaptiveExplorerTest.kt` | Nouveau test de retour depuis une autre application synthétique et changement des fichiers pendant l’absence. |
| `app/src/androidTest/java/fr/bonobo/filemanager/saf/TestDocumentsProvider.java` | Écritures perdues, URI incorrectes, documents fantômes/illisibles, lecture lente annulable et écriture interrompue via pipe fiable. |
| `app/src/androidTest/java/fr/bonobo/filemanager/saf/TestGrantActivity.java` | Permissions synthétiques refusées/révocables et révocation réelle d’un octroi de test. |
| `app/src/androidTest/java/fr/bonobo/filemanager/saf/TestBackgroundActivity.java` | Nouvelle application synthétique pour provoquer un vrai passage en arrière-plan. |
| `app/src/androidTest/java/fr/bonobo/filemanager/saf/SafIntegrationTest.kt` | Conservation des quatre tests précédents et ajout de cinq scénarios SAF. |
| `README-BETA16.md`, `validation/beta16/` | Rapport, journaux, résultats et empreinte de l’APK. |

Aucune intervention sur SMB, SFTP, HTTPS, les lecteurs ou les thèmes, sur SyncZen ou une autre application. Aucun NAS, smartphone, fichier personnel ou publication sur souffly.app. Aucun commit ni publication externe.

## Tests et compilation

Commande de livraison :

```sh
./gradlew assembleDebug testDebugUnitTest assembleDebugAndroidTest lintDebug --offline
```

- **Compilation Debug et AndroidTest : réussie.**
- **48 tests JVM : réussis**, zéro échec, erreur ou test ignoré ; tests existants conservés.
- **12 tests Android : réussis**, zéro échec final ou test ignoré, sur l’émulateur isolé API 35 `emulator-5554`.
- **Lint Debug : zéro erreur, 92 avertissements.** `abortOnError=true` conservé, aucun masquage ni baseline ajouté.
- Contrôle des espaces des fichiers suivis touchés : réussi. Les modifications préexistantes du dépôt ne sont pas annulées ni nettoyées.

L’AVD utilisé est la copie isolée déjà présente dans `/tmp/bonobo-beta15-avd`, dont le nom historique est `SyncZen-Recette-API35`. L’AVD original n’a pas été modifié. Seuls Bonobo Debug et son APK de tests ont été installés sur cette copie ; les données des scénarios sont synthétiques.

| Couverture Android | Résultat |
| --- | --- |
| Contexte applicatif et ancien test rotation/recréation/copie entre panneaux | Réussis |
| Quatre scénarios SAF de bêta 15 : conflits, copie dans les deux sens, déplacement, récursion, lecture seule, support absent, lecture tronquée et flux reçus | Réussis |
| Écriture perdue, URI existante renvoyée, création fantôme et destination illisible : déplacement refusé, source et document préexistant intacts | Réussi |
| Repository et base fermés/réouverts ; fichiers et dossiers SAF encore présents, contenu contrôlé ; déplacements interne → SAF et SAF → interne ; copie SAF → SAF | Réussi |
| Annulation pendant une lecture lente : déplacement interrompu, fichier source présent, destination incomplète supprimée | Réussi |
| Permission SAF réellement révoquée, `SecurityException` synthétique et document invalide ; erreur puis nouvelle sélection/octroi possible | Réussi |
| Écriture interrompue par fournisseur ; échec de déplacement récursif : sources conservées | Réussi |
| Retour d’une application synthétique après modification du dossier : ancienne entrée retirée et nouvelle entrée affichée | Réussi |

### Échecs intermédiaires conservés

- Premier lancement de Gradle dans le bac à sable : cache du wrapper en lecture seule ; compilation relancée avec l’autorisation nécessaire.
- Première suite Android : 10/11 réussis ; un ancien test attendait « Support » avec une majuscule dans le message d’indisponibilité. La formulation compatible a été rétablie sans retirer le test.
- Suite suivante : 11/12 réussis ; le retour d’application déclenchait un problème de nettoyage `ActivityScenario` parce que la seconde activité restait ouverte. La fixture est maintenant fermée explicitement avant les assertions de retour.
- Une variante intermédiaire de fixture utilisant un récepteur a déclenché une erreur Lint sur les drapeaux de réception. Cette variante a été retirée ; le test utilise une activité avec retour explicite, sans récepteur supplémentaire.
- Après corrections : suite complète 12/12, puis validation de l’APK portant la version bêta 16. Les journaux intermédiaires sont conservés dans `validation/beta16/`.

### Non exécutés et limites

- Aucun essai sur clé USB physique, aucune exécution d’ES Explorer PRO, aucun retrait/rebranchement physique ni redémarrage de téléphone. L’émulateur ne reproduit pas le pilote USB, le format de la clé ou le fournisseur du téléphone.
- La fermeture/réouverture testée concerne le repository et sa base ; elle ne démontre pas une persistance des permissions après arrêt forcé de l’application ou redémarrage Android. La fixture accorde des permissions temporaires et teste leur révocation réelle.
- La relecture confirme les octets **visibles via Android au moment de la vérification**, pas leur persistance physique après retrait. SAF ne fournit pas de transaction universelle ni de garantie d’écriture durable sur tous les fournisseurs. Un fournisseur différant la validation ou cachant les données peut rester problématique. Voir la documentation [DocumentsProvider / openDocument](https://developer.android.com/reference/android/provider/DocumentsProvider#openDocument(java.lang.String,java.lang.String,android.os.CancellationSignal)) sur les descripteurs fiables et la détection de fin d’écriture.
- Des modifications concurrentes restent possibles entre vérification et suppression : le fournisseur n’offre pas une réservation atomique commune à toutes les opérations. Une panne pendant la phase de suppression elle-même peut laisser une source partiellement supprimée ; la copie est déjà intégralement vérifiée avant cette phase. La protection de la source est validée pour les échecs et interruptions pendant la copie/vérification, sans prétendre à une transaction atomique de déplacement.
- Une création incorrecte ou un nettoyage refusé peut laisser une destination partielle. Aucune suppression récursive automatique n’est faite pour la nettoyer. Les doubles représentations d’un même dossier via un chemin local et une URI SAF restent une limite pour la détection des descendants ; privilégier une seule voie d’accès au volume pour un déplacement de dossier.
- La relecture SHA-256 augmente le temps et les lectures nécessaires, en particulier pour les dossiers profonds et les déplacements.
- Tests réseau réels, lecteurs et thèmes non réexécutés sur appareils ; ils n’ont pas été modifiés. Aucun APK Release signé n’est préparé : cet artefact Debug sert à la validation sur émulateur. La bêta 15 Release archivée a un autre package et un autre certificat.

## Vérification courte pour le testeur

Utiliser exclusivement un petit fichier et un dossier imbriqué **de test**, avec une copie de référence conservée ailleurs.

1. Installer la bêta 16 de validation, brancher la clé et ouvrir le même dossier que précédemment. Noter si l’accès se fait par le volume local affiché ou par **Sélectionner manuellement** dans le sélecteur Android.
2. Copier fichier et dossier : interne → USB, USB → interne et USB → USB. Fermer complètement Bonobo, le rouvrir puis contrôler noms et contenu à destination, également dans le gestionnaire Android. Refaire avec des noms déjà présents : l’opération doit être refusée et le contenu existant inchangé.
3. Déplacer des copies de test dans les deux sens. Vérifier tout le contenu à destination après relance, puis vérifier que la source n’a disparu qu’après la fin confirmée du transfert.
4. Utiliser la clé dans ES Explorer PRO, puis revenir à Bonobo sans débrancher. Vérifier l’actualisation automatique. Si l’accès échoue, relever le message, essayer **Réessayer l’accès**, puis **Sélectionner le dossier USB / OTG**. Comparer l’accès depuis le gestionnaire Android ; seulement ensuite essayer le débranchement/rebranchement manuel.
5. Sur une copie de test volumineuse, interrompre le transfert ou retirer le support : erreur explicite, source conservée pendant la copie/vérification ; vérifier les éventuels fichiers/dossiers partiels après reconnexion. Un retrait ne doit jamais être considéré comme garanti sans risque par l’application.

En cas d’échec, communiquer modèle du téléphone, version Android, format de la clé, sens du transfert, accès local ou SAF, étape exacte, message affiché et comportement du gestionnaire Android après ES. Ne transmettre aucun fichier personnel.

Le travail s’arrête après tests et compilation pour validation ; aucune diffusion ou installation sur smartphone n’est effectuée.
