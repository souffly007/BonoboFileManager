# Bonobo Files Explorateur 1.3.0 beta10d

Correctif serveur SFTP Android :
- Apache MINA SSHD aligné sur 2.19.0 (common/core/sftp) ;
- user.home Android initialisé vers le dossier privé de l’application ;
- règles R8/ProGuard MINA SSHD ajoutées ;
- message d’erreur SFTP affiche désormais la cause racine réelle ;
- client SFTP, SMB, lecteurs et interface conservés.

## 1.3.0-beta12c — consolidation SMB
- SMB : le champ Partage accepte désormais un partage seul (`Documents`) ou un partage + dossier de départ (`c/Users/Nico/Desktop`).
- SMB : tri Nom / Date / Taille, ascendant/descendant, dossiers toujours séparés et placés en premier.
- SMB : favoris de dossiers réseau par connexion, accessibles depuis l'icône marque-page.
- SMB : correction de « Mettre ici » : le chemin courant complet est transmis et normalisé au format SMB avant création de la cible.
- Copier / Déplacer / Dupliquer restent disponibles sur SMB.
