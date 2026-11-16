# Audit du journal technique

Inspection de TechnicalJournal et de tous les appels Diagnostic.event : entrée limitée à deux enums, un UUID et trois Long optionnels. Aucun argument String, Throwable, URI ou ByteArray ; la réflexion JVM vérifie aussi la signature de record. Aucun nom, chemin ou empreinte n’est transmis par les adaptateurs. Les exceptions servent exclusivement à choisir un code contrôlé ou sont relancées inchangées dans les opérations existantes.

Le consommateur sérialise lui-même les champs ASCII puis utilise File.appendBytes sur deux noms constants dans noBackupFilesDir. Aucun appel réseau, Logcat, logger ou SDK tiers. Les événements ne passent pas par TransferEvents, Coil, SLF4J ou un moteur réseau. L’export explicite utilise uniquement le ContentResolver Android. Aucun changement de dépendance ou de permission réseau. La permission INTERNET préexistante sert aux fonctions réseau de l’explorateur.

La file est bornée à 256 tâches ; le rejet est silencieux. Le verrou pris par les producteurs ne protège aucune E/S ; le thread d’écriture et l’export utilisent un autre verrou. Les erreurs d’E/S du journal sont absorbées et aucune exception technique du journal n’est attachée aux erreurs de transfert. Les tests couvrent notamment un chemin de journal inutilisable, un export en erreur et un export bloqué pendant la saturation de file.

Les événements USB n’inspectent aucun extra du broadcast. L’état de permissions lu au premier plan est réduit à un booléen global lecture/écriture. Aucune application tierce n’est interrogée. Le diagnostic après un autre explorateur requiert une reproduction manuelle ; les traces ne permettent pas d’affirmer cette cause.

L’ancien journal de reçus usb-operation-artifacts conserve ses données nécessaires aux garanties de sécurité de la bêta 19.3 et n’est pas le journal technique. Il n’est jamais exporté ici. Les fichiers exportés explicitement ne sont pas supprimés par Effacer le journal.

Sources du lot figées dans code-sha256.json. Les tests instrumentés et le matériel restent à valider par la procédure testeur ; cet audit de code ne prétend pas démontrer les propriétés d’un fournisseur OEM.
