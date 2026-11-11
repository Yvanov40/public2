'use strict';
// =====================================================================================
//  core.js — le socle de l'appli « Cahier de classe »
//  1. Petits outils (dates, texte)            4. Fenêtres, formulaires, impression
//  2. Les données (enregistrées dans le       5. Sauvegarde sur clé USB
//     navigateur, sur cet appareil)           6. Note vocale (appui long sur une tuile)
//  3. Le moteur : place chaque séance sur
//     l'emploi du temps, automatiquement
// =====================================================================================

// ---------- 1. Petits outils ----------
const KEY = 'cahier-classe-v1';                       // nom du « tiroir » où le navigateur range les données
const $ = (s, r = document) => r.querySelector(s);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const nl = s => esc(s).replace(/\n/g, '<br>');
const uid = () => Math.random().toString(36).slice(2, 9) + Date.now().toString(36).slice(-3);
const iso = d => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
const TODAY = () => iso(new Date());
const pd = s => new Date(s + 'T12:00:00');
const addD = (s, n) => { const d = pd(s); d.setDate(d.getDate() + n); return iso(d); };
const wd = s => pd(s).getDay();                       // 0 = dimanche … 6 = samedi
const monday = s => addD(s, -((wd(s) + 6) % 7));
const fD = (s, o = { weekday: 'long', day: 'numeric', month: 'long' }) => pd(s).toLocaleDateString('fr-FR', o);
const fS = s => fD(s, { weekday: 'short', day: 'numeric', month: 'short' });
const mins = t => { const [h, m] = t.split(':'); return h * 60 + +m; };
const toT = m => String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0');
const hm = t => t.replace(':', 'h');
const DAYS = ['dimanche', 'lundi', 'mardi', 'mercredi', 'jeudi', 'vendredi', 'samedi'];

const REM = { imp: ['🖨️', 'Impression'], mat: ['🧰', 'Matériel'], mot: ['✉️', 'Mot aux parents'], equ: ['🎒', 'Équipement des élèves'] };
const CATS = { apc: ['APC', '#23795b'], equipe: ['Équipe pédagogique', '#3355c0'], parents: ['Parents (réunions, rendez-vous)', '#0e7c96'], formation: ['Formation pédagogique', '#a2630c'], conseil: ['Conseil d’école', '#8a3b9c'], classe: ['Vie de la classe', '#27433d'], perso: ['Autre (hors 108 h)', '#59607c'] };
// Les 108 h : « équipe pédagogique » et « parents » sont deux catégories distinctes de rendez-vous,
// mais elles puisent dans la même enveloppe de 48 heures.
const qcat = k => k === 'parents' ? 'equipe' : k;
const qlab = k => k === 'equipe' ? 'Équipe pédagogique et parents' : CATS[k][0];
// Les types de notes sur un élève. « Comportement » ne se crée que par les boutons du comportement en un geste.
const NOTE = { observation: ['📝', 'Observation'], incident: ['🚑', 'Incident'], difficulte: ['🧩', 'Difficulté'], comportement: ['🙂', 'Comportement'] };
const NTITLE = { observation: 'Nouvelle observation', incident: 'Nouvel incident', difficulte: 'Nouvelle difficulté', comportement: 'Comportement' };
const INC = ['Dispute', 'Blessure', 'Maladie', 'Communication avec la famille', 'Autre'];
// Les niveaux d'acquisition, et leur version affinée avec + et −.
const LV = { NA: ['Non atteint', 'red'], EVA: ['En voie d’acquisition', 'yel'], A: ['Acquis', 'ok'], D: ['Dépassé', ''] };
const FINE = ['NA', 'NA+', 'EVA-', 'EVA', 'EVA+', 'A-', 'A', 'A+', 'D'];
const lvBase = v => String(v).replace(/[+-]$/, '');
const lvLabel = v => LV[lvBase(v)] ? LV[lvBase(v)][0] + (/\+$/.test(v) ? ' +' : /-$/.test(v) ? ' −' : '') : '';
const numToLv = (x, max) => { const p = x / (max || 20) * 100, s = db.settings.seuils; return p >= s.d ? 'D' : p >= s.a ? 'A' : p >= s.eva ? 'EVA' : 'NA'; };
function gradeView(e, g) {                              // affichage d'un résultat : niveau, ou note + niveau équivalent
  if (!g || g.v === '' || g.v == null) return '';
  if (e.mode === 'num') { const lv = numToLv(+g.v, e.max); return `${esc(String(g.v).replace('.', ','))}/${esc(e.max)} <span class="chip ${LV[lv][1]}">${LV[lv][0]}</span>`; }
  return LV[lvBase(g.v)] ? `<span class="chip ${LV[lvBase(g.v)][1]}">${lvLabel(g.v)}</span>` : '';
}
const cols = () => db.settings.cols.filter(c => c.on);  // rubriques du déroulé choisies dans les réglages

