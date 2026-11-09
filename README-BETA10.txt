Bonobo Files Explorateur 1.3.0 beta10

Base: beta9 SMB Documents validée par testeur.

Modifications:
- SMB: lecture audio et vidéo à distance via copie temporaire dans le cache privé puis lecteurs Bonobo existants.
- Réseau: interface recentrée sur SMB et FTPS (FTP sécurisé TLS); Clouds retirés de la navigation Réseaux.
- FTPS: fichiers distants ouvrables via cache pour les formats pris en charge; FTP non chiffré reste désactivé.
- Interface catégories: titre placé sous la barre d'icônes, centré et lisible (Images, Téléchargements, Gros fichiers, etc.).
- Build debug: suppression des suffixes .https.preview et -https-preview.
- Version: 1.3.0-beta10.

Test conseillé:
1. Compiler dans Android Studio.
2. SMB: ouvrir MP3/FLAC et MP4/MKV et vérifier lecture/retour.
3. Vérifier PDF/TXT/DOCX/ODT/XLSX/ODS/PPTX/ODP sur SMB (non-régression).
4. Vérifier les titres des catégories longues.
5. Réseaux: vérifier que seuls SMB et FTPS sont proposés dans la navigation.
6. FTPS: connexion, navigation et ouverture d'un fichier.

Note: Gradle n'a pas pu être exécuté dans l'environnement de génération car le wrapper tente de télécharger Gradle 8.9 depuis services.gradle.org, réseau indisponible ici.
