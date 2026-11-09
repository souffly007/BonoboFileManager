# Bêta 18 — lot 2 : gestionnaire de transferts

Le gestionnaire commun est implémenté pour les copies et déplacements existants. Le travail suit PLAN-BETA18.md et conserve les protections du lot 1. La version reste 1.3.0-beta17 / code 27. Les contraintes des lots précédents sont conservées : aucun autre projet/application modifié, aucun NAS réel, aucune installation sur téléphone, aucune publication, aucune nouvelle clé ou dépendance issue d'ES Explorer. Les tests utilisent uniquement des fixtures isolées.

## Fonctionnement et interface

Les deux explorateurs partagent le même TransferManager et son StateFlow. Un seul lot est accepté à la fois ; chaque racine est traitée séquentiellement. Un lancement supplémentaire est refusé jusqu'à la fin réelle du travail, y compris son nettoyage et l'écriture du journal. La destination et les sources sont capturées au lancement : naviguer ensuite ne les change pas. Les opérations sont indépendantes de la durée de vie d'un écran/ViewModel.

Un panneau Material 3 commun affiche le nom courant, source et destination, états, fichiers terminés/restants lorsque leur total est connu, éléments traités, volume copié/total, pourcentage de copie calculable, débit mesuré et ETA de copie uniquement après plusieurs secondes de débit suffisamment stable. Les relectures ne sont pas comptées comme de nouveaux octets transférés. Un pourcentage de copie à 100 % ne termine pas le travail : les phases vérification et suppression éventuelle restent distinctes. Le débit et l'ETA sont masqués pendant préparation, vérification, suppression et annulation.

Les détails montrent les octets du fichier courant, sa taille connue, le dossier destination et les résultats individuels. Les couleurs, typographies et formes suivent LIGHT, DARK, CYANOGEN et SYSTEM/dynamique. Une présentation compacte en paysage garde les informations détaillées accessibles. Le panneau ne bloque pas la navigation ; les actions de modification/suppression des explorateurs sont refusées pendant un transfert actif. Les actions de sélection réseau utilisent une barre séparée pour les écrans étroits.

Les sélections multiples locales existantes sont raccordées. Des cases de sélection réseau permettent de constituer un lot sur les routes prises en charge. Les succès sont retirés du presse-papiers ; les erreurs et opérations interrompues y restent. Les racines locales équivalentes et leurs descendants déjà sélectionnés sont exclus ; les identifiants SAF sont parcourus comme des identifiants opaques, sans supposer une structure de chemin. SMB normalise les séparateurs et exclut les descendants déjà couverts ; il ne fusionne pas deux sélections différant seulement par la casse sans preuve d’équivalence. Si le lot réduit réussit, les racines couvertes sont signalées sans seconde copie.

L’inspection SMB réutilise le refus de jonctions/points de réanalyse du vérificateur du lot 1, avant de descendre dans un dossier. Le comptage commun est itératif et refuse les arbres au-delà de 128 niveaux ; cette limite évite une récursion excessive des moteurs existants. Les totaux sont établis pendant la préparation, avec des compteurs Long et détection du débordement. Ils restent inconnus si une inspection/une taille n'est pas disponible. Les événements des moteurs permettent de compter les fichiers réellement copiés dans les dossiers ; si la taille ou le nombre diffère de la préparation, les totaux correspondants deviennent inconnus. Un dossier n'incrémente les fichiers terminés qu'après son résultat final vérifié ; les dossiers eux-mêmes sont comptés comme éléments sélectionnés, pas comme fichiers.

## Moteurs et capacités réelles