// ---------- Apparence : palettes et polices (choisies dans Réglages › Apparence) ----------
// Chaque palette donne six couleurs : le menu, le fond, les feuilles, le texte, la marge du cahier, l'accent.
const THEMES = {
  ardoise: { n: 'Ardoise', c: { ardoise: '#27433d', fond: '#eceef7', papier: '#fcfdff', encre: '#1b2c66', marge: '#d5334b', jaune: '#f2c230' } },
  violette: { n: 'Encre violette', c: { ardoise: '#3b2a5c', fond: '#f1edf7', papier: '#fffdff', encre: '#2e1f4f', marge: '#c2305a', jaune: '#f0b429' } },
  ocean: { n: 'Océan', c: { ardoise: '#0f4c5c', fond: '#e6f0f2', papier: '#fbfeff', encre: '#12333d', marge: '#d9541a', jaune: '#f6bd60' } },
  kraft: { n: 'Kraft', c: { ardoise: '#5b4636', fond: '#f0e9df', papier: '#fffaf2', encre: '#3a2a1e', marge: '#b23a48', jaune: '#e9b44c' } },
  noir: { n: 'Tableau noir', c: { ardoise: '#121c1a', fond: '#1b2623', papier: '#25332f', encre: '#eef0e4', marge: '#ff8696', jaune: '#f2c230' } },
  contraste: { n: 'Contraste fort', c: { ardoise: '#000000', fond: '#ffffff', papier: '#ffffff', encre: '#000000', marge: '#b80000', jaune: '#ffd400' } }
};
const TCOL = { ardoise: 'Menu', fond: 'Fond', papier: 'Feuilles', encre: 'Texte', marge: 'Marge et alertes', jaune: 'Accent' };
// Les polices : [nom affiché, pile CSS, famille à charger chez Google Fonts (vide = déjà sur l'appareil)].
const FONTS = {
  atkinson: ['Atkinson Hyperlegible (très lisible)', '"Atkinson Hyperlegible",system-ui,sans-serif', ''],
  lexend: ['Lexend (lecture facilitée)', 'Lexend,system-ui,sans-serif', 'Lexend:wght@400;700'],
  andika: ['Andika (lettres d’école)', 'Andika,system-ui,sans-serif', 'Andika:wght@400;700'],
  nunito: ['Nunito (ronde)', 'Nunito,system-ui,sans-serif', 'Nunito:wght@400;700'],
  merri: ['Merriweather (à empattements)', 'Merriweather,Georgia,serif', 'Merriweather:wght@400;700'],
  patrick: ['Patrick Hand (manuscrite)', '"Patrick Hand","Comic Sans MS",cursive', 'Patrick+Hand'],
  bricolage: ['Bricolage Grotesque', '"Bricolage Grotesque","Trebuchet MS",system-ui,sans-serif', ''],
  systeme: ['Police de l’appareil', 'system-ui,-apple-system,"Segoe UI",Roboto,sans-serif', '']
};
// Une couleur est-elle sombre ? (pour choisir un texte clair ou foncé par-dessus)
const dark = h => { const n = parseInt(h.slice(1), 16); return (0.299 * (n >> 16) + 0.587 * (n >> 8 & 255) + 0.114 * (n & 255)) < 140; };
function applyTheme() {
  const t = db.settings.theme, r = document.documentElement.style;
  for (const k in TCOL) r.setProperty('--' + k, t[k]);
  r.setProperty('--craie', dark(t.ardoise) ? '#f3f1e6' : '#16201d');           // texte du menu
  r.setProperty('--champ', dark(t.papier) ? 'color-mix(in srgb,' + t.papier + ' 88%,#fff)' : '#fff');   // fond des champs de saisie
  r.setProperty('color-scheme', dark(t.papier) ? 'dark' : 'light');
  r.setProperty('--taille', (+t.size || 16) + 'px');
  const f = FONTS[t.font] || FONTS.atkinson, ft = t.titles === 'same' ? f : FONTS[t.titles] || FONTS.bricolage;
  r.setProperty('--texte', f[1]); r.setProperty('--titre', ft[1]);
  for (const x of [f, ft]) if (x[2] && !document.getElementById('gf-' + x[2])) { const l = document.createElement('link'); l.id = 'gf-' + x[2]; l.rel = 'stylesheet'; l.href = 'https://fonts.googleapis.com/css2?family=' + x[2] + '&display=swap'; document.head.appendChild(l); }
  const m = document.querySelector('meta[name=theme-color]'); if (m) m.content = t.ardoise;
}

// ---------- 2. Les données ----------
function blank() {
  const S = (name, color, subs) => ({ id: uid(), name, color, subs });
  return {
    v: 1,
    settings: {
      teacher: '', school: '', year: '2026-2027', start: '2026-09-01', end: '2027-07-02', days: [1, 2, 4, 5],
      holidays: [{ from: '2026-10-17', to: '2026-11-01', label: 'Toussaint' }, { from: '2026-12-19', to: '2027-01-03', label: 'Noël' }],
      quotas: { apc: 36, equipe: 48, formation: 18, conseil: 6 }, mark: 'color',
      theme: { pal: 'ardoise', ...THEMES.ardoise.c, font: 'atkinson', titles: 'bricolage', size: 16 },
      cols: [{ k: 'phase', l: 'Étape', on: true }, { k: 'ens', l: 'Rôle de l’enseignant', on: true }, { k: 'cons', l: 'Consignes', on: true }, { k: 'att', l: 'Attendus', on: true }, { k: 'elv', l: 'Rôle des élèves', on: true }, { k: 'x1', l: 'Différenciation', on: false }, { k: 'x2', l: 'Matériel', on: false }],
      behav: [{ id: 'b1', l: 'Travail', pos: true }, { id: 'b2', l: 'Progrès', pos: true }, { id: 'b3', l: 'Écoute', pos: true }, { id: 'b4', l: 'Autonomie', pos: true }, { id: 'b5', l: 'Entraide', pos: true }, { id: 'b6', l: 'Bavardage', pos: false }, { id: 'b7', l: 'Travail non fait', pos: false }, { id: 'b8', l: 'Matériel oublié', pos: false }],
      seuils: { eva: 50, a: 75, d: 95 },
      redac: 'libre',      // rédaction du déroulé : « libre » (un texte) ou « tableau » (une case par rubrique)
      reasons: ['Sortie', 'Intervenant', 'Évaluations', 'Spectacle', 'Absence', 'Formation', 'Grève', 'Retard pris'],   // motifs proposés quand une séance est remplacée
      apiKey: '', model: 'claude-sonnet-4-6', subInfo: '', safety: '', lastBackup: '', backupHour: 16
    },
    classes: [{ id: uid(), name: 'Ma classe', level: '', color: '#3355c0', icon: '🦊' }],
    students: [],
    subjects: [
      S('Français', '#3355c0', ['Lecture', 'Écriture', 'Grammaire', 'Orthographe', 'Vocabulaire']),
      S('Mathématiques', '#c0392b', ['Nombres', 'Calcul', 'Géométrie', 'Grandeurs et mesures', 'Problèmes']),
      S('Questionner le monde', '#23795b', []), S('EPS', '#d08400', []), S('Arts plastiques', '#8a3b9c', []),
      S('Éducation musicale', '#0e7c96', []), S('EMC', '#6b7280', []), S('Anglais', '#b83280', [])
    ],
    timetables: [],      // versions de l'emploi du temps : { id, from, label, slots:[{ id, day, start, end, subjectId, sub, classId }] }
    sequences: [],       // blocs de séances : { id, title, subjectId, sub, classId, level, start, sessions:[…] }
    att: {},             // appel : « date|M ou S|élève » → 'A' (absent) ou 'R' (retard)
    absInfo: {},         // « date|élève » → { motif, just }
    calls: {},           // « date|M ou S|classe » → heure de validation de l'appel
    mots: [],            // mots aux parents écrits : { id, date, model, v, text, title }
    groupSets: [], avoid: [], plans: [], drawn: {},   // gestion de classe : groupes, élèves à ne pas associer, plans, élèves déjà tirés au sort
    hw: [], hwRec: [],   // devoirs ajoutés à la main, devoirs réguliers
    notes: [], results: [], meetings: [], lists: [], events: [],
    ov: {},              // créneaux non faits ou prêtés : « date|créneau » → { t:'lost' | 'host', … }
    adhoc: {},           // séances écrites directement dans le cahier journal, hors programmation
    prep: [],            // créneaux de préparation hors classe, chaque semaine : { id, title, day, start, end }
    extra: [],           // créneaux ponctuels ajoutés à l'emploi du temps : { id, date, start, end, subjectId, sub, classId }
    evals: [],           // évaluations : { id, classId, subjectId, sub, title, date, mode:'niv'|'fin'|'num', max }
    grades: {},          // résultats : « évaluation|élève » → { v, c }
    bilans: {},          // appréciations des bilans : « élève|du|au » → texte
    remDone: {}, dayNotes: {}, dirty: false
  };
}
let db;
try { db = JSON.parse(localStorage.getItem(KEY)); } catch (e) { db = null; }
function normalize() {                                 // complète ce qui manquerait dans d'anciennes données
  const b = blank();
  if (!db || !db.settings) { db = b; return; }
  for (const k in b) if (db[k] === undefined) db[k] = b[k];
  for (const k in b.settings) if (db.settings[k] === undefined) db.settings[k] = b.settings[k];
  db.settings.theme = { ...b.settings.theme, ...db.settings.theme };
  // Anciennes versions : la santé devient le PAI ; les résultats saisis élève par élève deviennent des évaluations.
  db.notes.forEach(n => { if (n.type === 'evaluation' || n.type === 'commentaire') n.type = 'observation'; });   // évaluation et commentaire réunis
  db.students.forEach(s => { if (s.pai === undefined) { s.pai = s.health || ''; delete s.health; } if (s.needs === undefined) s.needs = ''; });
  if (db.results.length) {
    const map = { 'Non atteint': 'NA', 'Partiellement atteint': 'EVA', 'Atteint': 'A', 'Dépassé': 'D' };
    db.results.forEach(r => { const st = db.students.find(s => s.id === r.sid); if (!st) return; const e = { id: uid(), classId: st.classId, subjectId: r.subjectId, sub: '', title: r.title, date: r.date, mode: 'niv', max: 20 }; db.evals.push(e); db.grades[e.id + '|' + r.sid] = { v: map[r.level] || '', c: [r.score, r.comment].filter(Boolean).join(' ') }; });
    db.results = [];
  }
}
normalize();

