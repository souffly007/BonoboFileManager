# Bonobo Files Explorateur — bêta 19 corrective

Version 1.3.0-beta19, versionCode 29. Livraison Release sans signature. Lot demandé après les retours de bêta 18. Aucun développement de client FTP/FTPS dans ce lot ; voir PLAN-CLIENT-FTP-FTPS.md.

## 1. Résultat de transfert réaffiché

Défaut identifié : TransferCoordinator.dismiss() remettait uniquement son StateFlow à zéro. Le fichier transfers/latest.properties conservait le lot terminé ; TransferJournal.read() le reconstruisait à chaque nouvelle instance du processus. Une reconnexion ou une navigation ne soumet pas de nouvelle opération, mais peut accompagner une recréation du processus/de l'interface. Le lien exact avec les reconnexions du testeur reste à confirmer sur sa machine.

Correction : un attribut acknowledged persiste l'acquittement dans le journal atomique existant. Le résultat, son identifiant et ses compteurs restent conservés, tandis que TransferPanel masque un résultat acquitté. Fermer ou consulter les détails d'un résultat terminal acquitte le résultat ; les détails restent visibles jusqu'à leur fermeture. Une simple recomposition ou un retour à l'écran ne retire pas cet acquittement. La consultation pendant un transfert actif ne masque pas son résultat futur. Un nouveau lot possède son propre identifiant et remet l'acquittement à false.

La persistance terminale et l'acquittement sont sérialisés pour éviter qu'une écriture de fin de transfert n'annule l'acquittement. Un journal inaccessible laisse le résultat visible et indique l'échec d'enregistrement. Les anciens journaux restent lisibles et peuvent afficher une dernière fois un résultat encore non acquitté. Un transfert actif au moment de l'arrêt est toujours présenté comme interrompu et non acquitté ; aucune reprise automatique.

Audit : le service ManagedTransferService est START_NOT_STICKY, supprime sa notification lorsque le transfert cesse d'être actif et n'est pas démarré par les événements de volume. Le rafraîchissement de MainScreen ne soumet aucun transfert. Le journal est un résumé borné du dernier lot, pas un historique complet ; les noms/détails demeurent limités à la mémoire de session comme auparavant.

## 2. Double panneau

Chaque panneau conserve son chemin et sa racine de navigation. Les parents SAF sont désormais indexés par panneau et URI ; rouvrir une racine n'efface plus les parents SAF de l'autre panneau. Le calcul d'une racine locale ne traite plus une ancienne URI SAF comme un chemin java.io.File.

Les sélections sont distinctes : le changement de panneau échange la sélection active et celle du panneau inactif. Les listes de chaque panneau utilisent leur propre sélection ; un changement de dossier efface seulement la sélection concernée, un rafraîchissement d'un dossier la conserve. Le retour à l'accueil remet explicitement le premier panneau au stockage interne sans remplacer le second.

Dans l'explorateur à deux panneaux, les boutons Mémoire interne et USB / OTG ouvrent le stockage dans le panneau actif. Ils permettent USB à gauche/interne à droite et l'inverse. Les événements MEDIA_MOUNTED, UNMOUNTED, REMOVED et BAD_REMOVAL rafraîchissent les deux chemins existants. Les jetons de chargement restent distincts, pour ignorer une réponse devenue obsolète.

Les autorisations SAF appartiennent au processus Android, pas à un panneau : chaque panneau garde son URI de tree, et l'application utilise les droits Android déjà accordés. Le bouton du volume réutilise une autorisation de lecture enregistrée du fournisseur externalstorage correspondant à l'UUID du volume, au lieu de lancer inutilement une nouvelle demande. Les autres fournisseurs restent accessibles via les dossiers enregistrés ou la sélection manuelle.

## 3. Corbeille

Restaurer propose désormais systématiquement deux destinations : origine ou dossier choisi par le sélecteur Android. Un appui long active la sélection ; le bouton Restaurer traite la sélection vers l'origine de chaque élément ou vers le même dossier choisi.

Traitement séquentiel avec BatchTransfer : chaque résultat est collecté indépendamment, un conflit ou une erreur d'un élément ne bloque pas les suivants. Le bilan indique le nombre vérifié et les erreurs par nom. Une restauration en cours interdit une seconde restauration et les mutations locales passant par transferBusy, notamment collage et vidage de corbeille. L'annulation de la coroutine libère l'état occupé.

Les moteurs de copie/déplacement, TrashRestorer et RestoreReceipts ne sont pas réécrits. Chaque élément conserve le journal privé anti-doublons, l'intention durable avant copie, la vérification d'intégrité avant reçu final, les contrôles de destination et le refus de remplacement. Les sources restent dans la corbeille même après succès : suppression explicite après contrôle. Un reçu déjà vérifié interdit une copie supplémentaire si sa destination existe encore.

