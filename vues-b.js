'use strict';
// =====================================================================================
//  vues-b.js — agenda de classe, hors classe et 108 heures, élèves, évaluations, bilans,
//  appel, administratif, réglages.
// =====================================================================================

// ---------- Agenda de la classe ----------
const fmtH = h => { const m = Math.round(h * 60); return Math.floor(m / 60) + ' h' + (m % 60 ? ' ' + String(m % 60).padStart(2, '0') : ''); };
function h108() {                                      // heures faites et prévues, par enveloppe des obligations de service
  const q = db.settings.quotas, r = {}, t = TODAY();
  for (const k in q) r[k] = { quota: +q[k] || 0, done: 0, plan: 0, parts: {} };
  for (const e of db.events) { const x = r[qcat(e.cat)]; if (!x) continue; const h = +e.hours || 0; x[e.date <= t ? 'done' : 'plan'] += h; x.parts[e.cat] = (x.parts[e.cat] || 0) + h; }
  return r;
}
const evLi = e => `<li><span class="dot" style="background:${CATS[e.cat][1]}"></span><button class="lnk" data-a="editEv" data-id="${e.id}">${e.start ? hm(e.start) + ' ' : ''}<b>${esc(e.title)}</b></button> ${+e.hours && db.settings.quotas[qcat(e.cat)] !== undefined ? `<span class="chip">${fmtH(+e.hours)}</span>` : ''} ${cbadge(e.classId)}</li>`;
const weekNav = () => `<div class="row" style="margin-bottom:10px"><button class="btn np" data-a="week" data-n="-7" aria-label="Semaine précédente">‹</button><b>Semaine du ${esc(fD(ui.week, { day: 'numeric', month: 'long', year: 'numeric' }))}</b><button class="btn np" data-a="week" data-n="7" aria-label="Semaine suivante">›</button><button class="btn np" data-a="week" data-n="0">Cette semaine</button></div>`;
const visEv = () => db.events.filter(e => !ui.cls || !e.classId || e.classId === ui.cls);
V.agenda = () => V.hors();          // l'ancien « agenda de la classe » est réuni dans l'agenda de l'enseignant
function evForm(e, date, cat) {
  openForm({ title: e ? 'Modifier' : cat === 'classe' ? 'Nouvel événement de classe' : 'Nouveau rendez-vous', values: e || { date: date || TODAY(), cat: cat || 'equipe', classId: '' },
    fields: [{ k: 'title', label: 'Objet', req: 1 }, { k: 'cat', label: 'Catégorie', type: 'select', options: Object.entries(CATS).map(([k, v]) => [k, v[0]]) }, { k: 'date', label: 'Date', type: 'date', req: 1 }, { k: 'start', label: 'Début', type: 'time' }, { k: 'end', label: 'Fin', type: 'time' },
    { k: 'hours', label: 'Durée comptée en heures (vide : calculée d’après début et fin)', type: 'number' }, ...(db.classes.length > 1 ? [{ k: 'classId', label: 'Classe concernée', type: 'select', options: [['', 'Aucune en particulier'], ...clsOpts()] }] : []), { k: 'note', label: 'Notes, compte rendu', type: 'textarea' }],
    onSave: v => { if (v.hours === '' && v.start && v.end) v.hours = Math.max(0, (mins(v.end) - mins(v.start)) / 60); upsert(db.events, e && e.id, v); },
    onDelete: e ? () => remove(db.events, e.id) : null });
}
A.newEv = d => evForm(null, d.d, d.cat);
A.editEv = d => evForm(db.events.find(e => e.id === d.id));

// ---------- Hors classe ----------
// Un emploi du temps à part pour le travail hors de la classe : les créneaux de préparation qui
// reviennent chaque semaine (corrections, préparation, photocopies…) et les rendez-vous datés
// (réunions, APC, formations, conseils d'école). Le suivi des 108 heures est en bas de la page.
V.hors = () => {
  const t = TODAY(), H = h108(), evs = visEv(), vc = visCls();
  const days = [0, 1, 2, 3, 4, 5, 6].map(i => addD(ui.week, i)), end = days[6];
  const colsG = days.map(d => ({ day: wd(d), label: fD(d, { weekday: 'short', day: 'numeric', month: 'short' }) }));
  const blocks = days.flatMap((d, i) => [
    ...db.prep.filter(p => p.day === wd(d)).map(p => ({ col: i, start: p.start, end: p.end, color: '#59607c', attrs: `data-a="editPrep" data-id="${p.id}"`, html: `<b>${esc(p.title)}</b>${hm(p.start)}-${hm(p.end)}` })),
    ...evs.filter(e => e.date === d && e.start && e.end && e.end > e.start).map(e => ({ col: i, start: e.start, end: e.end, color: CATS[e.cat][1], attrs: `data-a="editEv" data-id="${e.id}"`, html: `<b>${esc(e.title)}</b>${esc(CATS[e.cat][0])}` }))]);
  // On resserre le temps de classe pour ne montrer que le temps hors classe (matin, midi, soir).
  // Une plage de classe sans rien dedans est repliée en une bande. Si un créneau hors classe y tombe
  // (préparation du mercredi matin, par exemple), elle reste ouverte mais à échelle réduite, et les
  // colonnes des jours de classe y sont hachurées.
  const c = ctx(), tt = c.ttFor(ui.week), iv = [];
  (tt ? tt.slots : []).filter(s => vc.includes(s.classId)).map(s => [mins(s.start), mins(s.end)]).sort((x, y) => x[0] - y[0]).forEach(([s, e]) => { const l = iv[iv.length - 1]; if (l && s - l[1] <= 30) l[1] = Math.max(l[1], e); else iv.push([s, e]); });
  const cuts = blocks.map(x => [Math.floor(mins(x.start) / 15) * 15, Math.ceil(mins(x.end) / 15) * 15]);
  const fold = [], shade = [];
  iv.forEach(seg => {
    const libre = cuts.reduce((parts, [x, y]) => parts.flatMap(([p, q]) => y <= p || x >= q ? [[p, q]] : [[p, Math.max(p, x)], [Math.min(q, y), q]].filter(([u, v]) => v > u)), [seg]).filter(([u, v]) => v - u >= 30);
    let cur = seg[0];
    const ouvert = (u, v) => { if (v <= u) return; fold.push([u, v, (v - u) * .5]); days.forEach((d, col) => { if (c.school(d)) shade.push({ col, a: u, b: v }); }); };
    libre.forEach(([u, v]) => { ouvert(cur, u); fold.push([u, v]); cur = v; }); ouvert(cur, seg[1]);
  });
  const gopt = { fold, shade, foldLabel: 'En classe', lo: iv.length ? Math.min(450, iv[0][0] - 60) : 480, hi: iv.length ? Math.max(1140, iv[iv.length - 1][1] + 120) : 1140 };
  const wk = evs.filter(e => e.date >= ui.week && e.date <= end).sort((a, b) => (a.date + (a.start || '')).localeCompare(b.date + (b.start || '')));
  const rems = reminders().filter(r => vc.includes(r.o.classId) && ((r.date >= ui.week && r.date <= end) || (r.date < t && !db.remDone[r.key])));
  const prepH = db.prep.reduce((a, p) => a + (mins(p.end) - mins(p.start)) / 60, 0);
  const sum = Object.values(H).reduce((a, x) => a + x.done + x.plan, 0), quota = Object.values(H).reduce((a, x) => a + x.quota, 0);
  return `<div class="head"><h1>Agenda de l’enseignant</h1><span class="sp"></span><div class="row np"><button class="btn pri" data-a="newEv">Ajouter un rendez-vous</button><button class="btn" data-a="addPrep" data-day="1">Ajouter un créneau de préparation</button></div></div>
  ${weekNav()}${grid(colsG, blocks, 'addPrep', gopt)}
  <p class="small muted np">En gris, vos créneaux de préparation : ils reviennent chaque semaine${prepH ? ' (' + fmtH(prepH) + ' par semaine)' : ''}. Touchez une colonne pour en ajouter un, ou un créneau pour le modifier. En couleur, les rendez-vous datés. Les heures de classe sont repliées ou resserrées (zones hachurées) pour ne montrer que le temps hors classe.</p>
  <div class="cols" style="margin-top:14px"><div class="sheet"><h2>À préparer</h2>${rems.length ? `<ul class="plain">${rems.map(r => remLi(r, t)).join('')}</ul>` : '<p class="muted">Rien à préparer cette semaine. Les rappels viennent des fiches de séance (impression, matériel, mots, équipement).</p>'}</div>
  <div class="sheet"><h2>Rendez-vous de la semaine</h2>${wk.length ? `<ul class="plain">${wk.map(e => `<li><span class="small muted">${esc(fD(e.date, { weekday: 'short', day: 'numeric' }))}</span>${evLi(e).slice(4, -5)}</li>`).join('')}</ul>` : '<p class="muted">Aucun rendez-vous cette semaine.</p>'}</div></div>
  <div class="sheet" style="margin-top:16px"><div class="mg">108 h</div><h2>Obligations de service : ${fmtH(sum)} inscrites sur ${quota} h</h2>
   ${sum >= quota ? '<p class="chip ok">Les ' + quota + ' heures sont atteintes : rien n’est dû au-delà.</p>' : `<p class="small muted">Il reste ${fmtH(quota - sum)} à inscrire d’ici la fin de l’année.</p>`}
   ${Object.entries(H).map(([k, x]) => `<div style="margin:10px 0"><div class="row"><b>${qlab(k)}</b>${k === 'equipe' ? `<span class="small muted">équipe ${fmtH(x.parts.equipe || 0)}, parents ${fmtH(x.parts.parents || 0)}</span>` : ''}<span class="sp"></span><span class="small">${fmtH(x.done)} faites${x.plan ? ', ' + fmtH(x.plan) + ' prévues' : ''}, sur ${x.quota} h ${x.done + x.plan > x.quota ? '<span class="chip yel">dépassé de ' + fmtH(x.done + x.plan - x.quota) + '</span>' : ''}</span></div>
     <div class="bar"><i style="width:${Math.min(100, x.done / (x.quota || 1) * 100)}%;background:${CATS[k][1]}"></i><i style="width:${Math.min(100 - Math.min(100, x.done / (x.quota || 1) * 100), x.plan / (x.quota || 1) * 100)}%;background:${CATS[k][1]};opacity:.4"></i></div></div>`).join('')}</div>
  <div class="sheet"><h2>Détail des heures de l’année</h2>${db.events.some(e => H[qcat(e.cat)]) ? `<div class="tw"><table><thead><tr><th>Date</th><th>Objet</th><th>Catégorie</th><th>Durée</th></tr></thead><tbody>${[...db.events].filter(e => H[qcat(e.cat)]).sort((a, b) => a.date.localeCompare(b.date)).map(e => `<tr><td>${esc(fS(e.date))}</td><td>${esc(e.title)}${e.note ? '<br><span class="small muted">' + nl(e.note) + '</span>' : ''}</td><td>${CATS[e.cat][0]}</td><td>${fmtH(+e.hours || 0)}</td></tr>`).join('')}</tbody></table></div>` : '<p class="muted">Ajoutez vos APC, réunions, rendez-vous de parents, formations et conseils d’école : le décompte se fait tout seul.</p>'}</div>`;
};
function prepForm(p, day, start) {
  openForm({ title: p ? 'Créneau de préparation' : 'Nouveau créneau de préparation', intro: '<p class="small">Il revient chaque semaine dans votre emploi du temps hors classe.</p>', values: p || { title: 'Préparation', day, start: toT(start), end: toT(start + 60) },
    fields: [{ k: 'title', label: 'Quoi (préparation, corrections, photocopies…)', req: 1 }, { k: 'day', label: 'Jour', type: 'select', options: [1, 2, 3, 4, 5, 6, 0].map(d => [d, DAYS[d]]) }, { k: 'start', label: 'Début', type: 'time', req: 1 }, { k: 'end', label: 'Fin', type: 'time', req: 1 }],
    onSave: v => { if (v.end <= v.start) { toast('L’heure de fin doit être après le début.'); return false; } v.day = +v.day; upsert(db.prep, p && p.id, v); }, onDelete: p ? () => remove(db.prep, p.id) : null });
}
A.addPrep = (d, el, e) => prepForm(null, +d.day, d.lo ? clickT(d, el, e) : 1020);
A.editPrep = d => prepForm(db.prep.find(p => p.id === d.id));

