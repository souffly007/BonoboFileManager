# Bêta 18 — lot 1 : fiabilité et intégrité

Implémentation du premier lot autorisé, suivant AUDIT-BETA17.md et PLAN-BETA18.md. Version conservée : 1.3.0-beta17 / code 27. Aucun gestionnaire de transferts ni nouvelle interface de progression. Aucun APK ES utilisé, aucune dépendance ajoutée, aucune clé modifiée. Pas de NAS, téléphone, publication ni manipulation de données personnelles.

## Corbeille

La restauration copie avec le moteur SafFiles existant, relit la destination et compare une empreinte SHA-256 récursive incluant les noms et la structure. Elle relit aussi la source. Aucun succès n'est annoncé sans ces contrôles ; la source reste toujours dans la corbeille.

Un journal privé persistant enregistre l'intention AVANT la copie, puis un reçu vérifié APRÈS les contrôles. Les opérations sont sérialisées, y compris la suppression et le vidage depuis le repository, pour éviter une suppression concurrente pendant la restauration.

- Une nouvelle demande sur une destination déjà restaurée déclenche sa relecture : si elle est intacte, message « Déjà restauré et vérifié », aucune copie supplémentaire, même vers un autre dossier.
- Une intention interrompue dont la destination existe bloque toute répétition automatique. Une destination modifiée, inaccessible, un reçu illisible ou une source modifiée bloque également la répétition. La résolution reste manuelle ; aucune suppression automatique du résultat incertain.
- Si la destination précédente est réellement absente et la source inchangée, une restauration peut être relancée. Une erreur d'accès n'est pas assimilée à une absence.
- Les conflits refusent tout remplacement. Le moteur conserve ses créations exclusives et ses vérifications USB bêta 16.
- Chemins canoniques, liens symboliques et identifiants du fournisseur Android externalstorage sont contrôlés. Une destination dans la corbeille, un alias ou une restauration sur elle-même est refusé.
- Le parent d'origine doit exister et appartenir au stockage Android reconnu : aucun dossier de volume disparu n'est recréé automatiquement. Les anciennes entrées ambiguës restent restaurables vers un dossier choisi et reconnu.

**Limites et restriction volontaire :** les fournisseurs SAF opaques, les URI sans équivalence démontrable et certains volumes amovibles Android 26–29 sont refusés comme destinations de restauration. Choisir un dossier local reconnu. Cette restriction évite un alias non détectable ; elle réduit les destinations acceptées par la bêta 17. Les copies réussies AVANT ce journal n'ont pas de reçu : un conflit à destination est conservé, mais une ancienne copie placée ailleurs ne peut pas être retrouvée automatiquement. Une entrée ayant un résultat incertain nécessite contrôle manuel. Le journal ne fait pas de migration destructive et ne nettoie pas automatiquement les reçus anciens. La synchronisation du fichier du reçu ne garantit pas à elle seule la persistance lors d'une panne matérielle. La remise en corbeille intervolume existante n'est pas remaniée dans ce lot.

## Déplacements réseau

Les clients existants sont réutilisés. Les fichiers sont lus en flux, avec buffers bornés ; aucun fichier entier n'est conservé en mémoire. Le téléchargement réseau vers local utilise un temporaire privé, la copie sécurisée SafFiles, une relecture SHA-256 locale, une nouvelle lecture distante et une dernière relecture de la destination avant la phase de suppression. Les erreurs conservent le résultat local éventuel et empêchent la suppression lorsque la vérification échoue. Le presse-papiers reste disponible sur erreur.

