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
const CATS = { apc: ['APC', '#23795b'], equipe: ['Équipe pédagogique et parents', '#3355c0'], formation: ['Formation pédagogique', '#a2630c'], conseil: ['Conseil d’école', '#8a3b9c'], classe: ['Vie de la classe', '#27433d'], perso: ['Autre (hors 108 h)', '#59607c'] };
const NOTE = { incident: ['🚑', 'Incident'], comportement: ['🙂', 'Comportement'], evaluation: ['📝', 'Évaluation'], commentaire: ['💬', 'Commentaire'] };
const INC = ['Dispute', 'Blessure', 'Maladie', 'Communication avec la famille', 'Autre'];
const LEVELS = ['Non atteint', 'Partiellement atteint', 'Atteint', 'Dépassé'];

// ---------- 2. Les données ----------
function blank() {
  const S = (name, color, subs) => ({ id: uid(), name, color, subs });
  return {
    v: 1,
    settings: {
      teacher: '', school: '', year: '2026-2027', start: '2026-09-01', end: '2027-07-02', days: [1, 2, 4, 5],
      holidays: [{ from: '2026-10-17', to: '2026-11-01', label: 'Toussaint' }, { from: '2026-12-19', to: '2027-01-03', label: 'Noël' }],
      quotas: { apc: 36, equipe: 48, formation: 18, conseil: 6 }, mark: 'color',
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
    notes: [], results: [], meetings: [], lists: [], events: [],
    ov: {},              // créneaux non faits ou prêtés : « date|créneau » → { t:'lost' | 'host', … }
    adhoc: {},           // séances écrites directement dans le cahier journal, hors programmation
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
  occs.sort((a, b) => (a.date + a.start).localeCompare(b.date + b.start));
  for (const o of occs) {
    byKey[o.key] = o; (byDate[o.date] ??= []).push(o); (base[o.q] ??= []).push(o);
    const v = db.ov[o.key];
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
        const bo = B[j++] || null;                                  // la date « idéale »
        const o = (s.merge && prev) ? prev : (Aq[i++] || null);     // la date réelle
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
  const push = (s, o, label) => (s.rem || []).forEach(r => out.push({ key: r.id, date: addD(o.date, -(+r.days || 0)), r, s, o, label }));
  for (const seq of db.sequences) for (const s of seq.sessions) { const x = c.sess[s.id]; if (x && x.o) push(s, x.o, qname(x.q)); }
  for (const k in db.adhoc) if (c.byKey[k]) push(db.adhoc[k], c.byKey[k], qname(c.byKey[k].q));
  return out.sort((a, b) => a.date.localeCompare(b.date));
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
  for (const id of [v.merged, v.skipped]) { const f = id && findS(id); if (f) { f.s.merge = false; f.s.skip = false; } }
  delete db.ov[key];
}

// ---------- 4. L'écran : menu, bandeau, fenêtres, impression ----------
const VIEWS = [['home', '🏠', 'Aujourd’hui'], ['journal', '📓', 'Cahier journal'], ['edt', '🗓️', 'Emploi du temps'], ['prog', '🧭', 'Programmation'], ['agenda', '⏰', 'Agenda, 108 h'], ['eleves', '🧒', 'Élèves'], ['appel', '✅', 'Appel'], ['admin', '🗂️', 'Administratif'], ['reglages', '⚙️', 'Réglages']];
const PARENT = { eleve: 'eleves', seance: 'journal' };
function go(v) { ui.view = v; if (location.hash.slice(1) !== v) history.pushState(null, '', '#' + v); render(); window.scrollTo(0, 0); }
window.addEventListener('popstate', () => { const v = location.hash.slice(1); ui.view = V[v] && !((v === 'eleve' && !ui.stu) || (v === 'seance' && !ui.sess)) ? v : 'home'; render(); });
const needBackup = () => db.dirty && new Date().getHours() >= (+db.settings.backupHour || 16) && (db.settings.lastBackup || '').slice(0, 10) !== TODAY() && ui.snooze !== TODAY();

function render() {
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
function openForm(o) {
  ui.form = o; const v = o.values || {};
  showModal(`<form class="dlg" id="frm"><h2>${esc(o.title)}</h2>${o.intro || ''}${o.fields.map(f => field(f, v[f.k])).join('')}<div class="row end">${o.onDelete ? '<button type="button" class="btn danger" data-a="formDel">Supprimer</button>' : ''}<span class="sp"></span><button type="button" class="btn" data-a="closeModal">Annuler</button><button class="btn pri">${esc(o.ok || 'Enregistrer')}</button></div></form>`);
  const first = $('#frm input,#frm textarea,#frm select'); if (first) first.focus();
}
function showModal(html) { const m = $('#modal'); m.innerHTML = '<div class="ov" data-a="closeModal"></div>' + html; m.hidden = false; }
function closeModal() { $('#modal').hidden = true; $('#modal').innerHTML = ''; ui.form = null; }
A.closeModal = closeModal;
A.formDel = () => { if (confirm('Supprimer définitivement ?')) { ui.form.onDelete(); closeModal(); done(); } };
// Ajoute ou modifie une fiche dans une liste.
function upsert(arr, id, vals) { const x = id && arr.find(a => a.id === id); if (x) Object.assign(x, vals); else arr.push({ id: uid(), ...vals }); }
const remove = (arr, id) => { const i = arr.findIndex(a => a.id === id); if (i >= 0) arr.splice(i, 1); };

document.addEventListener('submit', e => {
  e.preventDefault();
  if (e.target.id !== 'frm' || !ui.form) return;
  const vals = {};
  ui.form.fields.forEach(f => { const el = e.target.elements[f.k]; vals[f.k] = f.type === 'checkbox' ? el.checked : f.type === 'number' ? (el.value === '' ? '' : +el.value) : el.value.trim(); });
  if (ui.form.onSave(vals) !== false) { closeModal(); done(); }
});
document.addEventListener('click', e => {
  const el = e.target.closest('[data-a]'); if (!el) return;
  if (ui.held) { ui.held = false; return; }             // on vient de finir un appui long : ce n'est pas un clic
  const f = A[el.dataset.a]; if (f) f(el.dataset, el, e);
});
document.addEventListener('input', e => { const el = e.target.closest('[data-c]'); if (el && C[el.dataset.c]) C[el.dataset.c](el.dataset, el); });
document.addEventListener('keydown', e => { if (e.key === 'Escape') { if (!$('#modal').hidden) closeModal(); if (!$('#voice').hidden) A.voiceCancel(); } });

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
document.addEventListener('pointerdown', e => { const t = e.target.closest('.tile'); if (!t) return; const id = t.dataset.id; hold = { timer: setTimeout(() => { hold.on = true; startVoice(id); }, 450) }; });
function endHold() { if (!hold) return; clearTimeout(hold.timer); if (hold.on) { ui.held = true; setTimeout(() => ui.held = false, 400); stopVoice(); } hold = null; }
document.addEventListener('pointerup', endHold); document.addEventListener('pointercancel', endHold);
document.addEventListener('contextmenu', e => { if (e.target.closest('.tile')) e.preventDefault(); });
function startVoice(id) {
  ui.voice = { id, text: '', time: new Date().toTimeString().slice(0, 5), date: TODAY(), on: true }; showVoice();
  if (!SR) return;
  try {
    rec = new SR(); rec.lang = 'fr-FR'; rec.continuous = true; rec.interimResults = true; let fin = '';
    rec.onresult = ev => { let it = ''; for (let i = ev.resultIndex; i < ev.results.length; i++) { const r = ev.results[i]; if (r.isFinal) fin += r[0].transcript + ' '; else it += r[0].transcript; } if (!ui.voice) return; ui.voice.text = (fin + it).trim(); const el = $('#vtext'); if (el) el.value = ui.voice.text; };
    rec.onerror = () => { }; rec.start();
  } catch (e) { rec = null; }
}
function stopVoice() { if (rec) { try { rec.stop(); } catch (e) { } } if (ui.voice) { ui.voice.on = false; showVoice(); const el = $('#vtext'); if (el) el.focus(); } }
function showVoice() {
  const v = ui.voice, el = $('#voice');
  el.innerHTML = `<div class="ov"></div><div class="vp">${Object.entries(NOTE).map(([k, [ic, l]]) => `<button class="bb" data-a="voiceSave" data-t="${k}"><div><span>${ic}</span>${l}</div></button>`).join('')}
    <div class="mid"><b>${esc(sname(stu(v.id)))}</b><span class="small muted">${fS(v.date)} à ${hm(v.time)}</span>
    ${v.on ? `<span class="pulse small">${SR ? '🎙️ J’écoute… relâchez pour finir' : 'Relâchez pour écrire la note'}</span>` : ''}
    <textarea id="vtext" data-c="vtext" placeholder="${SR ? 'Le texte dicté apparaît ici' : 'Dictée indisponible ici : écrivez la note, ou utilisez le micro du clavier'}">${esc(v.text)}</textarea></div>
    <button class="x" data-a="voiceCancel">Annuler</button></div>`;
  el.hidden = false;
}
C.vtext = (d, el) => { if (ui.voice) ui.voice.text = el.value; };
A.voiceCancel = () => { if (rec) { try { rec.abort(); } catch (e) { } } ui.voice = null; $('#voice').hidden = true; };
A.voiceSave = d => {
  const v = ui.voice; if (!v) return; const text = ($('#vtext').value || '').trim();
  if (!text) { toast('La note est vide : dictez ou écrivez quelques mots.'); return; }
  db.notes.push({ id: uid(), sid: v.id, type: d.t, sub: '', text, date: v.date, time: v.time });
  A.voiceCancel(); toast(NOTE[d.t][1] + ' enregistré pour ' + sname(stu(v.id)) + '.'); done();
};
