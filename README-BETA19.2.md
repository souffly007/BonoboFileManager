# Bonobo Files Explorateur — bêta 19.2 corrective

Version : **1.3.0-beta19.2**, versionCode **31** (bêta 19.1 : 30). Livraison Release non signée. Audit relu avant modification : [AUDIT-BETA19.1-RETOUR-TESTEUR.md](AUDIT-BETA19.1-RETOUR-TESTEUR.md).

## Suppressions et corbeille

Les messages Snackbar qui annonçaient un succès dès le clic ont été supprimés. `MainViewModel.removeItems` attend les résultats du moteur, puis affiche un bilan sous « Résultat de l’opération ». Il indique le nombre réellement confirmé et les causes des échecs, y compris les exceptions sans message. Une exception lancée directement est également traitée. Les opérations se font en séquence ; une nouvelle suppression ou un transfert depuis cet écran attend la fin de la suppression en cours.

Les éléments réussis quittent la sélection du panneau concerné ; les éléments refusés restent sélectionnés. Une actualisation est demandée après le bilan. Les erreurs d’une suppression de dossier signalent qu’une partie de son contenu peut déjà avoir disparu. Une annulation de coroutine n’est jamais transformée en succès.

Le dialogue d’un document SAF ne propose plus la corbeille locale. Pour une sélection mixte local/SAF, cette action est également retirée ; le moteur de lot la refuse sans toucher à aucun élément si elle est appelée malgré tout. Aucune substitution par une suppression définitive. La suppression définitive conserve son bouton et sa confirmation explicites.

La corbeille locale existante (`.bonobo_trash`, déplacement local, format des noms) est conservée. L’échec de création de son dossier est désormais vérifié et rapporté. La suppression SAF continue de respecter le flag DELETE et le résultat de `DocumentsContract.deleteDocument` ; aucun contournement de permission Android.

Fichiers : `MainScreen.kt`, `MainViewModel.kt`, `OperationDialog.kt`, `FileRepositoryImpl.kt`, nouveau `DeletionBatch.kt`.

## Destinations SAF sans chemin local

`TransferEngineFactory.local().execute` n’exige plus un alias de chemin local pour une destination SAF. Le moteur utilise l’URI du document et les API `ContentResolver` / `DocumentsContract`. Les alias connus restent utilisés pour refuser une copie de dossier dans sa propre arborescence.

Les contrôles conservés ou renforcés sont les suivants : capacité CREATE du parent, listing réel et refus d’un nom existant, confirmation de l’identité/du nom/du type après création, capacité WRITE du fichier créé, ouverture réelle des flux, fermeture de la sortie puis réouverture en lecture. La taille lue est contrôlée lorsqu’elle est connue ; les octets écrits et relus ainsi que SHA-256 sont comparés. Une ouverture en écriture réussie seule ne valide jamais un transfert. Un refus de lecture lors de la vérification fait échouer l’opération.

Pour les dossiers, `SafFiles.copy` établit un inventaire des enfants avant toute création, avec contrôle des cycles, profondeur et liens symboliques locaux. La récursion ne découvre ensuite aucun nouvel enfant à copier. Les identités des répertoires sont comparées à celle du parent destination et les contrôles SAF de descendance restent en place. Après création de la racine destination, les listes d’enfants source sont relues avant écriture du contenu : si la création apparaît dans la source par un alias opaque, le transfert s’arrête. Un tel refus peut laisser un dossier vide créé, qui doit être contrôlé manuellement.

Cette protection permet de travailler sans déduire un chemin depuis un identifiant opaque. Elle n’accorde aucun droit et ne prétend pas éliminer toute concurrence avec un fournisseur ou une autre application. L’inventaire supplémentaire coûte du temps et de la mémoire pour les grandes arborescences. Les modifications concurrentes détectées entraînent un refus prudent.

Fichiers : `TransferEngineFactory.kt`, `SafFiles.kt`.

## Déplacer, Couper et annuler

Le menu Déplacer et Couper/coller utilisent le même mode MOVE. Aucune branche convertissant silencieusement MOVE en COPY n’a été identifiée. La cause exacte de l’incident du testeur ne peut pas être confirmée sans son fournisseur, sa version Android et les erreurs observées.

Le moteur exige toujours la capacité de suppression SAF avant de commencer un déplacement, puis vérifie la copie et compare à nouveau l’arbre source/destination avant suppression. Les causes désormais distinguées dans les messages de résultat sont :

- Échec de vérification finale après copie : « Copie effectuée, source conservée : vérification finale insuffisante », avec cause.
- Échec de suppression après copie vérifiée : « Copie effectuée, source conservée ou partiellement supprimée : suppression source non confirmée », avec cause. La réserve est nécessaire pour un dossier dont certains enfants auraient déjà été supprimés.
- Annulation pendant copie : diagnostic indiquant si le fichier incomplet a été supprimé ou si une destination partielle peut subsister. Le coordinateur conserve ce diagnostic dans le résultat interrompu.