| Route | Copie/déplacement proposés | Vérification/protection |
| --- | --- | --- |
| Local ↔ local | Fichiers et dossiers, simples/multiples | SafFiles existant, création exclusive, relecture et comparaison avant suppression |
| Local/USB SAF | Routes existantes, fichiers et dossiers sous les restrictions ci-dessous | SafFiles et contrôles bêta 16 conservés, événements d'observation uniquement |
| SMB → SMB | Fichiers/dossiers, simples/multiples ; duplication SMB existante | Moteur du lot 1, relectures récursives, créations FILE_CREATE, rejet des points de réanalyse |
| SMB/SFTP/WebDAV → local | Fichiers simples/multiples | copyOrMoveToLocal du lot 1 : cache privé, copie sécurisée, relectures et suppression vérifiée par protocole |
| FTPS → local | Route fichier existante réutilisée | Relectures communes ; pas de nouvelle garantie de suppression conditionnelle |
| SFTP/WebDAV → eux-mêmes | Non proposés | Moteur existant ne prend pas en charge cette route |
| Réseau → SAF, local → réseau, interconnexions | Refusés | Aucune capacité artificielle ou upload d'édition détourné |
| Dossiers réseau → local hors moteur existant | Refusés | Aucun remplacement par une implémentation non vérifiée |

Aucun remplacement/fusion silencieux : le conflit est signalé pour l'élément et le lot poursuit les autres éléments. Pour le traiter, choisir une autre destination et relancer les éléments restants, ou retirer les éléments concernés du presse-papiers. La duplication sous un nom neuf reste celle du moteur SMB existant ; il n'y a pas de dialogue de remplacement ni de politique générale « conserver les deux » dans ce lot.

**Restriction SAF explicite :** le gestionnaire refuse une destination SAF dont l'équivalence locale ne peut pas être démontrée. Pour un dossier, une combinaison local/SAF sans équivalence sûre est également refusée. Cela conserve la prudence du lot 1 et peut refuser un fournisseur opaque ou un volume amovible Android 26–29. L'accès USB direct du moteur bêta 16 n'est pas supprimé, mais toutes ses routes opaques ne sont pas proposées par ce gestionnaire. Les restaurations de corbeille restent dans leur mécanisme dédié ; le journal de restauration et ses refus ne sont pas contournés.

## Annulation et intégrité

La demande passe immédiatement à « Annulation demandée », mais le résultat « Annulé » attend le retour réel du moteur et la fermeture de ses ressources. Une lecture réseau/native bloquée peut attendre les délais de son client. Le lot n'engage plus les éléments suivants. Le résultat courant est signalé comme interrompu/à contrôler ; les éléments non démarrés gardent leurs sources. Les éléments déjà terminés restent terminés : pas de rollback global.

Les moteurs conservent leurs points de contrôle avant suppression. Une destination incomplète ou non vérifiée n'autorise jamais la suppression. L'observation de progression ne remplace aucune preuve d'intégrité. Les erreurs de transfert/vérification gardent les sources ; une erreur pendant une suppression peut laisser un résultat inconnu ou partiel, et n'est jamais présentée comme un succès.

Le flux distant est compté lors du téléchargement initial seulement. La recopie privée vers la destination apparaît comme préparation de la destination ; les relectures apparaissent comme vérification. Aucun fichier entier n'est chargé en mémoire. Les buffers existants restent bornés à 64–256 Kio ; les temporaires du moteur réseau restent sur disque privé et sont nettoyés par son finally.

## Service et journal

ManagedTransferService est un hôte dataSync non exporté, distinct de l'ancien téléchargement vers MediaStore/document choisi. Il réutilise les permissions déjà présentes et les conventions de notification/WakeLock du projet. Ses notifications ne contiennent que des compteurs, sans nom de fichier, URI ou secret ; aucun secret n'est passé dans un Intent. Il est non redémarrable automatiquement (START_NOT_STICKY). Le timeout système demande l'annulation ; les limites Android et les appels bloqués restent applicables. Le téléchargement distant ancien et un lot géré ne sont pas lancés simultanément depuis l'interface.

Le journal est un **résumé privé du dernier lot**, versionné, publié atomiquement lorsque le système de fichiers le permet (sinon remplacement privé ordinaire), avec synchronisation du fichier. Au redémarrage, un résumé actif devient « interrompu » ; aucun moteur, copie ou effacement n'est rejoué. Un journal illisible donne également une indication de contrôle manuel. Il conserve compteurs/état, sans noms, chemins, URI, hôtes ou identifiants d'authentification. Les détails individuels restent en mémoire pendant le processus ; l'historique détaillé persistant prévu comme extension du plan n'est pas livré ici.

