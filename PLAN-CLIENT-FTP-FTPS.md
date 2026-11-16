# Lot ultérieur — client FTP / FTPS distant

Demande clarifiée le 12 octobre 2026 : se connecter depuis Bonobo Files à un serveur distant. Aucune demande de serveur hébergé sur smartphone.

Ce lot nécessite une validation supplémentaire et n'est pas implémenté dans la bêta 19.

- FTP : protocole de transfert sans TLS ; afficher clairement le niveau de protection.
- FTPS : FTP protégé par TLS, préciser modes explicite/implicite et validation des certificats ; ne pas désactiver cette validation.
- SFTP : protocole de transfert sur SSH, distinct de FTP et FTPS ; préserver son implémentation actuelle.
- Connexions enregistrées et favoris réseau : définir configuration, navigation et protection des secrets en cohérence avec SMB, SFTP et WebDAV existants.
- Réutiliser le coordinateur de transferts, progression réelle, annulation, vérification d'intégrité et protection des sources ; préciser les capacités du serveur et les limites de vérification.
- Valider sur serveurs de test dédiés, sans NAS réel ni fichiers personnels : connexion, TLS valide/invalide, listing, téléchargements, envois, conflits, coupures, annulation et intégrité.

Avant développement : valider le périmètre des modes FTPS, authentification, stockage des secrets et favoris. Aucun serveur FTP ajouté dans la bêta 19, aucune modification des connexions SMB/SFTP/WebDAV dans ce lot correctif.
