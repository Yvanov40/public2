# Publier Liseuse sur le Google Play Store

Tout ce qu'il faut pour la Play Console : textes de la fiche, réponses aux formulaires et étapes dans l'ordre.
Les images sont dans ce dossier (`banniere.png`, `icone-512.png`, `captures/`).

---

## 1. Avant de commencer

- [ ] **Nom de l'appli** : « Liseuse » est très générique (difficile à trouver dans le Store, et peut-être déjà pris).
      Idées : *Liseuse Docs*, *Lis Mes Docs*, *Docalivre*, *Papyrus – Word en livre*… (30 caractères maximum).
- [ ] **Identifiant** : `fr.liseuse.lecteur` (dans `build.gradle`). Il est **définitif** une fois publié.
- [ ] **Adresse e-mail de contact** : à remplacer dans `confidentialite.html` (`ADRESSE@EXEMPLE.FR`).
- [ ] **Politique de confidentialité en ligne** : publier `confidentialite.html`, par exemple avec GitHub Pages,
      et noter son adresse.

## 2. Compte et clé de signature

1. Créer le compte sur <https://play.google.com/console> (25 $ une fois, vérification d'identité).
2. Créer la **clé d'envoi** (« upload key ») sur un ordinateur où Java est installé :
   ```sh
   keytool -genkeypair -keystore liseuse-upload.jks -alias liseuse -keyalg RSA -keysize 2048 -validity 10000
   ```
   **Garder ce fichier et ses mots de passe en lieu sûr**, hors du dépôt (le dépôt est public).
3. Dans GitHub → *Settings* → *Secrets and variables* → *Actions*, ajouter :
   - `LISEUSE_KEYSTORE_BASE64` : le résultat de `base64 -w0 liseuse-upload.jks`
   - `LISEUSE_KEYSTORE_PASSWORD`, `LISEUSE_KEY_ALIAS` (`liseuse`), `LISEUSE_KEY_PASSWORD`
4. Relancer la construction (onglet *Actions* → « Liseuse (Play Store) » → *Run workflow*).
   Le fichier `.aab` signé se télécharge en bas de la page de la construction (*Artifacts*).
5. Lors du premier envoi, accepter **Play App Signing** (Google garde la clé finale ; la clé d'envoi peut être
   réinitialisée en cas de perte).

## 3. Créer l'appli dans la Play Console

- Langue par défaut : Français (France)
- Appli ou jeu : **Appli** — Gratuite ou payante : **Gratuite** (la version Pro est un achat intégré)
- Catégorie : **Livres et références**

## 4. Le produit « Liseuse Pro »

*Monétiser → Produits → Produits intégrés → Créer un produit*

| Champ | Valeur |
|---|---|
| ID du produit | **`liseuse_pro`** (doit être exactement celui-ci, il est dans le code) |
| Nom | Liseuse Pro |
| Description | Débloque les PDF, les dossiers, la sélection multiple et la personnalisation. Achat unique. |
| Prix | 3,99 € (conseillé : entre 2,99 € et 4,99 €) |