// ---------- Élèves ----------
// Trois volets : la classe (tuiles et fiches), les évaluations (saisie en liste de classe), les bilans pour les parents.
const initials = s => ((s.first || '?')[0] + (s.last || '')[0]).toUpperCase();
V.eleves = () => {
  const tab = ui.etab || 'classe', tb = (k, l) => `<button class="btn ${tab === k ? 'pri' : ''}" data-a="etab" data-t="${k}">${l}</button>`;
  const head = `<div class="head"><h1>Élèves</h1><span class="sp"></span><div class="row np">${tb('classe', 'La classe')}${tb('evals', 'Évaluations')}${tb('bilans', 'Bilans pour les parents')}</div></div>`;
  if (tab === 'evals') return head + (db.evals.find(e => e.id === ui.ev) ? evalGrid(db.evals.find(e => e.id === ui.ev)) : evalList());
  if (tab === 'bilans') return head + bilansHTML();
  const t = TODAY(), vc = visCls();
  return head + `<div class="row np" style="margin-bottom:8px"><button class="btn pri" data-a="newStu">Ajouter un élève</button><button class="btn" data-a="newStuList">Ajouter une liste</button></div>
  ${ui.sel ? '' : `<div class="sheet np"><div class="mg">Noter</div>${quickBar()}<p class="small muted" style="margin:8px 0 0">Choisissez le type de note, écrivez-la, puis cochez le ou les élèves (recherche par prénom). Touchez une tuile pour ouvrir la fiche ; maintenez-la pour dicter une note.</p></div>`}
  ${vc.map(cid => {
    const st = visStu().filter(s => s.classId === cid);
    return `${db.classes.length > 1 ? `<h2 style="margin:14px 0 8px">${cbadge(cid, 1)} <span class="small muted">${st.length} élèves</span></h2>` : ''}
    ${st.length ? `<div class="tiles">${st.map(s => { const ab = db.att[t + '|M|' + s.id] === 'A' || db.att[t + '|S|' + s.id] === 'A', n = db.notes.filter(x => x.sid === s.id && x.date === t).length;
      return `<button class="tile ${ui.sel && ui.sel.has(s.id) ? 'on' : ''}" data-a="${ui.sel ? 'selStu' : 'openStu'}" data-id="${s.id}" ${ui.sel ? `aria-pressed="${ui.sel.has(s.id)}"` : ''}>${ui.sel ? `<span class="tick" aria-hidden="true">${ui.sel.has(s.id) ? '✓' : ''}</span>` : ''}<span class="av" style="background:${esc(cls(cid).color)}">${esc(db.settings.mark === 'icon' ? cls(cid).icon || initials(s) : initials(s))}</span><b>${esc(s.first)}<br>${esc(s.last)}</b>${s.level ? `<span class="chip">${esc(s.level)}</span>` : ''}${s.pai ? '<span class="chip red">PAI</span>' : ''}${s.needs ? '<span class="chip yel">besoins particuliers</span>' : ''}${ab ? '<span class="chip red">absent</span>' : ''}${n ? `<span class="chip yel">${n} note${n > 1 ? 's' : ''} ce jour</span>` : ''}</button>`; }).join('')}</div>` : '<div class="sheet"><p class="muted">Aucun élève. Ajoutez-les un par un, ou collez toute la liste.</p></div>'}`;
  }).join('')}${ui.sel ? selBar() : ''}`;
};
// ---------- Sélection de plusieurs élèves : une même note pour tous ----------
function selBar() {
  const n = ui.sel.size, B = db.settings.behav, dis = n ? '' : 'disabled';
  return `<div class="selbar np"><div class="row"><b>${n ? n + ' élève' + (n > 1 ? 's' : '') + ' sélectionné' + (n > 1 ? 's' : '') : 'Touchez les élèves concernés'}</b><span class="sp"></span><button class="btn sm" data-a="selAll">Tous</button>${n ? '<button class="btn sm" data-a="selNone">Aucun</button>' : ''}<button class="btn sm pri" data-a="selOff">Terminé</button></div>
    ${ui.selBeh ? `<div class="row" style="margin-top:6px">${B.map(x => `<button class="btn sm" style="border-color:${x.pos ? 'var(--ok)' : 'var(--marge)'}" data-a="selBeh" data-b="${x.id}" ${dis}>${x.pos ? '＋ ' : ''}${esc(x.l)}</button>`).join('')}<button class="btn sm ghost" data-a="selBehT">Fermer</button></div>`
    : `<div class="row" style="margin-top:6px">${['observation', 'incident', 'difficulte'].map(t => `<button class="btn sm" data-a="selNote" data-t="${t}" ${dis}>${NOTE[t][0]} ${NOTE[t][1]}</button>`).join('')}<button class="btn sm" data-a="selBehT" ${dis}>🙂 Comportement</button></div>`}
    <p class="small muted" style="margin:4px 0 0">Ou maintenez une tuile pour dicter la note pour toute la sélection.</p></div>`;
}
A.selOn = () => { ui.sel = new Set(); ui.selBeh = false; render(); };
A.selOff = () => { ui.sel = null; render(); };
A.selStu = d => { ui.sel.has(d.id) ? ui.sel.delete(d.id) : ui.sel.add(d.id); render(); };
A.selAll = () => { visStu().forEach(s => ui.sel.add(s.id)); render(); };
A.selNone = () => { ui.sel.clear(); render(); };
A.selBehT = () => { ui.selBeh = !ui.selBeh; render(); };
A.selNote = d => noteForm(null, d.t, [...ui.sel]);
A.selBeh = d => { const x = db.settings.behav.find(y => y.id === d.b), ids = [...ui.sel]; addNotes(ids, { type: 'comportement', sub: x.l, pos: x.pos, text: '', date: TODAY(), time: new Date().toTimeString().slice(0, 5) }); done(); toast('« ' + x.l + ' » noté pour ' + whoTxt(ids) + '.'); };
A.etab = d => { ui.etab = d.t; ui.ev = null; render(); };
function stuForm(s) {
  openForm({ title: s ? 'Modifier la fiche' : 'Nouvel élève', values: s || { classId: defCls(), level: ui.lvl },
    fields: [{ k: 'first', label: 'Prénom', req: 1 }, { k: 'last', label: 'Nom' }, ...(db.classes.length > 1 ? [{ k: 'classId', label: 'Classe', type: 'select', options: clsOpts() }] : []), { k: 'level', label: 'Niveau de classe', type: 'select', options: [['', 'Non précisé'], ...LEVELS.map(x => [x, x]), ...(s && s.level && !LEVELS.includes(s.level) ? [[s.level, s.level]] : [])] },
    { k: 'sex', label: 'Sexe (sert aussi à la mixité des groupes)', type: 'select', options: [['', 'Non précisé'], ['F', 'Fille'], ['G', 'Garçon']] }, { k: 'birth', label: 'Date de naissance', type: 'date' },
    { k: 'momN', label: 'Mère : nom' }, { k: 'momT', label: 'Mère : téléphone', type: 'tel' }, { k: 'dadN', label: 'Père : nom' }, { k: 'dadT', label: 'Père : téléphone', type: 'tel' },
    { k: 'othN', label: 'Autre contact : nom' }, { k: 'othL', label: 'Autre contact : lien (grand-mère, assistante maternelle…)' }, { k: 'othT', label: 'Autre contact : téléphone', type: 'tel' },
    { k: 'contacts', label: 'Précisions sur les responsables (garde, personnes autorisées…)', type: 'textarea' },
    { k: 'pai', label: 'PAI : pathologie, conduite à tenir, où est la trousse', type: 'textarea' }, { k: 'needs', label: 'Besoins particuliers : PPS, PAP, PPRE, AESH, aménagements', type: 'textarea' },
    { k: 'info', label: 'Autres informations (sortie, garderie, cantine…)', type: 'textarea' }],
    onSave: v => { if (!v.classId) v.classId = s ? s.classId : defCls(); upsert(db.students, s && s.id, v); },
    onDelete: s ? () => { remove(db.students, s.id); ui.stu = null; ui.view = 'eleves'; } : null });
}
A.newStu = () => stuForm(null);
A.editStu = () => stuForm(stu(ui.stu));
A.newStuList = () => openForm({ title: 'Ajouter une liste d’élèves', values: { classId: defCls(), level: ui.lvl }, fields: [{ k: 'list', label: 'Un élève par ligne : Prénom Nom', type: 'textarea', rows: 10 }, ...(db.classes.length > 1 ? [{ k: 'classId', label: 'Classe', type: 'select', options: clsOpts() }] : []), { k: 'level', label: 'Niveau (facultatif)' }],
  onSave: v => v.list.split('\n').map(x => x.trim()).filter(Boolean).forEach(l => { const p = l.split(/\s+/); db.students.push({ id: uid(), first: p[0], last: p.slice(1).join(' '), classId: v.classId || defCls(), level: v.level, birth: '', contacts: '', pai: '', needs: '', info: '' }); }) });
A.openStu = d => { ui.stu = d.id; go('eleve'); };

