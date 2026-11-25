# Ce qui se passe en mémoire pendant une recherche

Ce document explique, à partir du code de l'application Android, ce qui occupe la mémoire quand on
cherche une photo, et pourquoi la recherche peut faire planter l'application. Il décrit l'état actuel
du code. Les chiffres sont ceux qu'on a observés ou calculés ; ce qui reste à mesurer est signalé.

## 1. Les deux mémoires

Une application Android a deux espaces de mémoire qui ne se comptent pas de la même façon.

- **Le tas Java** (*Java heap*) : les objets que le code Kotlin crée (listes, chaînes, fiches, tables
  de hachage). Il a une limite fixe. Sur le Pixel de test, le message d'erreur indique
  `target footprint 268435456` : la limite est donc **256 Mo**. Si le tas est plein, Android lève une
  `OutOfMemoryError` et arrête l'application.
- **La mémoire native** : les bibliothèques en C++ et les données qu'elles gèrent. ONNX Runtime, qui fait
  tourner les modèles, y range ses données. Cette mémoire n'est pas comptée dans les 256 Mo. Elle a sa
  propre limite, plus large, et elle est surveillée par `WorkerGuard` pendant l'analyse.

Un fichier lu avec `MappedByteBuffer` (mappage mémoire) est dans la mémoire native, pas dans le tas.
C'est ce que fait `ModelFiles.session` pour le modèle de texte.

**Le plantage de la recherche se joue dans le tas Java.** La mémoire native peut être pleine sans
planter l'application de la même façon.

## 2. Ce qui reste en mémoire en permanence

Quand l'écran Rechercher est ouvert, le carnet est en mémoire sous forme de fiches décodées.

- Le carnet est le fichier `library.json`, une liste JSON d'une fiche par photo analysée.
- `Library.load` lit le fichier, le transforme en objets JSON, puis crée une `LibraryEntry` par photo.
  Chaque entrée garde ses champs (nom, chemin, date, texte lu, indicateurs) et son vecteur, un
  `FloatArray` de 768 nombres (environ 3 Ko par photo).
- `MainScreen` garde le résultat dans un `VersionedCache`. Le carnet n'est donc **pas relu à chaque
  recherche** : il est relu quand `dataVersion` change, c'est-à-dire après une analyse ou un changement de
  dossiers (`dataVersion++` dans `MainScreen.kt`).

Ce qui reste donc en mémoire pendant une recherche est surtout le carnet décodé. Sa taille grossit avec le
nombre de photos analysées.

## 3. Qui d'autre lit le carnet

Le carnet est lu à plusieurs endroits, et chaque lecture peut créer sa propre copie en mémoire.

| Qui | Comment | Ce qui reste en mémoire |
|---|---|---|
| Écran Rechercher (`Library.load`) | Lit tout le fichier, crée les fiches | Les fiches, pendant toute la session |
| Analyse (`FullAnalysis.run`) | `LibraryStore.read` puis `readKnown` : lit tout le fichier au démarrage de chaque analyse | Les objets JSON de chaque entrée, pendant toute l'analyse |
| Page des dossiers (`overview`) | Même lecture complète que l'analyse | Les objets, le temps de l'affichage |
| Page « Ajouter un dossier » (`KnownSummary`) | Lit le fichier **entrée par entrée**, ne garde que la taille, la date et le type | Presque rien : c'est la seule lecture économe |

Donc **le carnet peut être présent plusieurs fois dans le tas**, si une analyse tourne pendant qu'on
consulte les dossiers ou qu'on cherche. L'analyse est la lecture la plus lourde, parce qu'elle garde
les objets pendant toute sa durée.

## 4. Le déroulé d'une recherche

Chaque étape est une fonction du code. Les numéros suivent l'ordre dans lequel les choses se passent.

**Étape 1 : la frappe.** `SearchField` met à jour `state.query`. Les lettres s'affichent tout de suite.

**Étape 2 : le délai.** `SearchScreen` attend 400 ms après la dernière lettre (`LaunchedEffect(query)`).
Pendant ce temps, rien n'est calculé. Les résultats texte sont eux aussi retardés de 250 ms.

**Étape 3 : le vecteur de la phrase.** `TextModels.vectorFor` (thread `Dispatchers.Default`) :
- au **premier appel** de l'application, `TextModels.get` crée un `TextModel` ;
- `TextModel` ouvre le modèle de texte (mappé, donc en mémoire native) ;
- il crée un `SigLipTokenizer` : le fichier `tokenizer.json` est lu en texte, puis converti en tables
  de hachage (`vocab`, `ranks`, `cache`). **C'est le dictionnaire.** Il contient des dizaines de milliers
  de morceaux de mots, et chaque entrée est un objet Java ;
