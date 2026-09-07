# Journal des modifications

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