// La fiche d'un élève : tout son dossier sur une page, imprimable.
const stuGrades = (s, from, to) => db.evals.filter(e => db.grades[e.id + '|' + s.id] && (!from || (e.date >= from && e.date <= to))).sort((a, b) => b.date.localeCompare(a.date));
V.eleve = () => {
  const s = stu(ui.stu); if (!s) return V.eleves();
  const notes = db.notes.filter(n => n.sid === s.id).sort((a, b) => (b.date + b.time).localeCompare(a.date + a.time)), beh = notes.filter(n => n.type === 'comportement'), obs = notes.filter(n => n.type === 'observation'), dif = notes.filter(n => n.type === 'difficulte'), inc = notes.filter(n => n.type === 'incident');
  const res = stuGrades(s), meet = db.meetings.filter(m => m.sid === s.id).sort((a, b) => b.date.localeCompare(a.date));
  const lists = db.lists.filter(l => !l.classId || l.classId === s.classId), imp = lists.filter(l => l.important);
  const abs = Object.keys(db.att).filter(k => k.endsWith('|' + s.id)).sort(), nA = abs.filter(k => db.att[k] === 'A'), nJ = nA.filter(k => (db.absInfo[k.slice(0, 10) + '|' + s.id] || {}).just);
  const nli = n => `<li><span class="small muted">${esc(fS(n.date))}${n.time ? ' ' + hm(n.time) : ''}</span><span class="chip ${n.pos === true ? 'ok' : n.pos === false ? 'yel' : ''}">${NOTE[n.type][0]} ${esc(n.sub || NOTE[n.type][1])}</span><button class="lnk" data-a="editNote" data-id="${n.id}" style="flex:1;min-width:160px">${n.text ? nl(n.text) : '<span class="muted small">Ajouter un détail</span>'}</button>${n.gid ? '<span class="chip" title="Note prise pour plusieurs élèves">groupe</span>' : ''}${n.type === 'difficulte' ? difWhere(n) : ''}</li>`;
  const sec = (title, body, act, label) => `<div class="sheet"><div class="row"><h2>${title}</h2><span class="sp"></span>${act ? `<button class="btn sm np" data-a="${act}">${label}</button>` : ''}</div>${body}</div>`;
  const B = db.settings.behav;
  return `<div class="head"><h1>${esc(sname(s))}</h1><span class="sp"></span><div class="row np"><button class="btn" data-a="go" data-v="eleves">‹ Tous les élèves</button><button class="btn" data-a="editStu">Modifier la fiche</button></div></div>
  <div class="sheet"><p style="margin:0">${cbadge(s.classId, 1)} ${s.level ? `<span class="chip">${esc(s.level)}</span>` : ''} ${s.birth ? (s.sex === 'G' ? 'Né' : s.sex === 'F' ? 'Née' : 'Né(e)') + ' le ' + esc(fD(s.birth, { day: 'numeric', month: 'long', year: 'numeric' })) + ', ' + ageAt(s.birth) + ' ans' + (() => { const x = bdays(TODAY(), addD(TODAY(), 365), [s])[0]; if (!x) return ''; const n = Math.round((pd(x.date) - pd(TODAY())) / 864e5); return n === 0 ? ' <span class="chip yel">🎂 c’est son anniversaire !</span>' : n <= 14 ? ' <span class="chip yel">🎂 anniversaire ' + esc(fD(x.date, { weekday: 'long', day: 'numeric' })) + '</span>' : ''; })() : ''}</p>
   ${s.pai ? `<p class="warn">PAI : ${nl(s.pai)}</p>` : ''}${s.needs ? `<p><span class="chip yel">Besoins particuliers</span> ${nl(s.needs)}</p>` : ''}
   ${contactsHTML(s)}${s.contacts ? `<p class="small">${nl(s.contacts)}</p>` : ''}${s.info ? `<p>${nl(s.info)}</p>` : ''}
   ${!s.pai && !s.needs ? '<p class="small muted np">Ni PAI ni besoin particulier enregistré. « Modifier la fiche » pour en ajouter.</p>' : ''}
   ${imp.length ? `<p>${imp.map(l => `<span class="chip ${l.checked[s.id] ? 'ok' : 'red'}">${l.checked[s.id] ? '✓' : '✗'} ${esc(l.title)}</span>`).join(' ')}</p>` : ''}</div>
  <div class="sheet"><div class="row"><h2>Comportement et points positifs</h2></div>
   <div class="np"><p class="small muted">Un appui enregistre le comportement, daté et horodaté. Touchez-le ensuite dans la liste pour ajouter un détail.</p>
   <div class="row">${B.filter(b => b.pos).map(b => `<button class="btn sm" style="border-color:var(--ok)" data-a="quickObs" data-b="${b.id}">＋ ${esc(b.l)}</button>`).join('')}</div>
   <div class="row" style="margin:8px 0">${B.filter(b => !b.pos).map(b => `<button class="btn sm" style="border-color:var(--marge)" data-a="quickObs" data-b="${b.id}">${esc(b.l)}</button>`).join('')}</div></div>
   ${beh.length ? `<ul class="plain">${beh.map(nli).join('')}</ul>` : ''}</div>
  ${sec('Résultats', res.length ? `<div class="tw"><table><thead><tr><th>Date</th><th>Matière</th><th>Compétence ou évaluation</th><th>Résultat</th><th>Commentaire</th></tr></thead><tbody>${res.map(e => { const g = db.grades[e.id + '|' + s.id]; return `<tr><td>${esc(fS(e.date))}</td><td>${esc(subj(e.subjectId).name)}${e.sub ? ' › ' + esc(e.sub) : ''}</td><td><button class="lnk" data-a="openEval" data-id="${e.id}">${esc(e.title)}</button></td><td>${gradeView(e, g)}</td><td>${nl(g.c)}</td></tr>`; }).join('')}</tbody></table></div>` : '<p class="muted">Aucun résultat. Les notes se saisissent pour toute la classe dans Élèves › Évaluations.</p>')}
  ${sec('Observations', obs.length ? `<ul class="plain">${obs.map(nli).join('')}</ul>` : '<p class="muted">Aucune observation : travail, évaluation, commentaire…</p>', 'newObs', 'Ajouter une observation')}
  ${sec('Difficultés', dif.length ? `<ul class="plain">${dif.map(nli).join('')}</ul>` : '<p class="muted">Aucune difficulté notée. Une difficulté est rattachée à sa séance et rappelée à la séance suivante de la matière.</p>', 'newDif', 'Ajouter une difficulté')}
  ${sec('Incidents', inc.length ? `<ul class="plain">${inc.map(nli).join('')}</ul>` : '<p class="muted">Aucun incident (dispute, blessure, maladie, échange avec la famille).</p>', 'newInc', 'Ajouter un incident')}
  ${sec('Réunions avec les parents', meet.length ? `<ul class="plain">${meet.map(m => `<li><span class="small muted">${esc(fS(m.date))}</span><button class="lnk" data-a="editMeet" data-id="${m.id}" style="flex:1;min-width:160px"><b>${esc(m.who || 'Rendez-vous')}</b><br>${nl(m.text)}</button></li>`).join('')}</ul>` : '<p class="muted">Aucun compte rendu.</p>', 'newMeet', 'Ajouter un compte rendu')}
  ${sec('Absences', `<p><b>${nA.length}</b> demi-journée${nA.length > 1 ? 's' : ''} d’absence, dont ${nJ.length} justifiée${nJ.length > 1 ? 's' : ''} ; ${abs.length - nA.length} retard${abs.length - nA.length > 1 ? 's' : ''}.</p>${abs.length ? `<p class="small">${abs.map(k => { const [d, h] = k.split('|'), i = db.absInfo[d + '|' + s.id] || {}; return esc(fD(d, { day: 'numeric', month: 'short' })) + ' ' + (h === 'M' ? 'matin' : 'après-midi') + (db.att[k] === 'R' ? ' (retard)' : '') + (i.motif ? ' : ' + esc(i.motif) : '') + (db.att[k] === 'A' && !i.just ? ' <span class="warn">non justifiée</span>' : ''); }).join(' ; ')}</p>` : ''}`)}
  ${sec('Administratif', lists.length ? `<ul class="plain">${lists.map(l => `<li><label><input type="checkbox" data-c="chk" data-l="${l.id}" data-s="${s.id}" ${l.checked[s.id] ? 'checked' : ''}> ${esc(l.title)}</label>${l.important ? '<span class="chip yel">important</span>' : ''}</li>`).join('')}</ul>` : '<p class="muted">Aucune liste. Créez-les dans Administratif.</p>')}`;
};
A.quickObs = d => { const x = db.settings.behav.find(y => y.id === d.b); addNotes([ui.stu], { type: 'comportement', sub: x.l, pos: x.pos, text: '', date: TODAY(), time: new Date().toTimeString().slice(0, 5) }); done(); toast('« ' + x.l + ' » noté pour ' + sname(stu(ui.stu)) + '.'); };
// Où en est une difficulté : sa séance d'origine et la séance où la reprendre.
function difWhere(n) {
  if (n.done) return '<span class="chip ok">reprise</span>';
  const c = ctx(), o = c.byKey[n.occ], x = difNext(n);
  return `<span class="small muted" style="flex-basis:100%">${o ? 'Séance du ' + esc(fS(o.date)) + ', ' + esc(qname(o.hostQ || o.q)) + '. ' : 'Pas de séance rattachée. '}${x ? 'À reprendre le ' + esc(fS(x.date)) + ' à ' + hm(x.start) + '.' : ''}</span>`;
}
// Formulaire de note, pour un élève ou pour plusieurs (ids).
function noteForm(n, type, ids) {
  type = n ? n.type : type; ids = n ? [n.sid] : ids || [ui.stu];
  const B = db.settings.behav, now = new Date().toTimeString().slice(0, 5), f = [];
  if (type === 'incident') f.push({ k: 'sub', label: 'Nature', type: 'select', options: [['', 'Sans précision'], ...INC.map(x => [x, x])] });
  if (type === 'comportement') f.push({ k: 'sub', label: 'Comportement', type: 'select', options: B.map(x => [x.l, (x.pos ? '＋ ' : '') + x.l]) });
  if (type === 'difficulte') {
    const list = pastOccs(ids), c = ctx(), cur = n && c.byKey[n.occ];
    if (cur && !list.includes(cur)) list.unshift(cur);
    f.push({ k: 'occ', label: 'Séance où la difficulté est apparue (proposée d’office : la séance en cours ou la dernière)', type: 'select', options: [...list.map(x => [x.key, occLabel(x, c)]), ['', 'Pas de séance précise']] });
  } else f.push({ k: 'date', label: 'Date', type: 'date', req: 1 }, { k: 'time', label: 'Heure', type: 'time' });
  f.push({ k: 'text', label: type === 'difficulte' ? 'Difficulté observée' : 'Note', type: 'textarea', rows: 4 });
  if (type === 'difficulte' && n) f.push({ k: 'done', label: 'Reprise : ne plus la rappeler', type: 'checkbox' });
  const def = { date: TODAY(), time: now, sub: '', occ: (pastOccs(ids, 1)[0] || {}).key || '' };
  openForm({ title: (n ? NOTE[type][1] : NTITLE[type]) + (ids.length > 1 ? ' pour ' + ids.length + ' élèves' : ''),
    intro: ids.length > 1 ? `<p class="small">${esc(ids.map(id => sname(stu(id))).join(', '))}. Une note est créée dans la fiche de chacun.</p>` : '',
    values: n || def, fields: f,
    onSave: v => {
      if (type === 'comportement') v.pos = (B.find(x => x.l === v.sub) || {}).pos ?? null;
      if (type === 'difficulte') { const o = ctx().byKey[v.occ]; Object.assign(v, o ? { q: o.hostQ || o.q, date: o.date, time: o.start } : { q: '', date: n ? n.date : TODAY(), time: n ? n.time : now }); }
      if (n) Object.assign(n, v); else addNotes(ids, { type, ...v });
      if (ids.length > 1) toast(NOTE[type][1] + ' enregistrée pour ' + ids.length + ' élèves.');
    }, onDelete: n ? () => remove(db.notes, n.id) : null });
}
A.newObs = () => noteForm(null, 'observation'); A.newInc = () => noteForm(null, 'incident'); A.newDif = () => noteForm(null, 'difficulte'); A.editNote = d => noteForm(db.notes.find(n => n.id === d.id));
function meetForm(m) {
  openForm({ title: m ? 'Modifier le compte rendu' : 'Réunion avec les parents', values: m || { date: TODAY() }, fields: [{ k: 'date', label: 'Date', type: 'date', req: 1 }, { k: 'who', label: 'Personnes présentes' }, { k: 'text', label: 'Compte rendu, décisions prises', type: 'textarea', rows: 6 }],
    onSave: v => upsert(db.meetings, m && m.id, { ...v, sid: ui.stu }), onDelete: m ? () => remove(db.meetings, m.id) : null });
}
A.newMeet = () => meetForm(null); A.editMeet = d => meetForm(db.meetings.find(m => m.id === d.id));

