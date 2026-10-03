# PocketAI Android 4

Assistant Android avec modèles GGUF locaux et catalogue de 21 modèles pour le chat, le code, la vision, les documents, la voix et les images. Les fonctions spécialisées utilisent des serveurs compatibles configurés explicitement. Les modèles ne sont pas inclus dans l’APK.

## Installer et commencer

- **Android 13 ou supérieur, téléphone ARM64** (`arm64-v8a`).
- Dans les [Actions GitHub](https://github.com/tchoutchoune/PocketAI-Android/actions), ouvrir un build réussi de **Build PocketAI 4**, télécharger l’artefact **PocketAI-4.3.0-model-hub-arm64-debug**, extraire le ZIP et installer `PocketAI-4.3.0-model-hub-arm64-debug.apk`. GitHub peut demander une connexion pour télécharger les artefacts.
- Autoriser l’installation depuis la source choisie si Android le demande. Cet APK est un build de développement signé avec une clé de débogage.

La preview utilise le paquet **`io.github.tchoutchoune.pocketai.preview`** et la même signature que la preview 4.2.3 : elle met à jour cette application en conservant ses données. Les applications d’un autre paquet restent séparées.

L’onglet **Chat** permet d’envoyer une question, d’arrêter une opération et de commencer une nouvelle conversation. Les conversations et créations sont conservées sur le téléphone. **Décharger** un modèle libère sa mémoire ; **Supprimer** retire son fichier local.

## Choisir le modèle et les performances

Le catalogue contient 11 nouveaux GGUF de texte : Qwen 3.5 2B/4B, Qwen 3 4B Instruct 2507, Ministral 3 3B, SmolLM3 3B, Gemma 4 E2B/E4B, Qwen 2.5 Coder 1,5B/7B, Phi 4 mini et Qwen 3 4B. Quantification Q4_K_M, sauf Gemma 4 Q4_0. Les métadonnées HF LFS ont été vérifiées le 3 octobre 2026 et sont épinglées dans [ModelHub.kt](app/src/main/java/com/pocketai/app/ModelHub.kt). Leur architecture est vérifiée par llama.cpp au chargement ; la taille du fichier ne représente pas toute la RAM nécessaire. La vision de ces familles reste réservée au mode serveur. Les trois modèles historiques restent disponibles :

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

Dans **Modèles**, filtrer par usage. **Télécharger le GGUF texte** installe le fichier sur le téléphone ; **Charger** le sélectionne pour le chat hors ligne. **Configurer** enregistre, pour chaque modèle, une URL HTTPS (par exemple `https://serveur.example/v1`), son alias exact et une clé facultative chiffrée. Le serveur doit être déployé séparément et héberger réellement ce modèle. PocketAI ne transforme pas une URL Hugging Face en endpoint. **Tester le serveur** vérifie sa liste `/models` ; certains serveurs spécialisés n’exposent pas cette route. **Utiliser** active explicitement le chat distant ; le bandeau affiche alors **Serveur**. Charger un GGUF revient au chat local. Les requêtes chat serveur retournent une réponse complète, sans streaming ; le temps indiqué inclut le réseau.

| Modèles / usage | Route serveur attendue | Interface / données envoyées |
| --- | --- | --- |
| Chat du catalogue ou Qwen 3.5 9B, Qwen 3.8 27B, Gemma 4 26B-A4B/31B, Qwen 3 Coder Next | `POST /chat/completions` | Chat : question, historique récent limité, extraits joints |
| Qwen 3.5, Ministral 3, Gemma 4, SmolVLM2 2,2B | Même route, contenu `image_url` | Photo jointe réduite à 1 280 pixels et JPEG 85 %, plus OCR/extraits ; une photo à la fois. Vidéo non intégrée |
| Qwen 3 Embedding 0,6B | `POST /embeddings`, vecteurs float | Activer dans Réglages : passages du document et question transmis même avec chat local. 64 passages au maximum répartis dans tout le document, 4 retenus par similarité cosinus. Recherche partielle, sans index persistant |
| Whisper small | `POST /audio/transcriptions`, multipart | Créer → Transcrire : audio du sélecteur, 25 Mo maximum. Transcription enregistrée en TXT |
| Kokoro 82M | `POST /audio/speech`, WAV | Créer → Voix : texte (4 000 caractères maximum), voix `ff_siwis` ou `fm_gilles` selon disponibilité du serveur. WAV à ouvrir/enregistrer/partager |
| Qwen Image 2.1 | `POST /images/generations` et `/images/edits`, réponse `b64_json` | Créer → Image ou Modifier la photo jointe : description, et photo réduite pour l’édition. Nécessite un backend diffusion compatible ; licence Qwen Research |

Un serveur qui n’implémente pas la route ou le modèle sélectionné provoque une erreur explicite ; aucun modèle de remplacement n’est choisi automatiquement. Chaque modèle peut pointer vers un serveur différent. Les noms de dépôts proposés par défaut doivent être remplacés par les alias annoncés par le serveur si nécessaire. Toutes les connexions utilisent HTTPS avec validation TLS ; les redirections authentifiées sont refusées. Les embeddings sont désactivés par défaut. L’inférence spécialisée n’est pas embarquée hors ligne dans l’APK. Le bouton **Lire** du chat conserve la voix Android locale ; Kokoro est une création audio distincte.

**Créer** permet de demander au modèle sélectionné (local ou serveur) le contenu d’un fichier : texte, Markdown, code, JSON, CSV ou HTML selon le nom choisi. Chaque réponse du chat peut aussi être exportée en **texte, Markdown, HTML, JSON, CSV ou PDF**. Les exports JSON/CSV contiennent le texte de la réponse ; le PDF conserve ce texte. Enregistrer, partager ou supprimer une création depuis sa fiche. Les fichiers restent accessibles après redémarrage ; l’historique des créations est limité à **100 fichiers et 512 Mo**.

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

- `out/PocketAI-4.3.0-model-hub-arm64-debug.apk` et `out/SHA256.txt` ;
- preuves de vérification dans `out/` ;
- rapports dans `app/build/reports/tests/` et `lib/build/reports/tests/`.

Pour une chaîne Android/JDK existante, fournir `JAVA_HOME` et `ANDROID_HOME` ou `ANDROID_SDK_ROOT`, ainsi que `POCKETAI_LLAMA_DIR` et `POCKETAI_VULKAN_DIR` si nécessaire. `POCKETAI_TOOLS_DIR` change le répertoire des outils. Les compilations utilisent quatre workers par défaut. Le [workflow GitHub](.github/workflows/build-native.yml) prépare les dépendances, construit, teste et publie le ZIP de l’APK avec ses preuves et un artefact séparé de rapports de tests ; les artefacts expirent après 14 jours.

La compilation et les tests logiciels ne remplacent pas la validation sur téléphone : **le fonctionnement du GPU, les gains de performance et les appels aux API payantes restent à vérifier sur un appareil réel avec les comptes concernés**.

Les avis de licence du code natif et de l’adaptation du wrapper llama.cpp figurent dans [LICENSE](LICENSE) et [NOTICE](NOTICE). Les modèles et les fournisseurs conservent leurs propres licences et conditions.
