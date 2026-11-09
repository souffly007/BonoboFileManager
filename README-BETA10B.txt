Bonobo Files Explorateur 1.3.0 beta 10b

Correction Réseau :
- Le second mode Réseau est maintenant un CLIENT SFTP/SSH, pas un serveur de partage du téléphone.
- Réseau = SMB + SFTP uniquement dans le sélecteur principal.
- SFTP : hôte/IP, port 22 par défaut, utilisateur, mot de passe.
- Navigation distante, lecture/téléchargement et ouverture via cache Bonobo.
- TXT/MD : ré-enregistrement distant SFTP pris en charge via upload du cache.
- Vérification de clé SSH par TOFU : la première clé hôte rencontrée est mémorisée, tout changement ultérieur est refusé.
- L'ancien écran serveur FTPS reste dans le code historique mais n'est plus exposé par la section Réseau.

Base : beta10 SMB Media + UI.
