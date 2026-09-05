# Contribuer

1. Utilisez JDK 17 et Android SDK 34.
2. Modifiez le module `src/fr/frunified`.
3. Incrémentez `extVersionCode` dans `src/fr/frunified/build.gradle` pour toute version publiée.
4. Vérifiez le format et la compilation :

```bash
./gradlew :src:fr:frunified:spotlessApply
./gradlew :src:fr:frunified:assembleDebug
```

N’ajoutez pas de clé de signature, de jeton API privé ou de cookie au dépôt. Les changements des catalogues et scrapeurs doivent rester isolés afin qu’une source indisponible ne bloque pas les autres.
