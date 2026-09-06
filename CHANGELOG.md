# Journal des modifications

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
