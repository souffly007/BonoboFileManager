# Bonobo Files Explorateur 1.3.0-beta19.5 — corrections USB

Version **1.3.0-beta19.5**, `versionCode 34`. Cette bêta applique la phase B de [l’audit des sept journaux 19.4](AUDIT-BETA19.5-LOGS.md). Les hypothèses de l’audit ne sont pas présentées comme des causes confirmées.

## Corrections

- **Annulation et reprise :** les créations locales et SAF sont suivies par reçus privés d’opération. Le nettoyage attend la sortie de la copie et la fermeture des flux, puis n’enlève que les fichiers dont l’identité, le parent, le nom et le type concordent. Les dossiers SAF restent conservés par prudence ; un dossier partiel et un nettoyage indéterminé sont affichés explicitement. Une reprise au même nom refuse le conflit et invite à choisir un autre nom ou à examiner le résidu. Aucun remplacement automatique de préexistant.
- **Suppression :** le parcours fichier/dossier de l’explorateur, y compris les menus et le double panneau, passe par des événements `DELETE_REQUEST`, `DELETE_START`, `DELETE_PROVIDER_RESULT`, `DELETE_VERIFY`, `DELETE_END`. Les opérations locales, SAF, corbeille et effacement de corbeille sont distinguées par catégories. Une suppression SAF ne devient un succès qu’après disparition observée dans un parent accessible. Le parent SAF découvert par `findDocumentPath` est désormais mémorisé après vérification de l’identité.
- **Navigation :** la réouverture d’une racine, la permission persistante de l’arbre utilisé, l’ouverture et la lecture d’un dossier, les erreurs classées du fournisseur et la nouvelle sélection SAF sont journalisées. L’absence de droit persistant est distincte d’un refus réel ; une autorisation temporaire peut encore fonctionner. Aucun correctif propre à ES Explorer n’a été introduit.
- **Déplacement USB :** la source reste conservée lorsque la destination est SAF ou amovible. Le message est « Copie vérifiée — source conservée par sécurité. Déplacement non terminé. » L’opération reste incomplète.
- **Diagnostic :** volontaire et désactivé à chaque démarrage, stocké localement dans `noBackupFilesDir`, rotation à 1 Mo, export manuel et effacement confirmé. Les nouveaux champs sont des enums, des UUID éphémères et des durées ; aucun nom, chemin, URI, message d’exception, contenu ou stack trace n’est fourni au journal. Aucun événement par bloc de copie.

## Limites

Le code refuse de supprimer automatiquement un dossier SAF même s’il semble vide : le fournisseur peut effectuer une suppression récursive sans transaction ni protection contre un ajout concurrent. Un reçu incertain demande un examen manuel. Les événements techniques sont asynchrones et peuvent être abandonnés si la file est saturée ; ils ne doivent jamais changer le résultat du transfert. Une vérification SHA-256 immédiate ne prouve pas la persistance après éjection et reconnexion. Les essais physiques restent décrits dans la [procédure testeur](validation/beta19.5/PROCEDURE-TESTEUR.md).

La validation automatisée et l’APK non signée sont documentées dans [les résultats](validation/beta19.5/RESULTATS.md). Aucune distribution n’est autorisée par cette préparation.
