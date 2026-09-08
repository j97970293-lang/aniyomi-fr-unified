# Rapport de validation — FR Unifié 16.12

Date : **8 septembre 2026**

## 16.12 — contrôles réalisés avant publication

La version **16.12 / code 12** apporte :
- Une interface de classement des sources Nuvio et des langues/qualités aérée et lisible, avec boutons flèches compacts sans padding intrusif et désactivation visuelle aux extrémités ;
- La correction de la résolution des épisodes récents de One Piece (gestion bidirectionnelle TMDB TV saisons/épisodes ↔ numérotation absolue pour les sources Nuvio animées et généralistes) et de Wistoria (appariement multi-titres TMDB intégrant les titres romaji, originaux japonais et les espaces typographiques français) ;
- Le décodage UTF-8 systématique des requêtes HTTP/JSON pour préserver les caractères accentués et japonais ;
- L'enrichissement du runtime JS (`TextDecoder`, `TextEncoder`, `Object.fromEntries`, `Object.values`, `Object.entries`, `String.prototype.replaceAll`, `crypto.randomUUID`).

Nouveaux tests déterministes : `TitleMatchTest` (appariement Wistoria et One Piece, calcul des cibles d'épisodes) et extension de `RhinoRuntimeTest` (tests d'exécution des polyfills JS).

## 16.11 — contrôles réalisés avant publication

La version **16.11 / code 11** ajoute le moteur Nuvio parallèle (plus de lots bloquants), la revérification des liens Nuvio au clic, le DNS over HTTPS et un classement à flèches qui persiste réellement. Les contrôles déterministes (ktlint, compilation Kotlin, suite JVM) sont exécutés par `build.yml` sur la pull request ; la signature de l’APK l’est par `publish.yml` sur le tag `v16.11`.

Nouveaux tests déterministes : `FrDnsTest` (cartes DoH, construction/analyse d’un paquet DNS avec `ANCOUNT` en en-tête) et extension de `StreamRankerTest` (un seul flux préféré après `sorted`). `NuvioConcurrencyTest` reste le garde-fou du lancement simultané.

## 16.9 — contrôles réalisés avant publication

La version **16.9 / code 9** a été préparée sans accès à Maven Central ni au SDK Android depuis l’environnement de travail ; les contrôles ci-dessous ont donc été exécutés avec le compilateur Kotlin 2.2.0 et ktlint 1.7.1 autonomes, contre des doublures minimales des API Android/Aniyomi. La compilation Gradle complète, le lint Android et la signature de l’APK sont assurés par les workflows GitHub (`build.yml` sur la pull request, `publish.yml` sur le tag `v16.9`).

| Contrôle | Résultat |
|---|---|
| ktlint 1.7.1 (mêmes règles que Spotless) sur `src/**` et `test/**` | Réussi, 0 violation |
| Compilation Kotlin 2.2.0 des sources de l’extension (options `-Xcontext-parameters`, `-Xmulti-dollar-interpolation`, `-Xjvm-default=all-compatibility`) | Réussie, 0 erreur ; le même harnais reproduit l’erreur réelle de compilation corrigée en 16.8, il détecte donc les erreurs sémantiques |
| Suite JVM reproductible (21 classes, 44 tests déclarés) exécutée avec un lanceur minimal | **33/33 tests exécutés réussis**, 11 sondes réseau opt-in ignorées, 0 échec |
| Scénario de bout en bout Nuvio : manifest et bundle servis par un serveur HTTP local, exécution Rhino, conversion en flux, regroupement des serveurs, `updateSources()` puis `autoUpdateIfDue()` | Réussi : titres `(VF) 1080p · flemmix · Nuvio · Uqload`, serveur `Nuvio · flemmix : VF, VOSTFR`, bilan « Dépôts relus : 1 · sources actives : 2 · scripts mis à jour : 2 » |
| Scénario Stremio : flux HTTP, torrent et lecteur sans langue | Réussi : `(VF) 1080p · … · Stremio · Frenchstream UQLOAD`, `(MULTI) 4K · … · Torrent …`, un lecteur sans langue reste non préféré |

Nouveaux tests déterministes : `StreamLabelTest` (rendu/analyse des titres, détection des langues et qualités, détail sans redite, noms de serveurs) et `StreamRankerTest` (ordre par défaut, ordre à flèches, anciens titres, ordre des serveurs, lecture et migration du réglage). Les tests existants restent verts après la refonte des titres (`StremioParserTest`, `StremioHosterTest`, `NuvioLanguageRuntimeTest`, `FrSettingsTest`…).