Le produit n'est utilisable qu'après l'envoi d'une première version contenant la bibliothèque d'achats
(n'importe quelle piste de test).

Pour tester l'achat sans payer : *Paramètres → Test de licence*, ajouter votre adresse Gmail.

## 5. Fiche du Store

**Nom** (30 car. max) : `Liseuse – Word, PDF en livre`

**Description courte** (80 car. max) :
> Lisez vos documents Word et PDF comme de vrais livres, page par page.

**Description complète** :

> Vos documents méritent mieux qu'un zoom et un défilement sans fin. **Liseuse** transforme vos fichiers Word (.docx), PDF et texte en véritables livres numériques, pour les lire confortablement sur votre téléphone, page par page.
>
> **Vos documents, enfin lisibles**
> • Conversion Word fidèle : titres et chapitres, sommaire cliquable, images, notes de bas de page, listes, tableaux, gras, italique, couleurs.
> • Repère tout seul les « Chapitre 1 », « Prologue »… même sans mise en forme.
> • Ajout en un geste : bouton « Ajouter », ou « Partager / Ouvrir avec » depuis Gmail, Drive, Word, vos fichiers…
>
> **Une lecture sur mesure**
> • Taille du texte, interligne, marges, texte justifié, césure.
> • 6 thèmes (clair, crème, sépia, tilleul, nuit, noir OLED) et polices très lisibles, dont une police adaptée à la dyslexie.
> • Mode pages ou défilement, deux pages en paysage, touches de volume, lecture à une main.
> • Luminosité plus sombre que le minimum du téléphone et filtre anti-lumière bleue pour lire le soir.
> • Marque-pages, recherche dans le livre, reprise automatique là où vous vous êtes arrêté.
>
> **Liseuse Pro** (achat unique, sans abonnement)
> • PDF : convertis en livre au texte adaptable, ou lus tels quels avec zoom, même très gros.
> • Tableaux et graphiques des PDF conservés.
> • Dossiers et sous-dossiers, sélection multiple.
> • Couleur de l'appli, choix de l'icône, raccourci avec votre image, thème de lecture sur mesure.
>
> **Vos documents restent chez vous**
> Aucun compte, aucune publicité, aucun envoi sur Internet : tout est converti et stocké sur votre téléphone. Exportez vos livres en EPUB pour les lire sur une liseuse (Kobo…) quand vous le souhaitez.
>
> Idéal pour relire un manuscrit, réviser ses cours, lire des rapports, des mémoires, des recettes ou des documents de travail.

**Éléments graphiques** :
- Icône : `icone-512.png` (512 × 512)
- Bannière : `banniere.png` (1024 × 500)
- Captures téléphone : `captures/*.png` (1080 × 1920)

**Coordonnées** : adresse e-mail (obligatoire), site web facultatif.

## 6. Contenu de l'appli (formulaires obligatoires)

| Formulaire | Réponse |
|---|---|
| Règles de confidentialité | Adresse de `confidentialite.html` en ligne |
| Accès aux applis | Toutes les fonctionnalités sont accessibles sans restriction (pas de compte) |
| Annonces | **Non**, l'appli ne contient pas d'annonces |
| Classification du contenu | Questionnaire : catégorie « Référence, actualités ou éducation » ; répondre **Non** à tout (pas de violence, pas d'échange entre utilisateurs, pas de contenu généré partagé…) → classement « Tout public » |
| Public cible | 13 ans et plus (évite les obligations spécifiques aux applis pour enfants) |
| Sécurité des données | **Aucune donnée collectée**, **aucune donnée partagée** ; les données ne quittent pas l'appareil. (Les achats sont gérés par Google Play.) |
| Appli d'actualités | Non |
| Applis gouvernementales / financières / santé | Non |

## 7. Test fermé obligatoire (comptes personnels récents)

Google exige un **test fermé avec au moins 12 testeurs pendant 14 jours d'affilée** avant la publication.

1. *Tester → Test fermé → Créer une piste*, envoyer le `.aab`.
2. Ajouter les adresses Gmail des testeurs (famille, collègues, amis…) dans une liste.
3. Leur envoyer le lien d'inscription au test : ils installent l'appli depuis le Play Store et la gardent 14 jours.
4. Ensuite : *Production → Demander l'accès à la production*, répondre au questionnaire sur le test.

## 8. Mises à jour

1. Augmenter `versionCode` (et `versionName`) dans `version.properties`.
2. La construction GitHub produit le nouveau `.aab` signé.
3. *Production → Créer une release* → envoyer le `.aab` → notes de version → publier.

---

### Deux éditions

| | Édition Play Store | Édition complète |
|---|---|---|
| Construite par | Gradle (GitHub Actions) | `./construire.sh` |
| Identifiant | `fr.liseuse.lecteur` | `fr.liseuse` |
| Pro | achat intégré | tout débloqué |

Les deux peuvent être installées en même temps sur un téléphone.
