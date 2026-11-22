# Mini Launcher

Un écran d'accueil Android épuré (portrait uniquement) : les applis s'ouvrent depuis un alphabet vertical sur les
bords de l'écran, et les dossiers de l'accueil s'ouvrent en cercle.

Pour l'activer : Paramètres > Applis par défaut > Écran d'accueil > **Mini Launcher**
(ou bouton dans les réglages de l'appli).

## Alphabet sur les bords

- Poser le doigt sur un bord et le glisser de haut en bas : la lettre sous le doigt
  grossit (effet loupe) et ses applis apparaissent à côté, en direct.
- Glisser vers l'intérieur jusqu'à l'appli et lâcher. Lâcher sur l'alphabet laisse les
  applis affichées pour un toucher.
- En haut ★ les favoris (épinglés + plus utilisés).
- Appui long sur une appli : épingler, ajouter à un dossier, poser sur l'accueil, infos.

## Accueil

- Horloge (toucher = alarmes) puis une grille d'applis, de dossiers et de widgets.
- **Appui long sur une zone vide** : ajouter une appli, créer un dossier, ajouter un widget,
  fond d'écran, réglages.
- **Applis** : toucher = ouvrir ; appui long puis glisser = déplacer ; appui long sans
  bouger = menu. Lâcher une appli sur une autre crée un dossier ; sur un dossier, elle y entre.
- **Dossiers** : poser le doigt ouvre le dossier en cercle autour de lui ; glisser jusqu'à
  l'appli et lâcher. Un simple toucher garde le cercle ouvert. Maintenir sans bouger :
  déplacer le dossier, ou son menu (applis, renommer, retirer, supprimer).
- **Widgets** : reçoivent les touchers ; appui long = déplacer, taille, régler, retirer.
- **Glisser vers le bas** au milieu : à gauche les notifications, à droite le centre de
  contrôle. Sur HyperOS, le centre de contrôle s'ouvre parfois à moitié par l'appel direct :
  les réglages proposent plusieurs méthodes (relance, fermer puis ouvrir, notifications puis
  centre de contrôle, ou désactivé) avec un bouton « Essayer ».

## Gestes

Réglables dans les réglages : double tap dans le vide (par défaut : verrouiller), glisser
vers le haut (par défaut : applis récentes), vers le bas à gauche (notifications) et à droite (centre de contrôle), vers
la gauche, vers la droite. Actions : rien, verrouiller, notifications, centre de contrôle,
ouvrir une appli, lampe torche, appareil photo, recherche, réglages, applis récentes.

Applis récentes : glisser vers le haut les ouvre en roue autour du doigt ; continuer jusqu'à
l'appli et lâcher (un toucher garde la roue ouverte). Avec l'« Accès aux données
d'utilisation » (autorisation Android, pas l'accessibilité), toutes les applis utilisées ;
sans, celles ouvertes depuis le launcher.

Le verrouillage utilise le droit « administrateur de l'appareil » limité au verrouillage
(demandé au premier usage). Pour désinstaller l'appli : d'abord « Retirer le droit de
verrouillage » dans les réglages.

## Réglages

Appui long sur une zone vide > Réglages du launcher (ou « Mini Launcher » dans le tiroir).

- Alphabet : côté(s), hauteur, position, effet loupe, couleur et opacité de la zone,
  couleur des lettres, toujours visible, largeur de la zone tactile.
- Lisibilité : icônes de la barre d'état et texte de l'accueil (horloge, noms) automatiques
  selon la clarté du fond d'écran, ou forcés clair/foncé ; voile sombre en haut.
- Horloge : afficher, taille, position, date.
- Grille : colonnes, lignes, taille des icônes, noms.
- Favoris : nombre, complément automatique.
- Dossiers : fond et opacité du cercle, taille des icônes et du cercle, noms des applis,
  bordure et fond de l'icône. Pour un dossier précis : maintenir puis lâcher sur place >
  Personnaliser (couleur, style d'icône — grille, première appli, initiale —, forme).
- Effets : couleur d'accent, assombrissement du fond, animations, vibrations.

## Installer

Chaque envoi sur GitHub construit l'APK (action « Mini Launcher (APK) ») et le publie dans
une release `mini-launcher-vN` (fichier `MiniLauncher-<version>.apk`). Les versions suivantes s'installent par-dessus (clé de
signature fixe dans `app/debug.keystore` : clé de test, pas pour le Play Store).

## Code

Java sans dépendance. `HomeActivity`/`HomeView` : le launcher et ses gestes. `HomeGrid` :
la grille. `AlphaStripView` : l'alphabet. `FolderWheelView` : un dossier ouvert en cercle.
`Store` : favoris, dossiers, accueil, réglages. `Menus` : appuis longs. `Sheet` : panneaux
qui montent du bas (listes, choix, saisie). `WidgetPicker` : galerie des widgets. `Shade` : volets.