Points non couverts localement et à confirmer sur l’appareil : l’affichage de la boîte de dialogue à flèches (vue construite par code), le déclenchement de la mise à jour automatique au lancement (une fois par 24 h) et la lisibilité des titres dans le lecteur Aniyomi.

## 16.7 — rapport d’origine

Date : **6 septembre 2026**

## Résumé

La version **16.7 / code 7** a été compilée et signée pour Aniyomi avec **minSdk 26** et **targetSdk 34**. Elle conserve le certificat de la 16.6, donc Android peut l’installer comme mise à jour. Le chemin Kotlin/Rhino, les catalogues et hosters Stremio, la concurrence Nuvio et l’import manuel All-in-One-Nuvio ont été contrôlés.

## Contrôles automatiques

| Contrôle | Résultat |
|---|---|
| Spotless (`spotlessApply` puis `spotlessCheck`) | Réussi |
| Suite JVM reproductible | **23/23 tests exécutés réussis**, 11 sondes réseau opt-in ignorées, 0 échec sur 34 tests déclarés |
| Sondes réseau ciblées et bornées | **8/8 contrôles finaux réussis** ; 2 sondes facultatives ignorées (exhaustive et flux tiers momentanément vide) |
| Android Lint debug | Réussi — **0 erreur**, 10 avertissements non bloquants |
| Build release signé | Réussi (`assembleRelease`, un worker, JVM Gradle limitée à 800 Mio) |
| Vérification `apksigner` | Réussie — signature APK v2, un seul signataire |
| Métadonnées `aapt` | Version 16.7/code 7, minSdk 26, targetSdk/compileSdk 34 |
| Génération du dépôt Aniyomi | Réussie (`index.json`, `index.min.json`, `repo.json`, APK et icône) |

Les tests opt-in ne font pas dépendre la suite reproductible de sites tiers. Une première exécution agrégée des sondes réseau a atteint la limite externe de 30 minutes et n’a pas été comptée comme un succès ; chaque classe ciblée a ensuite été relancée séparément avec un délai maximal et a rendu un résultat Gradle explicite.

Le premier assemblage release a atteint L8/D8 puis a été tué par la limite mémoire de la machine de validation (2 Gio, sans swap). La reprise séquentielle avec une JVM Gradle de 800 Mio a terminé toutes les étapes, dont L8, lint vital, validation de signature et empaquetage. La copie temporaire de la clé de signature a été supprimée après le build.

## Catalogues Stremio et filtre réellement appliqué

- Le manifest TMDB Stremio français expose **12 entrées `catalogs[]`** ; les 12 sont détectées séparément et leurs clés sont uniques.
- Les options propres au catalogue sélectionné sont exposées dynamiquement. Le test déterministe vérifie exactement la route :

  ```text
  /catalog/movie/by-year/genre=2026.json
  ```

- La sonde réseau finale a choisi le catalogue réel `series / tmdb.year`, transmis `genre=2026` et reçu une rangée non vide : le filtre n’est donc pas seulement affiché, il atteint bien l’addon Stremio.
- La recherche Stremio localisée a trouvé **One Piece**, chargé sa fiche et vérifié une liste de plus de 24 épisodes.
- La découverte des manifests est parallèle et limitée à 10 secondes par addon. Le cache des catalogues est trié de manière stable et n’est réécrit que si son contenu change.

## Lecture Stremio indépendante de Nuvio

- Le test déterministe `StremioHosterTest` désactive Nuvio, expose un hoster depuis un manifest `stream`, puis résout ce hoster au clic jusqu’à un magnet avec sa qualité.
- La sonde réelle Snixi a validé le contrat `manifest`/`stream` et l’exposition du hoster avec Nuvio désactivé. Une première passe a récupéré **2 flux**, conservé leurs en-têtes et éliminé le faux flux `/troll/master.m3u8`.
- Lors de la toute dernière relecture, Snixi est resté joignable mais ses trois titres de contrôle ont momentanément rendu zéro flux ; la vérification de contenu a donc été marquée facultative/ignorée plutôt que présentée comme un succès. Le chemin complet reste couvert par la première passe réelle et par le test local déterministe jusqu’au magnet.
- Les variantes IMDb/TMDB et `series`/`tv`, les flux HTTP/torrent, les sous-titres et l’ordre Stremio/Nuvio restent pris en charge.

## Nuvio : concurrence, langues et sélection