const ui = { view: 'home', date: TODAY(), week: monday(TODAY()), cls: '', lvl: '', edt: 'week', tt: null, stu: null, sess: null, back: 'journal', tab: 'appel', month: TODAY().slice(0, 7), snooze: '' };
const V = {}, A = {}, C = {};                         // V = les pages, A = les actions des boutons, C = les réactions aux champs
let _c = null, _saveT = null;

function save() {                                     // enregistre tout dans le navigateur
  db.dirty = true; _c = null;
  try { localStorage.setItem(KEY, JSON.stringify(db)); } catch (e) { toast('Enregistrement impossible : le stockage du navigateur est plein. Faites une sauvegarde.'); }
}
const saveSoon = () => { clearTimeout(_saveT); _saveT = setTimeout(save, 350); };
const done = () => { save(); render(); };

const cls = id => db.classes.find(c => c.id === id);
const subj = id => db.subjects.find(s => s.id === id) || { name: '?', color: '#888', subs: [] };
const stu = id => db.students.find(s => s.id === id);
// « de Lina », mais « d’Adam » : l'élision devant une voyelle ou un h.
const deN = n => (/^[aeiouyhàâäéèêëîïôöûüAEIOUYHÀÂÄÉÈÊËÎÏÔÖÛÜ]/.test(n) ? 'd’' : 'de ') + n;
const sname = s => s ? (s.first + ' ' + s.last).trim() : '?';
const levelsOf = c => (c.level || '').split(/[\s,\/+-]+/).filter(Boolean);
const allLevels = () => [...new Set([...db.classes.flatMap(levelsOf), ...db.students.map(s => s.level).filter(Boolean)])].sort();
const visCls = () => db.classes.filter(c => (!ui.cls || c.id === ui.cls) && (!ui.lvl || levelsOf(c).includes(ui.lvl) || db.students.some(s => s.classId === c.id && s.level === ui.lvl))).map(c => c.id);
const visStu = () => { const v = visCls(); return db.students.filter(s => v.includes(s.classId) && (!ui.lvl || !s.level || s.level === ui.lvl)).sort((a, b) => (a.last + a.first).localeCompare(b.last + b.first, 'fr')); };
const defCls = () => ui.cls || (db.classes[0] || {}).id || '';
// Le repère d'une classe : une pastille de couleur ou une icône, selon le réglage.
function cbadge(id, force) {
  const c = cls(id); if (!c || (db.classes.length < 2 && !force)) return '';
  return db.settings.mark === 'icon' ? `<span class="cb">${esc(c.icon || '🏫')} ${esc(c.name)}</span>` : `<span class="cb"><i style="background:${esc(c.color)}"></i>${esc(c.name)}</span>`;
}
const qk = o => o.classId + '|' + o.subjectId + '|' + (o.sub || '');   // une « file » = une classe + une matière + une sous-matière
function qname(q) { const [c, s, sub] = q.split('|'); return subj(s).name + (sub ? ' › ' + sub : '') + (db.classes.length > 1 && cls(c) ? ' (' + cls(c).name + ')' : ''); }
const whatOpts = () => db.subjects.flatMap(s => [[s.id + '|', s.name], ...(s.subs || []).map(x => [s.id + '|' + x, s.name + ' › ' + x])]);
const clsOpts = () => db.classes.map(c => [c.id, c.name]);
function findS(id) { for (const seq of db.sequences) { const s = seq.sessions.find(x => x.id === id); if (s) return { s, seq }; } return null; }
const free = o => !(o.ov && (o.ov.t === 'lost' || o.ov.t === 'host'));   // créneau ni remplacé ni prêté à une autre matière
const newSess = title => ({ id: uid(), title: title || 'Nouvelle séance', obj: '', comp: '', mat: '', steps: [], rem: [], bilan: '' });

// Jours fériés français d'une année (Pâques est calculé, les autres sont fixes).
function feries(y) {
  const a = y % 19, b = Math.floor(y / 100), c = y % 100, d = Math.floor(b / 4), e = b % 4, f = Math.floor((b + 8) / 25), g = Math.floor((b - f + 1) / 3),
    h = (19 * a + b - d - g + 15) % 30, i = Math.floor(c / 4), k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7, m = Math.floor((a + 11 * h + 22 * l) / 451),
    mo = Math.floor((h + l - 7 * m + 114) / 31), da = ((h + l - 7 * m + 114) % 31) + 1, E = iso(new Date(y, mo - 1, da));
  return [y + '-01-01', addD(E, 1), y + '-05-01', y + '-05-08', addD(E, 39), addD(E, 50), y + '-07-14', y + '-08-15', y + '-11-01', y + '-11-11', y + '-12-25'];
}

