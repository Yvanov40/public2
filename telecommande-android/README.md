# Télécommande (Android)

Transforme un téléphone Android (9 ou plus) en télécommande **Bluetooth** pour Google TV / Android TV,
avec des **raccourcis d'applis** personnalisables.

Le téléphone se présente à la TV comme un périphérique Bluetooth HID (clavier + touches multimédia),
exactement comme une télécommande ou un clavier Bluetooth : pas de Wi-Fi, rien à installer sur la TV.

## Installer

L'APK est compilé automatiquement par GitHub Actions (workflow « APK Télécommande ») et publié dans la
release **telecommande** du dépôt : télécharger `telecommande.apk` sur le téléphone et l'ouvrir
(autoriser l'installation d'applis inconnues).

## Première connexion

1. Ouvrir l'appli, accepter l'autorisation « Appareils à proximité ».
2. Icône Bluetooth › **Rendre visible**.
3. Sur la TV : Paramètres › Télécommandes et accessoires › **Associer un accessoire**, choisir le téléphone, accepter.

Ensuite la connexion se rétablit seule à l'ouverture de l'appli.

## Touches

Marche/arrêt, clavier, menu, Assistant/recherche, réglages (appui long Accueil), pavé directionnel + OK,
retour, accueil, TV, volume ± / muet, chaîne ±, guide, retour/avance rapide, lecture/pause.
Maintenir une touche la répète. Les touches de volume du téléphone règlent la TV (désactivable).

## Raccourcis d'applis

Un raccourci rejoue une suite de touches enregistrée une fois (ex. Accueil › flèches jusqu'à l'icône Netflix › OK),
avec pauses et texte possibles. Appui long : renommer, couleur, modifier les étapes (`k:home`, `w:1000`, `t:texte`), supprimer.

Le Bluetooth HID ne permet pas d'ouvrir directement une appli par son nom : la séquence enregistrée est la
méthode fiable. Placer ses applis favorites en tête de la rangée d'applis de la TV garde les séquences stables.

## Limites

- Certains fabricants désactivent le profil « HID Device » : l'appli l'indique au démarrage.
- Le micro ne transmet pas la voix (il ouvre l'Assistant de la TV) : utiliser le clavier pour taper.
- Clavier en QWERTY (disposition par défaut d'Android TV) ; accents retirés.
