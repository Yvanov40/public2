'use strict';
// =====================================================================================
//  demo.js — les matières des programmes selon le niveau, la classe d'exemple, et le démarrage.
//  Ce fichier est chargé en dernier : il lance le premier affichage une fois tout le reste prêt.
// =====================================================================================

// ---------- Les matières selon le niveau ----------
// [nom, couleur, sous-matières] pour chaque cycle.
const CYCLES = {
  c1: { l: 'Maternelle (PS, MS, GS)', s: [['Langage', '#3355c0', ['Oral', 'Phonologie', 'Graphisme', 'Écriture', 'Albums']], ['Outils mathématiques', '#c0392b', ['Nombres', 'Formes et grandeurs', 'Suites organisées']],
    ['Explorer le monde', '#23795b', ['Le temps', 'L’espace', 'Le vivant', 'Les objets, la matière']], ['Activité physique', '#d08400', ['Motricité', 'Jeux collectifs', 'Danse']], ['Activités artistiques', '#8a3b9c', ['Arts visuels', 'Chant et musique']], ['Vivre ensemble', '#6b7280', []]] },
  c2: { l: 'Cycle 2 (CP, CE1, CE2)', s: [['Français', '#3355c0', ['Lecture', 'Écriture', 'Étude de la langue', 'Oral']], ['Mathématiques', '#c0392b', ['Nombres et calculs', 'Grandeurs et mesures', 'Espace et géométrie']],
    ['Questionner le monde', '#23795b', ['Le vivant', 'La matière', 'Les objets', 'Le temps', 'L’espace']], ['Anglais', '#b83280', []], ['EPS', '#d08400', []], ['Arts plastiques', '#8a3b9c', []], ['Éducation musicale', '#0e7c96', []], ['EMC', '#6b7280', []]] },
  c3: { l: 'Cycle 3 (CM1, CM2)', s: [['Français', '#3355c0', ['Lecture', 'Écriture', 'Étude de la langue', 'Oral']], ['Mathématiques', '#c0392b', ['Nombres et calculs', 'Grandeurs et mesures', 'Espace et géométrie']],
    ['Sciences et technologie', '#23795b', []], ['Histoire', '#a2630c', []], ['Géographie', '#0f766e', []], ['Anglais', '#b83280', []], ['EPS', '#d08400', []], ['Arts plastiques', '#8a3b9c', []], ['Éducation musicale', '#0e7c96', []], ['Histoire des arts', '#7c3aed', []], ['EMC', '#6b7280', []]] }
};
// Charge les matières d'un cycle. Celles déjà utilisées (emploi du temps, séquences, évaluations, devoirs) sont gardées.
function loadCycle(k) {
  const used = new Set([...db.timetables.flatMap(t => t.slots.map(s => s.subjectId)), ...db.sequences.map(s => s.subjectId), ...db.evals.map(e => e.subjectId), ...db.hw.map(h => h.subjectId), ...db.extra.map(x => x.subjectId)]);
  const keep = db.subjects.filter(s => used.has(s.id));
  CYCLES[k].s.forEach(([name, color, subs]) => { const x = keep.find(s => s.name === name); if (x) subs.forEach(u => { if (!x.subs.includes(u)) x.subs.push(u); }); else keep.push({ id: uid(), name, color, subs: [...subs] }); });
  db.subjects = keep;
}
A.subjCycle = d => { if (!confirm(`Charger les matières : ${CYCLES[d.k].l} ?\nLes matières déjà utilisées sont gardées, les autres sont remplacées.`)) return; loadCycle(d.k); done(); toast('Matières chargées.'); };

