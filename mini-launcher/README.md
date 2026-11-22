# Mini Launcher

Un écran d'accueil sans icônes : les applis s'ouvrent avec une roue alphabétique, depuis
les bords de l'écran. Utilisable aussi par-dessus un autre launcher (icône, widget, bulle).

## Écran d'accueil (launcher)

Réglages > Applis par défaut > Écran d'accueil > **Mini Launcher** (ou bouton dans les
réglages de l'appli).

- **Bandes gauche et droite** (un quart de la largeur, presque toute la hauteur) : poser
  le doigt ouvre la roue sous le doigt ; on glisse jusqu'à l'appli et on lâche — un seul
  geste. Un simple toucher ouvre la roue et attend le doigt suivant.
- **Glisser vers le bas** : moitié gauche → notifications, moitié droite → centre de contrôle.
- **Glisser vers le haut** au milieu : la roue (comme un tiroir d'applis).
- **Appui long sur une zone vide** : ajouter une appli, ajouter un widget, fond d'écran,
  réglages.
- **Grille de 4 colonnes** sous l'horloge pour poser des applis et des widgets. Toucher une
  appli l'ouvre. Appui long puis glisser : déplacer. Appui long sans bouger : retirer,
  infos, ou taille (widgets). Les widgets reçoivent les touchers normalement ; la roue ne
  s'ouvre que depuis les zones vides des bandes.
- Depuis la roue, appui long sur une appli > « Ajouter à l'écran d'accueil ».

## La roue

- **Centre** : la dernière appli utilisée (un toucher la rouvre).
- **Anneau des favoris** : les applis épinglées, complétées par les plus utilisées.
- **Anneau des lettres** A–Z, # et des **groupes** (pastilles de couleur).
- Glisser sur une lettre ou un groupe et s'y arrêter un instant ouvre ses applis sans lever
  le doigt. Un coup de doigt rapide vers une lettre l'ouvre aussi.
- Une lettre qui n'a qu'une appli la lance directement.
- **Appui long sur une appli** : épingler en favori, ajouter à un groupe (ou en créer un),
  retirer du groupe, infos de l'appli.
- **Appui long au centre d'un groupe ouvert** : renommer ou supprimer le groupe.
- Près d'un bord ou dans un coin, les anneaux deviennent des éventails.

## Par-dessus un autre launcher

- **Icône** : si le launcher ne transmet pas la position de l'icône, maintenir le centre
  de la roue puis glisser pour la placer (mémorisé).
- **Widget 1×1** : la roue s'ouvre exactement autour.
- **Bulle flottante** (réglages) : visible par-dessus toutes les applis ; poser le doigt
  dessus ouvre la roue, glisser jusqu'à l'appli, lâcher. Maintenir la bulle sans bouger
  pour la déplacer. Demande l'autorisation « Afficher par-dessus les autres applis ».

## Installer

Chaque envoi sur GitHub construit l'APK (action « Mini Launcher (APK) ») et le publie dans
une release `mini-launcher-vN`. Les versions suivantes s'installent par-dessus (clé de
signature fixe dans `app/debug.keystore` : clé de test, pas pour le Play Store).

## Code

Java sans dépendance. `RadialView` : la roue. `HomeView`/`HomeActivity` : le launcher.
`BubbleService` : la bulle. `RadialActivity` : la roue lancée par l'icône ou le widget.
`Store` : favoris, groupes, usage, réglages. `Menus` : appuis longs. `Shade` : volets.