- `encode` transforme la phrase en morceaux, puis en 768 nombres (le vecteur de la phrase).

Point important : le `TextModel` n'est gardé en cache que si sa création **réussit**. Si elle échoue
(par exemple faute de mémoire), la prochaine lettre tapée recommence la création. Une série de frappes
peut donc relancer le même pic plusieurs fois.

**Étape 4 : le classement.** `Library.rankDescription` parcourt toutes les fiches :
- `filter { inPlace }` crée une liste ;
- `filter { faces }` peut en créer une autre ;
- `mapNotNull { Match(entry, dot(query, vector)) }` crée un objet `Match` par photo ;
- `sortedByDescending` crée encore une liste triée.

Ces listes sont temporaires. Pour 2 825 photos, chaque `Match` pèse quelques dizaines d'octets : le
classement représente donc **quelques centaines de Ko**, et non plusieurs dizaines de Mo. (Estimation à
vérifier avec le profileur.)

**Étape 5 : les résultats.** `SearchResults.compute` (thread `Default`) construit les lignes affichées.
`Removals.sourcesFor` lit le fichier des photos retirées de cette recherche, lui aussi en JSON.

**Étape 6 : l'affichage.** `GridThumb` décode des miniatures (`decodeThumbnail`) sur le thread IO. Les
pixels d'une image ne sont pas dans le tas Java sur les versions récentes d'Android : ce point reste à
confirmer avec le profileur.

## 5. Le plantage du soir

La trace de plantage (`FATAL EXCEPTION: main`, `OutOfMemoryError`) a les caractéristiques suivantes :

- l'erreur est une `OutOfMemoryError` : c'est un manque de **tas Java**, pas un plantage natif ;
- la dernière allocation qui échoue est celle du tokenizer, dans `SigLipTokenizer.<init>` ;
- la pile remonte à `TextModels.vectorFor`, puis à la recherche (`SearchScreen`) ;
- au moment du plantage, il restait environ 157 Ko libres sur 256 Mo.

Donc la recherche a déclenché le chargement du dictionnaire, alors que le tas était déjà presque plein.
Ce qui remplissait le tas n'est pas encore établi : le carnet décodé est le candidat principal, et les
copies faites par l'analyse ou la page des dossiers sont à vérifier.

## 6. Ce qu'on ne sait pas encore

- La **taille réelle du carnet** en mémoire (pas la taille du fichier sur le disque, qu'on connaît : environ
  22 à 28 Mo pour 2 825 photos).
- La **taille réelle du dictionnaire** du tokenizer.
- Les **copies qui coexistent** au moment du plantage : cela dépend de ce qui tournait (analyse, page des
  dossiers, écran Rechercher).
- La **taille des miniatures** en mémoire.

## 7. Comment mesurer

1. Dans Android Studio, ouvre le **Memory Profiler** sur le téléphone branché, avec l'application lancée.
2. Note la mémoire Java avant la recherche, puis tape une phrase. Prends un **heap dump** juste après
   le premier résultat : il liste les objets par taille et par classe.
3. Pour savoir ce qui était présent pendant le plantage, garde le **Logcat** enregistré
   (`Save Logcat to File`), avec les lignes `FATAL EXCEPTION` et la pile.
4. Le journal de l'application (`ChatLab/diagnostics`) contient déjà `searchRank` (durée, nombre de
   photos, nombre de résultats) et `searchTimeout`. Il ne contient pas encore la mémoire utilisée.

## 8. Pistes, non implémentées

- **Une seule lecture du carnet, partagée.** Aujourd'hui, l'analyse, la page des dossiers et l'écran
  Rechercher lisent chacun le fichier. Une seule copie, ou une lecture entrée par entrée comme
  `KnownSummary`, supprimerait les pics qui se superposent.
- **Des vecteurs rangés dans un seul tableau de nombres**, au lieu d'un objet par photo : 2 825 photos
  × 768 × 4 octets ≈ 8,7 Mo, contre plusieurs fois plus avec les objets.
- **Un dictionnaire compact** pour le tokenizer, ou une lecture qui ne garde que ce qui sert.
- **Un message d'erreur propre** en cas de `OutOfMemoryError` pendant le chargement du modèle, au lieu
  d'un arrêt brutal. Ce n'est qu'un filet de sécurité, pas une correction.

Ces pistes doivent être choisies après la mesure, pour cibler ce qui pèse vraiment.
