# Mini Launcher

Une seule icône sur l'écran d'accueil à la place de toutes les autres. On la touche :
l'alphabet s'ouvre **en cercle autour de l'icône**, on choisit une lettre, et ses applis
se déploient autour du centre. Ce n'est pas un launcher de remplacement : l'écran d'accueil
habituel reste en place.

## Utilisation

- **Toucher une lettre** : ses applis s'affichent. Les lettres sans appli sont grisées.
- **Glisser** sur la roue : la lettre sous le doigt s'allume (petite vibration) ; si on
  s'y arrête un instant, les applis s'ouvrent sans lever le doigt, et on glisse jusqu'à
  l'appli voulue puis on lâche — tout en un seul geste.
- **Appui long sur une appli** : sa fiche dans les paramètres (désinstaller, notifications…).
- **Centre** : ferme la roue (lettres) ou revient aux lettres (applis). Toucher à côté ou
  Retour fonctionne aussi.
- Beaucoup d'applis pour une lettre : une case « +n » mène à la suite.
- Les lettres accentuées sont regroupées (É → E) ; chiffres et symboles vont dans « # ».

## Installer

Chaque envoi sur GitHub construit l'APK (action « Mini Launcher (APK) ») et le publie dans
la release `mini-launcher-latest`. Sur le téléphone : ouvrir `MiniLauncher.apk`, autoriser
l'installation depuis le navigateur, installer. Les versions suivantes s'installent
par-dessus (la clé de signature est fixe, dans `app/debug.keystore` : clé de test, pas
pour le Play Store).

Puis : glisser l'icône **Mini Launcher** sur l'écran d'accueil et ranger les autres icônes
dans le tiroir d'applis.

## Construire soi-même

Android Studio (ouvrir le dossier `mini-launcher`) ou, avec le SDK Android installé :
`gradle assembleDebug`. Code en Java, sans dépendance : `RadialView` dessine la roue,
`AppRepository` liste les applis, `RadialActivity` est l'écran transparent.