- `NuvioConcurrencyTest` utilise trois providers servis par un serveur HTTP local et mesure au moins **deux téléchargements/exécutions simultanés**. Les modes rapide et équilibré ne parcourent donc plus les providers strictement un par un.
- Le mode rapide continue après un résultat VOSTFR tant qu’aucune VF n’a été trouvée. La sonde réseau a obtenu une VF pour One Piece et a vérifié la présence de la qualité.
- Le bundle Anime-Sama amont courant a été téléchargé, exécuté dans le chemin Kotlin/Rhino et a rendu **4 liens** lors de la sonde finale.
- Le bilan recommandé a réussi pour **5 providers sur 6** ; Mugiwarastream a bien exécuté son bundle, mais ses deux candidats ont été rejetés après des réponses 403. La recherche globale a néanmoins rendu plusieurs VF/VOSTFR pour One Piece et deux résultats pour *The Runner*.
- Le test `NuvioLanguageRuntimeTest` prouve qu’un provider déclaré en turc est exclu avec la langue `fr`, puis sélectionné et réellement exécuté par Rhino avec la langue `tr`.
- Le sélecteur complet affiche les providers de toute langue, ceux désactivés par leur manifest et ceux dont le type est inconnu. Les restrictions de langue/type/activation s’appliquent seulement lors de l’exécution.
- Les contrôles de flux continuent de refuser avant le lecteur les statuts explicites 401, 403, 404, 410, 429 et 451. Movix reste exclu par défaut tant que sa route de test répond 403.

## Import manuel All-in-One-Nuvio

URL validée :

```text
https://raw.githubusercontent.com/D3adlyRocket/All-in-One-Nuvio/refs/heads/main/manifest.json
```

Résultats :

- manifest reconnu comme dépôt **Nuvio** ;
- révision amont vérifiée : `716057b2a0d5` ;
- **61 providers** analysés et transmis au même sélecteur individuel que les dépôts configurés ;
- cache Nuvio invalidé après import afin que les plugins apparaissent sans conserver un ancien manifest ;
- priorité donnée au dépôt ajouté le plus récemment en cas d’identifiant dupliqué ;
- URL absente de `DEFAULT_NUVIO_REPOS` : le dépôt reste **strictement optionnel** et n’est pas ajouté par défaut.

## Providers internationaux externes

La compatibilité multilingue du moteur est couverte de façon déterministe par le provider turc local exécuté sur Rhino. Les essais exploratoires externes restent dépendants des sites tiers :

- `animecix` a chargé son bundle et TMDB mais n’a rendu aucun lien pour la sonde One Piece ;
- `dizifilm` a atteint ses domaines, qui ont répondu 403/404 ;
- Yoruix `vixsrc` a chargé son manifest, son bundle et TMDB mais n’a rendu aucun lien pour Fight Club ;
- une nouvelle sonde `fullhdfilm` n’a pas terminé avant le délai externe imposé ;
- certains bundles Phisher requièrent encore Cheerio/Node, indisponible sur Android.

Ces indisponibilités ne remettent pas en cause le filtrage multilingue ni le runtime Kotlin/Rhino : chaque provider est isolé et l’échec de l’un ne bloque pas les autres moteurs.

## APK validé

- Fichier utilisateur : `FR-Unifie-Aniyomi-v16.7.apk`
- Taille : **897385 octets**
- Package : `eu.kanade.tachiyomi.animeextension.fr.frunified`
- Version : `16.7` (`versionCode` 7)
- Android : minSdk 26, targetSdk 34, compileSdk 34
- Source Aniyomi : `6917344484142790022`
- SHA-256 APK : `d3b01eb485ff619f498a99ea4e856265360085359da622951b4414adae90671e`
- Empreinte SHA-256 du certificat : `e2f3ec03556cebbf3d062687eb00282926c0f61d9b4eb8126d788cccf1b1da9e`
- Signature : schéma v2, RSA 4096 bits, un signataire

L’empreinte du certificat est identique à celle de l’APK 16.6. Le `minSdk 26` et le désucrage des bibliothèques Java maintiennent la compatibilité Android 8.x, notamment l’appareil OPPO Android 8.1 visé.

## Avertissements Lint non bloquants

Les 10 avertissements sont inchangés : un conseil Android 12 sur `dataExtractionRules`, une ressource d’icône signalée inutilisée, cinq avertissements de forme d’icône et trois recommandations d’utiliser un catalogue TOML. Aucun ne concerne le runtime, la sécurité des flux ou la compatibilité API 26.

## Limites externes

Les catalogues, addons et providers sont des services tiers pouvant changer, refuser certaines régions/adresses IP ou exiger des modules non disponibles sur Android. FR Unifié borne, filtre ou isole ces pannes ; il ne peut garantir la disponibilité permanente de leurs contenus.