Limites maintenues : la corbeille existante utilise des sources locales. La destination peut être locale ou SAF lorsque son équivalence locale est connue et validée hors corbeille. Les fournisseurs opaques sans équivalence vérifiable restent refusés ; ce lot n'annonce pas une restauration universelle depuis toutes les corbeilles SAF. Android peut interdire certains dossiers racines au sélecteur ; choisir un sous-dossier autorisé. Un conflit est signalé, sans écrasement ni renommage automatique.

## 4. Retour USB après ES Explorer PRO : diagnostic, pas résolution annoncée

Le code Bonobo utilise StorageManager.storageVolumes pour les volumes, les URI tree/document de DocumentsContract et les autorisations Android persistantes. SafFiles rapporte explicitement SecurityException comme refus d'accès et les autres échecs comme support/fournisseur inaccessible. Une autorisation conservée ne garantit ni la présence du volume, ni la validité actuelle du document, ni la réponse du fournisseur.

MainScreen relit les deux panneaux sur ON_RESUME, notamment après retour d'une autre application, ainsi que sur les événements de montage/démontage média. Les accès déjà ouverts ne sont pas conservés comme des streams entre les lectures ; les lectures passent de nouveau par le fournisseur. Les opérations de transfert ne sont pas rejouées par ce rafraîchissement.

Le sélecteur affiche maintenant le nombre de volumes amovibles, le nombre d'autorisations enregistrées et l'état Android des volumes indisponibles. Réessayer l'accès, Rouvrir la racine et Sélectionner le dossier USB / OTG restent disponibles après erreur. Sélectionner manuellement ouvre ACTION_OPEN_DOCUMENT_TREE sans dépendre d'un chemin local précédemment connu, pour réautoriser le dossier sans débranchement lorsque Android expose encore le volume.

Hypothèses à distinguer sur appareil : tree/document invalidé, accès temporaire perdu, fournisseur indisponible, volume démonté, prise en charge USB propre à une autre application. Aucune de ces hypothèses n'est attribuée comme fait à ES Explorer PRO. Aucun montage forcé, aucune prise de contrôle USB, aucune permission contournée. Les notifications USB matérielles sans événement média et les changements internes d'un fournisseur en arrière-plan ne font pas l'objet d'un observateur dédié ; le retour au premier plan ou Réessayer déclenche la relecture.

Référence Android : [Storage Access Framework et autorisations persistantes](https://developer.android.com/training/data-storage/shared/documents-files). Android indique notamment que l'accès persistant à un document peut être perdu après déplacement ou suppression.

## 5. Validation et livraison

Les résultats définitifs, le nombre de tests, les rapports Lint et l'empreinte de l'APK sont consignés dans validation/beta19/RESULTATS.md. Les journaux des tentatives intermédiaires sont conservés pour distinguer échecs puis validation finale.

Tests ajoutés : acquittement durable avec relectures répétées, conservation des compteurs, transfert interrompu encore visible, échec d'acquittement, indépendance des sélections avec permutations USB/interne, restauration groupée avec conflit et anti-doublons. Tests Android ajoutés : recomposition après consultation des détails et restauration groupée vers un dossier SAF synthétique avec conflit et conservation des sources.

Aucun test instrumenté exécuté dans ce lot : les tests Android sont compilés seulement. Aucun essai répété avec l'émulateur défaillant. Les tests JVM utilisent des fichiers synthétiques temporaires ; les tests instrumentés utilisent un fournisseur de documents de test et le cache de test.

Non validé sur appareil réel : cycle déconnexion/reconnexion OTG, USB/interne dans les deux orientations, sortie/retour ES Explorer PRO, sélecteur et permissions persistantes réels, gros fichiers sur clé, restauration groupée SAF réelle. SMB/SFTP/WebDAV ne sont pas testés sur NAS/serveurs réels dans ce lot ; leurs connexions et implémentations ne sont pas modifiées. Le futur client FTP/FTPS exige sa propre validation.

Risques résiduels : différences entre fournisseurs SAF et Android constructeur ; autorisation enregistrée devenue inutilisable ; défaut matériel ou fournisseur externe non récupérable ; affichage/ergonomie à deux panneaux encore à confirmer sur écran étroit ; sérialisation de petites écritures de journal lors de l'acquittement. Les tests de modèle ne remplacent pas une validation complète du ViewModel Android sur matériel.

Aucun accès NAS, installation smartphone, modification d'autres applications, publication sur souffly.app ou modification des clés de signature Release. Le dépôt comportait déjà de nombreuses modifications avant ce lot ; elles ont été conservées. Travail arrêté après la validation automatisée disponible et la préparation de l'artefact non signé.
