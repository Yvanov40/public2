# Cahier de classe

Appli web pour la classe : cahier journal, emploi du temps, programmation annuelle, agenda de l'enseignant et 108 heures, devoirs, gestion de classe, appel, dossiers élèves, mots aux familles.

- `index.html` : la page et son apparence
- `core.js` : données, calcul automatique de la programmation, sauvegarde, note vocale
- `vues-a.js` : aujourd'hui, cahier journal, fiche de séance, emploi du temps, programmation
- `vues-b.js` : agenda de l'enseignant et 108 h, élèves, évaluations, bilans, appel, administratif, réglages
- `vues-c.js` : noter vite (un ou plusieurs élèves), mots aux familles, lecture d'une séance
- `vues-d.js` : gestion de classe (plans, groupes, tirage au sort) et devoirs
- `demo.js` : matières selon le niveau, classe d'exemple, démarrage
- `sw.js`, `manifest.webmanifest`, `icone*.png` : installation et mode hors connexion

Les données de la classe restent dans le navigateur de l'appareil. Rien n'est envoyé dans ce dépôt.

## Liseuse Android

Le dossier [`liseuse/`](liseuse/) contient une application Android indépendante qui convertit les documents Word en livres EPUB et les affiche comme une liseuse. APK prêt à installer : [`liseuse/Liseuse.apk`](liseuse/Liseuse.apk).