Ces cas ne produisent pas un statut DONE. Les éléments dont le déplacement n’est pas confirmé restent dans le presse-papiers. Une relance au même nom peut donc rencontrer la destination laissée par la tentative précédente ; elle n’est ni écrasée ni reprise automatiquement.

Le moteur écrit toujours directement sous le nom final. Il ne fournit ni publication atomique universelle SAF, ni reprise par offset. En cas d’erreur, il tente le nettoyage du fichier qu’il vient de créer, mais ne supprime jamais récursivement un dossier partiel qui pourrait contenir des ajouts externes. Une création non confirmée peut aussi laisser une entrée. Le testeur doit contrôler ces destinations, sans supprimer de données externes.

Les compteurs et UUID du coordinateur n’ont pas été réécrits : les tests existants de bêta 19.1 vérifient leur réinitialisation après annulation pour COPY/MOVE. La vitesse perçue sur clé réelle, l’effet des caches et le comportement après reconnexion restent à mesurer. Une barre à 100 % représente les octets copiés, pas l’achèvement de la vérification ou du déplacement.

Fichiers : `SafFiles.kt`, `TransferCoordinator.kt`.

## Accès USB après ES Explorer et résultats persistants

Aucun mécanisme de montage forcé ni solution supposée à ES Explorer n’a été ajouté. Le sélecteur USB existant propose « Sélectionner manuellement » : Android peut permettre de réaccorder l’accès sans débranchement. Un grant enregistré peut néanmoins être périmé ou le fournisseur peut ne plus répondre.

Le journal Logcat `BonoboSAF` indique LIST/COPY, la classe d’exception et celle de sa cause immédiate. Il n’écrit pas les messages, piles, noms, URI ou identifiants. Les messages détaillés restent dans l’interface de l’utilisateur. Une absence de trace ne garantit pas la santé du fournisseur ; les refus avant cette couche et les exceptions système peuvent emprunter d’autres parcours.

Le comportement d’acquittement des résultats reste conservé : Fermer enregistre l’acquittement ; un résultat terminé encore visible n’interdit pas le transfert suivant. Détails acquitte également un résultat terminé. Les tests de persistance existants sont inclus dans la validation.

## Tests ajoutés

JVM :

- `Beta192DeletionTest` : attente d’un moteur différé, exception sans message, suppression fichier/dossier factice, déplacement en corbeille factice, refus de corbeille SAF/mixte sans appel destructif, succès/refus/exception de suppression définitive.
- `Beta192TransferTest` : un échec de suppression après copie des octets reste FAILED et ne compte pas comme fichier terminé ; diagnostic de destination partielle conservé après annulation et nouvel UUID/compteurs à zéro au prochain transfert.

Android, compilés uniquement :

- `DeletionDialogTest` : corbeille présente en local et absente en SAF pour fichier/dossier, sélection mixte sans action automatique.
- `SafIntegrationTest` : vrai moteur obtenu par Hilt, fournisseur inconnu du mapping local et identifiants encodés ; fichiers/dossiers vers SAF en COPY/MOVE, copie SAF→SAF, conflit sans perte source, refus lecture seule, contenu perdu, destination illisible, écriture refusée ou interrompue. Suppression fichier/dossier réussie, flag DELETE absent et suppression source refusée après copie.
- Le fournisseur de test et son activité d’octroi de droits ont été étendus. Ils restent exclusivement dans l’APK de tests.

Les tests de moteur simulé ne prouvent pas les capacités Android. Les tests instrumentés n’ont pas été exécutés et ne constituent pas une validation USB physique.

## Validation et livraison

Les résultats définitifs, empreinte de l’APK, contrôle d’absence de signature et journaux de compilation sont consignés dans [validation/beta19.2/RESULTATS.md](validation/beta19.2/RESULTATS.md). La procédure de terrain se trouve dans [validation/beta19.2/PROCEDURE-TESTEUR.md](validation/beta19.2/PROCEDURE-TESTEUR.md).

Les nombreuses modifications préexistantes du workspace sont conservées. Ce lot ne touche pas les moteurs réseau, le NAS, FTP/FTPS, la fiche USB, les autres applications ni la configuration des clés. Les tâches Debug utilisent la signature de développement Gradle habituelle ; la Release livrée est non signée.

Limites à valider matériellement : réel fournisseur USB/constructeur, mémoire interne partagée et ses droits, suppression récursive, retrait du support, persistance après éjection sûre, deux orientations du double panneau, vitesse après annulation, accès après ES Explorer et reconnexion avec résultat acquitté.

Travail arrêté après validation automatisée et préparation de l’artefact ; aucune installation sur smartphone ni publication.