// ---------- 3. Le moteur de programmation ----------
// Principe : on ne range jamais une séance « à la main » sur une date. On déroule l'année jour par jour ;
// chaque créneau de l'emploi du temps reçoit la séance suivante de sa matière. Si un créneau saute
// (séance non faite, sortie, absence…), tout ce qui suit se décale tout seul. On calcule aussi le plan
// « idéal » (sans aucun créneau perdu) : la différence entre les deux donne le retard.
function ctx() {
  if (_c) return _c;
  const S = db.settings, hol = new Set();
  for (const y of new Set([+S.start.slice(0, 4), +S.end.slice(0, 4)])) feries(y).forEach(d => hol.add(d));
  const why = d => d < S.start || d > S.end ? 'Hors année scolaire' : hol.has(d) ? 'Jour férié' : (S.holidays.find(h => d >= h.from && d <= h.to) || {}).label || (!S.days.includes(wd(d)) ? 'Pas classe' : '');
  const school = d => !why(d);
  const tts = [...db.timetables].sort((a, b) => a.from < b.from ? -1 : 1);
  const ttFor = d => { let r = null; for (const t of tts) if (t.from <= d) r = t; return r || tts[0] || null; };
  const occs = [], byDate = {}, byKey = {}, base = {}, act = {};
  for (let d = S.start; d <= S.end; d = addD(d, 1)) {
    if (!school(d)) continue;
    const t = ttFor(d), w = wd(d); if (!t) continue;
    for (const s of t.slots) if (s.day === w) occs.push({ key: d + '|' + s.id, date: d, slot: s, start: s.start, end: s.end, classId: s.classId, subjectId: s.subjectId, sub: s.sub || '', q: qk(s) });
  }
  // Créneaux ponctuels ajoutés à la main, puis créneaux déplacés pour un seul jour.
  for (const x of db.extra) if (x.date >= S.start && x.date <= S.end) occs.push({ key: x.date + '|x' + x.id, date: x.date, slot: x, start: x.start, end: x.end, classId: x.classId, subjectId: x.subjectId, sub: x.sub || '', q: qk(x), extra: x.id });
  for (const o of occs) { const v = db.ov[o.key]; if (v && v.t === 'mv') { o.from = { date: o.date, start: o.start }; o.date = v.date; o.start = v.start; o.end = v.end; } }
  occs.sort((a, b) => (a.date + a.start).localeCompare(b.date + b.start));
  // Créneaux réservés : quand on reporte une séance sur un créneau précis (autre matière ou autre moment),
  // ce créneau lui est réservé ; les autres séances de la matière gardent leur ordre sur les créneaux restants.
  const live = new Set(), pinOcc = {};
  db.sequences.forEach(q => q.sessions.forEach(s => { if (!s.skip) live.add(s.id); }));
  for (const o of occs) {
    byKey[o.key] = o; (byDate[o.date] ??= []).push(o); (base[o.q] ??= []).push(o);
    const v = db.ov[o.key], rs = (v && v.t === 'host' && v.sid) || (o.extra && o.slot.sid);
    if (rs && live.has(rs) && !(v && v.t === 'lost')) { o.rsv = rs; pinOcc[rs] = o; }
    if (v) { o.ov = v; if (v.t === 'lost') continue; if (v.t === 'host') { o.hostQ = v.q; (act[v.q] ??= []).push(o); continue; } }
    (act[o.q] ??= []).push(o);
  }
  const sess = {}, occSess = {}, queues = {}, groups = {}, t = TODAY();
  for (const seq of db.sequences) (groups[qk(seq)] ??= []).push(seq);
  for (const q in groups) {
    let i = 0, j = 0, prev = null, nb = 0, na = 0, lost = 0, total = 0;
    const Aq = act[q] || [], B = base[q] || [];
    for (const seq of groups[q]) {
      if (seq.start) { while (i < Aq.length && Aq[i].date < seq.start) i++; while (j < B.length && B[j].date < seq.start) j++; }
      for (const s of seq.sessions) {
        if (s.skip) { sess[s.id] = { seq, q, skip: true }; continue; }
        total++;
        if (s.pin && !(s.merge && prev)) { while (i < Aq.length && Aq[i].date < s.pin) i++; while (j < B.length && B[j].date < s.pin) j++; }   // date choisie à la main
        const bo = B[j++] || null;                                  // la date « idéale »
        let o;                                                      // la date réelle
        if (s.merge && prev) o = prev;
        else if (pinOcc[s.id]) o = pinOcc[s.id];
        else { while (i < Aq.length && Aq[i].rsv) i++; o = Aq[i++] || null; }
        prev = o; sess[s.id] = { seq, q, o, bo };
        if (o) (occSess[o.key] ??= []).push(s); else lost++;
        if (bo && bo.date < t) nb++; if (o && o.date < t) na++;
      }
    }
    queues[q] = { seqs: groups[q], late: nb - na, lost, total, done: na, slots: B.length };
  }
  return _c = { school, why, ttFor, occs, byDate, byKey, sess, occSess, queues };
}
// Tous les rappels (impression, matériel, mots, équipement), datés d'après la date réelle de chaque séance.
function reminders() {
  const c = ctx(), out = [];
  const push = (s, o, label) => (s.rem || []).forEach(r => out.push({ key: r.id, date: r.date || addD(o.date, -(+r.days || 0)), r, s, o, label }));
  for (const seq of db.sequences) for (const s of seq.sessions) { const x = c.sess[s.id]; if (x && x.o) push(s, x.o, qname(x.q)); }
  for (const k in db.adhoc) if (c.byKey[k]) push(db.adhoc[k], c.byKey[k], qname(c.byKey[k].q));
  return out.sort((a, b) => a.date.localeCompare(b.date));
}
// ---------- Difficultés ----------
// Une difficulté est rattachée à la séance où elle est apparue (n.occ). Elle est rappelée à la prochaine séance
// de la même matière, recalculée à chaque changement de l'emploi du temps ou de la programmation.
const nowKey = () => TODAY() + new Date().toTimeString().slice(0, 5);
const occLabel = (x, c) => { const ss = c.occSess[x.key] || [], ad = db.adhoc[x.key]; return fS(x.date) + ' ' + hm(x.start) + ', ' + qname(x.hostQ || x.q) + (ss.length ? ' : « ' + ss.map(s => s.title).join(' + ') + ' »' : ad ? ' : « ' + ad.title + ' »' : ''); };
// Les séances déjà commencées des classes de ces élèves, de la plus récente à la plus ancienne.
function pastOccs(ids, n = 25) {
  const c = ctx(), k = new Set(ids.map(id => (stu(id) || {}).classId)), now = nowKey();
  return c.occs.filter(x => k.has(x.classId) && !(x.ov && x.ov.t === 'lost') && x.date + x.start <= now).slice(-n).reverse();
}
function difNext(n) {
  const c = ctx(), o = c.byKey[n.occ], q = o ? o.hostQ || o.q : n.q; if (!q) return null;
  const at = o ? o.date + o.start : (n.date || '') + '99';
  return c.occs.find(x => (x.hostQ || x.q) === q && !(x.ov && x.ov.t === 'lost') && x.date + x.start > at) || null;
}
function difMap() {                                     // créneau → difficultés à y reprendre (gardé en mémoire avec le calcul)
  const c = ctx(); if (c.dif) return c.dif; const m = {};
  db.notes.filter(n => n.type === 'difficulte' && !n.done).forEach(n => { const x = difNext(n); if (x) (m[x.key] ??= []).push(n); });
  return c.dif = m;
}

