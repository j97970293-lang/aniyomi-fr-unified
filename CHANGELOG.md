# Historique des versions

## 16.17 — 10 septembre 2026

- **sélecteur « Choisir les sources » repensé façon NuviO** : aperçu (N dépôts · M fournisseurs · K actifs), onglets par dépôt (avec compteurs actifs/total), zone de recherche, cartes « Dépôts installés » (↻ actualiser, 🗑 supprimer), et une carte par fournisseur : interrupteur, classement **↑↓ intégré à la liste** (celui du haut est interrogé d'abord), drapeau de langue, dépôt et types (movie | tv), dernier résultat du diagnostic, bouton **🧪 « Tester le fournisseur »**, bouton **⚙️** si la source demande des clés, et **🗑** / appui long pour supprimer tout le dépôt ;
- **filtre de qualité façon NuviO** : chips Auto/4K/1080p/720p/480p/360p/HDR/DV/REMUX/CAM/TS — un appui exclut cette qualité/marque de la liste des serveurs (l'exclusion s'applique aussi aux résultats déjà trouvés) ;
- **serveurs affichés PROGRESSIVEMENT pendant la recherche** : les moteurs Nuvio et Stremio partent en même temps (comme dans NuviO) ; les serveurs des sites rapides s'affichent d'abord, la recherche continue en arrière-plan et chaque nouvel affichage de l'écran de serveurs (réouverture, épisode suivant) montre les résultats qui ont suivi — plus de liste bloquée au site le plus lent ;
- **classement des addons Stremio** : le sélecteur « Choisir les addons » reprend l'écran NuviO (interrupteur par addon, ★ conseillés, nombre de catalogues exposés, **↑↓** pour l'ordre — celui du haut part d'abord, 🗑 / appui long pour supprimer vraiment) ; l'ordre des addons décide du premier addon interrogé ;
- **suppression d'un dépôt Nuvio « vraiment »** : les sources du dépôt supprimé sont retirées ET bloquées — elles ne reviennent plus même si un autre dépôt déclare le même identifiant ; réajouter l'URL du dépôt (ou « Restaurer » dans le sélecteur) les réactive ;
- **tous les sites sont listés** : le sélecteur affiche tous les fournisseurs de tous les dépôts (pas seulement les 20 activés) ; « Tout activer » part sur tous, et un fournisseur désactivé reste visible ;
- **bouton « Enregistrer » toujours à l'écran** : les longues listes de dialogues (langues des catalogues, sources, configuration, sauvegardes, diagnostic, catalogue Stremio) sont désormais dans une zone défilable bornée à ~58 % de la hauteur d'écran ;
- **extension plus de marque « obsolète »** : l'index du dépôt est corrigé — l'identifiant de source était publié sous forme de texte alors qu'Aniyomi attend un nombre (le parseur strict refusait l'index, l'extension restait « obsolète » même à jour) ; le dépôt est en outre servi via GitHub Pages ;
- **parallélisme Nuvio porté à 6** par défaut (2 à 8 au choix) et budget de site relevé à 25 s : les grands dépôts (25+ sites) répondent nettement plus vite ;
- **dépôt** : `source_id` numérique dans `tools/create-repo.py` ; mise à jour `extVersionCode = 17`.

## 16.16 — 9 septembre 2026

- **tous les sites Nuvio sont interrogés, comme dans NuviO** : les installations qui conservaient une ancienne liste de 6 sources « conseillées » passent automatiquement à « tout » (migration des réglages v11) ; le sélecteur Nuvio a maintenant un bouton **« Tout activer »** en un geste ;
- **flux illimités par défaut dans Nuvio** comme dans Stremio : l'ancienne limite (2/4/8/12) est convertie en « Illimité » par la migration, et « Illimité » est désormais la première option des deux menus ;
- **catalogue Stremio « Tout »** : une entrée « ⚡ Tout (tous les catalogues) » en tête des rangées Stremio cherche dans TOUS les catalogues actifs en parallèle — plus besoin d'ouvrir chaque catalogue séparément ;
- **suppression d'un dépôt Nuvio** : nouvelle action « Supprimer un dépôt Nuvio » qui liste les dépôts avec leur nombre de sources ; supprimer un dépôt retire d'un seul geste toutes ses sources (comme la suppression d'un addon Stremio) ;
- **ordre des flux en texte** : l'ancien classement à flèches (instable) est remplacé par une simple liste « un critère par ligne » (VF, VOSTFR, 1080p, 4K, EN…) avec bouton « Ordre conseillé » ; une langue nouvelle saisie est ajoutée au classement ;
- **classement des sources Nuvio à flèches supprimé** : l'ordre des sources reste réglable en texte dans « Options de recherche des sources » ;
- **sauvegarde où on veut** : « Partager » envoie la sauvegarde en VRAI fichier JSON que la feuille de partage permet d'enregistrer dans n'importe quel dossier (Fichiers, Drive, …) ; la restauration cherche les fichiers de sauvegarde dans le stockage partagé (Téléchargements et Documents) ;
- **sources robustes hors ligne** : si le téléchargement d'un script échoue, le dernier script mis en cache (même ancien) est utilisé — les sources ne disparaissent plus quand un dépôt est momentanément inaccessible.

## 16.15 — 9 septembre 2026

- même contenu que la 16.14 corrigée (les 12 corrections d'interface sont incluses), publiée sous un nouveau numéro de version afin que l'application propose automatiquement la mise à jour aux utilisateurs déjà passés en 16.14 ;
- mise à jour `extVersionCode = 15`.

## 16.14 — 9 septembre 2026

- **tous les sites, toujours** : suppression des modes de recherche rapide/équilibré/complet et de l'arrêt quand une VF est trouvée — tous les sites activés sont interrogés jusqu'au bout, en parallèle ; chaque site renvoie l'intégralité de ses liens (bornés uniquement par « flux maximum par site », illimité par défaut). C'est la cause principale du « seulement 2 sites sur 27 répondent » ;
- **configuration des sources dans l'application** : les variables d'environnement demandées par un manifest Nuvio (`env` / `requiredEnv` : clés API, jetons, domaines…) se saisissent dans « Configurer les sources » ; une source dont une clé obligatoire est vide n'est pas exécutée et est signalée « ⚙️ à configurer » dans le sélecteur et le diagnostic ;
- **moteur Rhino durci** pour les bundles de sites tiers (ex. Peachify, Moviebox) : polyfills complémentaires — `String.padStart/padEnd`, `trimStart/trimEnd`, `includes/startsWith/endsWith`, `Array.includes/find/findIndex`, `Array.from`, `Object.assign`, `Number.isInteger/isNaN/isFinite`, `performance.now` ;
- nettoyage des réglages obsolètes : les clés « langues Nuvio » et « modes de recherche » sont supprimées (migration des réglages v10) ;

### Corrections d'interface (retours de la 16.14 bêta)

- **listes à nouveau visibles partout** : les listes des dialogues (langues des catalogues, catalogue Stremio, catalogue des sources Stremio, sources Nuvio, sous-titres, sauvegardes, choix DNS, ajout de langue) sont rendues par l'extension elle-même avec des dimensions et des couleurs de texte explicites — les listes natives s'affichaient vides sous le thème de l'application hôte ;
- **classements lisibles et fonctionnels** : dans « Classer les flux » et « Classer les sources Nuvio », les libellés (n° + langue/qualité, nom + dépôt) sont visibles à nouveau et les flèches ⏫ ▲ ▼ ⏬ replacent correctement les entrées (dimensions explicites des boutons et de la colonne de texte) ;
- **recherche de source** : le sélecteur Nuvio dispose d'une zone de recherche en haut de liste pour filtrer les sources par nom (utile avec plus d'une centaine de sources) ;
- **configuration par source depuis le sélecteur** : chaque source qui déclare des variables d'environnement affiche un bouton ⚙️ sur sa ligne — ouvre directement la configuration de cette source sans quitter la liste ;
- **options de recherche complètes** : les options de parallélisme « 4 — rapide » et « 6 — parallèle » ne sont plus coupées (boutons radio empilés verticalement), plus l'ordre des sources au texte (cas avancé) et l'option « Garder les liens 403 (CDN stricts : Movix, FSVid) » ;
- **lien 403 conservé (Movix)** : par défaut les liens refusés en 403 (souvent un problème du créateur du dépôt / CDN strict, ex. Movix) ne sont plus écartés automatiquement ; désactivable dans « Options de recherche des sources » ;
- **diagnostic étendu** : on choisit combien de sources actives tester (5, 20 ou toutes), elles sont testées en parallèle, et une option « Tolérer les pages HTML/popup (comptées comme OK) » restaure la tolérance des popups pendant le test ;
- mise à jour `extVersionCode = 14`.

## 16.13 — 8 septembre 2026

- **interface bilingue français/anglais** : nouveau réglage « Langue de l'application » (section Général) qui traduit tous les libellés, popups, toasts, diagnostics et libellés d'épisodes ; FR Unifié n'est plus une extension « française » seulement — elle apparaît désormais dans tous les filtres de langue d'Aniyomi ;
- **plus aucun site bloqué par la langue** : la configuration « langues Nuvio » n'empêche plus l'exécution d'un provider — un site anglais ou turc s'exécute comme un site français ; la langue du flux est simplement classée avec les autres critères, et de **nouvelles langues deviennent classables** depuis « Classer les flux » (bouton « + Ajouter une langue » : EN, TR, ES, DE, IT, PT…) ;
- **dépôts visibles comme dans NuviO** : le sélecteur de sources affiche le dépôt d'origine de chaque site sous son nom (ex. « D3adlyRocket/Anime-Nuvio »), pour distinguer un site français d'un site international ;
- **suppression des dépôts et des addons** : appui long sur un site du sélecteur Nuvio (supprime le dépôt entier, avec confirmation) ou sur un addon du sélecteur Stremio (supprime l'addon sans le laisser ressusciter via les valeurs par défaut) ;
- **réglages simplifiés** : les réglages difficiles sont groupés dans des popups de type Cloudstream, au lieu de la longue liste d'origine :
  - « Catalogues » : les quatre catalogues (TMDB, AniList, Jikan, Stremio) en cases à cocher dans une seule fenêtre (remplace les quatre interrupteurs et l'ancien « Catalogues principaux ») ;
  - « Options de recherche des sources » : parallélisme, flux maximum par site, vérification anti-popups, mise à jour automatique + action « Mettre à jour les sources maintenant » ;
  - « Options Stremio » : flux maximum par addon, mise à jour automatique + action « Mettre à jour maintenant » ;
  - « Paramètres avancés » : clé API TMDB, clés API des sources, User-Agent, Referer, cookies ;
  - « Réglages DNS avancés » : DNS personnalisé (DoH ou UDP 53) + test de résolution ;
- **flux maximum par site illimité par défaut** (comme dans NuviO) ; l'ancienne valeur par défaut (4) est migrée automatiquement vers « illimité » ;
- **classement des sources sans liste vide** : si les dépôts ne sont pas lisibles, le dialogue s'ouvre sur un message clair avec un bouton « Mettre à jour maintenant » au lieu d'une liste vide ; les sources désactivées restent classables, signalées « désactivée » ;
- **sauvegarde sans lien obligatoire** : « Créer une sauvegarde » (presse-papiers, partage vers une application ou un dossier de votre choix, ou enregistrement dans Téléchargements) et « Restaurer une sauvegarde » (choisir un fichier du dossier Téléchargements, coller le JSON, ou lien HTTPS) ; la synchronisation quotidienne par lien HTTPS reste disponible et facultative ;
- migration des réglages (version 9) : l'ancien interrupteur « Catalogues principaux » est remplacé par les quatre cases « Catalogues » ;
- mise à jour `extVersionCode = 13`.

## 16.12 — 8 septembre 2026

- **interface de classement lisible** : refonte ergonomique des dialogues de classement des sources Nuvio et des langues/qualités avec boutons flèches compacts sans marges intrusives, libellés aérés et clairs, désactivation visuelle propre des flèches en tête/queue de liste et persistance immédiate ;
- **correction Nuvio des épisodes récents de One Piece et Wistoria** :
  - résolution automatique et bidirectionnelle des correspondances saison/épisode TMDB ↔ numérotation absolue (ex. One Piece Saison 21 Épisode 35 ↔ Épisode 1120) selon le type de source Nuvio (sources animées vs sources généralistes) avec bascule automatique de repli ;
  - recherche et appariement renforcés des animés récents (Wistoria: Wand and Sword / Tsue to Tsurugi no Wistoria…) avec comparaison de l'ensemble des titres connus (titres anglais, romaji, alternatifs et kanji originaux), prise en compte des espaces typographiques français et seuil d'acceptation adapté ;
  - décodage UTF-8 conforme pour l'ensemble des requêtes HTTP et JSON de `FrRuntime` afin de préserver l'intégrité des caractères accentués et des écritures japonaises ;
  - polyfills JS modernisés (`TextDecoder`, `TextEncoder`, `Object.fromEntries`, `Object.values`, `Object.entries`, `String.prototype.replaceAll`, `crypto.randomUUID`) ;
- mise à jour `extVersionCode = 12`.

## 16.11 — 8 septembre 2026

- bouton **« + Qualité »** dans le classement à flèches : ajout libre de résolutions (540p, 2160p, 8K et valeurs de 144p à 8640p), persistées et prises en compte par le tri ;
- libellés homogènes dans les listes de sources et de langues : emoji, nom court sans pays, et mention **★ conseillée** pour les valeurs recommandées ;
- préréglages DNS en un clic (Cloudflare, Google, Quad9, AdGuard, téléphone et personnalisé) et diagnostic séparant précisément les chemins HTTPS/DoH, UDP 53 et le repli système ;
- revérification des liens Nuvio juste avant lecture ou téléchargement ; un lien expiré ou devenu HTML est renouvelé auprès du même provider, pour le même épisode, la même langue et la même qualité ;
- mise à jour automatique quotidienne des manifests et catalogues Stremio, avec action manuelle et bilan chiffré ;
- `fetch` JavaScript réellement asynchrone sur un pool réseau hors du thread Rhino, boucle d'événements sûre, `Promise.all` parallèle et minuteries `setTimeout` fondées sur leur vraie échéance ;
- réglages répartis en **8 sections courtes**, avec l'organisation des saisons visible dans la première section ;
- sauvegarde JSON des réglages dans le presse-papiers, restauration manuelle et restauration quotidienne facultative depuis un lien HTTPS.

# Journal des modifications

## 16.10 — 8 septembre 2026

- **moteur Nuvio parallèle** : tous les sites actifs sont lancés ensemble (bornés par le réglage de concurrence, désormais jusqu'à 6) ; les modes rapide et équilibré n'attendent plus la fin d'un lot — dès qu'assez de VF sont trouvées, les sites encore en file sont sautés, ceux déjà en vol terminent ;
- **liens revérifiés avant lecture** : au clic sur un serveur Nuvio, chaque URL HTTP est re-sondée (403, page HTML/popup, HLS) afin d'écarter les liens expirés ou devenus une publicité depuis le listage ;
- **DNS avec DoH** : le DNS personnalisé interroge d'abord le résolveur en HTTPS (RFC 8484, `1.1.1.1` / `8.8.8.8` / `9.9.9.9` ou URL `https://…/dns-query`), puis UDP 53, puis le DNS du système ; lecture de `ANCOUNT` corrigée dans les réponses filaires ;
- **classement à flèches fiable** : l'ordre des langues et qualités est enregistré immédiatement (`commit`) ; « Ordre conseillé » met à jour la liste ouverte au lieu de la laisser périmée ; un seul flux est marqué préféré (celui classé n° 1) pour la lecture automatique Aniyomi.

## 16.9 — 8 septembre 2026

- **titres de flux lisibles et homogènes** pour Nuvio comme pour Stremio : `(VF) 1080p · flemmix · Nuvio · Uqload` — langue, qualité, source, moteur puis détail utile ; le détail ne répète plus le titre de l’œuvre ni les étiquettes déjà affichées ;
- **serveurs regroupés par moteur et par source** avec leurs langues annoncées : `Nuvio · flemmix : VF, VOSTFR`, `Stremio · French Streaming Providers` ; les serveurs Stremio chargés à la demande conservent ce nom ;
- **classement des langues et des qualités à l’aide de flèches** (nouveau réglage « Classer langues et qualités avec les flèches ») : un seul ordre mélangeant VF, VFF, VFQ, MULTI, VOSTFR, VO, 1080p, 4K, 1440p, 720p, 480p et 360p ; le premier critère satisfait décide, le suivant départage (une VF 720p passe avant une VOSTFR 1080p, une VF 1080p avant une VF 720p) ; les anciens « motifs de priorité » personnalisés sont convertis automatiquement, la valeur par défaut est remplacée ;
- le flux marqué **préféré** pour la lecture automatique d’Aniyomi est désormais le premier flux satisfaisant au moins un critère de l’ordre choisi ; les libellés d’addons ou de lecteurs ne sont plus confondus avec une langue ;
- **recherche rapide** (réglage désactivé par défaut) : n’interroge que TMDB et AniList, chaque appel borné à 6 secondes, sans repli Jikan ni addons Stremio en onglet mixte ;
- **recherche TMDB sans année en repli** : lorsqu’aucun résultat ne correspond au titre et à l’année, la même recherche est relancée sans année avant d’abandonner (identifiants TMDB pour Nuvio et fiches TMDB) ;
- **mise à jour automatique des sources Nuvio** : une fois par jour au lancement, les dépôts activés sont relus et les scripts modifiés retéléchargés (`If-Modified-Since`, écriture seulement si le contenu change) ; désactivable, avec action « Mettre à jour les sources maintenant » et bilan chiffré (dépôts, sources, scripts mis à jour / inchangés / en échec) ;
- langue déduite de la déclaration du provider lorsque le flux n’en indique aucune (provider turc → `TR`), sans jamais deviner VF ou VOSTFR pour un provider français ;
- migration des réglages en version 7 ; nouveaux tests JVM `StreamLabelTest` et `StreamRankerTest`.

## 16.8 — 7 septembre 2026

- classement des sources Nuvio à l’aide de flèches (tout en haut, monter, descendre, tout en bas) avec enregistrement immédiat ; le champ texte de l’ordre reste disponible pour les cas avancés ;
- drapeaux de langue (emoji) affichés dans le sélecteur des sources, des langues de sources et des langues de catalogue, à la place des seuls libellés textuels ;
- organisation des saisons réglable (« Classique », « Fusionnées » ou « Séparées ») : fusionnées = tous les épisodes de toutes les saisons dans une seule fiche, renumérotés et libellés `S1 E1 — titre`, avec palier « Toutes les saisons » pour les fiches enregistrées avant l’activation ; séparées = découpage « Titre — Saison N » dès le catalogue ; le comportement historique reste la valeur par défaut et les deux nouveaux modes sont désactivables ;
- rejet des « téléchargements » qui ramènent une page HTML ou une popup (FrenchStream et autres) : les sondes vérifient désormais le type de contenu et l’échantillon du corps (DOCTYPE, `<html>`, `window.open`, popunder, Adsterra…) et écartent ces liens avec la mention « page HTML/popup » dans le diagnostic, sans confondre avec les vrais 403/404 ; vérification désactivable par réglage ;
- réglage « DNS personnalisé » avec résolveur DNS UDP intégré (requêtes A puis AAAA, cache positif/négatif, bascule automatique sur le DNS du système en cas d’échec), appliqué aux catalogues, manifests, sources, sondes et sous-titres, avec action « Tester la résolution DNS » ;
- fiabilisation Stremio : chaque addon de flux est borné à 15 secondes afin qu’un addon lent ne bloque plus l’affichage des serveurs ;
- écran des réglages réorganisé en six sections titrées (catalogues, lecture, sources Nuvio, Stremio, réseau, aide) et guide des réglages lisible.

## 16.7 — 6 septembre 2026

- détection, cache et exposition **séparée de chaque entrée `catalogs[]`** de tous les manifests Stremio actifs, dans un filtre dynamique inspiré de l’extension Secozzi ;
- prise en charge des options propres au catalogue sélectionné (genre, année, langue…) et application réelle de ce choix à la requête Stremio ;
- serveurs Stremio désormais exposés dès le manifest puis chargés au clic, afin qu’ils restent visibles lorsque Nuvio est désactivé ;
- résolution Stremio renforcée avec variantes d’identifiants IMDb/TMDB et types `series`/`tv`, flux HTTP ou torrents, en-têtes, qualité et sous-titres ;
- Nuvio et Stremio restent tous deux visibles selon l’ordre choisi, afin qu’un résultat VOSTFR d’un moteur ne masque jamais une VF de l’autre ;
- modes Nuvio rapide et équilibré convertis en exécution simultanée par lots bornés de 2 à 4 providers ; le mode rapide poursuit les lots lorsqu’il n’a trouvé que du VOSTFR ;
- rétablissement permanent des réglages avancés utiles : ordre des providers, motifs de priorité, concurrence, clés API, User-Agent, Referer et cookies, sans ajouter de champ URL redondant ;
- le sélecteur Nuvio affiche désormais tous les providers de tous les dépôts ajoutés avant le filtre de langues, y compris ceux désactivés dans leur manifest ou déclarant un type encore inconnu ; un dépôt ajouté plus tard remplace la variante par défaut portant le même identifiant ;
- actualisation immédiate du cache après l’ajout d’un dépôt Nuvio ;
- découverte Stremio parallèle bornée à 10 secondes par manifest et cache de catalogues trié/stable, réécrit uniquement lorsque son contenu change ;
- validation spécifique du dépôt optionnel All-in-One-Nuvio fourni par l’utilisateur (**61 providers détectés sans l’intégrer aux dépôts par défaut**) ;
- conservation de toutes les corrections 16.6 : One Piece non tronqué, catalogues principaux désactivables, langues multiples, sélection individuelle, en-têtes et rejet des flux explicitement refusés.

## 16.6 — 6 septembre 2026

- correction de **One Piece** et des autres séries en cours : suppression du repli fixe à 24 épisodes, prise en compte de `nextAiringEpisode` d’AniList et comptage paginé Jikan jusqu’à la dernière page disponible ;
- ajout des catalogues Stremio complets (catalogue, recherche, fiche, saisons, épisodes et flux), selon les conventions de l’extension Stremio du dépôt Secozzi ;
- ajout d’un sélecteur de catalogue Stremio et possibilité de désactiver ensemble ou séparément TMDB, AniList et Jikan ;
- choix de la priorité **Nuvio → Stremio** ou **Stremio → Nuvio**, tout en continuant à chercher une VF lorsqu’un premier moteur ne fournit que du VOSTFR ;
- choix multiple des langues de catalogue : français, anglais, espagnol, allemand, italien, portugais, japonais, hindi, turc, indonésien, polonais et arabe ;
- ajout de cinq familles de dépôts Nuvio : Gowaru, D3adlyRocket, Yoruix, Phisher et Turkish ; sélection individuelle des providers et filtrage par langue ;
- activation par défaut de tous les providers compatibles avec les langues choisies, sauf exclusion explicite de Movix tant que ses flux répondent HTTP 403 ;
- amélioration du moteur Rhino pour les bundles internationaux : abaissement des boucles `for…of`, prise en charge des littéraux regex, compatibilité des déclarations `const`, chemins de scripts amont de repli et cache séparé par dépôt ;
- conservation des en-têtes de lecture, affichage de la langue/du lecteur/de la qualité et rejet préalable des flux répondant explicitement 401/403/404/410/429/451 ;
- suppression complète de l’intégration CloudStream : seuls les manifests Nuvio et Stremio réellement exécutables peuvent désormais être ajoutés ;
- les indisponibilités propres à un site ou à un provider international n’empêchent plus la publication de l’extension.

## 16.5 — 6 septembre 2026 (correctifs après test OPPO)

- correction du filtre de catalogue : Animés/Films/Séries s’applique aussi avec une recherche vide et reste mémorisé ;
- le même choix pilote désormais les onglets Populaires et Derniers ;
- les titres des serveurs affichent réellement VF/VOSTFR, l’hébergeur et la qualité ;
- le mode rapide continue après une source VOSTFR lorsqu’une VF peut encore être trouvée ;
- FrenchStream, validé en VF par la sonde réseau, passe avant les liens Movix actuellement refusés ;
- validation légère des playlists HLS et de leur premier segment, avec rejet des liens répondant explicitement 403 ;
- Nuvio est exécuté avant Stremio afin de ne plus faire expirer ses URL signées pendant l’attente d’un autre moteur ;
- les sous-titres externes restent attachés aux liens Nuvio, avec une attente strictement limitée à trois secondes ;
- Stremio devient le moteur de repli uniquement lorsque Nuvio ne trouve aucun lien valide ;
- simplification des réglages : une seule entrée clairement identifiée pour coller chaque type d’URL.

## 16.4 — 6 septembre 2026 (candidate Nuvio)

- Nuvio utilise désormais le mode rapide par défaut et s’arrête à la première source réussie ;
- Movix, FrenchStream et Anime-Sama sont les trois seules sources Nuvio activées par défaut ;
- l’ordre des sources est adapté aux films/séries et aux animés ;
- chaque moteur Rhino est borné à 40 secondes, avec arrêt des callbacks tardifs ;
- ajout des modes Nuvio rapide, équilibré et complet ;
- ajout d’un diagnostic Nuvio réel avec durée, état Rhino et détails HTTP sans URL sensible ;
- séparation des entrées et sélecteurs Nuvio, Stremio et CloudStream ;
- ajout d’une migration v3 pour les réglages Nuvio existants ;
- aucun changement fonctionnel des moteurs Stremio et CloudStream dans cette candidate.

## 16.3 — 6 septembre 2026

- correction du crash AndroidX `Key cannot be null` à l’ouverture des deux nouvelles actions de réglages ;
- les actions possèdent désormais une clé stable et interceptent `onClick()` avant le dialogue standard d’`EditTextPreference` ;
- le dialogue standard est explicitement bloqué afin que seule la fenêtre Nuvio/Stremio/CloudStream soit affichée.

## 16.2 — 6 septembre 2026

- ajout d’une fenêtre multi-sélection Nuvio, Stremio et passerelles CloudStream ;
- activation/désactivation persistante de chaque scraper ou addon ;
- ajout de l’import d’un manifest Nuvio, d’un addon Stremio ou d’un `repo.json` CloudStream ;
- associations CloudStream→Nuvio pour les providers français reconnus ;
- mise à jour des dépôts français suivis et ajout du suivi des révisions amont ;
- correction du runtime Rhino (pile dédiée et requêtes GET sans corps fantôme) ;
- correction Stremio des en-têtes de flux, de la qualité et du filtrage des liens factices ;
- ajout de tests déterministes et de sondes réseau Nuvio/Stremio/CloudStream ;
- ajout d’une migration qui rétablit la configuration Stremio par défaut sur les installations existantes v16.1.

## 16.1

- première version du port Aniyomi FR Unifié ;
- catalogues TMDB, AniList et Jikan ;
- moteurs Nuvio, Stremio et sous-titres externes.