// ---------- Évaluations ----------
// Une évaluation = une matière + l'intitulé de la compétence (ou de l'évaluation) + une date.
// La saisie se fait en liste de classe. Trois façons de noter : quatre niveaux, niveaux affinés
// avec + et −, ou note chiffrée (l'équivalent en niveau est calculé d'après les seuils des réglages).
const EMODE = { niv: 'Niveaux', fin: 'Niveaux affinés (+ et −)', num: 'Note chiffrée' };
const evalStu = e => visStu().filter(s => s.classId === e.classId);
function evalList() {
  const vc = visCls(), evs = db.evals.filter(e => vc.includes(e.classId)).sort((a, b) => b.date.localeCompare(a.date));
  return `<div class="row np" style="margin-bottom:10px"><button class="btn pri" data-a="newEval">Nouvelle évaluation</button></div>
  ${evs.length ? `<div class="sheet flat"><div class="tw"><table><thead><tr><th>Date</th><th>Matière</th><th>Compétence ou évaluation</th><th>Notation</th><th>Répartition de la classe</th></tr></thead><tbody>${evs.map(e => {
    const n = { NA: 0, EVA: 0, A: 0, D: 0 }; let tot = 0; db.students.filter(s => s.classId === e.classId).forEach(s => { const g = db.grades[e.id + '|' + s.id]; if (g && g.v !== '' && g.v != null) { n[e.mode === 'num' ? numToLv(+g.v, e.max) : lvBase(g.v)]++; tot++; } });
    return `<tr><td>${esc(fS(e.date))}</td><td><span class="dot" style="background:${subj(e.subjectId).color}"></span>${esc(subj(e.subjectId).name)}${e.sub ? ' › ' + esc(e.sub) : ''} ${cbadge(e.classId)}</td><td><button class="lnk" data-a="openEval" data-id="${e.id}"><b>${esc(e.title)}</b></button></td><td>${EMODE[e.mode]}${e.mode === 'num' ? ' sur ' + esc(e.max) : ''}</td><td>${tot ? Object.keys(LV).filter(k => n[k]).map(k => `<span class="chip ${LV[k][1]}">${n[k]} ${LV[k][0].toLowerCase()}</span>`).join(' ') : '<span class="muted small">à saisir</span>'}</td></tr>`; }).join('')}</tbody></table></div></div>` : '<div class="sheet"><p>Créez une évaluation (matière et compétence évaluée), puis saisissez les résultats de toute la classe sur une seule liste : niveau ou note, et un commentaire si besoin.</p></div>'}`;
}
function evalForm(e) {
  openForm({ title: e ? 'Modifier l’évaluation' : 'Nouvelle évaluation', values: e ? { ...e, what: e.subjectId + '|' + (e.sub || '') } : { date: TODAY(), mode: 'niv', max: 20, classId: defCls(), what: (db.subjects[0] || {}).id + '|' },
    fields: [{ k: 'title', label: 'Compétence évaluée, ou intitulé de l’évaluation', req: 1 }, { k: 'what', label: 'Matière', type: 'select', options: whatOpts() }, ...(db.classes.length > 1 ? [{ k: 'classId', label: 'Classe', type: 'select', options: clsOpts() }] : []), { k: 'date', label: 'Date', type: 'date', req: 1 },
    { k: 'mode', label: 'Notation', type: 'select', options: [['niv', 'Niveaux : non atteint, en voie d’acquisition, acquis, dépassé'], ['fin', 'Niveaux affinés, avec des intermédiaires + et −'], ['num', 'Note chiffrée, avec son équivalent en niveau']] }, { k: 'max', label: 'Barème, pour une note chiffrée (sur combien ?)', type: 'number' }],
    onSave: v => { const [subjectId, sub] = v.what.split('|'), n = { title: v.title, subjectId, sub, classId: v.classId || (e ? e.classId : defCls()), date: v.date, mode: v.mode, max: +v.max || 20 }; if (e) Object.assign(e, n); else { const id = uid(); db.evals.push({ id, ...n }); ui.ev = id; } },
    onDelete: e ? () => { remove(db.evals, e.id); for (const k in db.grades) if (k.startsWith(e.id + '|')) delete db.grades[k]; ui.ev = null; } : null });
}
A.newEval = () => evalForm(null);
A.editEval = () => evalForm(db.evals.find(e => e.id === ui.ev));
A.openEval = d => { ui.ev = d.id; ui.etab = 'evals'; go('eleves'); };
A.closeEval = () => { ui.ev = null; render(); };
function evalGrid(e) {
  const st = evalStu(e), opts = e.mode === 'fin' ? FINE : Object.keys(LV);
  return `<div class="row np" style="margin-bottom:10px"><button class="btn" data-a="closeEval">‹ Toutes les évaluations</button><button class="btn" data-a="editEval">Modifier l’évaluation</button></div>
  <div class="sheet flat"><h2><span class="dot" style="background:${subj(e.subjectId).color}"></span>${esc(subj(e.subjectId).name)}${e.sub ? ' › ' + esc(e.sub) : ''} : ${esc(e.title)}</h2><p class="small muted">${esc(fD(e.date, { day: 'numeric', month: 'long', year: 'numeric' }))}, ${esc(cls(e.classId) ? cls(e.classId).name : '')}, ${EMODE[e.mode].toLowerCase()}${e.mode === 'num' ? ' sur ' + esc(e.max) : ''}. La saisie s’enregistre toute seule.</p>
  <div class="tw"><table><thead><tr><th>Élève</th><th>${e.mode === 'num' ? 'Note sur ' + esc(e.max) : 'Niveau'}</th>${e.mode === 'num' ? '<th>Équivalent</th>' : ''}<th style="width:45%">Commentaire</th></tr></thead><tbody>
  ${st.map(s => { const g = db.grades[e.id + '|' + s.id] || {}, v = g.v ?? '';
    return `<tr><td>${esc(sname(s))}</td><td>${e.mode === 'num' ? `<input type="number" min="0" max="${esc(e.max)}" step="0.5" inputmode="decimal" data-c="grade" data-s="${s.id}" value="${esc(v)}" style="width:5.5em" aria-label="Note de ${esc(sname(s))}">` : `<select data-c="grade" data-s="${s.id}" aria-label="Niveau de ${esc(sname(s))}"><option value="">Non évalué</option>${opts.map(o => `<option value="${o}" ${v === o ? 'selected' : ''}>${esc(lvLabel(o))}</option>`).join('')}</select>`}</td>${e.mode === 'num' ? `<td id="eq-${s.id}">${gradeView(e, g).replace(/^[^<]*/, '')}</td>` : ''}<td><input data-c="gcom" data-s="${s.id}" value="${esc(g.c)}" style="width:100%;min-width:140px" aria-label="Commentaire pour ${esc(sname(s))}"></td></tr>`; }).join('') || '<tr><td colspan="4" class="muted">Aucun élève dans cette classe.</td></tr>'}
  </tbody></table></div>${e.mode === 'num' ? `<p class="small muted">Équivalences (réglages) : en voie d’acquisition à partir de ${esc(db.settings.seuils.eva)} % de la note, acquis à partir de ${esc(db.settings.seuils.a)} %, dépassé à partir de ${esc(db.settings.seuils.d)} %.</p>` : ''}</div>`;
}
const gradeOf = s => db.grades[ui.ev + '|' + s] ??= { v: '', c: '' };
C.grade = (d, el) => { const g = gradeOf(d.s), e = db.evals.find(x => x.id === ui.ev); g.v = el.value; if (e.mode === 'num') $('#eq-' + d.s).innerHTML = gradeView(e, g).replace(/^[^<]*/, ''); if (g.v === '' && !g.c) delete db.grades[ui.ev + '|' + d.s]; saveSoon(); };
C.gcom = (d, el) => { const g = gradeOf(d.s); g.c = el.value; if (g.v === '' && !g.c) delete db.grades[ui.ev + '|' + d.s]; saveSoon(); };