const lostList = () => { const c = ctx(), r = []; for (const seq of db.sequences) for (const s of seq.sessions) { const x = c.sess[s.id]; if (x && !x.skip && !x.o) r.push(qname(x.q) + ' : ' + s.title); } return r; };
// Essaie une modification, regarde quelles séances sortiraient de l'année, puis remet tout en place.
function preview(fn) {
  const before = new Set(lostList()), snap = JSON.stringify([db.ov, db.sequences]);
  fn(); _c = null; const after = lostList().filter(x => !before.has(x));
  [db.ov, db.sequences] = JSON.parse(snap); _c = null; return after;
}
// Rétablit un créneau marqué « non fait » (et annule ce qui allait avec).
function restoreOcc(key) {
  const v = db.ov[key]; if (!v) return;
  if (v.host) delete db.ov[v.host];
  if (v.extraId) remove(db.extra, v.extraId);                        // le créneau ponctuel créé pour reporter la séance
  for (const id of [v.merged, v.skipped]) { const f = id && findS(id); if (f) { f.s.merge = false; f.s.skip = false; } }
  delete db.ov[key];
}

// ---------- 4. L'écran : menu, bandeau, fenêtres, impression ----------
const VIEWS = [['home', '🏠', 'Aujourd’hui'], ['journal', '📓', 'Cahier journal'], ['edt', '🗓️', 'Emploi du temps'], ['prog', '🧭', 'Programmation'], ['eleves', '🧒', 'Élèves'], ['classe', '🎲', 'Gestion de classe'], ['devoirs', '✏️', 'Devoirs'], ['appel', '✅', 'Appel'], ['hors', '⏰', 'Agenda de l’enseignant'], ['admin', '🗂️', 'Administratif'], ['reglages', '⚙️', 'Réglages']];
const PARENT = { eleve: 'eleves', seance: 'journal', agenda: 'hors' };
function go(v) { ui.view = v; if (location.hash.slice(1) !== v) history.pushState(null, '', '#' + v); render(); window.scrollTo(0, 0); }
window.addEventListener('popstate', () => { const v = location.hash.slice(1); ui.view = V[v] && !((v === 'eleve' && !ui.stu) || (v === 'seance' && !ui.sess)) ? v : 'home'; render(); });
const needBackup = () => db.dirty && new Date().getHours() >= (+db.settings.backupHour || 16) && (db.settings.lastBackup || '').slice(0, 10) !== TODAY() && ui.snooze !== TODAY();

function render() {
  applyTheme();
  const cur = PARENT[ui.view] || ui.view;
  $('#nav').innerHTML = `<div class="logo">Cahier de classe<small>${esc(db.settings.year)}</small></div>` + VIEWS.map(([k, ic, l]) => `<button data-a="go" data-v="${k}" ${cur === k ? 'aria-current="page"' : ''}><span class="ic">${ic}</span>${l}</button>`).join('');
  const lv = allLevels();
  $('#top').innerHTML = (db.classes.length > 1 ? `<select data-c="fcls" aria-label="Classe affichée"><option value="">Toutes les classes</option>${db.classes.map(c => `<option value="${c.id}" ${ui.cls === c.id ? 'selected' : ''}>${esc((db.settings.mark === 'icon' ? (c.icon || '') + ' ' : '') + c.name)}</option>`).join('')}</select>` : '')
    + (lv.length > 1 ? `<select data-c="flvl" aria-label="Niveau affiché"><option value="">Tous les niveaux</option>${lv.map(l => `<option ${ui.lvl === l ? 'selected' : ''}>${esc(l)}</option>`).join('')}</select>` : '')
    + `<span class="sp"></span><button class="btn" data-a="printPage">Imprimer</button><button class="btn" data-a="backup">Sauvegarder</button>`;
  let h = '';
  if (needBackup()) h += `<div class="banner">Fin de journée : enregistrez la sauvegarde sur votre clé USB.<span class="sp"></span><button class="btn pri" data-a="backup">Sauvegarder</button><button class="btn" data-a="snooze">Plus tard</button></div>`;
  try { h += (V[ui.view] || V.home)(); } catch (e) { console.error(e); h += `<div class="sheet"><h2>Cette page n’a pas pu s’afficher</h2><p>${esc(e.message)}</p><p>Vos données ne sont pas touchées. Faites une sauvegarde puis rechargez la page.</p></div>`; }
  $('#main').innerHTML = h;
}
A.go = d => go(d.v);
A.snooze = () => { ui.snooze = TODAY(); render(); };
A.printPage = () => window.print();
C.fcls = (d, el) => { ui.cls = el.value; render(); };
C.flvl = (d, el) => { ui.lvl = el.value; render(); };

let _toastT;
function toast(msg) { const t = $('#toast'); t.textContent = msg; t.hidden = false; clearTimeout(_toastT); _toastT = setTimeout(() => t.hidden = true, 4200); }

