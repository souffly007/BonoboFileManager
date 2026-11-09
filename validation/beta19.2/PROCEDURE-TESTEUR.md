# Procédure de test — bêta 19.2

Utiliser exclusivement une clé de test et des fichiers factices dans un nouveau dossier `BONOBO_TEST_192_<identifiant>` sur téléphone et USB. Conserver les fichiers personnels hors du périmètre. L’APK livrée est non signée : elle nécessite la chaîne de signature habituelle du responsable de livraison avant installation par le testeur. Aucun smartphone n’a été utilisé par l’agent.

## Préparation

Noter version 1.3.0-beta19.2/code 31, Android/API, modèle, fournisseur SAF, droits lecture/écriture et type d’ouverture de la clé (URI SAF ou chemin brut). Ne pas communiquer d’URI complète ni d’UUID de volume. Créer un fichier texte connu, un fichier vide, un fichier binaire assez grand pour annuler, un dossier vide et un dossier imbriqué. Conserver leurs tailles et SHA-256 de référence.

## Suppression et corbeille

1. Local : fichier puis dossier, corbeille puis restauration séparée ; vérifier le contenu. La confirmation de réussite ne doit apparaître qu’après la fin réelle. Refaire avec suppression définitive sur d’autres fixtures.
2. SAF : fichier et dossier ne doivent pas proposer la corbeille locale ; la sélection mixte SAF/local ne doit pas la proposer non plus. Aucune suppression automatique lors de l’ouverture/fermeture du dialogue.
3. SAF : confirmer explicitement la suppression définitive de fixtures. Vérifier disparition après actualisation. Avec un droit/capacité refusé, vérifier le bilan d’échec et la cause, sans message de succès inconditionnel. Pour un dossier, contrôler les enfants restants : un refus peut être partiel.
4. Pendant une suppression en cours, tenter une seconde opération ; elle doit être refusée sur cet écran jusqu’au résultat. Vérifier que seuls les éléments confirmés quittent la sélection.

## Transferts

Exécuter chaque ligne pour COPY, Couper/coller et Déplacer, fichier et dossier :

| Source | Destination |
|---|---|
| Local | Local |
| USB SAF | Local |
| Local | USB SAF |
| USB SAF | USB SAF, dossier distinct |

Répéter avec source panneau 1/destination panneau 2, puis l’inverse. Tester collage manuel et « Copier vers l’autre panneau ». Utiliser un nom neuf à chaque essai. Vérifier destination par taille et SHA-256 ; pour les dossiers, comparer noms, types et contenu. COPY garde la source ; MOVE ne la supprime qu’après vérification. Sur refus de suppression source, le déplacement doit être affiché en erreur avec « Copie effectuée, source conservée… », pas terminé avec succès.

Relancer ensuite vers un nom déjà présent : conflit attendu, aucun écrasement. Tester une destination en lecture seule et un accès refusé. Un dossier dans lui-même ou son descendant doit être refusé. Une erreur d’alias opaque peut laisser une entrée vide ; ne pas la prendre pour une copie réussie.

## Annuler et recommencer

Annuler pendant préparation, écriture puis vérification, séparément pour COPY et MOVE. Attendre l’arrêt effectif. Noter UUID, phase, octets et diagnostic de nettoyage. La source ne doit pas être supprimée avant vérification ; une suppression de dossier déjà engagée peut être partielle.

Contrôler les entrées partielles. Recommencer d’abord avec un nom neuf, clé toujours branchée : nouvel UUID, zéro octet initial, lecture depuis le début. Tester séparément le même nom pour constater un éventuel conflit. Aucun mécanisme de reprise automatique n’est proposé. Refaire après reconnexion et comparer les temps en distinguant copie et vérification. Une différence de vitesse seule ne prouve pas la réutilisation d’une progression.

## ES Explorer et reconnexion

Faire un accès Bonobo réussi au dossier factice. Ouvrir le même dossier dans ES Explorer, revenir dans Bonobo, actualiser. En cas d’échec : retour à la racine, puis sélecteur USB → « Sélectionner manuellement » et réautorisation du dossier si Android le permet. Noter les résultats avant toute reconnexion physique. Ne pas forcer le montage. Si Android lui-même ne propose plus le volume, conserver le diagnostic système ; le débranchement peut rester nécessaire selon le fournisseur.

Terminer un transfert sans fermer son résultat, puis en démarrer un autre : l’ancien résultat terminé ne doit pas bloquer. Fermer le nouveau résultat, reconnecter la clé et rouvrir l’application : le résultat acquitté ne doit pas réapparaître. Vérifier séparément un résultat laissé non fermé.

## Traces sans données personnelles

Sur le poste du testeur, capturer une courte fenêtre : `adb logcat -v threadtime -s BonoboSAF > bonobo-saf-local.txt`. Arrêter avec Ctrl-C. Ce tag ne journalise que l’opération et les classes d’erreur ; relever aussi le message dans l’interface.

Si nécessaire, suivre la procédure Logcat de l’audit validé pour les processus DocumentsProvider/ExternalStorageProvider/DocumentsUI/StorageManagerService/vold. Les tags varient selon le constructeur. Conserver les captures brutes localement et ne partager qu’un extrait expurgé des chemins, noms, URI, comptes, numéros de série et UUID. Ne pas envoyer de bugreport complet ni de liste de fichiers personnels. Rapporter l’heure relative, l’action, la phase, la classe d’erreur et le message anonymisé ; ne pas attribuer la cause à ES Explorer sans preuve.
