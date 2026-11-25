# Lecteur

Lecteur audio/vidéo YouTube léger pour Android (usage personnel).

- Audio qui continue écran verrouillé ou appli fermée (notification + contrôles écran de verrouillage)
- Bascule automatique en audio seul dès que l'appli n'est plus à l'écran ; la vidéo revient au retour
- Glisser vers le bas sur la vidéo → audio seul (bouton « Vidéo » pour revenir)
- Vitesse de lecture (roue dentée du lecteur, retenue d'une vidéo à l'autre)
- Sous-titres YouTube, y compris automatiques (bouton CC)
- Recherche, lien collé, ou « Partager » depuis l'appli YouTube
- Plein écran en tournant le téléphone ou via le bouton du lecteur
- Interface Material You (couleurs du fond d'écran), glisser entre les écrans, mini-lecteur
- Abonnements : chaînes suivies et leurs nouveautés ; chaînes dans la recherche
- Appui long sur une vidéo : écouter directement en arrière-plan, télécharger, voir la chaîne, partager
- Accueil : recherches récentes (appui long pour en retirer une), vidéos lues récemment, fil « D'après tes recherches »
- Hors ligne : téléchargement audio (m4a) ou vidéo (mp4 720p max) dans Music/Lecteur et Movies/Lecteur (mémoire interne) ; suivi en direct, annulation, relance des échecs
- Dossiers : ouvrir un fichier ou ajouter des dossiers du téléphone ; lecture enchaînée du dossier

## Installer

L'APK est compilé par GitHub Actions (workflow « APK Lecteur ») et publié dans la
release **lecteur** du dépôt : télécharger `lecteur.apk` depuis le téléphone et l'ouvrir.

## Quand YouTube casse la lecture

Relancer le workflow (Actions → APK Lecteur → Run workflow) : il recompile avec la
dernière version de NewPipeExtractor. Réinstaller l'APK par-dessus.

## Signature

Sans réglage, l'APK est signé avec une clé de debug gardée dans le cache de GitHub Actions
(effacée après 7 jours sans compilation : il faut alors désinstaller avant de réinstaller).
Pour une clé permanente, ajouter les secrets `LECTEUR_KEYSTORE_B64` (keystore en base64,
alias `lecteur`) et `LECTEUR_KEYSTORE_PASSWORD` dans Settings → Secrets → Actions.