// Un formulaire générique : on décrit les champs, il s'affiche dans une fenêtre et rend les valeurs saisies.
function field(f, val) {
  if (f.type === 'checkbox') return `<label class="chk"><input type="checkbox" name="${f.k}" ${val ? 'checked' : ''}> ${esc(f.label)}</label>`;
  let inp;
  if (f.type === 'select') inp = `<select name="${f.k}">${f.options.map(([a, b]) => `<option value="${esc(a)}" ${String(a) === String(val ?? '') ? 'selected' : ''}>${esc(b)}</option>`).join('')}</select>`;
  else if (f.type === 'textarea') inp = `<textarea name="${f.k}" rows="${f.rows || 3}" placeholder="${esc(f.ph || '')}">${esc(val)}</textarea>`;
  else inp = `<input type="${f.type || 'text'}" name="${f.k}" value="${esc(val)}" placeholder="${esc(f.ph || '')}" ${f.req ? 'required' : ''} ${f.type === 'number' ? 'step="any" inputmode="decimal"' : ''}>`;
  return `<label class="fld"><span>${esc(f.label)}</span>${inp}</label>`;
}
// Brouillons : tout ce qu'on tape dans un formulaire est gardé au fur et à mesure. Si on sort sans enregistrer
// (clic à côté, touche Échap, page rechargée), on retrouve sa saisie en rouvrant le même formulaire.
// Seul le bouton « Annuler » jette le brouillon.
const DKEY = 'cahier-classe-brouillons';
let drafts = {};
try { drafts = JSON.parse(localStorage.getItem(DKEY)) || {}; } catch (e) { drafts = {}; }
for (const k in drafts) if (Date.now() - (drafts[k].t || 0) > 7 * 864e5) delete drafts[k];          // on oublie les brouillons de plus d'une semaine
const draftKey = o => o.dk ??= [o.title, (o.values || {}).id || '', ui.view, ui.view === 'eleve' ? ui.stu : '', ui.view === 'seance' ? JSON.stringify(ui.sess) : ''].join('|');
const putDrafts = () => { try { localStorage.setItem(DKEY, JSON.stringify(drafts)); } catch (e) { } };
function saveDraft() {
  const f = $('#frm'); if (!ui.form || !f) return; const v = {};
  ui.form.fields.forEach(x => { const el = f.elements[x.k]; if (el) v[x.k] = x.type === 'checkbox' ? el.checked : el.value; });
  drafts[draftKey(ui.form)] = { t: Date.now(), v }; putDrafts();
}
function dropDraft(o) { if (o && drafts[draftKey(o)]) { delete drafts[draftKey(o)]; putDrafts(); } }
function openForm(o) {
  ui.form = o; const d = drafts[draftKey(o)], v = { ...(o.values || {}), ...(d ? d.v : {}) };
  showModal(`<form class="dlg" id="frm" novalidate><h2>${esc(o.title)}</h2>${d ? '<p class="banner" style="font-weight:400;font-size:.9rem">Saisie non enregistrée retrouvée.<span class="sp"></span><button type="button" class="btn sm" data-a="draftDrop">Repartir de zéro</button></p>' : ''}${o.intro || ''}${o.fields.map(f => field(f, v[f.k])).join('')}<div class="row end">${o.onDelete ? '<button type="button" class="btn danger" data-a="formDel">Supprimer</button>' : ''}<span class="sp"></span><button type="button" class="btn" data-a="formCancel">Annuler</button><button class="btn pri">${esc(o.ok || 'Enregistrer')}</button></div></form>`);
  const first = $('#frm input:not([type=date]):not([type=time]):not([type=hidden]),#frm textarea,#frm select'); if (first && !d) first.focus();
}
A.formCancel = () => { dropDraft(ui.form); closeModal(); };
A.draftDrop = () => { const o = ui.form; dropDraft(o); openForm(o); };
// Clic à côté de la fenêtre ou touche Échap : on ferme, mais la saisie en cours est gardée.
A.modalOut = () => { if (ui.form && drafts[draftKey(ui.form)]) toast('Saisie gardée : rouvrez le formulaire pour la retrouver.'); closeModal(); };
function showModal(html) { const m = $('#modal'); m.innerHTML = '<div class="ov" data-a="modalOut"></div>' + html; m.hidden = false; }
function closeModal() { $('#modal').hidden = true; $('#modal').innerHTML = ''; ui.form = null; }
A.closeModal = closeModal;
A.formDel = () => { if (confirm('Supprimer définitivement ?')) { ui.form.onDelete(); dropDraft(ui.form); closeModal(); done(); } };
// Ajoute ou modifie une fiche dans une liste.
function upsert(arr, id, vals) { const x = id && arr.find(a => a.id === id); if (x) Object.assign(x, vals); else arr.push({ id: uid(), ...vals }); }
const remove = (arr, id) => { const i = arr.findIndex(a => a.id === id); if (i >= 0) arr.splice(i, 1); };

