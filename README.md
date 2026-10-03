# PocketAI Android 4

Assistant Android avec modèles GGUF locaux et catalogue de 21 modèles pour le chat, le code, la vision, les documents, la voix et les images. Les fonctions spécialisées utilisent des serveurs compatibles configurés explicitement. Les modèles ne sont pas inclus dans l’APK.

## Installer et commencer

- **Android 13 ou supérieur, téléphone ARM64** (`arm64-v8a`).
- Dans les [Actions GitHub](https://github.com/tchoutchoune/PocketAI-Android/actions), ouvrir un build réussi de **Build PocketAI 4** sur la branche preview `codex/dynamic-context-budget`, télécharger l’artefact **PocketAI-4.5.2-vulkan-isolated-arm64-debug**, extraire le ZIP et installer `PocketAI-4.5.2-vulkan-isolated-arm64-debug.apk`. GitHub peut demander une connexion pour télécharger les artefacts.
- Autoriser l’installation depuis la source choisie si Android le demande. Cet APK est un build de développement signé avec une clé de débogage.

La preview utilise le paquet **`io.github.tchoutchoune.pocketai.preview`** et la même signature que la preview 4.2.3 : elle met à jour cette application en conservant ses données. Les applications d’un autre paquet restent séparées.

L’onglet **Discuter** permet d’envoyer une question, d’arrêter une opération et de commencer une nouvelle conversation. Les conversations et créations sont conservées sur le téléphone. **Décharger** un modèle libère sa mémoire ; **Supprimer** retire son fichier local.

## Choisir le modèle et les performances

Le catalogue contient 11 nouveaux GGUF de texte : Qwen 3.5 2B/4B, Qwen 3 4B Instruct 2507, Ministral 3 3B, SmolLM3 3B, Gemma 4 E2B/E4B, Qwen 2.5 Coder 1,5B/7B, Phi 4 mini et Qwen 3 4B. Quantification Q4_K_M, sauf Gemma 4 Q4_0. Les métadonnées HF LFS ont été vérifiées le 3 octobre 2026 et sont épinglées dans [ModelHub.kt](app/src/main/java/com/pocketai/app/ModelHub.kt). Leur architecture est vérifiée par llama.cpp au chargement ; la taille du fichier ne représente pas toute la RAM nécessaire. La vision de ces familles reste réservée au mode serveur. Les trois modèles historiques restent disponibles :

| Modèle | Téléchargement | Usage conseillé | Licence |
| --- | ---: | --- | --- |
| Qwen 2.5 Instruct 0,5B | 491 Mo | Commencer rapidement, appareils modestes | [Apache 2.0](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/blob/main/LICENSE) |
| Qwen 2.5 Instruct 1,5B | 1,12 Go | Équilibre entre rapidité et qualité | [Apache 2.0](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/blob/main/LICENSE) |
| Qwen 2.5 Instruct 3B | 2,10 Go | Qualité, davantage de RAM disponible | [Qwen Research, conditions spécifiques](https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/blob/main/LICENSE) |

Les tailles utilisent les unités décimales. Les téléchargements sont liés à des révisions précises ; leur taille, l’en-tête GGUF et le **SHA-256 officiel** sont vérifiés avant installation. Les références et empreintes figurent dans [ModelRepository.kt](app/src/main/java/com/pocketai/app/ModelRepository.kt). Les copies interrompues sont supprimées et un import ne remplace pas silencieusement un fichier existant. Les modèles ne sont pas inclus dans l’APK ; consulter leur licence avant utilisation, notamment les restrictions de la version 3B.

Un GGUF personnel peut être importé. Son en-tête et sa taille sont contrôlés, mais son origine et son intégrité ne bénéficient pas de la vérification du catalogue. Sa compatibilité dépend des architectures prises en charge par le moteur.

Dans **Réglages**, choisir **Auto · adaptatif**, **CPU · performance**, **CPU · équilibré**, **Autonomie** ou **Vulkan · expérimental**. La RAM disponible règle le contexte et les lots ; les cœurs CPU orientent le nombre de threads. Le chargement refuse les budgets mémoire insuffisants. Les réglages de profil s’appliquent au prochain chargement ; la chaleur réduit les threads pendant une génération.

La déclaration Vulkan d’Android permet de tenter l’accélération GPU. Le moteur vérifie ensuite le backend et peut revenir au CPU si le GPU ou ses allocations échouent. **Voir le matériel et le moteur** indique le backend réellement actif, les couches GPU, les threads et les mesures de génération. La présence de Vulkan ne garantit pas un gain de vitesse sur chaque téléphone.

## Vulkan : profil isolé 4.5.2

Le diagnostic du OnePlus CPH2747 identifie un Adreno 840. Un ancien essai Vulkan à 16 couches produisait une sortie corrompue ; la présence des bibliothèques et de Vulkan 1.4 ne prouve donc pas la fiabilité de l’inférence. Cette version propose une correction de compatibilité à vérifier sur l’appareil, sans annoncer de gain non mesuré.

- Détection native du GPU et empreinte du pilote avant l’initialisation ggml. Sur Adreno 840 : calcul F32, matrices coopératives/dot2/MMVQ et calculs entiers désactivés, aucune fusion, réorganisation du graphe ou réutilisation des descripteurs. Les soumissions synchrones traitent un nœud à la fois. Le cache KV passe en F32 sur le CPU, l’attention reste sur le CPU et les opérations GPU opportunistes sont désactivées ; les couches de poids restent partiellement sur le GPU. Flash Attention est désactivée. Ce profil isole les chemins encore actifs après les valeurs non finies constatées en 4.5.1 ; il peut être plus lent et ne prouve pas une correction du pilote. Le CPU conserve son chemin habituel.
- Une copie de compilation isolée du llama.cpp épinglé remplace `unpack8` par des décalages 32 bits et des conversions signées explicites, pour éviter les bitcasts d’octets signalés sur certains pilotes Qualcomm. Le test de shader vérifie le SPIR-V généré ; la correction sur Adreno 840 reste à confirmer avec les mesures du téléphone.
- **Réglages → Comparer et optimiser CPU / Vulkan** : trois textes publics fixes produisent douze distributions de scores CPU. Chaque configuration GPU doit les reproduire dans les tolérances avant et après un benchmark indépendant. Un contrôle CPU contre sa propre référence doit réussir avant les essais GPU. Six essais combinent 16 ou toutes les couches avec des lots physiques de 32 ou 64, puis 16 ou 4 couches avec un lot physique d’un seul token ; le batch logique reste séparé. Les deux derniers essais explorent un autre chemin de calcul, sans garantie de correction ni de vitesse. Le contexte GPU initial est limité à 2 048 tokens.
- Les refus conservent leur cause : allocation, code de décodage, scores non finis ou divergence numérique. Les écarts JS/RMS, les critères échoués et la première sonde fautive sont journalisés avant le retour au CPU. Le dernier essai reste visible dans le diagnostic du même modèle ; seules des erreurs numériques justifient une exclusion persistante du GPU. Aucun texte de conversation n’est utilisé.
- Après le premier refus pour scores non finis d’une comparaison, une sonde publique est rejouée dans un contexte indépendant avec observation des sorties GPU F32. La trace conserve le nom technique, l’opération et la forme du premier tenseur NaN/+Inf observé, sans enregistrer son contenu ; les -Inf de masque ne sont pas pris pour une erreur. Limites : 20 secondes, 64 Mio lus, 8 Mio par tenseur. L’observation insère des synchronisations : son résultat ne valide jamais un profil et n’entre jamais dans les mesures de vitesse. Aucun callback de trace n’est installé pour le chat ou le benchmark.
- Vulkan est retenu si la génération atteint au moins le CPU, la préparation reste à au moins 90 % du CPU, et le score pondéré atteint 105 %. Ces courts tests ne couvrent pas toutes les conversations ni une utilisation prolongée. Les scores non finis et les répétitions dégénérées déclenchent encore un retour au CPU pendant l’utilisation.
- Seul un profil GPU validé et effectivement retenu pour son gain est mémorisé. Le résultat est lié au modèle (taille/date), à la version du correctif, à Android et au pilote. Une mise à jour d’Android, du pilote ou du correctif de compatibilité invalide le réglage. Le profil isolé v2 invalide les anciennes recettes et exclusions v1. Un modèle revenu silencieusement au CPU est exclu des résultats GPU. La chauffe ou l’économie d’énergie interrompent la comparaison ; la conversation est conservée.

Installer la mise à jour, désactiver l’économie d’énergie, laisser refroidir l’appareil, charger le GGUF puis lancer la comparaison dans Réglages. Elle peut prendre plusieurs minutes et charger le modèle plusieurs fois. Exporter ensuite le diagnostic pour vérifier les couches réellement actives, les validations et les tokens/s.

## Moteur 4.4 et mesures réelles

- Threads distincts pour préparer le prompt et générer la réponse. CPU performance autorise la comparaison jusqu’à huit threads sur un appareil à huit cœurs, avec six threads de génération avant mesure et jusqu’à huit pour le prompt. L’auto-réglage compare 2/3/4/5/6/8 selon le profil, réalise un échauffement puis deux mesures et choisit séparément chaque phase. À moins de 5 % du maximum, le nombre de threads le plus faible est préféré. La chauffe interrompt le réglage ; l’annulation conserve le précédent. Les limites thermiques s’appliquent aux deux phases.
- Un pool CPU persistant est attaché au backend CPU effectivement sélectionné pour éviter les pools temporaires entre les décodages. Le benchmark utilise un contexte indépendant limité à ses courtes séquences, sans effacer la conversation.
- Pour l’architecture Qwen3 et la condition Jinja reconnue, le bloc de raisonnement vide reste stable dans les anciens tours lorsque le raisonnement est désactivé. Cela conserve un préfixe de tokens identique ; le cache n’est jamais réutilisé sans comparaison exacte. Le comportement avec raisonnement activé reste identique. Les templates inconnus conservent leur traitement d’origine.
- Les mesures détaillées sont repliées derrière **Infos** dans Discuter. Les onglets masqués ne sont plus reconstruits à chaque changement de génération.
- Les téléchargements calculent leur SHA-256 pendant la copie ; une reprise relit seulement le préfixe déjà reçu avant de poursuivre le digest.

Ces changements suppriment des coûts identifiés. Ils ne garantissent pas un nombre de tokens/s : exporter un diagnostic 4.4 et comparer le même modèle, le même prompt et un téléphone refroidi. Les tests du template utilisent le fichier Qwen3 de la révision llama.cpp épinglée, sur trois tours, et vérifient aussi le comportement avec raisonnement activé ou option absente.

## Fichiers, images, vidéos et Internet

Dans **Modèles**, **Mes modèles** contient les GGUF prêts sur le téléphone, **Catalogue** propose les téléchargements et **Serveurs** regroupe les fonctions distantes. Les modèles déjà présents sont reconnus par taille et SHA-256, même après renommage ; un import identique et un téléchargement identique réutilisent le fichier existant. Les empreintes sont mises en cache et invalidées lorsque la taille ou la date de modification change. La détection s’exécute hors du thread d’interface. **Choisir un dossier** autorise Android à lister tes GGUF dans ce dossier ; celui-ci est rescanné au retour dans l’application, avec une limite de 512 éléments et quatre niveaux. L’import reste explicite et copie le fichier dans le stockage privé. Aucun accès général au stockage n’est demandé. **Utiliser** sélectionne le modèle local pour discuter hors ligne. Dans **Serveurs**, **Configurer** enregistre, pour chaque modèle, une URL HTTPS (par exemple `https://serveur.example/v1`), son alias exact et une clé facultative chiffrée. Le serveur doit être déployé séparément et héberger réellement ce modèle. PocketAI ne transforme pas une URL Hugging Face en endpoint. **Tester le serveur** vérifie sa liste `/models` ; certains serveurs spécialisés n’exposent pas cette route. **Utiliser** active explicitement le chat distant ; le bandeau affiche alors **Serveur**. Charger un GGUF revient au chat local. Les requêtes chat serveur retournent une réponse complète, sans streaming ; le temps indiqué inclut le réseau.

| Modèles / usage | Route serveur attendue | Interface / données envoyées |
| --- | --- | --- |
| Chat du catalogue ou Qwen 3.5 9B, Qwen 3.8 27B, Gemma 4 26B-A4B/31B, Qwen 3 Coder Next | `POST /chat/completions` | Chat : question, historique récent limité, extraits joints |
| Qwen 3.5, Ministral 3, Gemma 4, SmolVLM2 2,2B | Même route, contenu `image_url` | Photo jointe réduite à 1 280 pixels et JPEG 85 %, plus OCR/extraits ; une photo à la fois. Vidéo non intégrée |
| Qwen 3 Embedding 0,6B | `POST /embeddings`, vecteurs float | Activer dans Réglages : passages du document et question transmis même avec chat local. 64 passages au maximum répartis dans tout le document, 4 retenus par similarité cosinus. Recherche partielle, sans index persistant |
| Whisper small | `POST /audio/transcriptions`, multipart | Outils → Transcrire : audio du sélecteur, 25 Mo maximum. Transcription enregistrée en TXT |
| Kokoro 82M | `POST /audio/speech`, WAV | Outils → Voix : texte (4 000 caractères maximum), voix `ff_siwis` ou `fm_gilles` selon disponibilité du serveur. WAV à ouvrir/enregistrer/partager |
| Qwen Image 2.1 | `POST /images/generations` et `/images/edits`, réponse `b64_json` | Outils → Image ou Modifier la photo jointe : description, et photo réduite pour l’édition. Nécessite un backend diffusion compatible ; licence Qwen Research |

Un serveur qui n’implémente pas la route ou le modèle sélectionné provoque une erreur explicite ; aucun modèle de remplacement n’est choisi automatiquement. Chaque modèle peut pointer vers un serveur différent. Les noms de dépôts proposés par défaut doivent être remplacés par les alias annoncés par le serveur si nécessaire. Toutes les connexions utilisent HTTPS avec validation TLS ; les redirections authentifiées sont refusées. Les embeddings sont désactivés par défaut. L’inférence spécialisée n’est pas embarquée hors ligne dans l’APK. Le bouton **Lire** du chat conserve la voix Android locale ; Kokoro est une création audio distincte.

**Outils** permet de demander au modèle sélectionné (local ou serveur) le contenu d’un fichier : texte, Markdown, code, JSON, CSV ou HTML selon le nom choisi. Chaque réponse du chat peut aussi être exportée en **texte, Markdown, HTML, JSON, CSV ou PDF**. Les exports JSON/CSV contiennent le texte de la réponse ; le PDF conserve ce texte. Enregistrer, partager ou supprimer une création depuis sa fiche. Les fichiers restent accessibles après redémarrage ; l’historique des créations est limité à **100 fichiers et 512 Mo**.

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

- `out/PocketAI-4.5.2-vulkan-isolated-arm64-debug.apk` et `out/SHA256.txt` ;
- preuves de vérification dans `out/` ;
- rapports dans `app/build/reports/tests/` et `lib/build/reports/tests/`.

Pour une chaîne Android/JDK existante, fournir `JAVA_HOME` et `ANDROID_HOME` ou `ANDROID_SDK_ROOT`, ainsi que `POCKETAI_LLAMA_DIR` et `POCKETAI_VULKAN_DIR` si nécessaire. `POCKETAI_TOOLS_DIR` change le répertoire des outils. Les compilations utilisent quatre workers par défaut. Le script de tests de template nécessite Python et Jinja2 (paquet Debian `python3-jinja2`). La branche `codex/model-hub` conserve le code ; la branche preview `codex/dynamic-context-budget` construit exactement le même commit avec son cache de signature existant. Le [workflow GitHub](.github/workflows/build-native.yml) prépare les dépendances, construit, teste et publie le ZIP de l’APK avec ses preuves et un artefact séparé de rapports de tests ; les artefacts expirent après 14 jours.

La compilation et les tests logiciels ne remplacent pas la validation sur téléphone : **le fonctionnement du GPU, les gains de performance et les appels aux API payantes restent à vérifier sur un appareil réel avec les comptes concernés**.

Les avis de licence du code natif et de l’adaptation du wrapper llama.cpp figurent dans [LICENSE](LICENSE) et [NOTICE](NOTICE). Les modèles et les fournisseurs conservent leurs propres licences et conditions.