Choix d'intégration : utiliser ce petit journal privé plutôt qu'une migration Room pour ce lot. Le schéma, les migrations, favoris et connexions existants restent intacts. Cela apporte le signalement d'interruption demandé sans introduire de migration destructive. Ce journal ne constitue ni une preuve physique universelle de durabilité ni un mécanisme de reprise par offset. Les derniers résultats en mémoire sont conservés pour permettre au ViewModel d'appliquer le bon résultat à son presse-papiers, même si un autre lot a démarré.

## Validation et limites

Les [résultats automatisés](validation/beta18-lot2/RESULTATS.md) et les journaux distinguent les tests exécutés des tests seulement compilés. La [liste exacte des fichiers applicatifs](validation/beta18-lot2/FICHIERS.txt) est calculée par comparaison au début de ce lot, pas avec le HEAD ancien du dépôt déjà modifié.

Les limitations du lot 1 restent : fenêtre de concurrence SMB/SFTP entre relecture et suppression ; suppression récursive non transactionnelle ; dépendance WebDAV à un ETag fort et au respect d'If-Match ; résultat de suppression potentiellement inconnu après coupure ; refus de fournisseurs/alias SAF indémontrables ; anciennes restaurations sans reçu non identifiables partout. Le gestionnaire ne transforme pas ces garanties en transaction distribuée.

La découverte conserve des listes de métadonnées par dossier et des identifiants visités pour les cycles : elle ne charge pas les contenus, mais les très grands dossiers restent une limite des listages existants. Les erreurs sont rapportées par racine sélectionnée ; un échec dans un dossier peut arrêter cette racine et laisser un dossier partiel. Les enfants ne sont pas des travaux indépendants reprenables. Les noms/nombres de fichiers ne sont pas persistés dans le résumé ; contrôler manuellement sources/destinations après fermeture du processus. Une opération extérieure ou déjà engagée peut toujours modifier le stockage : la sérialisation du gestionnaire ne verrouille pas le système de fichiers contre toutes les applications.

Le fonctionnement Android du service, les permissions SAF, la rotation et les thèmes n'ont pas été validés en exécution faute d'émulateur fonctionnel. Les nouveaux tests sont compilés, pas déclarés réussis. Les intégrations SMB/SFTP réelles et le débit sur clé physique restent une recette de laboratoire ultérieure. Aucun nouveau protocole n'est prétendument validé par une simulation JVM.

## Procédure courte de recette

1. Dans un dossier jetable, créer deux fichiers et un dossier avec plusieurs enfants. Sélectionner/copier puis coller dans un dossier neuf. Vérifier noms, compteur de fichiers, progression et phase de vérification ; aucun blocage de navigation.
2. Refaire en déplacement avec un conflit de nom sur un élément. Le conflit ne remplace rien ; chaque succès doit avoir sa destination vérifiée et l'élément en erreur doit garder sa source et rester dans le presse-papiers.
3. Annuler un fichier volumineux, puis une vérification. Le panneau doit attendre l'arrêt réel ; aucun prochain élément ne doit démarrer. Contrôler les destinations partielles éventuelles.
4. Changer de dossier, de panneau et tourner l'écran pendant la copie. La destination initiale reste utilisée. Vérifier LIGHT, DARK, CYANOGEN et SYSTEM, détails et sélection réseau sur écran étroit.
5. Sur serveur de laboratoire uniquement, copier/déplacer plusieurs fichiers SMB/SFTP/WebDAV vers local. WebDAV sans ETag fort refuse le déplacement ; troncature/corruption ou erreur de relecture doit conserver la source. Ne pas utiliser le NAS réel.
6. Arrêter le processus pendant une opération de test, puis relancer : indication d'interruption et aucune reprise/suppression automatique. Contrôler sources et destinations avant tout nouvel essai. Retester la restauration répétée de corbeille : pas de doublon automatique.

Arrêt après implémentation et validation automatisée du lot 2 ; aucune publication ni installation effectuée.
