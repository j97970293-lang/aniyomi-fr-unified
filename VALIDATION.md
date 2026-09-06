# Rapport de validation — FR Unifié 16.6

Date : **6 septembre 2026**

## Contrôles automatiques

| Contrôle | Résultat |
|---|---|
| Spotless (`spotlessApply` puis contrôle implicite du build) | Réussi |
| Tests JVM déterministes | 18/18 réussis |
| Sondes réseau ciblées Jikan/Stremio/Nuvio | 5/5 réussies |
| Android Lint debug | Réussi — 0 erreur, 10 avertissements non bloquants |
| Build release | Réussi |
| Vérification `apksigner` | Réussie — signature APK v2, un seul signataire |
| Métadonnées Android (`aapt`) | Version 16.6/code 6, minSdk 26, targetSdk 34 |
| Génération du dépôt Aniyomi | Réussie (`index.json`, `index.min.json`, `repo.json`, APK et icône) |

Les tests opt-in non sélectionnés sont ignorés pendant la suite déterministe afin qu’une panne d’un site tiers ne rende pas le build non reproductible.

## One Piece et catalogues

- Le compteur paginé Jikan de **One Piece** a atteint la dernière page réelle et validé un total d’au moins **1168 épisodes**, donc aucun repli à 24.
- Le parcours Stremio localisé a chargé les catalogues TMDB, recherché **One Piece**, chargé sa fiche et vérifié que sa liste vidéo contient plus de 24 épisodes.
- Les manifests Stremio sont filtrés selon leurs ressources, types et préfixes d’identifiant.
- Les tests déterministes couvrent la pagination par 20, les payloads d’épisodes absolus et les flux intégrés à `meta.streams`.

## Résultats réseau de lecture

- **Nuvio / Anime-Sama** : le bundle amont courant a été téléchargé, servi au runtime de test, exécuté par le chemin Kotlin/Rhino et a rendu **2 liens** pour One Piece lors de la validation finale.
- **Stremio / Snixi** : contrat `manifest`/`stream` valide et **2 flux** récupérés ; les en-têtes ont été conservés et le faux flux `/troll/master.m3u8` éliminé.
- Les contrôles de flux rejettent les statuts explicites 401, 403, 404, 410, 429 et 451 avant d’envoyer le lien au lecteur.

## Providers internationaux

La publication ne dépend pas de la disponibilité de chaque site tiers. Les contrôles exploratoires ont établi que :

- les boucles `for…of` et déclarations `const` problématiques sont abaissées avant Rhino sans modifier les chaînes, commentaires ou littéraux regex ;
- le bundle turc `fullhdfilm` franchit désormais l’évaluation Rhino, puis son site répond 403 depuis l’environnement de validation ;
- Yoruix `vixsrc` et `vidlink` atteignent l’appel réel sans erreur de syntaxe, mais ne rendent aucun lien pour la sonde Fight Club ;
- certains bundles Phisher demandent encore `cheerio-without-node-native`, module Node non présent sur Android.

Ces providers restent sélectionnables et isolés : l’échec de l’un ne fait pas échouer les autres moteurs.

## APK validé

- Fichier : `FR-Unifie-Aniyomi-v16.6.apk`
- Taille : **881233 octets**
- Package : `eu.kanade.tachiyomi.animeextension.fr.frunified`
- Version : `16.6` (`versionCode` 6)
- Android : minSdk 26, targetSdk 34
- Source Aniyomi : `6917344484142790022`
- SHA-256 APK : `7b71365dae1273d9559f6c17e11f1b2dc38f820f4c8e5299a13438c81d689a15`
- Empreinte SHA-256 du certificat : `e2f3ec03556cebbf3d062687eb00282926c0f61d9b4eb8126d788cccf1b1da9e`

La signature est identique à celle des versions publiques précédentes, ce qui autorise la mise à jour Android sur l’installation existante.

## Limites externes

Les catalogues, addons et providers sont des services tiers pouvant changer ou refuser certaines régions/adresses IP. FR Unifié signale ou isole ces pannes ; il ne peut garantir la disponibilité permanente de leurs contenus.