// ---------- Bilans pour les parents ----------
// On choisit la périodicité (entre deux vacances, trimestre, semestre, année, ou dates libres). Le bilan
// reprend les résultats de la période, les points positifs, l'assiduité et une appréciation à rédiger.
ui.bil = { kind: 'periode', i: -1, from: db.settings.start, to: TODAY(), res: true, pos: true, neg: false, inc: false, abs: true };
function periods(kind) {
  const S = db.settings, y = +S.start.slice(0, 4), P = (l, from, to) => ({ l, from, to });
  if (kind === 'trimestre') return [P('1er trimestre', S.start, y + '-11-30'), P('2e trimestre', y + '-12-01', (y + 1) + '-03-31'), P('3e trimestre', (y + 1) + '-04-01', S.end)];
  if (kind === 'semestre') return [P('1er semestre', S.start, (y + 1) + '-01-31'), P('2e semestre', (y + 1) + '-02-01', S.end)];
  if (kind === 'semaine' || kind === 'quinzaine') { const out = [], n = kind === 'semaine' ? 7 : 14; for (let d = monday(S.start), i = 1; d <= S.end; d = addD(d, n), i++) out.push(P((kind === 'semaine' ? 'Semaine du ' : 'Quinzaine du ') + fD(d, { day: 'numeric', month: 'short' }), d < S.start ? S.start : d, addD(d, n - 1) > S.end ? S.end : addD(d, n - 1))); return out; }
  if (kind === 'annee') return [P('Année ' + S.year, S.start, S.end)];
  const H = S.holidays.filter(h => h.from > S.start && h.to < S.end).sort((a, b) => a.from.localeCompare(b.from)), out = []; let a = S.start;
  H.forEach((h, i) => { out.push(P('Période ' + (i + 1), a, addD(h.from, -1))); a = addD(h.to, 1); }); out.push(P('Période ' + (H.length + 1), a, S.end)); return out;
}
function bilRange() {
  const b = ui.bil; if (b.kind === 'libre') return { l: 'Bilan', from: b.from, to: b.to };
  const ps = periods(b.kind); if (b.i < 0 || b.i >= ps.length) { const t = TODAY(); b.i = Math.max(0, ps.findIndex(p => t <= p.to)); if (t > ps[ps.length - 1].to) b.i = ps.length - 1; }
  return ps[b.i];
}
function bilData(s, r) {
  const notes = db.notes.filter(n => n.sid === s.id && n.date >= r.from && n.date <= r.to && n.type !== 'incident');
  const abs = Object.keys(db.att).filter(k => k.endsWith('|' + s.id) && k.slice(0, 10) >= r.from && k.slice(0, 10) <= r.to), nA = abs.filter(k => db.att[k] === 'A');
  const inc = db.notes.filter(n => n.sid === s.id && n.date >= r.from && n.date <= r.to && n.type === 'incident').sort((x, y) => (x.date + x.time).localeCompare(y.date + y.time));
  return { inc, res: stuGrades(s, r.from, r.to).reverse(), pos: notes.filter(n => n.pos === true), neg: notes.filter(n => n.pos === false), nA: nA.length, nJ: nA.filter(k => (db.absInfo[k.slice(0, 10) + '|' + s.id] || {}).just).length, nR: abs.length - nA.length };
}
const fRange = r => 'du ' + fD(r.from, { day: 'numeric', month: 'long', year: 'numeric' }) + ' au ' + fD(r.to, { day: 'numeric', month: 'long', year: 'numeric' });
function bilansHTML() {
  const b = ui.bil, r = bilRange(), ps = b.kind === 'libre' ? [] : periods(b.kind), st = visStu(), ck = (k, l) => `<label><input type="checkbox" data-c="bil" data-k="${k}" ${b[k] ? 'checked' : ''}> ${l}</label>`;
  return `<div class="row np" style="margin-bottom:10px"><span class="sp"></span><button class="btn pri" data-a="printBil">🖨️ Imprimer tous les bilans (${st.length})</button></div><div class="sheet np"><h2>Quel bilan ?</h2><div class="row" style="align-items:flex-end">
    <label class="fld"><span>Périodicité</span><select data-c="bil" data-k="kind">${[['semaine', 'Chaque semaine'], ['quinzaine', 'Tous les 15 jours'], ['periode', 'Par période (entre deux vacances)'], ['trimestre', 'Par trimestre'], ['semestre', 'Par semestre'], ['annee', 'Une fois par an'], ['libre', 'Dates libres']].map(([k, l]) => `<option value="${k}" ${b.kind === k ? 'selected' : ''}>${l}</option>`).join('')}</select></label>
    ${b.kind === 'libre' ? `<label class="fld"><span>Du</span><input type="date" data-c="bil" data-k="from" value="${b.from}"></label><label class="fld"><span>Au</span><input type="date" data-c="bil" data-k="to" value="${b.to}"></label>` : ps.length > 1 ? `<label class="fld"><span>Laquelle</span><select data-c="bil" data-k="i">${ps.map((p, i) => `<option value="${i}" ${b.i === i ? 'selected' : ''}>${esc(p.l)}</option>`).join('')}</select></label>` : ''}</div>
   <p class="small muted">${esc(r.l)} : ${esc(fRange(r))}.${b.kind === 'periode' ? ' Les périodes suivent les vacances saisies dans les réglages.' : ''}</p>
   <div class="row">${ck('res', 'Résultats')}${ck('pos', 'Points positifs')}${ck('neg', 'Points à travailler')}${ck('inc', 'Incidents')}${ck('abs', 'Absences et retards')}</div>
   <p style="margin-top:10px"><button class="btn pri" data-a="printBil">Imprimer les bilans de toute la classe</button></p></div>
  ${st.map(s => { const d = bilData(s, r); return `<div class="sheet"><div class="row"><h3>${esc(sname(s))}</h3><span class="small muted">${d.res.length} résultat${d.res.length > 1 ? 's' : ''}, ${d.pos.length} point${d.pos.length > 1 ? 's' : ''} positif${d.pos.length > 1 ? 's' : ''}, ${d.nA} demi-journée${d.nA > 1 ? 's' : ''} d’absence</span><span class="sp"></span><button class="btn sm np" data-a="printBil" data-id="${s.id}">Imprimer ce bilan</button></div>
    <label class="fld"><span>Appréciation générale pour les parents</span><textarea rows="3" data-c="bilTxt" data-s="${s.id}" placeholder="Ce qui est réussi, les progrès, ce qui reste à consolider, un conseil pour la maison…">${esc(db.bilans[s.id + '|' + r.from + '|' + r.to] || '')}</textarea></label></div>`; }).join('') || '<div class="sheet"><p class="muted">Ajoutez d’abord vos élèves.</p></div>'}`;
}
C.bil = (d, el) => { const b = ui.bil; if (el.type === 'checkbox') b[d.k] = el.checked; else if (d.k === 'i') b.i = +el.value; else { if (!el.value) return; b[d.k] = el.value; if (d.k === 'kind') b.i = -1; } render(); };
C.bilTxt = (d, el) => { const r = bilRange(); db.bilans[d.s + '|' + r.from + '|' + r.to] = el.value; saveSoon(); };
const tally = a => { const m = {}; a.forEach(n => { const k = n.sub || NOTE[n.type][1]; (m[k] ??= { n: 0, t: [] }).n++; if (n.text) m[k].t.push(n.text); }); return Object.entries(m).map(([k, v]) => `<li><b>${esc(k)}</b>${v.n > 1 ? ' (' + v.n + ' fois)' : ''}${v.t.length ? ' : ' + v.t.map(esc).join(' ; ') : ''}</li>`).join(''); };
function bilanHTML(s, r) {
  const b = ui.bil, d = bilData(s, r), k = cls(s.classId), bySub = {}; d.res.forEach(e => (bySub[subj(e.subjectId).name] ??= []).push(e));
  return docHead('Bilan ' + deN(sname(s))) + `<p>${esc(k ? k.name : '')}${s.level ? ', ' + esc(s.level) : ''}. ${esc(r.l)}, ${esc(fRange(r))}.</p>
  ${b.res ? `<h2>Résultats</h2>${d.res.length ? `<table><thead><tr><th>Matière</th><th>Compétence ou évaluation</th><th>Résultat</th><th>Commentaire</th></tr></thead><tbody>${Object.entries(bySub).map(([n, es]) => es.map((e, i) => { const g = db.grades[e.id + '|' + s.id]; return `<tr>${i ? '' : `<td rowspan="${es.length}"><b>${esc(n)}</b></td>`}<td>${esc(e.title)}</td><td>${gradeView(e, g)}</td><td>${nl(g.c)}</td></tr>`; }).join('')).join('')}</tbody></table><p style="font-size:9pt">Niveaux : non atteint, en voie d’acquisition, acquis, dépassé. Les signes + et − affinent le niveau.</p>` : '<p>Pas d’évaluation sur cette période.</p>'}` : ''}
  ${b.pos ? `<h2>Points positifs</h2>${d.pos.length ? `<ul>${tally(d.pos)}</ul>` : '<div class="blank" style="min-height:36pt"></div>'}` : ''}
  ${b.neg && d.neg.length ? `<h2>Points à travailler</h2><ul>${tally(d.neg)}</ul>` : ''}
  ${b.inc ? `<h2>Incidents</h2>${d.inc.length ? `<ul>${d.inc.map(n => `<li>${esc(fS(n.date))}${n.sub ? ', ' + esc(n.sub) : ''}${n.text ? ' : ' + esc(n.text) : ''}</li>`).join('')}</ul>` : '<p>Aucun incident sur la période.</p>'}` : ''}
  ${b.abs ? `<h2>Assiduité</h2><p>${d.nA} demi-journée${d.nA > 1 ? 's' : ''} d’absence${d.nA ? ', dont ' + d.nJ + ' justifiée' + (d.nJ > 1 ? 's' : '') : ''} ; ${d.nR} retard${d.nR > 1 ? 's' : ''}.</p>` : ''}
  <h2>Appréciation générale</h2>${db.bilans[s.id + '|' + r.from + '|' + r.to] ? `<p>${nl(db.bilans[s.id + '|' + r.from + '|' + r.to])}</p>` : '<div class="blank" style="min-height:80pt"></div>'}
  <table style="margin-top:18pt"><tbody><tr><td style="height:60pt;width:50%">L’enseignant(e) :</td><td>Vu et pris connaissance, les responsables :</td></tr></tbody></table>`;
}
A.printBil = d => { const r = bilRange(), st = d.id ? [stu(d.id)] : visStu(); if (!st.length) return; printDoc(st.map((s, i) => `<div class="${i ? 'pb' : ''}">${bilanHTML(s, r)}</div>`).join('')); };

// ---------- Appel ----------
// À la demi-journée. Chaque appel se valide (l'heure est gardée) ; chaque absence a son motif et sa justification.
const HALF = { M: 'Matin', S: 'Après-midi' };
V.appel = () => {
  const c = ctx(), d = ui.date, vc = visCls();
  const tabs = `<div class="row np"><button class="btn ${ui.tab === 'appel' ? 'pri' : ''}" data-a="tab" data-t="appel">Appel</button><button class="btn ${ui.tab === 'stats' ? 'pri' : ''}" data-a="tab" data-t="stats">Statistiques et registre</button></div>`;
  if (ui.tab === 'stats') return `<div class="head"><h1>Appel</h1><span class="sp"></span>${tabs}</div>` + statsHTML();
  return `<div class="head"><h1>Appel</h1><span class="sp"></span>${tabs}</div>
  <div class="row np" style="margin-bottom:10px"><button class="btn" data-a="day" data-n="-1" aria-label="Jour de classe précédent">‹</button><input type="date" value="${d}" data-c="jdate" aria-label="Date"><button class="btn" data-a="day" data-n="1" aria-label="Jour de classe suivant">›</button><button class="btn" data-a="today">Aujourd’hui</button><span class="sp"></span><button class="btn" data-a="subSheet">Feuille vierge pour le remplaçant</button></div>
  <h2 class="cap">${esc(fD(d, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }))} ${c.why(d) ? `<span class="chip yel">${esc(c.why(d))}</span>` : ''}</h2>
  ${vc.map(cid => {
    const st = visStu().filter(s => s.classId === cid);
    return `<div class="sheet"><div class="row">${cbadge(cid, 1)}<span class="small muted">${st.length} élèves, ${st.filter(s => db.att[d + '|M|' + s.id] === 'A').length} absents le matin, ${st.filter(s => db.att[d + '|S|' + s.id] === 'A').length} l’après-midi</span></div>
    <div class="tw"><table><thead><tr><th>Élève</th><th class="c">Matin</th><th class="c">Après-midi</th><th>Motif</th><th class="c">Justifiée</th></tr></thead><tbody>
    ${st.map(s => { const m = db.att[d + '|M|' + s.id] || '', a = db.att[d + '|S|' + s.id] || '', i = db.absInfo[d + '|' + s.id] || {}, b = h => `<button class="att ${h === 'M' ? m : a}" data-a="att" data-h="${h}" data-s="${s.id}" aria-label="${HALF[h]} : ${{ '': 'présent', A: 'absent', R: 'en retard' }[h === 'M' ? m : a]}">${(h === 'M' ? m : a) || 'P'}</button>`;
      return `<tr><td>${esc(sname(s))}</td><td class="c">${b('M')}</td><td class="c">${b('S')}</td><td>${m || a ? `<input data-c="motif" data-s="${s.id}" value="${esc(i.motif)}" placeholder="Motif" aria-label="Motif" style="width:100%;min-width:110px">` : ''}</td><td class="c">${m === 'A' || a === 'A' ? `<input type="checkbox" data-c="just" data-s="${s.id}" ${i.just ? 'checked' : ''} aria-label="Absence justifiée">` : ''}</td></tr>`; }).join('') || '<tr><td colspan="5" class="muted">Aucun élève dans cette classe.</td></tr>'}
    </tbody></table></div>
    <p class="row">${['M', 'S'].map(h => { const k = d + '|' + h + '|' + cid; return db.calls[k] ? `<span class="chip ok">${HALF[h]} : appel validé à ${hm(db.calls[k])}</span>` : `<button class="btn sm np" data-a="call" data-k="${k}">Valider l’appel : ${HALF[h].toLowerCase()}</button>`; }).join('')}</p>
    <p class="small muted np">P = présent, A = absent, R = en retard. Touchez la case pour changer.</p></div>`;
  }).join('')}`;
};
A.tab = d => { ui.tab = d.t; render(); };
A.today = () => { ui.date = TODAY(); render(); };
A.att = d => { const k = ui.date + '|' + d.h + '|' + d.s, v = db.att[k] || ''; if (v === '') db.att[k] = 'A'; else if (v === 'A') db.att[k] = 'R'; else delete db.att[k]; done(); };
const absI = s => db.absInfo[ui.date + '|' + s] ??= {};
C.motif = (d, el) => { absI(d.s).motif = el.value; saveSoon(); };
C.just = (d, el) => { absI(d.s).just = el.checked; save(); };
A.call = d => { db.calls[d.k] = new Date().toTimeString().slice(0, 5); done(); };

