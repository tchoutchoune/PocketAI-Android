# Diagnostic d'installation Android

Ce petit APK permet d'obtenir le résultat réel de `PackageInstaller` lorsque
l'installateur du téléphone affiche seulement « Appli non installée ». Il utilise
l'installation Android normale avec confirmation de l'utilisateur. Il ne corrige
pas un problème avant d'en connaître la cause.

1. Installer et ouvrir **PocketAI · diagnostic installation**.
2. Choisir l'APK PocketAI 4 déjà téléchargé.
3. Appuyer sur **Installer et obtenir le résultat** et autoriser cette application
   comme source d'installation, si Android le demande.
4. Confirmer avec l'installateur Android.
5. Revenir au diagnostic et copier `STATUS`, `LEGACY_STATUS` et `STATUS_MESSAGE`.

Le résultat, l'identifiant/version de l'APK, son SHA-256 et les empreintes publiques
de signature sont conservés uniquement dans le stockage privé de cette application.
Elle n'a aucune permission réseau. Elle accepte uniquement le paquet
`com.pocketai.app`, copie au maximum 128 Mo et ne désinstalle aucune application.
Une installation qui réussit installe normalement l'APK PocketAI sélectionné.

Le module Java n'utilise ni bibliothèques natives ni AndroidX. Son identifiant
`com.pocketai.installcheck` permet de l'installer indépendamment de PocketAI 3 et 4.

```bash
./gradlew :installer-diagnostic:assembleDebug :installer-diagnostic:testDebugUnitTest
```
