# Liseuse — Word → EPUB sur Android

Application Android qui transforme vos documents **Word (.docx)** en livres **EPUB** et les affiche
page par page, comme une liseuse. Tout se passe sur le téléphone : aucun fichier n'est envoyé sur Internet.

**Installer :** téléchargez [`Liseuse.apk`](Liseuse.apk) sur le téléphone, ouvrez-le et autorisez
l'installation depuis cette source si Android le demande. Android 7.0 ou plus récent.

## Ajouter un document

- bouton **Ajouter** dans la bibliothèque (plusieurs fichiers à la fois possibles) ;
- ou, depuis Word, Drive, Gmail, Fichiers… : **Partager** ou **Ouvrir avec → Liseuse**.

Formats acceptés : `.docx`, `.pdf`, `.epub` (lu tel quel), `.txt`. Pour un ancien `.doc`, un `.odt` ou un `.rtf`,
enregistrez-le d'abord en `.docx`.

### Les PDF

À l'ajout d'un PDF, la liseuse demande comment le lire (ce choix peut être mémorisé dans les réglages) :

- **le garder tel quel** : pages identiques à l'original, défilement, zoom au pincement ou double toucher,
  sommaire du PDF, « aller à la page », mode nuit (pages inversées), luminosité ; idéal pour magazines,
  plans, partitions, documents scannés. Il peut être converti plus tard (« Convertir ce PDF en livre ») ;