function attStats() {                                  // absences par élève et par mois, depuis la rentrée
  const c = ctx(), S = db.settings, t = TODAY(), st = visStu(), ids = new Set(st.map(s => s.id)), per = {}, months = {};
  st.forEach(s => per[s.id] = { A: 0, J: 0, R: 0, nj: {} });
  for (let d = S.start; d <= t && d <= S.end; d = addD(d, 1)) if (c.school(d)) (months[d.slice(0, 7)] ??= { days: 0, A: 0 }).days++;
  for (const k in db.att) { const [d, , sid] = k.split('|'); if (!ids.has(sid)) continue; const p = per[sid], ym = d.slice(0, 7);
    if (db.att[k] === 'R') { p.R++; continue; } p.A++; if (months[ym]) months[ym].A++; if ((db.absInfo[d + '|' + sid] || {}).just) p.J++; else p.nj[ym] = (p.nj[ym] || 0) + 1; }
  return { st, per, months };
}
const fM = ym => fD(ym + '-01', { month: 'long', year: 'numeric' });
function statsHTML() {
  const { st, per, months } = attStats(), n = st.length || 1;
  return `<div class="row np" style="margin-bottom:10px"><label>Registre du mois <select data-c="month">${(() => { const S = db.settings, o = []; for (let d = S.start.slice(0, 7) + '-01'; d <= S.end; d = iso(new Date(+d.slice(0, 4), +d.slice(5, 7), 1))) o.push(d.slice(0, 7)); return o.map(m => `<option value="${m}" ${ui.month === m ? 'selected' : ''}>${esc(fM(m))}</option>`).join(''); })()}</select></label><button class="btn" data-a="register">Imprimer le registre du mois</button></div>
  <div class="cols"><div class="sheet"><h2>Absences de la classe</h2><div class="tw"><table><thead><tr><th>Mois</th><th>Jours de classe</th><th>Demi-journées d’absence</th><th>Taux d’absence</th></tr></thead><tbody>
   ${Object.entries(months).map(([ym, m]) => `<tr><td style="text-transform:capitalize">${esc(fM(ym))}</td><td>${m.days}</td><td>${m.A}</td><td>${(m.A / (m.days * 2 * n) * 100).toFixed(1).replace('.', ',')} %</td></tr>`).join('') || '<tr><td colspan="4" class="muted">L’année n’a pas commencé.</td></tr>'}</tbody></table></div></div>
  <div class="sheet"><h2>Absences par élève</h2><div class="tw"><table><thead><tr><th>Élève</th><th>Absences</th><th>Justifiées</th><th>Non justifiées</th><th>Retards</th></tr></thead><tbody>
   ${st.map(s => { const p = per[s.id], al = Object.entries(p.nj).filter(([, v]) => v >= 4); return `<tr><td><button class="lnk" data-a="openStu" data-id="${s.id}">${esc(sname(s))}</button>${al.map(([ym, v]) => `<br><span class="chip red">${v} demi-journées non justifiées en ${esc(fM(ym))}</span>`).join('')}</td><td>${p.A}</td><td>${p.J}</td><td>${p.A - p.J}</td><td>${p.R}</td></tr>`; }).join('')}</tbody></table></div>
   <p class="small muted">Les absences sont comptées en demi-journées. À partir de 4 demi-journées non justifiées dans un même mois, l’élève est signalé en rouge.</p></div></div>`;
}
C.month = (d, el) => { if (el.value) ui.month = el.value; };
A.register = () => {
  const c = ctx(), ym = ui.month, days = []; for (let d = ym + '-01'; d.slice(0, 7) === ym; d = addD(d, 1)) if (c.school(d)) days.push(d);
  printDoc(visCls().map((cid, i) => {
    const st = visStu().filter(s => s.classId === cid), mk = (d, h, s) => db.att[d + '|' + h + '|' + s.id] || '·';
    return `<div class="${i ? 'pb' : ''}">${docHead('Registre d’appel, ' + fM(ym))}<p>Classe : ${esc(cls(cid).name)}. Chaque case : matin puis après-midi. A = absent, R = retard, · = présent. Absence soulignée = non justifiée.</p>
    <table style="font-size:8.5pt"><thead><tr><th>Élève</th>${days.map(d => `<th class="c">${+d.slice(8)}</th>`).join('')}<th>Abs.</th><th>Non just.</th></tr></thead><tbody>
    ${st.map(s => { let A_ = 0, nj = 0; const cells = days.map(d => { const m = mk(d, 'M', s), a = mk(d, 'S', s), j = (db.absInfo[d + '|' + s.id] || {}).just, na = (m === 'A') + (a === 'A'); A_ += na; if (!j) nj += na; return `<td class="c" ${na && !j ? 'style="text-decoration:underline"' : ''}>${m}${a}</td>`; }).join(''); return `<tr><td>${esc(sname(s))}</td>${cells}<td class="c">${A_}</td><td class="c">${nj}</td></tr>`; }).join('')}
    </tbody></table><p>Appels validés : ${days.reduce((n, d) => n + !!db.calls[d + '|M|' + cid] + !!db.calls[d + '|S|' + cid], 0)} sur ${days.length * 2} demi-journées. Signature de l’enseignant :</p></div>`;
  }).join(''));
};
// Feuille pour le remplaçant : tout ce qu'il faut pour tenir la classe, avec les zones à remplir à la main.
// On choisit ce qu'on y met : les séances passées, et (ou non) les séances à venir.
A.subSheet = () => openForm({ title: 'Feuille du remplaçant', ok: 'Imprimer', values: { past: true, next: true, pai: true },
  fields: [{ k: 'pai', label: 'PAI et besoins particuliers des élèves', type: 'checkbox' }, { k: 'past', label: 'Dernières séances menées', type: 'checkbox' }, { k: 'next', label: 'Séances à venir (décochez pour ne donner que ce qui a été fait)', type: 'checkbox' }],
  onSave: v => { printSub(v); } });
function printSub(opt) {
  const c = ctx(), t = TODAY(), S = db.settings, days = [...S.days].sort();
  printDoc(visCls().map((cid, i) => {
    const k = cls(cid), st = db.students.filter(s => s.classId === cid).sort((a, b) => (a.last + a.first).localeCompare(b.last + b.first, 'fr')), tt = c.ttFor(t);
    const lists = db.lists.filter(l => l.important && (!l.classId || l.classId === cid)), part = st.filter(s => s.pai || s.needs), autres = st.filter(s => s.info);
    const prog = Object.entries(c.queues).filter(([q]) => q.startsWith(cid + '|')).map(([q, v]) => { const all = v.list.filter(x => c.sess[x.id].o), past = all.filter(x => c.sess[x.id].o.date < t).slice(-3), next = all.filter(x => c.sess[x.id].o.date >= t).slice(0, 4);
      return `<tr><td><b>${esc(qname(q))}</b></td>${opt.past ? `<td>${past.map(x => `<b>${esc(fD(c.sess[x.id].o.date, { day: 'numeric', month: 'short' }))}</b> ${esc(x.title)}${x.bilan ? ' (' + esc(x.bilan) + ')' : ''}`).join('<br>') || '-'}</td>` : ''}${opt.next ? `<td>${next.map(x => `<b>${esc(fD(c.sess[x.id].o.date, { day: 'numeric', month: 'short' }))}</b> ${esc(x.title)}${x.obj ? ' : ' + esc(x.obj) : ''}`).join('<br>') || '-'}</td>` : ''}<td class="w" style="width:30%"></td></tr>`; }).join('');
    return `<div class="${i ? 'pb' : ''}">${docHead('Feuille du remplaçant')}<p>Classe : <b>${esc(k.name)}</b> ${esc(k.level || '')}, ${st.length} élèves. Imprimée le ${esc(fD(t, { day: 'numeric', month: 'long', year: 'numeric' }))}. Remplaçant(e) : ............................ du ........... au ...........</p>
    ${opt.pai ? `<h2>PAI et besoins particuliers</h2>${part.length ? `<table><thead><tr><th>Élève</th><th>PAI (santé, conduite à tenir)</th><th>Besoins particuliers (aménagements)</th></tr></thead><tbody>${part.map(s => `<tr><td><b>${esc(sname(s))}</b></td><td>${nl(s.pai)}</td><td>${nl(s.needs)}</td></tr>`).join('')}</tbody></table>` : '<p>Aucun PAI ni besoin particulier enregistré.</p>'}` : ''}
    <h2>Informations pratiques</h2>${S.subInfo ? `<p>${nl(S.subInfo)}</p>` : '<div class="blank"></div>'}
    <h2>Sécurité : incendie, confinement, évacuation</h2>${S.safety ? `<p>${nl(S.safety)}</p>` : '<div class="blank"></div>'}<p>Exercice ou alerte pendant le remplacement : ☐ non ☐ oui, le ........... Remarques : ........................................</p>
    ${autres.length || lists.length ? `<h2>À savoir sur les élèves</h2>${autres.length ? `<table><tbody>${autres.map(s => `<tr><td><b>${esc(sname(s))}</b></td><td>${nl(s.info)}</td></tr>`).join('')}</tbody></table>` : ''}${lists.map(l => { const no = st.filter(s => !l.checked[s.id]); return `<p><b>${esc(l.title)}</b>, non coché pour : ${no.map(sname).map(esc).join(', ') || 'personne'}.</p>`; }).join('')}` : ''}
    <h2>Emploi du temps</h2><table><thead><tr>${days.map(d => `<th>${DAYS[d]}</th>`).join('')}</tr></thead><tbody><tr>${days.map(d => `<td>${tt ? tt.slots.filter(s => s.day === d && s.classId === cid).sort((a, b) => a.start.localeCompare(b.start)).map(s => `${hm(s.start)}-${hm(s.end)} ${esc(subj(s.subjectId).name)}${s.sub ? ' › ' + esc(s.sub) : ''}`).join('<br>') : ''}</td>`).join('')}</tr></tbody></table>
    <h2>Programme${opt.past && opt.next ? ' : où en est la classe' : opt.past ? ' : ce qui a été fait' : ''}</h2><table><thead><tr><th>Matière</th>${opt.past ? '<th>Dernières séances menées</th>' : ''}${opt.next ? '<th>Séances à venir</th>' : ''}<th>Ce que vous avez fait</th></tr></thead><tbody>${prog || '<tr><td colspan="4">Programmation non renseignée.</td></tr>'}</tbody></table>
    <h2 class="pb">Appel (A = absent, R = retard ; M = matin, AM = après-midi)</h2><table><thead><tr><th rowspan="2">Élève</th>${days.map(d => `<th colspan="2" class="c">${DAYS[d]} ......</th>`).join('')}<th rowspan="2">Motif, maladie</th></tr><tr>${days.map(() => '<th class="c">M</th><th class="c">AM</th>').join('')}</tr></thead><tbody>${st.map(s => `<tr><td>${esc(sname(s))}</td>${days.map(() => '<td></td><td></td>').join('')}<td style="width:26%"></td></tr>`).join('')}</tbody></table>
    <h2 class="pb">Observations par élève</h2><table><tbody>${st.map(s => `<tr><td style="width:28%">${esc(sname(s))}</td><td class="w"></td></tr>`).join('')}</tbody></table>
    <h2>Incidents, soins, échanges avec les familles (date, heure, élève, faits)</h2><div class="blank" style="min-height:110pt"></div>
    <h2>Mot pour l’enseignant(e)</h2><div class="blank" style="min-height:90pt"></div></div>`;
  }).join(''));
}