| Route/protocole | Contrôle avant suppression | Limites |
| --- | --- | --- |
| SMB → SMB, fichiers et dossiers | Source initiale, destination complète, source relue, destination relue ; empreinte récursive, rejet des points de réanalyse, annulation dans les boucles | Pas de transaction atomique sur un arbre ; concurrence après contrôle possible ; une suppression récursive peut réussir partiellement |
| SMB → local, fichiers | Relectures locale/distante, puis dernière relecture SMB sur poignée de lecture sans partage d'écriture ; suppression fichier uniquement | La poignée doit être fermée avant suppression ; fenêtre de concurrence résiduelle |
| SFTP → local, fichiers | Relectures locale/distante, taille attendue, dernière ouverture SFTP et empreinte ; lstat impose un fichier ordinaire | SFTP ne fournit pas ici de DELETE conditionnel sur une version ; fenêtre entre lecture et rm |
| WebDAV → local, fichiers | Relectures, dernier GET sans cache, empreinte et ETag fort ; DELETE avec If-Match | ETag absent/faible : déplacement refusé. Réponse 412 : échec. Dépend du respect des préconditions par le serveur. Redirection du GET final refusée |

Une réponse positive au transfert ne constitue jamais la preuve d'intégrité. Les erreurs de vérification ou l'annulation avant la phase destructive préservent la source. Une coupure PENDANT une suppression peut rendre son résultat inconnu ; on ne garantit pas alors que la source existe encore. Les lectures et suppressions ne constituent pas une transaction distribuée et la modification par un autre client reste possible pour SMB/SFTP. L'annulation est contrôlée autour des lectures ; une lecture réseau bloquée reste dépendante des délais du client. Le coût de plusieurs relectures est assumé pour ce lot.

Les transferts de dossiers réseau vers local restent non pris en charge ; les copies/déplacements internes SFTP/WebDAV restent indisponibles. Les uploads d'édition existants ne deviennent pas un nouveau moteur de copie. Aucune reprise après interruption n'est annoncée. FTPS conserve son routage existant avec les relectures communes pour la sortie locale ; pas de nouvelle garantie de suppression conditionnelle FTPS.

## Fichiers et validation

La liste exacte des 16 fichiers source modifiés/ajoutés, calculée par rapport au début de ce lot (le dépôt contenait déjà des modifications), est dans [FICHIERS.txt](validation/beta18-lot1/FICHIERS.txt).

- Corbeille : TrashRestorer, RestoreReceipts, StorageEquivalence, LocalIntegrityAccess, SafFiles, FileRepositoryImpl et message existant MainViewModel.
- Réseau : IntegrityVerifier, RemoteFileRepository, SmbClientRepository, SftpClientRepository, WebDavClientRepository, délégation RemoteViewModel.
- Tests : Beta18IntegrityTest, WebDavIntegrityTest, SafIntegrationTest.

Les [résultats réellement exécutés](validation/beta18-lot1/RESULTATS.md) distinguent JVM, compilations, Lint et instrumentation non exécutée. Les nouveaux tests JVM couvrent concurrence, redémarrage du journal, doublons, intentions interrompues, annulation, conflits, reçu illisible, alias, corruption de même taille, troncature, erreurs de relecture et changement de source. Quatre tests WebDAV utilisent un serveur HTTP isolé sur loopback : ETag fort/If-Match, corruption, ETag absent/faible, échec conditionnel 412. Les cas SMB/SFTP incomplets testent le vérificateur partagé avec des accès simulés ; ils ne remplacent pas une intégration de ces clients avec leurs serveurs. Le test Android supplémentaire refuse un alias SAF vers la corbeille et un fournisseur indéterminé ; la fixture existante de restauration reçoit explicitement une équivalence de test.

## Testeur : après validation de ce lot

Utiliser uniquement des fichiers jetables dans un dossier isolé. Restaurer un fichier puis un dossier ; redemander la restauration, y compris vers un autre dossier : aucun doublon. Modifier la copie : erreur et original conservé. Tester conflit, parent disparu et ancienne entrée vers un dossier local choisi. Interrompre une restauration : conserver les données et contrôler la destination avant toute autre action. Tester un déplacement réseau sur un serveur de laboratoire : corruption/troncature ou erreur de relecture ne doit pas supprimer la source ; WebDAV sans ETag fort doit refuser le déplacement. Ne pas utiliser le NAS réel. Les essais Android et SMB/SFTP réels restent à réaliser avant validation de terrain.

Arrêt après le lot 1. Le lot 2 n'est pas commencé.