- **le convertir en livre** (EPUB), décrit ci-dessous. Le PDF d'origine reste consultable
  (« Voir le PDF d'origine » dans le menu du livre).

Un PDF est figé en pages ; la liseuse le **remet en texte fluide** pour qu'il s'adapte à l'écran :
lignes recollées en paragraphes, mots coupés en fin de ligne réparés, titres repérés à la taille des
caractères (chapitres et sommaire), en-têtes, pieds de page et numéros de page retirés, gras, italique
et appels de notes gardés, images découpées dans la page. La première page sert de couverture.
Les **tableaux** sont reconstruits, qu'ils aient des bordures (cellules fusionnées comprises) ou des
colonnes simplement alignées ; les **graphiques et schémas dessinés** sont gardés en image, avec leurs
étiquettes. Un PDF scanné (sans texte) est gardé en images de pages.

## Ce que la conversion conserve

| Dans Word | Dans le livre |
|---|---|
| Titre 1, 2, 3… (styles de titre) | Chapitres et sommaire cliquable |
| « Chapitre 1 », « Prologue »… tapés sans style | Repérés automatiquement comme chapitres |
| Images (y compris dans les zones de texte) | Gardées à leur taille relative, touchez-les pour zoomer |
| Notes de bas de page et de fin | Fenêtre de note au toucher de l'appel |
| Gras, italique, souligné, barré, exposant, petites capitales | Identiques |
| Couleurs et surlignage | Gardés (option « Couleurs du document ») |
| Listes à puces / numérotées, imbriquées | Gardées, numérotation continue |
| Tableaux (cellules fusionnées) | Gardés |
| Liens Internet et renvois internes | Cliquables |
| Citations, légendes, centré / à droite | Gardés |
| Sauts de page, `* * *` | Nouvelle page, séparateur de scène |
| Titre et auteur (propriétés du fichier) | Métadonnées du livre |
| Première image du document | Couverture (sinon une couverture est générée) |

La table des matières de Word, les en-têtes, pieds de page et numéros de page sont ignorés : la liseuse a les siens.

## La bibliothèque

- **Couvertures ou liste** : bouton en haut de l'écran (la liste montre format, taille, progression) ;
- **Dossiers** et sous-dossiers : menu ⋮ → « Nouveau dossier », puis « Déplacer » sur les livres.
  Un document ajouté depuis un dossier y est rangé directement. Supprimer un dossier ne supprime pas ses livres ;
- **Sélection multiple** : appui long sur un livre ou un dossier (ou menu ⋮ → « Sélectionner »),
  puis tout sélectionner, déplacer, supprimer, partager, enregistrer les EPUB dans un dossier du
  téléphone, marquer comme lu / non lu ;
- **Import de plusieurs fichiers** à la fois depuis le bouton « Ajouter ».

## Personnalisation de la lecture (bouton « Affichage »)

- **Taille du texte**, **interligne**, **marges** ;
- **6 thèmes** : Clair, Crème, Sépia, Tilleul, Nuit, Noir (écran OLED) ;
- **Polices** : Literata, Merriweather, Atkinson Hyperlegible (très lisible), OpenDyslexic (dyslexie),
  serif et sans-serif du système, ou police d'origine ;
- texte **justifié** ou **à gauche**, paragraphes **en retrait** ou **espacés**, **césure** ;
- mode **pages** (glisser ou toucher les bords) ou **défilement** continu ;
- **images** à leur taille ou en pleine largeur ; atténuées ou inversées en thème sombre ;
- **deux pages** côte à côte en paysage, **lecture à une main**, **touches de volume**,
  **plein écran**, **écran toujours allumé**, **luminosité** propre à la lecture ;
- texte « plus appuyé », couleurs du document, titre / page / heure en haut et en bas ;
- **liens vers Internet** activables ou non (désactivés, ils s'affichent comme du texte normal ; les renvois internes et les notes restent actifs).

**Luminosité** : en dessous du minimum du téléphone grâce au mode nuit (tout à gauche du curseur),
et **filtre chaud** contre la lumière bleue pour lire le soir.

**Personnalisation** (roue dentée de la bibliothèque) : **couleur de l'application** (8 teintes ou
couleur libre), **icône** sur l'écran d'accueil (8 modèles), **raccourci avec votre propre image** et
le nom de votre choix (Android 8 et plus), et un **thème de lecture personnalisé** (couleur du fond et
du texte au choix) dans le panneau Affichage.

Et aussi : **sommaire**, **marque-pages**, **recherche** dans tout le livre, position de lecture mémorisée,
« Continuer la lecture », tri et recherche dans la bibliothèque, **export EPUB** (pour l'utiliser dans
une autre liseuse, une Kobo…), **partage**, **reconversion** d'un livre avec de nouveaux réglages.

Réglages de conversion (roue dentée de la bibliothèque) : chapitre à chaque Titre 1 / 1 et 2 / 1 à 3,
repérage des chapitres sans style, sauts de page, allègement des grandes images, image de couverture.

## Deux éditions

- **Édition complète** (ce dépôt, `./construire.sh`) : toutes les fonctions débloquées, identifiant `fr.liseuse`.
- **Édition Play Store** (gratuite + achat unique « Liseuse Pro ») : construite par Gradle sur GitHub
  Actions (`.github/workflows/liseuse-android.yml`), identifiant `fr.liseuse.lecteur`. Les fichiers `.aab`
  et `.apk` se téléchargent dans l'onglet *Actions* de GitHub. Tout le nécessaire pour la publication
  (textes, formulaires, politique de confidentialité, bannière, captures) est dans [`play-store/`](play-store/FICHE.md).

Fonctions Pro : PDF, dossiers, sélection multiple, personnalisation (couleur, icône, raccourci, thème sur mesure).

## Construire l'APK

Sans Android Studio ni Gradle, sur Debian / Ubuntu :

```sh
sudo apt-get install android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner openjdk-17-jdk zip
./construire.sh
```

Le script compile les ressources (`aapt`), le Java (`javac`), produit le `classes.dex` (`dx`), aligne et
signe l'APK avec `liseuse.keystore` (créée au premier lancement si elle n'existe pas).
Cette clé n'est **pas** dans le dépôt, qui est public : gardez-en une copie à part. Une mise à jour
ne s'installe par-dessus l'ancienne version que si elle est signée avec la même clé.

## Organisation

- `src/fr/liseuse/DocxConverter.java` : lecture du .docx (styles, numérotation, notes, images, tableaux, champs)
- `src/fr/liseuse/EpubBuilder.java` : écriture de l'EPUB 3 (chapitres, sommaire nav + NCX, notes, couverture)
- `src/fr/liseuse/EpubLivre.java` : lecture d'un EPUB (métadonnées, ordre de lecture, sommaire)
- `src/fr/liseuse/MainActivity.java` : WebView, import / export / partage, pont JavaScript
- `src/fr/liseuse/AndroidImages.java` : réduction des images, couverture générée
- `assets/www/` : interface (bibliothèque, lecteur paginé, réglages) et polices
- `assets/www/visionneuse-pdf.js` : lecture d'un PDF gardé tel quel (pages rendues par pdf.js)
- `assets/www/conversion-pdf.js` : remise en page des PDF avec pdf.js (`assets/www/pdfjs/`, licence Apache 2.0)
- `graphisme/icone.py` : génère les 8 icônes au choix

Polices sous licence SIL Open Font License : Literata, Merriweather, Atkinson Hyperlegible, OpenDyslexic.
