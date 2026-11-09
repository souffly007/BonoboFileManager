Bonobo Files Explorateur 1.3.0-beta10c

Réseau :
- SMB client conservé
- SFTP client conservé
- retour du partage du téléphone en serveur SFTP/SSH
- serveur SFTP : port 2222, utilisateur bonobo, mot de passe aléatoire à chaque démarrage
- dossier partagé : Téléchargements/Bonobo-Partage
- clé hôte persistante dans le stockage privé de l'application
- aucune ouverture automatique de port Internet

Gradle : dépendances SSHJ + Apache MINA SSHD et versions BouncyCastle forcées selon la configuration validée fournie.

Note : compilation Gradle non exécutable dans l'environnement de génération, car le wrapper Gradle 8.9 tente d'accéder à services.gradle.org sans accès réseau.
