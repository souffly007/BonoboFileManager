## Téléchargement seedbox — version 1.2.7 conservée

Menu distant ⋮ → **Télécharger** → **Téléchargements** ou **Choisir ailleurs…**.
Transferts FTPS et SMB en flux, progression et annulation. Un transfert à la fois.
La reprise après coupure n'est pas encore disponible : relancer depuis le début.
Lire `BonoboFileManager-1.2.7-telechargement-seedbox.txt` pour les détails,
les essais effectués et les limites. L'APK est dans le dossier `APK` du ZIP.
Les notes suivantes sont l'historique des correctifs précédents.

---

## Correctif TLS Android — version 1.2.7 conservée

La clé TLS autorise désormais la signature de condensats attendue par Android.
Une nouvelle identité TLS est générée automatiquement et vérifiée au démarrage.
Comparer sa nouvelle empreinte dans FileZilla avant d'accepter le certificat.
Lire `BonoboFileManager-1.2.7-correction-tls-android.txt` en premier.
Les notes qui suivent décrivent les correctifs précédents.

---

## Correctif FTPS — toujours en version 1.2.7

Le refus FTP `421 Maximum login limit has been reached` est corrigé.
Le serveur utilise maintenant **FTPS explicite avec TLS obligatoire**, port 2121.
Dans FileZilla : FTP → Exiger une connexion FTP explicite sur TLS → mode Passif.
Comparer l'empreinte SHA-256 du certificat avec celle affichée dans Bonobo.

Lire `BonoboFileManager-1.2.7-correction-ftps.txt` pour l'installation, les tests
et les limites de validation. Le ZIP inclut l'APK corrigé dans `APK`.
Les rapports antérieurs ci-dessous décrivent les étapes historiques du projet.

---

## Version 1.2.7 — interface, miniatures et favoris

- Paramètres : curseur de taille Compact → XXL, aperçu et choix de thème simplifié.
- Explorateur : grandes miniatures photo/vidéo, grille verticale et icônes de dossiers compactes.
- Icônes harmonisées dans les thèmes Clair, Sombre et CyanogenMOD.
- Favoris : uniquement les dossiers marqués ; recherche limitée aux favoris.
- À propos : présentation compacte et numéro de version réel.

Voir `Modifications-BonoboFileManager-1.2.7.txt` pour les détails et les vérifications.

L'APK fourni dans le dossier `APK` du ZIP complet est une version de test distincte,
**Bonobo Aperçu** (`fr.bonobo.filemanager.preview`). Elle peut coexister avec
l'application habituelle et possède ses propres paramètres, favoris et coffre.
Pour une mise à jour de l'application habituelle, compiler la variante **release**
et la signer avec votre clé habituelle. Ne pas désinstaller l'ancienne application
pour essayer cet APK.

Compilation des sources : JDK 17, Android SDK 35 et Build Tools 35.0.0,
puis `./gradlew testDebugUnitTest assembleDebug` (ou `gradlew.bat` sous Windows).

---

## Version 1.2.6 — corrections après revue de la 1.2.5

Le détail des changements et des vérifications figure dans `Corrections-BonoboFileManager-1.2.6.txt`.

- Coffre : importation chiffrée dans le stockage privé, accès par mot de passe,
  verrouillage en arrière-plan et exportation explicite d'une copie déchiffrée.
- Ancien coffre : bouton « Récupérer l'ancien coffre ». Les originaux sont conservés.
  Vérifier les nouvelles copies avant de supprimer les fichiers encore en clair.
- Le coffre privé est supprimé à la désinstallation et n'est pas sauvegardé automatiquement.
  Conserver le mot de passe et des copies de secours adaptées.
- FTPS : dossier `Téléchargements/Bonobo-Partage` uniquement. Les connexions FTP classiques non chiffrées sont désactivées.
  ne chiffrent pas le transport : utiliser un réseau de confiance.
- Les fichiers existants ne sont plus remplacés silencieusement par les opérations corrigées.
- Les sauvegardes d'applications multi-APK sont refusées plutôt que produites incomplètes.

Les nouveaux fichiers du coffre utilisent AES-GCM et le mot de passe maître ; la biométrie
seule a été retirée de ce parcours, faute de clé cryptographique associée.
Les anciens fichiers AES-CBC ne sont pas convertis automatiquement.

---

-------------------------------------------------------------
🐵 BONOBO EXPLORATEUR 🦍 - "DonkeyKong Edition"
-------------------------------------------------------------
猿 Simple, Rapide, Puissant 猿
-------------------------------------------------------------

🚀 LA NAISSANCE D'UN PROJET
- Né de la volonté de créer un outil de gestion de fichiers à la fois simple,
puissant et respectueux de la vie privée, "Bonobo Explorateur" a été conçu
comme un véritable couteau suisse pour Android.

Fini les applications encombrées de publicités ! Ici, l'accent est mis
sur l'ergonomie (Material 3) et la performance brute.

👤 CRÉATEUR
by DonkeyKong 🦍 (humour et clin d'oeil au game)

![Android](https://img.shields.io/badge/Android-8.0%2B-green?logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-blue?logo=kotlin)
![License](https://img.shields.io/badge/License-GPL%20v3-orange)
![Version](https://img.shields.io/badge/Version-1.2.7-cyan)


------------------------------------------------------------
💎 LES FONCTIONNALITÉS PRO
------------------------------------------------------------

📂 GESTION DE FICHIERS COMPLÈTE
   - Naviguer, copier, déplacer, renommer.
   - Création instantanée de dossiers et de fichiers textes.
   - Sélection multiple (appui long) pour des actions groupées.

🛡️ SÉCURITÉ & PROTECTION
   - Cryptage AES-256 : Protégez vos fichiers sensibles par mot de passe (format .crypt).
   - Corbeille Intégrée : Ne perdez plus rien par erreur, restaurez vos fichiers en un clic.

🌐 CONNECTIVITÉ SANS LIMITES
   - Serveur FTPS : Accédez aux fichiers de votre téléphone depuis votre PC sans câble.
   - Client FTPS Distant : Connectez votre Seedbox, NAS ou serveur personnel.
   - Import FileZilla : Importez vos configurations de serveurs via fichier XML.

⚡ OPTIMISATION & NETTOYAGE
   - Nettoyeur Intelligent : Détecte les fichiers .tmp, .log et les dossiers vides.
   - Gestionnaire d'Applications : Listez vos apps, désinstallez-les ou sauvegardez leurs APK.
   - Analyse du stockage : Visualisez l'espace utilisé en un coup d'œil.

🎬 MULTIMÉDIA INTÉGRÉ
   - Visionneuse d'images : Slide (balayage) fluide entre les photos d'un dossier.
   - Lecteur Vidéo : Support des formats modernes (MP4, MKV...) en plein écran.
   - Lecteur Audio : Interface dédiée avec contrôles de lecture pour votre musique.

🎨 INTERFACE MODERNE
   - Design Material 3 avec support des couleurs dynamiques (Android 12+).
   - Dashboard (Accueil) organisé : Accès direct aux téléchargements, images, etc.
   - Tri mémorisé : L'app se souvient de vos préférences (Nom, Taille, Date).

------------------------------------------------------------
📜 Licence
Ce projet est sous licence GPL v3 — voir le fichier [LICENSE](LICENSE) pour plus de détails.

------------------------------------------------------------

🖐️ Merci de soutenir le projet !

👤 Auteur : souffly007 (Franck R.-F.)