document.addEventListener('submit', e => {
  e.preventDefault();
  if (e.target.id !== 'frm' || !ui.form) return;
  const vals = {}, miss = ui.form.fields.find(f => f.req && !e.target.elements[f.k].value.trim());
  if (miss) { toast('Il manque : ' + miss.label.replace(/\s*\(.*$/, '') + '.'); return; }
  ui.form.fields.forEach(f => { const el = e.target.elements[f.k]; vals[f.k] = f.type === 'checkbox' ? el.checked : f.type === 'number' ? (el.value === '' ? '' : +el.value) : el.value.trim(); });
  const o = ui.form; if (o.onSave(vals) !== false) { dropDraft(o); closeModal(); done(); }
});
document.addEventListener('click', e => {
  const el = e.target.closest('[data-a]'); if (!el) return;
  if (ui.held) { ui.held = false; return; }             // on vient de finir un appui long : ce n'est pas un clic
  const f = A[el.dataset.a]; if (f) f(el.dataset, el, e);
});
document.addEventListener('input', e => { if (e.target.closest('#frm')) saveDraft(); const el = e.target.closest('[data-c]'); if (el && C[el.dataset.c]) C[el.dataset.c](el.dataset, el); });
document.addEventListener('keydown', e => { if (e.key === 'Escape') { if (!$('#pick').hidden) { A.pickClose(); return; } if (!$('#modal').hidden) A.modalOut(); if (!$('#voice').hidden) A.voiceCancel(); } });

// ---------- Choisir une date ou une heure ----------
// Les champs date et heure du navigateur sont remplacés partout par un bouton qui ouvre notre propre
// sélecteur : un calendrier du mois (vacances et jours sans classe grisés) ou une grille d'heures et de minutes.
// Le champ d'origine reste dans la page, caché : il garde la valeur (2026-11-12 ou 16:45) pour le reste du code.
function pkLabel(inp) {
  const b = inp.nextElementSibling; if (!b || !b.classList.contains('pk')) return;
  const d = inp.dataset.kind === 'date';
  b.innerHTML = `<span aria-hidden="true">${d ? '📅' : '🕒'}</span> ` + (inp.value ? esc(d ? fD(inp.value, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' }) : hm(inp.value)) : `<span class="muted">${d ? 'Choisir une date' : 'Choisir l’heure'}</span>`);
}
function enhance(root) {
  if (!root.querySelectorAll) return;
  root.querySelectorAll('input[type=date],input[type=time]').forEach(inp => {
    inp.dataset.kind = inp.type; if (inp.required) { inp.dataset.req = 1; inp.required = false; }
    inp.type = 'hidden';
    const b = document.createElement('button'); b.type = 'button'; b.className = 'pk'; b.dataset.a = 'pick';
    if (inp.getAttribute('aria-label')) b.setAttribute('aria-label', inp.getAttribute('aria-label'));
    inp.after(b); pkLabel(inp);
  });
}
new MutationObserver(ms => { for (const m of ms) for (const n of m.addedNodes) if (n.nodeType === 1) enhance(n.parentNode || n); }).observe(document.body, { childList: true, subtree: true });
A.pick = (d, el) => {
  const inp = el.previousElementSibling, v = inp.value;
  ui.pk = inp.dataset.kind === 'date' ? { inp, kind: 'date', ym: (v || TODAY()).slice(0, 7) } : { inp, kind: 'time', hh: v ? +v.slice(0, 2) : null, mm: v ? +v.slice(3) : 0 };
  showPick();
};
function showPick() {
  const p = ui.pk, el = $('#pick'), v = p.inp.value, opt = !p.inp.dataset.req; let h;
  if (p.kind === 'date') {
    const c = ctx(), t = TODAY(), first = p.ym + '-01', pad = (wd(first) + 6) % 7; let cells = '<span></span>'.repeat(pad);
    for (let d = first; d.slice(0, 7) === p.ym; d = addD(d, 1)) cells += `<button type="button" class="${d === v ? 'sel' : ''} ${d === t ? 'today' : ''} ${c.why(d) ? 'off' : ''}" data-a="pkDay" data-d="${d}" ${c.why(d) ? `title="${esc(c.why(d))}"` : ''}>${+d.slice(8)}</button>`;
    h = `<div class="row"><button type="button" class="btn" data-a="pkNav" data-n="-1" aria-label="Mois précédent">‹</button><b class="sp pkm">${esc(fD(first, { month: 'long', year: 'numeric' }))}</b><button type="button" class="btn" data-a="pkNav" data-n="1" aria-label="Mois suivant">›</button></div>
      <div class="cal">${['L', 'M', 'M', 'J', 'V', 'S', 'D'].map(x => `<i>${x}</i>`).join('')}${cells}</div>
      <p class="small muted" style="margin:6px 0 0">En gris : vacances, jours fériés, jours sans classe.</p>
      <div class="row end"><button type="button" class="btn sm" data-a="pkDay" data-d="${t}">Aujourd’hui</button><button type="button" class="btn sm" data-a="pkDay" data-d="${addD(t, 1)}">Demain</button>${opt && v ? '<button type="button" class="btn sm" data-a="pkDay" data-d="">Effacer</button>' : ''}<span class="sp"></span><button type="button" class="btn sm" data-a="pickClose">Fermer</button></div>`;
  } else {
    const hs = p.more ? [...Array(24).keys()] : [7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20];
    h = `<p class="pkt">${p.hh == null ? '<span class="muted">-- h --</span>' : hm(toT(p.hh * 60 + p.mm))}</p>
      <p class="small muted" style="margin:0 0 4px">Heure</p><div class="tg" style="--n:${p.more ? 6 : 7}">${hs.map(x => `<button type="button" class="${p.hh === x ? 'sel' : ''}" data-a="pkH" data-h="${x}">${x}</button>`).join('')}</div>
      ${p.more ? '' : '<p style="margin:4px 0 0"><button type="button" class="lnk small" data-a="pkMore">Plus tôt ou plus tard</button></p>'}
      <p class="small muted" style="margin:8px 0 4px">Minutes</p><div class="tg" style="--n:6">${[0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55].map(x => `<button type="button" class="${p.hh != null && p.mm === x ? 'sel' : ''}" data-a="pkM" data-m="${x}">${String(x).padStart(2, '0')}</button>`).join('')}</div>
      <div class="row end">${opt && v ? '<button type="button" class="btn sm" data-a="pkSet" data-v="">Effacer</button>' : ''}<span class="sp"></span><button type="button" class="btn sm" data-a="pickClose">Fermer</button><button type="button" class="btn sm pri" data-a="pkOk">Valider</button></div>`;
  }
  el.innerHTML = `<div class="ov" data-a="pickClose"></div><div class="pkp" role="dialog" aria-label="${p.kind === 'date' ? 'Choisir une date' : 'Choisir une heure'}">${h}</div>`; el.hidden = false;
}
// La valeur choisie est écrite dans le champ caché, qui prévient le reste de l'appli comme si on l'avait tapée.
function pkSet(v) { const inp = ui.pk.inp; A.pickClose(); if (!document.contains(inp)) return; inp.value = v; pkLabel(inp); inp.dispatchEvent(new Event('input', { bubbles: true })); }
A.pickClose = () => { $('#pick').hidden = true; $('#pick').innerHTML = ''; };
A.pkNav = d => { const p = ui.pk, x = new Date(+p.ym.slice(0, 4), +p.ym.slice(5) - 1 + +d.n, 1); p.ym = iso(x).slice(0, 7); showPick(); };
A.pkDay = d => pkSet(d.d);
A.pkSet = d => pkSet(d.v);
A.pkMore = () => { ui.pk.more = true; showPick(); };
A.pkH = d => { ui.pk.hh = +d.h; showPick(); };                                   // on choisit l'heure…
A.pkM = d => { const p = ui.pk; p.mm = +d.m; if (p.hh == null) showPick(); else pkSet(toT(p.hh * 60 + p.mm)); };   // …puis les minutes, et c'est validé
A.pkOk = () => { const p = ui.pk; if (p.hh == null) { toast('Choisissez d’abord l’heure.'); return; } pkSet(toT(p.hh * 60 + p.mm)); };

// Imprime un document à part (fiche de préparation, registre, feuille du remplaçant…).
function printDoc(html) { $('#print').innerHTML = html; document.body.classList.add('pdoc'); setTimeout(() => window.print(), 60); }
window.addEventListener('afterprint', () => { document.body.classList.remove('pdoc'); $('#print').innerHTML = ''; });
const docHead = t => `<h1>${esc(t)}</h1><p>${esc([db.settings.school, db.settings.teacher, db.settings.year].filter(Boolean).join(', '))}</p>`;

// ---------- 5. Sauvegarde ----------
// Sur ordinateur (Chrome, Edge), une fenêtre demande où enregistrer : on y choisit la clé USB.
// Ailleurs, le fichier part dans les téléchargements. La clé de l'option IA n'est jamais copiée.
function download(name, text) { const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([text], { type: 'application/json' })); a.download = name; document.body.appendChild(a); a.click(); setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 500); }
async function saveFile(name, text) {
  if (window.showSaveFilePicker) {
    try { const h = await window.showSaveFilePicker({ suggestedName: name, types: [{ description: 'Fichier Cahier de classe', accept: { 'application/json': ['.json'] } }] }); const w = await h.createWritable(); await w.write(text); await w.close(); return true; }
    catch (e) { if (e.name === 'AbortError') return false; }
  }
  download(name, text); return true;
}
A.backup = async () => {
  const copy = JSON.parse(JSON.stringify(db)); copy.settings.apiKey = '';
  if (!await saveFile(`cahier-classe-${TODAY()}.json`, JSON.stringify(copy))) return;
  db.settings.lastBackup = TODAY() + ' ' + new Date().toTimeString().slice(0, 5); db.dirty = false;
  localStorage.setItem(KEY, JSON.stringify(db)); toast('Sauvegarde enregistrée.'); render();
};
// Lecture d'un fichier choisi par l'utilisateur (restauration, import d'un bloc ou d'une séance).
function pickFile(cb) { const f = $('#file'); f.value = ''; f.onchange = () => { const file = f.files[0]; if (!file) return; const r = new FileReader(); r.onload = () => { try { cb(JSON.parse(r.result)); } catch (e) { toast('Ce fichier n’est pas lisible par l’appli.'); } }; r.readAsText(file); }; f.click(); }

// ---------- 6. Note vocale ----------
// On maintient le doigt sur la tuile d'un élève : l'appli écoute et transcrit. On relâche :
// quatre bulles entourent le texte pour choisir le type de note. L'heure est notée automatiquement.
const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
let hold = null, rec = null;
document.addEventListener('pointerdown', e => { const t = e.target.closest('.tile'); if (!t) return; const id = t.dataset.id;
  // En mode sélection, la dictée vaut pour tous les élèves sélectionnés (plus celui qu'on maintient).
  hold = { timer: setTimeout(() => { hold.on = true; startVoice(ui.sel ? [...new Set([...ui.sel, id])] : [id]); }, 450) }; });
function endHold() { if (!hold) return; clearTimeout(hold.timer); if (hold.on) { ui.held = true; setTimeout(() => ui.held = false, 400); stopVoice(); } hold = null; }
document.addEventListener('pointerup', endHold); document.addEventListener('pointercancel', endHold);
document.addEventListener('contextmenu', e => { if (e.target.closest('.tile')) e.preventDefault(); });
function startVoice(ids) {
  ui.voice = { ids, text: '', time: new Date().toTimeString().slice(0, 5), date: TODAY(), on: true }; showVoice();
  if (!SR) return;
  try {
    rec = new SR(); rec.lang = 'fr-FR'; rec.continuous = true; rec.interimResults = true; let fin = '';
    rec.onresult = ev => { let it = ''; for (let i = ev.resultIndex; i < ev.results.length; i++) { const r = ev.results[i]; if (r.isFinal) fin += r[0].transcript + ' '; else it += r[0].transcript; } if (!ui.voice) return; ui.voice.text = (fin + it).trim(); const el = $('#vtext'); if (el) el.value = ui.voice.text; };
    rec.onerror = () => { }; rec.start();
  } catch (e) { rec = null; }
}
function stopVoice() { if (rec) { try { rec.stop(); } catch (e) { } } if (ui.voice) { ui.voice.on = false; showVoice(); const el = $('#vtext'); if (el) el.focus(); } }
const whoTxt = ids => ids.length === 1 ? sname(stu(ids[0])) : ids.length + ' élèves : ' + ids.map(id => (stu(id) || {}).first).join(', ');
function showVoice() {
  const v = ui.voice, el = $('#voice');
  if (v.beh) {                                         // le choix « Comportement » : les boutons du comportement en un geste
    const B = db.settings.behav, chip = b => `<button class="btn sm" style="border-color:${b.pos ? 'var(--ok)' : 'var(--marge)'}" data-a="voiceBeh" data-b="${b.id}">${b.pos ? '＋ ' : ''}${esc(b.l)}</button>`;
    el.innerHTML = `<div class="ov"></div><div class="dlg"><h2>Comportement</h2><p class="small muted">${esc(whoTxt(v.ids))}, ${fS(v.date)} à ${hm(v.time)}.${v.text ? ' Le texte dicté sera gardé en détail.' : ''}</p>
      <div class="row">${B.filter(b => b.pos).map(chip).join('')}</div><div class="row" style="margin-top:8px">${B.filter(b => !b.pos).map(chip).join('')}</div>
      <div class="row end"><button class="btn" data-a="voiceBack">‹ Retour</button><span class="sp"></span><button class="btn" data-a="voiceCancel">Annuler</button></div></div>`;
    el.hidden = false; return;
  }
  el.innerHTML = `<div class="ov"></div><div class="vp">${Object.entries(NOTE).map(([k, [ic, l]]) => `<button class="bb" data-a="${k === 'comportement' ? 'voiceBehOpen' : 'voiceSave'}" data-t="${k}"><div><span>${ic}</span>${l}</div></button>`).join('')}
    <div class="mid"><b>${esc(whoTxt(v.ids))}</b><span class="small muted">${fS(v.date)} à ${hm(v.time)}</span>
    ${v.on ? `<span class="pulse small">${SR ? '🎙️ J’écoute… relâchez pour finir' : 'Relâchez pour écrire la note'}</span>` : ''}
    <textarea id="vtext" data-c="vtext" placeholder="${SR ? 'Le texte dicté apparaît ici' : 'Dictée indisponible ici : écrivez la note, ou utilisez le micro du clavier'}">${esc(v.text)}</textarea></div>
    <button class="x" data-a="voiceCancel">Annuler</button></div>`;
  el.hidden = false;
}
C.vtext = (d, el) => { if (ui.voice) ui.voice.text = el.value; };
A.voiceCancel = () => { if (rec) { try { rec.abort(); } catch (e) { } } ui.voice = null; $('#voice').hidden = true; };
// Enregistre une note pour un ou plusieurs élèves (une note par élève, reliées par gid).
function addNotes(ids, base) {
  const gid = ids.length > 1 ? uid() : undefined;
  if (base.type === 'difficulte' && base.occ === undefined) { const o = pastOccs(ids, 1)[0]; base.occ = o ? o.key : ''; }
  if (base.type === 'difficulte') { const o = ctx().byKey[base.occ]; if (o) Object.assign(base, { q: o.hostQ || o.q, date: o.date, time: o.start }); }
  ids.forEach(sid => db.notes.push({ id: uid(), gid, sid, sub: '', ...base }));
}
A.voiceSave = d => {
  const v = ui.voice; if (!v) return; const text = ($('#vtext').value || '').trim();
  if (!text) { toast('La note est vide : dictez ou écrivez quelques mots.'); return; }
  addNotes(v.ids, { type: d.t, text, date: v.date, time: v.time });
  const n = db.notes[db.notes.length - 1], x = d.t === 'difficulte' && n.occ ? ctx().byKey[n.occ] : null;
  A.voiceCancel(); done();
  toast(NOTE[d.t][1] + ' enregistrée pour ' + whoTxt(v.ids) + '.' + (x ? ' Rattachée à la séance de ' + qname(x.hostQ || x.q) + ' du ' + fS(x.date) + ' (modifiable dans la fiche).' : ''));
};
A.voiceBehOpen = () => { const el = $('#vtext'); if (el) ui.voice.text = el.value; ui.voice.beh = true; ui.voice.on = false; if (rec) { try { rec.stop(); } catch (e) { } } showVoice(); };
A.voiceBack = () => { ui.voice.beh = false; showVoice(); };
A.voiceBeh = d => { const v = ui.voice, b = db.settings.behav.find(x => x.id === d.b); addNotes(v.ids, { type: 'comportement', sub: b.l, pos: b.pos, text: (v.text || '').trim(), date: v.date, time: v.time }); A.voiceCancel(); done(); toast('« ' + b.l + ' » noté pour ' + whoTxt(v.ids) + '.'); 
};
