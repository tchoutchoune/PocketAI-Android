# PocketAI Android 4

Assistant Android avec modèles de langage locaux, choix du modèle et réglages automatiques du matériel. Les réponses affichent le Markdown ; les métadonnées techniques et les blocs de raisonnement restent hors du chat.

## Installer et commencer

- **Android 13 ou supérieur, téléphone ARM64** (`arm64-v8a`).
- Dans les [Actions GitHub](https://github.com/tchoutchoune/PocketAI-Android/actions), ouvrir un build réussi de **Build PocketAI 4**, télécharger l’artefact **PocketAI-4.0-arm64-debug**, extraire le ZIP et installer `PocketAI-4.0-arm64-debug.apk`. GitHub peut demander une connexion pour télécharger les artefacts.
- Autoriser l’installation depuis la source choisie si Android le demande. Cet APK est un build de développement signé avec une clé de débogage.

La version 4 utilise le paquet **`com.pocketai.app`** et peut cohabiter avec l’ancienne version 3. Ses modèles et conversations ne sont pas transférés automatiquement : **réimporter les fichiers GGUF** dans l’onglet **Modèles**, puis choisir **Charger**. Conserver la version 3 le temps de récupérer les données souhaitées.

L’onglet **Chat** permet d’envoyer une question, d’arrêter une opération et de commencer une nouvelle conversation. Les conversations et créations sont conservées sur le téléphone. **Décharger** un modèle libère sa mémoire ; **Supprimer** retire son fichier local.

## Choisir le modèle et les performances

Le catalogue contient les GGUF officiels Qwen en quantification **Q4_K_M** :

| Modèle | Téléchargement | Usage conseillé | Licence |
| --- | ---: | --- | --- |
| Qwen 2.5 Instruct 0,5B | 491 Mo | Commencer rapidement, appareils modestes | [Apache 2.0](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/blob/main/LICENSE) |
| Qwen 2.5 Instruct 1,5B | 1,12 Go | Équilibre entre rapidité et qualité | [Apache 2.0](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/blob/main/LICENSE) |
| Qwen 2.5 Instruct 3B | 2,10 Go | Qualité, davantage de RAM disponible | [Qwen Research, conditions spécifiques](https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/blob/main/LICENSE) |

Les tailles utilisent les unités décimales. Les téléchargements sont liés à des révisions précises ; leur taille, l’en-tête GGUF et le **SHA-256 officiel** sont vérifiés avant installation. Les références et empreintes figurent dans [ModelRepository.kt](app/src/main/java/com/pocketai/app/ModelRepository.kt). Les copies interrompues sont supprimées et un import ne remplace pas silencieusement un fichier existant. Les modèles ne sont pas inclus dans l’APK ; consulter leur licence avant utilisation, notamment les restrictions de la version 3B.

Un GGUF personnel peut être importé. Son en-tête et sa taille sont contrôlés, mais son origine et son intégrité ne bénéficient pas de la vérification du catalogue. Sa compatibilité dépend des architectures prises en charge par le moteur.

Dans **Réglages**, choisir **Automatique · équilibré**, **Performances**, **Autonomie** ou **CPU uniquement**. La RAM disponible règle le contexte et les lots ; les cœurs CPU orientent le nombre de threads. Le chargement refuse les budgets mémoire insuffisants. Les réglages de profil s’appliquent au prochain chargement ; la chaleur réduit les threads pendant une génération.

La déclaration Vulkan d’Android permet de tenter l’accélération GPU. Le moteur vérifie ensuite le backend et peut revenir au CPU si le GPU ou ses allocations échouent. **Voir le matériel et le moteur** indique le backend réellement actif, les couches GPU, les threads et les mesures de génération. La présence de Vulkan ne garantit pas un gain de vitesse sur chaque téléphone.

## Fichiers, images, vidéos et Internet

**Créer** permet de demander au modèle local le contenu d’un fichier : texte, Markdown, code, JSON, CSV ou HTML selon le nom choisi. Chaque réponse du chat peut aussi être exportée en **texte, Markdown, HTML, JSON, CSV ou PDF**. Les exports JSON/CSV contiennent le texte de la réponse ; le PDF conserve ce texte. Enregistrer, partager ou supprimer une création depuis sa fiche. Les fichiers restent accessibles après redémarrage ; l’historique des créations est limité à **100 fichiers et 512 Mo**.

Les services en ligne sont **facultatifs et inactifs par défaut**, sans clé préinstallée :

| Fonction | Configuration dans Réglages | Données transmises |
| --- | --- | --- |
| Compléter une réponse avec le web | Clé **Brave Search API**, puis activer le bouton Web du chat | Question recherchée ; les sources retournées accompagnent la réponse |
| Générer une image | Clé d’un fournisseur compatible avec l’API images **OpenAI**, URL HTTPS et modèle | Description de l’image |
| Générer une vidéo | Clé **fal.ai** et modèle compatible ; Wan vidéo configuré par défaut | Description de la vidéo |

Images et vidéos sont générées par les fournisseurs, puis réellement téléchargées avant d’être proposées à l’enregistrement. Elles ne sont pas générées localement par le GGUF. Les services peuvent facturer les requêtes selon leurs conditions ; une annulation vidéo est demandée au fournisseur sans garantie d’arrêt d’un travail déjà lancé. Le chat local fonctionne sans ces services une fois le modèle installé.

Les clés restent sur l’appareil, chiffrées avec **AES-GCM et Android Keystore**. Les sauvegardes automatiques de l’application sont désactivées. Les journaux locaux, limités et rotatifs, contiennent le matériel, les paramètres et les erreurs techniques ; ils excluent le texte des conversations et masquent les clés et paramètres d’URL. **Exporter les logs de débogage** crée un fichier à enregistrer ou partager explicitement. Désinstaller l’application efface ses modèles, conversations, créations et paramètres privés.

## Construire et vérifier

Sur le cloud **Linux x86_64 Debian trixie**, depuis le checkout existant :

```bash
bash scripts/setup.sh
bash scripts/build.sh
```

Le script de préparation installe dans le répertoire d’outils le **JDK 17**, le **SDK Android 36**, les Build Tools **35.0.0 / 36.0.0**, le **NDK 29.0.13113456**, **CMake 3.31.6** et les outils Vulkan issus des paquets Debian vérifiés. Il prépare llama.cpp à la révision figée **`db00347a4b33393bf53986e976fe8505bbf92665`** et préserve un checkout déjà présent. Le wrapper utilise **Gradle 8.14.3**.

`scripts/build.sh` construit l’APK ARM64 avec CPU et Vulkan, lance les tests unitaires de `app` et `lib`, puis contrôle le ZIP, la signature, le paquet et les bibliothèques natives. Résultats :

- `out/PocketAI-4.0-arm64-debug.apk` et `out/SHA256.txt` ;
- preuves de vérification dans `out/` ;
- rapports dans `app/build/reports/tests/` et `lib/build/reports/tests/`.

Pour une chaîne Android/JDK existante, fournir `JAVA_HOME` et `ANDROID_HOME` ou `ANDROID_SDK_ROOT`, ainsi que `POCKETAI_LLAMA_DIR` et `POCKETAI_VULKAN_DIR` si nécessaire. `POCKETAI_TOOLS_DIR` change le répertoire des outils. Les compilations utilisent quatre workers par défaut. Le [workflow GitHub](.github/workflows/build-native.yml) prépare les dépendances, construit, teste et publie le ZIP de l’APK avec ses preuves et un artefact séparé de rapports de tests ; les artefacts expirent après 14 jours.

La compilation et les tests logiciels ne remplacent pas la validation sur téléphone : **le fonctionnement du GPU, les gains de performance et les appels aux API payantes restent à vérifier sur un appareil réel avec les comptes concernés**.

Les avis de licence du code natif et de l’adaptation du wrapper llama.cpp figurent dans [LICENSE](LICENSE) et [NOTICE](NOTICE). Les modèles et les fournisseurs conservent leurs propres licences et conditions.
