# Rhino intégré

`src/fr/frunified/libs/rhino-nuvio-1.9.1.jar` dérive de **Mozilla Rhino 1.9.1** (`Rhino1_9_1_Release`) et reste soumis à la Mozilla Public License 2.0.

Le moteur est :

- adapté aux API Android disponibles ;
- corrigé pour la syntaxe `yield` produite dans certains bundles Nuvio ;
- relocalisé de `org.mozilla.javascript` vers `com.frunified.rhino` afin d’éviter les collisions avec l’application hôte.

Le source upstream est disponible à <https://github.com/mozilla/rhino/tree/Rhino1_9_1_Release>. Les modifications exactes sont conservées dans :

- `rhino-parser-patches.patch` ;
- `rhino-android-patches.patch` ;
- `rhino-full-patches.patch` (ensemble complet utilisé pour le jar fourni) ;
- `Relocate.java` (relocalisation du bytecode et des ressources).

## Reconstruction

```bash
git clone --branch Rhino1_9_1_Release --depth 1 https://github.com/mozilla/rhino.git /tmp/rhino
cd /tmp/rhino
git apply /chemin/vers/aniyomi-fr-unified/third_party/rhino/rhino-full-patches.patch
./gradlew :rhino:jar

cd /chemin/vers/aniyomi-fr-unified
JAVA_HOME=/chemin/vers/jdk-17 \
  ./third_party/rhino/build-relocated.sh /tmp/rhino/rhino/build/libs/rhino-1.9.1.jar
```

Si le tag upstream ou son système de build change, partez toujours du tag indiqué ci-dessus ; les patchs correspondent à cette révision.