// ---------- Administratif ----------
// Des listes à cocher par élève (droit à l'image, coopérative, assurance…). Une liste « importante »
// s'affiche aussi en tête de la fiche de chaque élève et sur la feuille du remplaçant.
V.admin = () => {
  const st = visStu(), vc = visCls(), lists = db.lists.filter(l => !l.classId || vc.includes(l.classId));
  return `<div class="head"><h1>Administratif</h1><span class="sp"></span><div class="row np"><button class="btn pri" data-a="newList">Nouvelle liste à cocher</button></div></div>
  ${motsHTML()}
  ${lists.length ? `<div class="sheet flat"><div class="tw"><table><thead><tr><th>Élève</th>${lists.map(l => { const ok = st.filter(s => (!l.classId || l.classId === s.classId) && l.checked[s.id]).length, n = st.filter(s => !l.classId || l.classId === s.classId).length; return `<th class="c"><button class="lnk" data-a="editList" data-id="${l.id}">${esc(l.title)}</button>${l.important ? '<br><span class="chip yel">important</span>' : ''}<br><span class="small muted">${ok}/${n}</span></th>`; }).join('')}</tr></thead>
   <tbody>${st.map(s => `<tr><td><button class="lnk" data-a="openStu" data-id="${s.id}">${esc(sname(s))}</button> ${cbadge(s.classId)}</td>${lists.map(l => `<td class="c">${!l.classId || l.classId === s.classId ? `<input type="checkbox" data-c="chk" data-l="${l.id}" data-s="${s.id}" ${l.checked[s.id] ? 'checked' : ''} aria-label="${esc(l.title)} : ${esc(sname(s))}">` : ''}</td>`).join('')}</tr>`).join('') || `<tr><td colspan="${lists.length + 1}" class="muted">Ajoutez d’abord vos élèves.</td></tr>`}</tbody></table></div>
   ${lists.filter(l => l.note).map(l => `<p class="small"><b>${esc(l.title)} :</b> ${nl(l.note)}</p>`).join('')}</div>` : '<div class="sheet"><p>Créez une liste par document ou par collecte : droit à l’image, assurance, coopérative, autorisation de sortie… Cochez au fur et à mesure qui a rendu quoi.</p></div>'}`;
};
function listForm(l) {
  openForm({ title: l ? 'Modifier la liste' : 'Nouvelle liste', values: l || { classId: ui.cls, important: false },
    fields: [{ k: 'title', label: 'Titre (ex. Droit à l’image accordé)', req: 1 }, ...(db.classes.length > 1 ? [{ k: 'classId', label: 'Classe', type: 'select', options: [['', 'Toutes les classes'], ...clsOpts()] }] : []), { k: 'important', label: 'Important : afficher sur la fiche de chaque élève', type: 'checkbox' }, { k: 'note', label: 'Précisions', type: 'textarea' }],
    onSave: v => { if (l) Object.assign(l, v); else db.lists.push({ id: uid(), classId: '', ...v, checked: {} }); }, onDelete: l ? () => remove(db.lists, l.id) : null });
}
A.newList = () => listForm(null); A.editList = d => listForm(db.lists.find(l => l.id === d.id));
C.chk = (d, el) => { const l = db.lists.find(x => x.id === d.l); if (el.checked) l.checked[d.s] = 1; else delete l.checked[d.s]; save(); };