// ---------- La classe d'exemple : un CE1-CE2 sur toute l'année ----------
const DEMO_TOPICS = {
  'Français|Lecture': ['Le loup qui voulait changer de couleur', 'Les personnages d’un récit', 'Lire un documentaire', 'Les fables', 'Lire une recette', 'Le Petit Chaperon rouge et ses détournements', 'Poésie : les saisons', 'Lire des consignes', 'Roman : une enquête', 'Lire une bande dessinée', 'Théâtre : petites saynètes', 'Les Trois Brigands', 'Contes du monde', 'Lire un album sans texte'],
  'Français|Étude de la langue': ['La phrase', 'Le nom et le déterminant', 'Le verbe', 'Le présent des verbes en -er', 'Le pluriel des noms', 'L’adjectif', 'Le masculin et le féminin', 'Être et avoir au présent', 'Le futur', 'Les accords dans le groupe nominal', 'L’imparfait', 'Le passé composé', 'Les homophones a / à', 'Les homophones et / est', 'Le sujet du verbe', 'Le complément de phrase'],
  'Français|Écriture': ['Écrire une liste', 'La carte postale', 'Raconter un week-end', 'Écrire une recette', 'Décrire un personnage', 'Inventer la suite d’une histoire', 'Écrire un poème', 'Écrire une lettre'],
  'Français|Oral': ['Se présenter', 'Raconter une histoire', 'Dire une poésie', 'Exposé : mon animal préféré', 'Débat : la cour de récréation', 'Jeux de rôle'],
  'Mathématiques|Nombres et calculs': ['Les nombres jusqu’à 100', 'Les nombres jusqu’à 1 000', 'L’addition posée', 'La soustraction posée', 'Doubles et moitiés', 'Les tables de multiplication', 'Le sens de la multiplication', 'La multiplication posée', 'Partager, diviser', 'Problèmes additifs', 'Problèmes multiplicatifs', 'Stratégies de calcul mental', 'Les fractions simples', 'Comparer et ranger les nombres'],
  'Mathématiques|Espace et géométrie': ['Se repérer sur un quadrillage', 'Les solides', 'Les figures planes', 'L’angle droit', 'La symétrie', 'Reproduire une figure', 'Programmes de déplacement', 'Le cercle'],
  'Mathématiques|Grandeurs et mesures': ['Mesurer des longueurs', 'Lire l’heure', 'Les masses', 'Les contenances', 'La monnaie', 'Le calendrier et les durées'],
  'Questionner le monde|': ['Les animaux et leur milieu', 'Les besoins des plantes', 'Le corps humain', 'Les états de l’eau', 'L’air', 'La frise du temps', 'Les saisons', 'Plans et cartes', 'Les objets techniques', 'L’électricité', 'Les paysages', 'Hygiène et santé'],
  'Anglais|': ['Hello! Se présenter', 'Colours and numbers', 'My family', 'Animals', 'Food', 'Christmas', 'My body', 'Clothes', 'The weather', 'Easter', 'In the classroom'],
  'EPS|': ['Jeux collectifs : balle au capitaine', 'Course longue', 'Gymnastique', 'Danse', 'Jeux d’opposition', 'Athlétisme : sauter, lancer', 'Course d’orientation', 'Jeux de raquettes', 'Jeux traditionnels'],
  'Arts plastiques|': ['Les couleurs primaires', 'À la manière de Kandinsky', 'Land art', 'Le portrait', 'Collages', 'Modelage', 'Paysages d’hiver'],
  'Éducation musicale|': ['Chants d’automne', 'Les instruments de l’orchestre', 'Le rythme', 'Pierre et le loup', 'Chants de Noël', 'Percussions corporelles', 'Chorale de fin d’année'],
  'EMC|': ['Les règles de la classe', 'Le respect', 'Les émotions', 'Les droits de l’enfant', 'Dire non au harcèlement', 'L’égalité filles-garçons', 'Les symboles de la République', 'Petits débats philo']
};
const STEPS = n => ({ 5: ['Découverte', 'Recherche', 'Structuration', 'Entraînement', 'Évaluation'], 6: ['Découverte', 'Recherche', 'Structuration', 'Entraînement', 'Réinvestissement', 'Évaluation'], 7: ['Découverte', 'Recherche', 'Structuration', 'Entraînement', 'Entraînement (2)', 'Réinvestissement', 'Évaluation'] })[n];
const W = (title, obj, txt, more = {}) => ({ ...newSess(title), obj, txt, mode: 'libre', ...more });
// Trois séquences rédigées en entier, placées sur les séances qui arrivent.
function demoWritten(T) {
  const rm = (type, text, d) => ({ id: uid(), type, text, days: d, date: '' });
  return {
    'Mathématiques|Nombres et calculs': { title: 'La soustraction posée', sessions: [
      W('Soustraction : le marchand', 'Comprendre la soustraction comme la recherche d’un reste, dans un problème concret.', '10\' Calcul mental : le furet des compléments à 10, puis à 100, en cercle. Celui qui se trompe relance.\n15\' Le marchand : « J’ai 63 €, j’achète un jouet à 28 €. Combien me reste-t-il ? » Recherche en binômes avec la monnaie factice.\n15\' Mise en commun : on compare les façons de faire (avancer de 28 à 63, enlever les dizaines puis les unités).\n5\' On retient : dans une soustraction, on cherche ce qui reste, ou l’écart entre deux nombres.', { mat: 'Monnaie factice, étiquettes prix, ardoises.', rem: [rm('imp', 'Étiquettes prix et billets factices', 1)] }),
      W('Soustraction : avec le matériel', 'Soustraire en échangeant une dizaine contre 10 unités.', '10\' Calcul mental : 10 de moins, 10 de plus (jeu du « plus ou moins » sur l’ardoise).\n20\' Manipulation : 52 − 17 avec les barres et les cubes. Problème : il n’y a que 2 cubes ! On casse une barre.\n10\' Les CE2 écrivent les étapes ; les CE1 refont avec 43 − 18.\n5\' Phrase du jour : « Quand il n’y a pas assez d’unités, je casse une dizaine. »', { mat: 'Barres et cubes, plateau dizaines-unités.' }),
      W('Soustraction : poser l’opération', 'Poser et calculer une soustraction en colonnes, avec retenue (méthode par cassage).', '10\' Rappel avec le matériel : 64 − 27.\n15\' On passe à l’écrit : poser en colonnes, unités sous les unités. Je montre au tableau, les élèves disent les étapes.\n15\' Entraînement guidé sur l’ardoise : 3 opérations, validation collective.\n5\' Lecture de la leçon C8 et collage dans le cahier.', { mat: 'Leçon C8 photocopiée, ardoises.', lesson: 'Leçon C8 : la soustraction posée', lmode: 'spaced', hw: 'Relire la leçon C8 et faire l’exercice 1 page 54.', rem: [rm('imp', 'Leçon C8, 24 copies', 1)] }),
      W('Soustraction : ateliers', 'S’entraîner à poser et calculer des soustractions, en autonomie.', '5\' Présentation des 3 ateliers, rotation toutes les 12 minutes.\n36\' Atelier 1 : « Bataille des différences » avec les cartes (le plus grand écart gagne). Atelier 2 : fichier p. 55. Atelier 3 : avec moi, les élèves qui ont besoin du matériel.\n5\' Chacun colorie sa ceinture de calcul s’il a réussi 3 opérations sans aide.', { mat: 'Jeux de cartes 0-9, fichier, ceintures de calcul.' }),
      W('Soustraction : l’escape game du pirate', 'Réinvestir la soustraction pour résoudre des problèmes, de façon ludique.', '5\' Mise en scène : le pirate Barbe-Grise a enfermé le goûter dans un coffre à code !\n30\' Par équipes de 4, 5 énigmes (problèmes de soustraction). Chaque bonne réponse donne un chiffre du code. Indices à demander contre une pénalité de 2 minutes.\n10\' Ouverture du coffre et retour : quelles énigmes étaient les plus dures ? Pourquoi ?', { mat: 'Enveloppes d’énigmes, cadenas à code, coffre, sablier.', rem: [rm('imp', 'Énigmes du pirate, 6 jeux', 2), rm('mat', 'Cadenas à code et boîte', 1)] }),
      W('Soustraction : évaluation', 'Évaluer : poser et calculer des soustractions, résoudre un problème.', '5\' Consignes lues ensemble.\n30\' Évaluation individuelle (version CE1 et version CE2).\n10\' Pour ceux qui ont fini : défis du jour au fond de la classe.', { mat: 'Fiches d’évaluation CE1 et CE2.', rem: [rm('imp', 'Évaluations soustraction, 12 CE1 et 12 CE2', 2)] })] },
    'Français|Lecture': { title: 'Le loup qui voulait changer de couleur', sessions: [
      W('Le loup : la couverture', 'Émettre des hypothèses sur l’histoire à partir de la couverture et du titre.', '10\' Couverture projetée, cachée en partie : on dévoile petit à petit. Que voit-on ?\n15\' Le titre : pourquoi veut-il changer de couleur ? Chacun dessine sa prédiction sur l’ardoise.\n15\' Lecture offerte des deux premières pages.\n5\' On garde les hypothèses affichées pour la fin de la séquence.', { mat: 'Album, vidéoprojecteur, ardoises.' }),
      W('Le loup : lundi, il devient vert', 'Comprendre le début de l’histoire : le personnage et son problème.', '10\' Rappel des hypothèses.\n20\' Lecture silencieuse de la page du lundi (texte adapté pour les CE1), puis questions orales.\n10\' Le loup est-il content ? Relevé des mots qui le montrent.\n5\' Lecture à voix haute par trois volontaires.', { hw: 'Relire la page du lundi à voix haute à un adulte.' }),
      W('Le loup : les jours de la semaine', 'Comprendre la structure répétitive de l’album.', '10\' Jeu : je lis un jour, vous devinez la couleur.\n20\' Par groupes de lecture, remettre dans l’ordre les étiquettes des jours et des couleurs.\n15\' Mise en commun : on construit la frise de la semaine du loup.', { mat: 'Étiquettes jours et couleurs, une enveloppe par groupe.' }),
      { ...newSess('Le loup : lecture à deux voix'), obj: 'Lire à voix haute de façon fluide un extrait, en binômes.', mat: 'Extraits découpés, chronomètre.', mode: 'tableau', steps: [
        { dur: 5, phase: 'Présentation', ens: 'Montre l’exemple avec un élève.', cons: '« Vous allez lire à deux voix : chacun son personnage. »', att: 'Les élèves comprennent le jeu.', elv: 'Écoutent.' },
        { dur: 20, phase: 'Entraînement', ens: 'Passe dans les binômes, aide au déchiffrage.', cons: '« Lisez trois fois votre extrait, de plus en plus vite et avec le ton. »', att: 'Lecture plus fluide à chaque passage.', elv: 'En binômes.' },
        { dur: 15, phase: 'Spectacle', ens: 'Anime, chronomètre.', cons: '« Chaque binôme présente son extrait. »', att: 'Lecture expressive.', elv: 'Lisent devant la classe, les autres applaudissent.' }] },
      W('Le loup : la fin de l’histoire', 'Comprendre la chute et le message de l’album.', '10\' Lecture offerte de la fin.\n15\' Débat : pourquoi le loup est-il finalement content de sa couleur ?\n15\' On revient aux hypothèses du début : qui avait deviné ?\n5\' Phrase à recopier : « Je suis bien comme je suis. »'),
      W('Le loup : rallye lecture', 'Vérifier la compréhension de l’album, de manière ludique.', '5\' Règle du rallye : questions en équipes, 1 point par bonne réponse.\n25\' Rallye en 10 questions (vrai/faux, qui dit quoi, retrouver l’image).\n10\' Correction collective et podium.\n5\' Vote : la couleur préférée du loup, pour l’affiche de la classe.', { rem: [rm('imp', 'Questions du rallye, 6 jeux', 1)] })] },
    'Français|Étude de la langue': { title: 'Le verbe', sessions: [
      W('Le verbe : le jeu du mime', 'Comprendre que le verbe dit ce que l’on fait.', '15\' Jeu du mime : un élève tire une carte action et la mime ; la classe devine et dit la phrase.\n15\' On écrit les phrases au tableau, on souligne le mot qui dit l’action.\n10\' Entraînement : souligner le verbe dans 5 phrases.', { mat: 'Cartes actions.' }),
      W('Le verbe change avec le temps', 'Repérer le verbe en changeant le temps de la phrase.', '10\' Hier, aujourd’hui, demain : une phrase, trois temps. Quel mot change ?\n20\' Par deux, transformer 4 phrases et entourer le mot qui change.\n10\' On retient : le verbe change selon le moment.'),
      W('L’infinitif', 'Trouver l’infinitif d’un verbe conjugué.', '10\' Rappel du jeu du mime.\n15\' « Il faut… » : il chante → il faut chanter. Recherche collective.\n15\' Exercice : trouver l’infinitif de 8 verbes.\n5\' Leçon G5 dans le cahier.', { lesson: 'Leçon G5 : le verbe et son infinitif', lmode: 'daily', hw: 'Apprendre la leçon G5.' }),
      W('Le verbe : la boîte à verbes', 'Classer des verbes selon leur infinitif.', '15\' Jeu de la boîte : chacun tire un verbe conjugué et va le ranger dans la bonne boîte (-er ou autre).\n20\' Fichier : tri de verbes.\n10\' Correction en binômes avec la boîte.'),
      W('Le verbe : bingo', 'Réinvestir : reconnaître le verbe et trouver son infinitif, en jouant.', '30\' Bingo des verbes : je lis une phrase, on coche l’infinitif du verbe sur sa grille. Le premier qui a une ligne gagne.\n10\' Bilan : ce qui est facile, ce qui est encore difficile.', { rem: [rm('imp', 'Grilles de bingo, 24', 1)] }),
      W('Le verbe : évaluation', 'Évaluer : repérer le verbe et donner son infinitif.', '30\' Évaluation.\n10\' Rattrapage en petit groupe pour ceux qui en ont besoin.')] }
  };
}
// Des dates de naissance (CE1 nés en 2019, CE2 en 2018) dont une cette semaine, et des numéros fictifs.
function demoBirth(i) { if (i === 0) return '2019' + addD(TODAY(), 1).slice(4); if (i === 3) return '2018' + addD(TODAY(), 4).slice(4);
  return (i % 2 ? 2018 : 2019) + '-' + String(1 + (i * 5) % 12).padStart(2, '0') + '-' + String(1 + (i * 11) % 28).padStart(2, '0'); }
