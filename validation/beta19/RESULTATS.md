# Validation bêta 19 — 12 octobre 2026

Validation finale : BUILD SUCCESSFUL. JDK jbr-21.0.11, Gradle du projet, dépendances locales en mode offline, deux workers.

Commande :

```sh
JAVA_HOME=/var/home/Snoopy63/.jdks/jbr-21.0.11 ./gradlew --offline --max-workers=2 testDebugUnitTest testReleaseUnitTest assembleDebug assembleRelease assembleDebugAndroidTest lintDebug lintRelease
```

- JVM Debug : 93 tests, 0 échecs, 0 erreurs, 0 ignorés.
- Lint Debug : 0 erreur(s), 92 avertissement(s).
- JVM Release : 93 tests, 0 échecs, 0 erreurs, 0 ignorés.
- Lint Release : 0 erreur(s), 90 avertissement(s).

- APK Debug et Release compilés ; Release sans configuration de signature.
- Contrôle de non-signature : apksigner verify retourne 1, « DOES NOT VERIFY / Missing META-INF/MANIFEST.MF », attendu pour cet artefact. Inspection ZIP : aucun certificat/signature JAR ni bloc de signature APK v2/v3. Aucun accès aux clés Release ; Debug utilise la signature de développement habituelle de Gradle.
- Tests Android compilés dans assembleDebugAndroidTest ; aucun test instrumenté exécuté.
- APK livré : `app/release/bonobo-files-explorateur-1.3.0-beta19-unsigned.apk` (25084534 octets).
- SHA-256 : `f9b43b7c093d6679f9382b45cc356a91381b444d7e5f3f9812ab396adea84208`.

Rapports JVM et Lint copiés dans ce dossier. Lint conserve des avertissements existants (dépréciations, versions et autres recommandations) ; aucune erreur bloquante. Les tentatives intermédiaires ont rencontré une compilation réalisée pendant des changements de sources puis une indentation Lint ; ces défauts sont absents de la validation finale. Le premier lancement sans JDK configuré et le cache Gradle en lecture seule ont été corrigés par l'environnement JDK et l'autorisation d'écriture du cache.

Tests bêta 19 JVM : 5 scénarios ; Android : 2 nouveaux scénarios compilés (interface acquittée et restauration groupée SAF synthétique). Les suites antérieures sont conservées, notamment intégrité, transferts multiples et annulation.

Aucun smartphone, NAS, serveur réel ou fichier personnel utilisé. Aucun émulateur lancé. USB matériel, passage ES Explorer PRO, permissions persistantes réelles, gros fichiers USB et restauration SAF réelle restent non testés ; consulter README-BETA19.md. Le client FTP/FTPS est uniquement planifié dans PLAN-CLIENT-FTP-FTPS.md et nécessite validation avant implémentation.