// ---------- Réglages ----------
V.reglages = () => {
  const S = db.settings, inp = (k, l, type = 'text', ph = '') => `<label class="fld"><span>${l}</span><input type="${type}" data-c="set" data-k="${k}" value="${esc(S[k])}" placeholder="${ph}"></label>`;
  const T = S.theme, fopt = (cur, keys) => keys.map(k => `<option value="${k}" ${cur === k ? 'selected' : ''}>${esc(FONTS[k][0])}</option>`).join('');
  return `<div class="head"><h1>Réglages</h1></div>
  <div class="sheet"><div class="mg">Aa</div><h2>Apparence</h2>
   <p class="small muted" style="margin-bottom:4px">Palette</p>
   <div class="row">${Object.entries(THEMES).map(([k, p]) => `<button class="pal" data-a="pal" data-p="${k}" aria-pressed="${T.pal === k}"><span>${Object.values(p.c).map(x => `<i style="background:${x}"></i>`).join('')}</span>${p.n}</button>`).join('')}</div>
   <p class="small muted" style="margin:12px 0 4px">Couleurs sur mesure${T.pal === 'perso' ? ' (palette personnelle)' : ''}</p>
   <div class="row">${Object.entries(TCOL).map(([k, l]) => `<label class="small" style="text-align:center">${l}<br><input type="color" data-c="tcol" data-k="${k}" value="${esc(T[k])}"></label>`).join('')}</div>
   <div class="row" style="margin-top:10px;align-items:flex-end"><label class="fld" style="flex:1;min-width:200px"><span>Police du texte</span><select data-c="tfont" data-k="font">${fopt(T.font, ['atkinson', 'lexend', 'andika', 'nunito', 'merri', 'patrick', 'systeme'])}</select></label>
    <label class="fld" style="flex:1;min-width:200px"><span>Police des titres</span><select data-c="tfont" data-k="titles"><option value="same" ${T.titles === 'same' ? 'selected' : ''}>La même que le texte</option>${fopt(T.titles, ['bricolage', 'atkinson', 'lexend', 'andika', 'nunito', 'merri', 'patrick', 'systeme'])}</select></label>
    <label class="fld" style="flex:1;min-width:200px"><span>Taille du texte : <b id="tsz">${esc(T.size)}</b> px</span><input type="range" min="13" max="22" step="1" data-c="tsize" value="${esc(T.size)}" style="padding:0"></label></div>
   <p class="small muted">Les changements s’appliquent tout de suite, sur cet appareil. L’impression reste toujours en noir sur blanc. Hors connexion, une police pas encore chargée est remplacée par celle de l’appareil.</p>
   <p class="np"><button class="btn sm" data-a="pal" data-p="ardoise" data-all="1">Revenir à l’apparence d’origine</button></p></div>
  <div class="cols">
  <div class="sheet"><h2>L’école</h2>${inp('teacher', 'Enseignant(e)')}${inp('school', 'École')}${inp('year', 'Année scolaire')}</div>
  <div class="sheet"><div class="row"><h2>Classes</h2><span class="sp"></span><button class="btn sm" data-a="newCls">Ajouter une classe</button></div>
   <ul class="plain">${db.classes.map(c => `<li>${cbadge(c.id, 1)} <span class="muted small">${esc(c.level)}</span><span class="sp"></span><button class="btn sm" data-a="editCls" data-id="${c.id}">Modifier</button></li>`).join('')}</ul>
   <label class="fld"><span>Repérer chaque classe par</span><select data-c="set" data-k="mark" data-r="1"><option value="color" ${S.mark === 'color' ? 'selected' : ''}>une couleur</option><option value="icon" ${S.mark === 'icon' ? 'selected' : ''}>une icône</option></select></label>
   <p class="small muted">Avec plusieurs classes ou niveaux, un filtre apparaît en haut de chaque page.</p></div>
  <div class="sheet"><div class="row"><h2>Matières</h2><span class="sp"></span><button class="btn sm" data-a="newSubj">Ajouter une matière</button></div>
   <p class="small muted" style="margin-bottom:4px">Les matières des programmes selon le niveau :</p><div class="row">${Object.entries(CYCLES).map(([k, x]) => `<button class="btn sm" data-a="subjCycle" data-k="${k}">${esc(x.l)}</button>`).join('')}</div>
   <ul class="plain">${db.subjects.map(s => `<li><span class="dot" style="background:${esc(s.color)}"></span><b>${esc(s.name)}</b><span class="small muted" style="flex:1">${esc((s.subs || []).join(', '))}</span><button class="btn sm" data-a="editSubj" data-id="${s.id}">Modifier</button></li>`).join('')}</ul></div>
  <div class="sheet"><h2>Fiche de préparation</h2><label class="fld"><span>Rédaction du déroulé, par défaut</span><select data-c="set" data-k="redac"><option value="libre" ${S.redac !== 'tableau' ? 'selected' : ''}>En texte libre (plus rapide)</option><option value="tableau" ${S.redac === 'tableau' ? 'selected' : ''}>En tableau, une case par rubrique</option></select></label><p class="small muted">Cochez les rubriques du déroulé que vous voulez, et renommez-les à votre façon. La durée de chaque étape est toujours affichée.</p>
   ${S.cols.map((c, i) => `<div class="row" style="margin:5px 0"><input type="checkbox" data-c="colOn" data-i="${i}" ${c.on ? 'checked' : ''} aria-label="Afficher la rubrique ${esc(c.l)}"><input data-c="colL" data-i="${i}" value="${esc(c.l)}" style="flex:1;min-width:140px" aria-label="Nom de la rubrique"></div>`).join('')}</div>
  <div class="sheet"><div class="row"><h2>Comportements</h2><span class="sp"></span><button class="btn sm" data-a="newBehav">Ajouter</button></div><p class="small muted">Ils servent de boutons sur la fiche de chaque élève : un appui, et l’observation est notée.</p>
   <ul class="plain">${S.behav.map(x => `<li><span class="chip ${x.pos ? 'ok' : 'yel'}">${x.pos ? 'positif' : 'à travailler'}</span><b style="flex:1">${esc(x.l)}</b><button class="btn sm" data-a="editBehav" data-id="${x.id}">Modifier</button></li>`).join('')}</ul></div>
  <div class="sheet"><h2>Évaluations</h2><p class="small muted">Pour une note chiffrée, le niveau équivalent est donné à partir de ce pourcentage de la note maximale. En dessous du premier seuil : non atteint.</p>
   <div class="row">${[['eva', 'En voie d’acquisition'], ['a', 'Acquis'], ['d', 'Dépassé']].map(([k, l]) => `<label class="fld" style="flex:1;min-width:120px"><span>${l}, dès (%)</span><input type="number" min="0" max="100" data-c="seuil" data-k="${k}" value="${esc(S.seuils[k])}" style="width:100%"></label>`).join('')}</div></div>
  <div class="sheet"><h2>Calendrier</h2>${inp('start', 'Premier jour de classe', 'date')}${inp('end', 'Dernier jour de classe', 'date')}
   <p style="margin-bottom:2px" class="small muted">Jours de classe</p><div class="row">${[1, 2, 3, 4, 5, 6].map(d => `<label><input type="checkbox" data-c="sday" data-d="${d}" ${S.days.includes(d) ? 'checked' : ''}> ${DAYS[d]}</label>`).join('')}</div>
   <div class="row" style="margin-top:12px"><h3>Vacances</h3><span class="sp"></span><button class="btn sm" data-a="newHol">Ajouter une période</button></div>
   <ul class="plain">${S.holidays.map((h, i) => `<li><b>${esc(h.label)}</b><span class="small" style="flex:1">du ${esc(fD(h.from, { day: 'numeric', month: 'short', year: 'numeric' }))} au ${esc(fD(h.to, { day: 'numeric', month: 'short', year: 'numeric' }))} inclus</span><button class="btn sm" data-a="editHol" data-i="${i}">Modifier</button></li>`).join('')}</ul>
   <p class="small muted">Les jours fériés sont déjà comptés. Les dates dépendent de votre zone : vérifiez-les et ajoutez les vacances d’hiver et de printemps, sinon des séances seront placées pendant les vacances.</p></div>
  <div class="sheet"><h2>108 heures</h2><div class="row">${Object.keys(S.quotas).map(k => `<label class="fld" style="flex:1;min-width:120px"><span>${qlab(k)}</span><input type="number" min="0" data-c="quota" data-k="${k}" value="${esc(S.quotas[k])}" style="width:100%"></label>`).join('')}</div>
   <p class="small ${Object.values(S.quotas).reduce((a, b) => a + +b, 0) === 108 ? 'muted' : 'warn'}">Total : ${Object.values(S.quotas).reduce((a, b) => a + +b, 0)} h.</p></div>
  <div class="sheet"><h2>Option IA</h2><p class="small muted">Pour faire rédiger une fiche de préparation en un clic. Sans clé, le bouton propose de copier la demande dans l’IA de votre choix. La clé reste sur cet appareil et n’est jamais copiée dans les sauvegardes.</p>${inp('apiKey', 'Clé API Anthropic', 'password', 'sk-ant-…')}${inp('model', 'Modèle')}</div>
  <div class="sheet"><h2>Motifs de remplacement</h2><label class="fld"><span>Proposés d’un appui quand une séance est remplacée (un par ligne)</span><textarea rows="5" data-c="reasons">${esc(S.reasons.join('\n'))}</textarea></label></div>
  <div class="sheet"><h2>Pour le remplaçant</h2><label class="fld"><span>Informations pratiques (horaires, récréations, cantine, sortie, codes, collègues à qui s’adresser)</span><textarea rows="4" data-c="set" data-k="subInfo">${esc(S.subInfo)}</textarea></label>
   <label class="fld"><span>Sécurité (signal incendie, trajet d’évacuation, point de rassemblement, confinement)</span><textarea rows="4" data-c="set" data-k="safety">${esc(S.safety)}</textarea></label><p class="np"><button class="btn" data-a="subSheet">Imprimer la feuille du remplaçant</button></p></div>
  <div class="sheet"><h2>Sauvegarde et années</h2><p>Dernière sauvegarde : <b>${S.lastBackup ? esc(fS(S.lastBackup.slice(0, 10)) + ' à ' + hm(S.lastBackup.slice(11))) : 'jamais'}</b></p>
   <label class="fld"><span>Proposer la sauvegarde à partir de (heure)</span><input type="number" min="0" max="23" data-c="set" data-k="backupHour" value="${esc(S.backupHour)}" style="width:5em"></label>
   <p class="row"><button class="btn pri" data-a="backup">Sauvegarder sur clé USB</button><button class="btn" data-a="restore">Restaurer une sauvegarde</button></p>
   <p class="row"><button class="btn" data-a="newYear">Préparer l’année suivante</button><button class="btn danger" data-a="wipe">Tout effacer</button></p>
   <p class="small muted">Les données vivent dans ce navigateur, sur cet appareil. Sans sauvegarde, elles sont perdues si l’on vide l’historique ou si l’on change d’appareil.</p></div></div>`;
};
A.pal = d => { const T = db.settings.theme; Object.assign(T, THEMES[d.p].c, { pal: d.p }); if (d.all) Object.assign(T, { font: 'atkinson', titles: 'bricolage', size: 16 }); done(); };
C.tcol = (d, el) => { const T = db.settings.theme; T[d.k] = el.value; T.pal = 'perso'; applyTheme(); saveSoon(); };     // sans réafficher : le sélecteur de couleur reste ouvert
C.tfont = (d, el) => { db.settings.theme[d.k] = el.value; applyTheme(); saveSoon(); };
C.tsize = (d, el) => { db.settings.theme.size = +el.value; $('#tsz').textContent = el.value; applyTheme(); saveSoon(); };
C.reasons = (d, el) => { db.settings.reasons = el.value.split('\n').map(x => x.trim()).filter(Boolean); saveSoon(); };
C.set = (d, el) => { db.settings[d.k] = el.value; if (d.r) done(); else saveSoon(); };
C.colOn = (d, el) => { db.settings.cols[d.i].on = el.checked; saveSoon(); };
C.colL = (d, el) => { db.settings.cols[d.i].l = el.value; saveSoon(); };
C.seuil = (d, el) => { db.settings.seuils[d.k] = +el.value || 0; saveSoon(); };
function behavForm(x) {
  openForm({ title: x ? 'Modifier le comportement' : 'Nouveau comportement', values: x ? { l: x.l, pos: x.pos ? '1' : '0' } : { pos: '1' }, fields: [{ k: 'l', label: 'Intitulé (ex. Persévérance)', req: 1 }, { k: 'pos', label: 'Rangé dans', type: 'select', options: [['1', 'Points positifs'], ['0', 'Points à travailler']] }],
    onSave: v => upsert(db.settings.behav, x && x.id, { l: v.l, pos: v.pos === '1' }), onDelete: x ? () => remove(db.settings.behav, x.id) : null });
}
A.newBehav = () => behavForm(null); A.editBehav = d => behavForm(db.settings.behav.find(x => x.id === d.id));
C.quota = (d, el) => { db.settings.quotas[d.k] = +el.value || 0; saveSoon(); };
C.sday = (d, el) => { const n = +d.d, a = db.settings.days; if (el.checked) { if (!a.includes(n)) a.push(n); } else if (a.length > 1) a.splice(a.indexOf(n), 1); done(); };
function clsForm(c) {
  openForm({ title: c ? 'Modifier la classe' : 'Nouvelle classe', values: c || { color: ['#3355c0', '#c0392b', '#23795b', '#d08400', '#8a3b9c'][db.classes.length % 5], icon: ['🦊', '🐢', '🦉', '🐳', '🦋'][db.classes.length % 5] },
    fields: [{ k: 'name', label: 'Nom (ex. CE1-CE2 Mme Martin)', req: 1 }, { k: 'level', label: 'Niveaux, séparés par une virgule (ex. CE1, CE2)' }, { k: 'color', label: 'Couleur', type: 'color' }, { k: 'icon', label: 'Icône (un émoji)' }],
    onSave: v => upsert(db.classes, c && c.id, v), onDelete: c && db.classes.length > 1 ? () => { remove(db.classes, c.id); if (ui.cls === c.id) ui.cls = ''; } : null });
}
A.newCls = () => clsForm(null); A.editCls = d => clsForm(cls(d.id));
function subjForm(s) {
  openForm({ title: s ? 'Modifier la matière' : 'Nouvelle matière', values: s ? { ...s, subs: (s.subs || []).join('\n') } : { color: '#3355c0', subs: '' },
    fields: [{ k: 'name', label: 'Nom', req: 1 }, { k: 'color', label: 'Couleur', type: 'color' }, { k: 'subs', label: 'Sous-matières, une par ligne', type: 'textarea', rows: 5 }],
    onSave: v => { v.subs = v.subs.split('\n').map(x => x.trim()).filter(Boolean); upsert(db.subjects, s && s.id, v); }, onDelete: s ? () => remove(db.subjects, s.id) : null });
}
A.newSubj = () => subjForm(null); A.editSubj = d => subjForm(db.subjects.find(s => s.id === d.id));
function holForm(i) {
  const H = db.settings.holidays, h = H[i];
  openForm({ title: 'Période de vacances', values: h || {}, fields: [{ k: 'label', label: 'Nom (ex. Vacances d’hiver)', req: 1 }, { k: 'from', label: 'Premier jour sans classe', type: 'date', req: 1 }, { k: 'to', label: 'Dernier jour sans classe', type: 'date', req: 1 }],
    onSave: v => { if (h) Object.assign(h, v); else H.push(v); H.sort((a, b) => a.from.localeCompare(b.from)); }, onDelete: h ? () => H.splice(i, 1) : null });
}
A.newHol = () => holForm(-1); A.editHol = d => holForm(+d.i);
A.restore = () => pickFile(j => {
  if (!j.settings || !j.classes) { toast('Ce fichier n’est pas une sauvegarde complète.'); return; }
  if (!confirm('Remplacer toutes les données de cet appareil par cette sauvegarde ?')) return;
  const key = db.settings.apiKey; db = j; normalize(); db.settings.apiKey = key; save(); db.dirty = false; localStorage.setItem(KEY, JSON.stringify(db)); ui.stu = ui.sess = ui.tt = null; ui.cls = ''; render(); toast('Sauvegarde restaurée.');
});
A.wipe = () => { if (confirm('Effacer toutes les données de cet appareil ? Faites une sauvegarde avant.') && confirm('Vraiment tout effacer ?')) { const key = db.settings.apiKey, th = db.settings.theme; db = blank(); db.settings.apiKey = key; db.settings.theme = th; save(); ui.cls = ''; ui.tt = null; go('home'); } };
// Année suivante : on garde ce qui se réutilise (matières, emploi du temps, blocs et séances, listes), on vide le reste.
A.newYear = () => { const y = +db.settings.end.slice(0, 4);
  openForm({ title: 'Préparer l’année suivante', ok: 'Créer la nouvelle année', intro: '<p>Sont <b>reportés</b> : classes, matières, emploi du temps, tous les blocs et leurs séances (fiches complètes), créneaux de préparation, titres des listes administratives. Vous choisirez ensuite la date de départ de chaque bloc.<br>Sont <b>vidés</b> : appel, notes, évaluations, bilans, réunions, agenda, créneaux non faits ou déplacés, dates des rappels.</p><p class="warn">Enregistrez d’abord une sauvegarde de l’année qui se termine : elle servira d’archive.</p>',
    values: { year: y + '-' + (y + 1), start: y + '-09-01', end: (y + 1) + '-07-06', keep: false },
    fields: [{ k: 'year', label: 'Année scolaire', req: 1 }, { k: 'start', label: 'Premier jour de classe', type: 'date', req: 1 }, { k: 'end', label: 'Dernier jour de classe', type: 'date', req: 1 }, { k: 'keep', label: 'Garder les élèves (je suis ma classe)', type: 'checkbox' }],
    onSave: v => {
      const b = blank(), S = db.settings; Object.assign(S, { year: v.year, start: v.start, end: v.end, holidays: [] });
      const tt = ctx().ttFor(TODAY()) || db.timetables[0]; db.timetables = tt ? [{ ...tt, from: v.start, label: 'Emploi du temps de base' }] : [];
      db.sequences.forEach(q => { q.start = ''; q.sessions.forEach(s => { s.merge = s.skip = false; s.bilan = ''; s.pin = ''; (s.rem || []).forEach(r => { r.date = ''; delete r.days; }); }); });
      db.lists.forEach(l => { if (!v.keep) l.checked = {}; });
      if (!v.keep) db.students = [];
      for (const k of ['att', 'absInfo', 'calls', 'notes', 'results', 'meetings', 'events', 'ov', 'adhoc', 'remDone', 'dayNotes', 'extra', 'evals', 'grades', 'bilans']) db[k] = b[k];
      ui.tt = null; ui.ev = null; setTimeout(() => { go('prog'); A.planSeqs(); toast('Nouvelle année créée. Choisissez la date de départ de chaque bloc, puis saisissez les vacances dans les réglages.'); }, 60);
    } }); };