function demoContacts(i, last) { const n = k => '06 00 00 ' + String(10 + i).padStart(2, '0') + ' ' + String(k).padStart(2, '0');
  return { momN: ['Sophie', 'Claire', 'Julie', 'Marie', 'Laura'][i % 5] + ' ' + last, momT: n(1), dadN: i % 4 === 3 ? '' : ['Thomas', 'Nicolas', 'Julien', 'Mehdi', 'Pierre'][i % 5] + ' ' + last, dadT: i % 4 === 3 ? '' : n(2),
    othN: i % 3 === 0 ? 'Mme ' + ['Durand', 'Leroy', 'Moreau'][i % 3] : '', othL: i % 3 === 0 ? ['Grand-mère', 'Assistante maternelle', 'Tante'][i % 3] : '', othT: i % 3 === 0 ? n(3) : '' }; }
A.demo = () => {
  if ((db.students.length || db.sequences.length) && !confirm('Ajouter la classe d’exemple à vos données actuelles ?')) return;
  loadCycle('c2'); const sid = n => db.subjects.find(s => s.name === n).id;
  const k = db.classes[0]; k.name = 'CE1-CE2'; k.level = 'CE1, CE2';
  'Lina F,Hugo G,Inès F,Noé G,Jade F,Adam G,Zoé F,Liam G,Mila F,Sacha G,Chloé F,Nathan G,Léa F,Gabriel G,Emma F,Louis G,Alice F,Jules G,Rose F,Arthur G,Manon F,Tom G,Lou F,Paul G'.split(',').forEach((x, i) => {
    const [first, sex] = x.split(' '), last = ['Morel', 'Petit', 'Garcia', 'Lambert', 'Rousseau', 'Fontaine', 'Chevalier', 'Faure', 'Girard', 'André', 'Blanc', 'Bonnet', 'Mercier', 'Dupuis', 'Roux', 'Vincent', 'Muller', 'Lefèvre', 'Henry', 'Perrin', 'Robin', 'Masson', 'Marchand', 'Leroy'][i];
    db.students.push({ id: uid(), first, last, sex, classId: k.id, level: i % 2 ? 'CE2' : 'CE1', birth: demoBirth(i), contacts: '', ...demoContacts(i, last), pai: i === 2 ? 'Allergie aux arachides. Trousse d’urgence dans le bureau, appeler le 15.' : '', needs: i === 7 ? 'PAP : consignes reformulées, temps supplémentaire, AESH le matin.' : '', info: '' });
  });
  // L'emploi du temps de la semaine (lundi, mardi, jeudi, vendredi).
  const S = (day, start, end, name, sub = '') => ({ id: uid(), day, start, end, subjectId: sid(name), sub, classId: k.id });
  const FR = 'Français', MA = 'Mathématiques', QLM = 'Questionner le monde', slots = [];
  [1, 2, 4, 5].forEach(d => slots.push(S(d, '08:30', '09:30', FR, 'Lecture'), S(d, '09:30', '10:15', MA, 'Nombres et calculs')));
  slots.push(S(1, '10:30', '11:15', FR, 'Étude de la langue'), S(1, '11:15', '12:00', MA, 'Espace et géométrie'), S(1, '13:30', '14:15', QLM), S(1, '14:15', '15:00', 'Anglais'), S(1, '15:15', '16:00', 'EMC'),
    S(2, '10:30', '11:15', FR, 'Étude de la langue'), S(2, '11:15', '12:00', FR, 'Écriture'), S(2, '13:30', '14:30', 'EPS'), S(2, '14:30', '15:15', QLM), S(2, '15:30', '16:15', 'Éducation musicale'),
    S(4, '10:30', '11:15', FR, 'Étude de la langue'), S(4, '11:15', '12:00', MA, 'Grandeurs et mesures'), S(4, '13:30', '14:15', FR, 'Oral'), S(4, '14:15', '15:00', 'Anglais'), S(4, '15:15', '16:15', 'Arts plastiques'),
    S(5, '10:30', '11:15', FR, 'Écriture'), S(5, '11:15', '12:00', MA, 'Espace et géométrie'), S(5, '13:30', '14:30', 'EPS'), S(5, '14:30', '15:15', FR, 'Étude de la langue'), S(5, '15:30', '16:15', MA, 'Grandeurs et mesures'));
  db.timetables = [{ id: uid(), from: db.settings.start, label: 'Emploi du temps de base', slots }];
  // Des vacances d'hiver et de printemps pour avoir cinq périodes (dates d'exemple : à remplacer par celles de votre zone).
  const H = db.settings.holidays; if (H.length < 4) { H.push({ from: '2027-02-13', to: '2027-02-28', label: 'Hiver (dates d’exemple)' }, { from: '2027-04-10', to: '2027-04-25', label: 'Printemps (dates d’exemple)' }); H.sort((x, y) => x.from.localeCompare(y.from)); }
  // Des séquences de 5 à 7 séances pour toute l'année, dans chaque matière.
  _c = null; let c = ctx(); const count = {}; c.occs.forEach(o => count[o.q] = (count[o.q] || 0) + 1);
  Object.entries(DEMO_TOPICS).forEach(([key, topics]) => { const [name, sub] = key.split('|'), q = k.id + '|' + sid(name) + '|' + sub; let n = 0, i = 0;
    while (n < (count[q] || 0)) { const len = [6, 5, 7, 6, 6, 5][i % 6], topic = topics[i % topics.length] + (i >= topics.length ? ' (approfondissement)' : '');
      db.sequences.push({ id: uid(), title: topic, subjectId: sid(name), sub, classId: k.id, level: '', start: '', sessions: STEPS(len).map(st => newSess(topic.replace(/ \(approfondissement\)$/, '') + ' : ' + st.toLowerCase())) }); n += len; i++; } });
  // Les séquences rédigées arrivent à partir de la prochaine séance de leur matière.
  _c = null; c = ctx(); const T = TODAY(), written = demoWritten(T);
  Object.entries(written).forEach(([key, w]) => { const [name, sub] = key.split('|'), q = k.id + '|' + sid(name) + '|' + sub, seqs = db.sequences.filter(s => qk(s) === q);
    for (const seq of seqs) { const p = seq.sessions.findIndex(s => c.sess[s.id] && c.sess[s.id].o && c.sess[s.id].o.date >= T); if (p < 0) continue;
      const nw = { id: uid(), title: w.title, subjectId: sid(name), sub, classId: k.id, level: '', start: '', sessions: w.sessions };
      const at = db.sequences.indexOf(seq); if (p === 0) db.sequences.splice(at, 1, nw); else { seq.sessions = seq.sessions.slice(0, p); db.sequences.splice(at + 1, 0, nw); } break; } });
  // On ajuste la fin de chaque matière pour que toutes les séances tiennent dans l'année (pas de « hors année »).
  _c = null; c = ctx(); const fit = {}; c.occs.forEach(o => { if (!(o.ov && o.ov.t === 'lost')) fit[o.q] = (fit[o.q] || 0) + 1; });
  [...new Set(db.sequences.map(qk))].forEach(q => { const seqs = db.sequences.filter(x => qk(x) === q); let tot = seqs.reduce((a, x) => a + x.sessions.length, 0);
    while (tot > (fit[q] || 0) && seqs.length) { const last = seqs[seqs.length - 1]; last.sessions.pop(); tot--; if (!last.sessions.length) { remove(db.sequences, last.id); seqs.pop(); } } });
  // Une évaluation, des notes, des groupes, un plan de classe, des devoirs réguliers, l'agenda.
  _c = null; c = ctx();
  const ev = { id: uid(), classId: k.id, subjectId: sid(MA), sub: 'Nombres et calculs', title: 'L’addition posée', date: addD(T, -12), mode: 'fin', max: 20 }; db.evals.push(ev);
  db.students.forEach((s, i) => db.grades[ev.id + '|' + s.id] = { v: FINE[(i * 7 + 3) % FINE.length], c: '' });
  const st = db.students, now = nowT(), past = c.occs.filter(o => o.date < T).slice(-3)[0];
  db.notes.push({ id: uid(), sid: st[0].id, type: 'comportement', sub: 'Progrès', pos: true, text: 'Lit beaucoup plus vite depuis la rentrée.', date: addD(T, -2), time: '09:10' },
    { id: uid(), sid: st[3].id, type: 'comportement', sub: 'Entraide', pos: true, text: '', date: addD(T, -1), time: '10:40' },
    { id: uid(), sid: st[5].id, type: 'incident', sub: 'Dispute', text: 'Bousculade dans la file à la récréation, discussion avec Tom.', date: addD(T, -1), time: '10:20' },
    { id: uid(), sid: st[7].id, type: 'observation', text: 'Très bon travail en atelier de manipulation.', date: addD(T, -6), time: '14:00' });
  if (past) [st[1].id, st[9].id].forEach(id => db.notes.push({ id: uid(), gid: 'demo1', sid: id, type: 'difficulte', occ: past.key, q: past.hostQ || past.q, text: 'Oublie la retenue.', date: past.date, time: past.start }));
  const lv = id => st.findIndex(s => s.id === id), byLv = [...st].sort((a, b) => FINE.indexOf(db.grades[ev.id + '|' + b.id].v) - FINE.indexOf(db.grades[ev.id + '|' + a.id].v));
  db.groupSets.push({ id: uid(), classId: k.id, name: 'Groupes de lecture', durable: true, date: db.settings.start, note: 'Révisés à chaque période.', groups: ['🦉 Hiboux', '🦊 Renards', '🐢 Tortues', '🐝 Abeilles'].map((n, i) => ({ n, ids: byLv.slice(i * 6, i * 6 + 6).map(s => s.id) })) });
  db.avoid.push([st[5].id, st[21].id]);
  const plan = { id: uid(), classId: k.id, name: 'Îlots', kind: 'ilots', active: true, ...buildLayout('ilots', st.length) }; db.plans.push(plan); randPlan(plan);
  db.hwRec.push({ id: uid(), classId: k.id, text: 'Apprendre les mots de la dictée', subjectId: sid(FR), give: 5, due: 4, from: '' }, { id: uid(), classId: k.id, text: 'Lire 10 minutes avec un adulte', subjectId: sid(FR), give: 1, due: 2, from: '' });
  db.lists.push({ id: uid(), title: 'Droit à l’image accordé', classId: '', important: true, note: '', checked: Object.fromEntries(st.slice(0, 20).map(s => [s.id, 1])) }, { id: uid(), title: 'Coopérative scolaire', classId: '', important: false, note: '', checked: Object.fromEntries(st.slice(0, 15).map(s => [s.id, 1])) });
  db.events.push({ id: uid(), title: 'Conseil d’école', cat: 'conseil', date: addD(T, 5), start: '18:00', end: '20:00', hours: 2, classId: '', note: '' }, { id: uid(), title: 'APC lecture', cat: 'apc', date: addD(T, -6), start: '11:45', end: '12:30', hours: .75, classId: '', note: '' },
    { id: uid(), title: 'Animation pédagogique', cat: 'formation', date: addD(T, -20), start: '09:00', end: '12:00', hours: 3, classId: '', note: '' }, { id: uid(), title: 'Conseil de cycle', cat: 'equipe', date: addD(T, 2), start: '16:45', end: '18:15', hours: 1.5, classId: '', note: '' },
    { id: uid(), title: 'Rendez-vous parents de Liam', cat: 'parents', date: addD(T, 1), start: '16:45', end: '17:15', hours: .5, classId: '', note: '' }, { id: uid(), title: 'Sortie au musée', cat: 'classe', date: addD(T, 9), start: '09:00', end: '12:00', hours: '', classId: k.id, note: '' });
  db.prep.push({ id: uid(), title: 'Préparation de la semaine', day: 3, start: '09:00', end: '11:30' });
  done(); toast('Classe d’exemple chargée. « Tout effacer » dans les réglages la supprime.');
};

// ---------- Démarrage ----------
(function () { const h = location.hash.slice(1); if (V[h] && !PARENT[h]) ui.view = h; render(); })();
// Toutes les 5 minutes, on vérifie s'il est l'heure de proposer la sauvegarde (sans déranger une saisie en cours).
setInterval(() => { if (needBackup() && !$('.banner') && $('#modal').hidden && $('#voice').hidden && !/INPUT|TEXTAREA|SELECT/.test((document.activeElement || {}).tagName || '')) render(); }, 300000);
