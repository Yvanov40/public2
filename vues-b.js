'use strict';
// =====================================================================================
//  vues-b.js — agenda et 108 heures, dossiers élèves, appel, administratif, réglages.
// =====================================================================================

// ---------- Agenda et 108 heures ----------
const fmtH = h => { const m = Math.round(h * 60); return Math.floor(m / 60) + ' h' + (m % 60 ? ' ' + String(m % 60).padStart(2, '0') : ''); };
function h108() {                                      // heures faites et prévues, par catégorie des obligations de service
  const q = db.settings.quotas, r = {}, t = TODAY();
  for (const k in q) r[k] = { quota: +q[k] || 0, done: 0, plan: 0 };
  for (const e of db.events) if (r[e.cat]) r[e.cat][e.date <= t ? 'done' : 'plan'] += +e.hours || 0;
  return r;
}
V.agenda = () => {
  const c = ctx(), t = TODAY(), vc = visCls(), H = h108(), rems = reminders().filter(r => vc.includes(r.o.classId));
  const sum = Object.values(H).reduce((a, x) => a + x.done + x.plan, 0), quota = Object.values(H).reduce((a, x) => a + x.quota, 0);
  const evs = db.events.filter(e => !ui.cls || !e.classId || e.classId === ui.cls);
  const ev = e => `<li><span class="dot" style="background:${CATS[e.cat][1]}"></span><button class="lnk" data-a="editEv" data-id="${e.id}">${e.start ? hm(e.start) + ' ' : ''}<b>${esc(e.title)}</b></button> ${+e.hours && db.settings.quotas[e.cat] !== undefined ? `<span class="chip">${fmtH(+e.hours)}</span>` : ''} ${cbadge(e.classId)}</li>`;
  const days = [0, 1, 2, 3, 4, 5, 6].map(i => addD(ui.week, i));
  return `<div class="head"><h1>Agenda</h1><span class="sp"></span><div class="row np"><button class="btn pri" data-a="newEv">Ajouter un rendez-vous</button></div></div>
  <div class="sheet"><div class="mg">108 h</div><h2>Obligations de service : ${fmtH(sum)} inscrites sur ${quota} h</h2>
   ${sum >= quota ? '<p class="chip ok">Les ' + quota + ' heures sont atteintes : rien n’est dû au-delà.</p>' : `<p class="small muted">Il reste ${fmtH(quota - sum)} à inscrire d’ici la fin de l’année.</p>`}
   ${Object.entries(H).map(([k, x]) => `<div style="margin:10px 0"><div class="row"><b>${CATS[k][0]}</b><span class="sp"></span><span class="small">${fmtH(x.done)} faites${x.plan ? ', ' + fmtH(x.plan) + ' prévues' : ''}, sur ${x.quota} h ${x.done + x.plan > x.quota ? '<span class="chip yel">dépassé de ' + fmtH(x.done + x.plan - x.quota) + '</span>' : ''}</span></div>
     <div class="bar"><i style="width:${Math.min(100, x.done / (x.quota || 1) * 100)}%;background:${CATS[k][1]}"></i><i style="width:${Math.min(100 - Math.min(100, x.done / (x.quota || 1) * 100), x.plan / (x.quota || 1) * 100)}%;background:${CATS[k][1]};opacity:.4"></i></div></div>`).join('')}</div>
  <div class="row" style="margin-bottom:10px"><button class="btn np" data-a="week" data-n="-7" aria-label="Semaine précédente">‹</button><b>Semaine du ${esc(fD(ui.week, { day: 'numeric', month: 'long', year: 'numeric' }))}</b><button class="btn np" data-a="week" data-n="7" aria-label="Semaine suivante">›</button><button class="btn np" data-a="week" data-n="0">Cette semaine</button></div>
  ${days.map(d => {
    const occ = (c.byDate[d] || []).filter(o => vc.includes(o.classId)), dr = rems.filter(r => r.date === d), de = evs.filter(e => e.date === d).sort((a, b) => (a.start || '').localeCompare(b.start || ''));
    const inC = de.filter(e => e.cat === 'classe'), out = de.filter(e => e.cat !== 'classe');
    if (!occ.length && !dr.length && !de.length && (wd(d) === 0 || wd(d) === 6)) return '';
    return `<div class="sheet" ${d === t ? 'style="border-color:var(--encre);border-width:2px"' : ''}><div class="mg">${esc(fD(d, { weekday: 'short' }))}<br>${esc(fD(d, { day: 'numeric', month: 'short' }))}</div>
     <div class="cols" style="grid-template-columns:repeat(auto-fit,minmax(220px,1fr))"><div><h3>En classe</h3><ul class="plain">
       ${occ.length ? `<li><button class="lnk" data-a="toJournal" data-d="${d}">${occ.filter(o => !(o.ov && o.ov.t === 'lost')).length} séance(s) au cahier journal</button></li>` : `<li class="muted small">${esc(c.why(d) || 'Pas de créneau')}</li>`}
       ${inC.map(ev).join('')}${dr.map(r => remLi(r, t)).join('')}</ul></div>
     <div><h3>Hors classe</h3><ul class="plain">${out.map(ev).join('') || '<li class="muted small">Rien de prévu</li>'}</ul>
       <p class="np"><button class="btn sm" data-a="newEv" data-d="${d}">Ajouter ce jour-là</button></p></div></div></div>`;
  }).join('')}
  <div class="sheet"><h2>Détail des heures de l’année</h2>${db.events.some(e => H[e.cat]) ? `<div class="tw"><table><thead><tr><th>Date</th><th>Objet</th><th>Catégorie</th><th>Durée</th></tr></thead><tbody>${[...db.events].filter(e => H[e.cat]).sort((a, b) => a.date.localeCompare(b.date)).map(e => `<tr><td>${esc(fS(e.date))}</td><td>${esc(e.title)}${e.note ? '<br><span class="small muted">' + nl(e.note) + '</span>' : ''}</td><td>${CATS[e.cat][0]}</td><td>${fmtH(+e.hours || 0)}</td></tr>`).join('')}</tbody></table></div>` : '<p class="muted">Ajoutez vos APC, réunions, rendez-vous de parents, formations et conseils d’école : le décompte se fait tout seul.</p>'}</div>`;
};
function evForm(e, date) {
  openForm({ title: e ? 'Modifier le rendez-vous' : 'Nouveau rendez-vous', values: e || { date: date || TODAY(), cat: 'equipe', classId: '' },
    fields: [{ k: 'title', label: 'Objet', req: 1 }, { k: 'cat', label: 'Catégorie', type: 'select', options: Object.entries(CATS).map(([k, v]) => [k, v[0]]) }, { k: 'date', label: 'Date', type: 'date', req: 1 }, { k: 'start', label: 'Début', type: 'time' }, { k: 'end', label: 'Fin', type: 'time' },
    { k: 'hours', label: 'Durée comptée en heures (vide : calculée d’après début et fin)', type: 'number' }, ...(db.classes.length > 1 ? [{ k: 'classId', label: 'Classe concernée', type: 'select', options: [['', 'Aucune en particulier'], ...clsOpts()] }] : []), { k: 'note', label: 'Notes, compte rendu', type: 'textarea' }],
    onSave: v => { if (v.hours === '' && v.start && v.end) v.hours = Math.max(0, (mins(v.end) - mins(v.start)) / 60); upsert(db.events, e && e.id, v); },
    onDelete: e ? () => remove(db.events, e.id) : null });
}
A.newEv = d => evForm(null, d.d);
A.editEv = d => evForm(db.events.find(e => e.id === d.id));

// ---------- Élèves ----------
const initials = s => ((s.first || '?')[0] + (s.last || '')[0]).toUpperCase();
V.eleves = () => {
  const t = TODAY(), vc = visCls();
  return `<div class="head"><h1>Élèves</h1><span class="sp"></span><div class="row np"><button class="btn pri" data-a="newStu">Ajouter un élève</button><button class="btn" data-a="newStuList">Ajouter une liste</button></div></div>
  <p class="np small muted">Touchez une tuile pour ouvrir la fiche. <b>Maintenez le doigt dessus</b> pour dicter une note : l’heure est notée, et vous choisissez ensuite incident, comportement, évaluation ou commentaire.</p>
  ${vc.map(cid => {
    const st = visStu().filter(s => s.classId === cid);
    return `${db.classes.length > 1 ? `<h2 style="margin:14px 0 8px">${cbadge(cid, 1)} <span class="small muted">${st.length} élèves</span></h2>` : ''}
    ${st.length ? `<div class="tiles">${st.map(s => { const ab = db.att[t + '|M|' + s.id] === 'A' || db.att[t + '|S|' + s.id] === 'A', n = db.notes.filter(x => x.sid === s.id && x.date === t).length;
      return `<button class="tile" data-a="openStu" data-id="${s.id}"><span class="av" style="background:${esc(cls(cid).color)}">${esc(db.settings.mark === 'icon' ? cls(cid).icon || initials(s) : initials(s))}</span><b>${esc(s.first)}<br>${esc(s.last)}</b>${s.level ? `<span class="chip">${esc(s.level)}</span>` : ''}${ab ? '<span class="chip red">absent</span>' : ''}${n ? `<span class="chip yel">${n} note${n > 1 ? 's' : ''} ce jour</span>` : ''}</button>`; }).join('')}</div>` : '<div class="sheet"><p class="muted">Aucun élève. Ajoutez-les un par un, ou collez toute la liste.</p></div>'}`;
  }).join('')}`;
};
function stuForm(s) {
  openForm({ title: s ? 'Modifier la fiche' : 'Nouvel élève', values: s || { classId: defCls(), level: ui.lvl },
    fields: [{ k: 'first', label: 'Prénom', req: 1 }, { k: 'last', label: 'Nom' }, ...(db.classes.length > 1 ? [{ k: 'classId', label: 'Classe', type: 'select', options: clsOpts() }] : []), { k: 'level', label: 'Niveau (CP, CE1…)' }, { k: 'birth', label: 'Date de naissance', type: 'date' },
    { k: 'contacts', label: 'Responsables et téléphones', type: 'textarea' }, { k: 'health', label: 'Santé, PAI, allergies, besoins particuliers', type: 'textarea' }, { k: 'info', label: 'Autres informations (sortie, garderie, cantine…)', type: 'textarea' }],
    onSave: v => { if (!v.classId) v.classId = s ? s.classId : defCls(); upsert(db.students, s && s.id, v); },
    onDelete: s ? () => { remove(db.students, s.id); ui.stu = null; ui.view = 'eleves'; } : null });
}
A.newStu = () => stuForm(null);
A.editStu = () => stuForm(stu(ui.stu));
A.newStuList = () => openForm({ title: 'Ajouter une liste d’élèves', values: { classId: defCls(), level: ui.lvl }, fields: [{ k: 'list', label: 'Un élève par ligne : Prénom Nom', type: 'textarea', rows: 10 }, ...(db.classes.length > 1 ? [{ k: 'classId', label: 'Classe', type: 'select', options: clsOpts() }] : []), { k: 'level', label: 'Niveau (facultatif)' }],
  onSave: v => v.list.split('\n').map(x => x.trim()).filter(Boolean).forEach(l => { const p = l.split(/\s+/); db.students.push({ id: uid(), first: p[0], last: p.slice(1).join(' '), classId: v.classId || defCls(), level: v.level, birth: '', contacts: '', health: '', info: '' }); }) });
A.openStu = d => { ui.stu = d.id; go('eleve'); };

// La fiche d'un élève : tout son dossier sur une page, imprimable.
V.eleve = () => {
  const s = stu(ui.stu); if (!s) return V.eleves();
  const notes = db.notes.filter(n => n.sid === s.id).sort((a, b) => (b.date + b.time).localeCompare(a.date + a.time)), obs = notes.filter(n => n.type !== 'incident'), inc = notes.filter(n => n.type === 'incident');
  const res = db.results.filter(r => r.sid === s.id).sort((a, b) => b.date.localeCompare(a.date)), meet = db.meetings.filter(m => m.sid === s.id).sort((a, b) => b.date.localeCompare(a.date));
  const lists = db.lists.filter(l => !l.classId || l.classId === s.classId), imp = lists.filter(l => l.important);
  const abs = Object.keys(db.att).filter(k => k.endsWith('|' + s.id)).sort(), nA = abs.filter(k => db.att[k] === 'A'), nJ = nA.filter(k => (db.absInfo[k.slice(0, 10) + '|' + s.id] || {}).just);
  const nli = n => `<li><span class="small muted">${esc(fS(n.date))}${n.time ? ' ' + hm(n.time) : ''}</span><span class="chip">${NOTE[n.type][0]} ${esc(n.sub || NOTE[n.type][1])}</span><button class="lnk" data-a="editNote" data-id="${n.id}" style="flex:1;min-width:160px">${nl(n.text)}</button></li>`;
  const sec = (title, body, act, label) => `<div class="sheet"><div class="row"><h2>${title}</h2><span class="sp"></span>${act ? `<button class="btn sm np" data-a="${act}">${label}</button>` : ''}</div>${body}</div>`;
  return `<div class="head"><h1>${esc(sname(s))}</h1><span class="sp"></span><div class="row np"><button class="btn" data-a="go" data-v="eleves">‹ Tous les élèves</button><button class="btn" data-a="editStu">Modifier la fiche</button></div></div>
  <div class="sheet"><p style="margin:0">${cbadge(s.classId, 1)} ${s.level ? `<span class="chip">${esc(s.level)}</span>` : ''} ${s.birth ? 'Né(e) le ' + esc(fD(s.birth, { day: 'numeric', month: 'long', year: 'numeric' })) : ''}</p>
   ${s.contacts ? `<p><b>Responsables :</b> ${nl(s.contacts)}</p>` : ''}${s.health ? `<p class="warn">Santé : ${nl(s.health)}</p>` : ''}${s.info ? `<p>${nl(s.info)}</p>` : ''}
   ${imp.length ? `<p>${imp.map(l => `<span class="chip ${l.checked[s.id] ? 'ok' : 'red'}">${l.checked[s.id] ? '✓' : '✗'} ${esc(l.title)}</span>`).join(' ')}</p>` : ''}</div>
  ${sec('Résultats', res.length ? `<div class="tw"><table><thead><tr><th>Date</th><th>Matière</th><th>Évaluation</th><th>Résultat</th><th>Commentaire</th></tr></thead><tbody>${res.map(r => `<tr><td>${esc(fS(r.date))}</td><td>${esc(subj(r.subjectId).name)}</td><td><button class="lnk" data-a="editRes" data-id="${r.id}">${esc(r.title)}</button></td><td>${esc(r.level)}${r.score ? ' ' + esc(r.score) : ''}</td><td>${nl(r.comment)}</td></tr>`).join('')}</tbody></table></div>` : '<p class="muted">Aucun résultat noté.</p>', 'newRes', 'Ajouter un résultat')}
  ${sec('Observations', obs.length ? `<ul class="plain">${obs.map(nli).join('')}</ul>` : '<p class="muted">Aucune observation.</p>', 'newObs', 'Ajouter une observation')}
  ${sec('Incidents', inc.length ? `<ul class="plain">${inc.map(nli).join('')}</ul>` : '<p class="muted">Aucun incident (dispute, blessure, maladie, échange avec la famille).</p>', 'newInc', 'Ajouter un incident')}
  ${sec('Réunions avec les parents', meet.length ? `<ul class="plain">${meet.map(m => `<li><span class="small muted">${esc(fS(m.date))}</span><button class="lnk" data-a="editMeet" data-id="${m.id}" style="flex:1;min-width:160px"><b>${esc(m.who || 'Rendez-vous')}</b><br>${nl(m.text)}</button></li>`).join('')}</ul>` : '<p class="muted">Aucun compte rendu.</p>', 'newMeet', 'Ajouter un compte rendu')}
  ${sec('Absences', `<p><b>${nA.length}</b> demi-journée${nA.length > 1 ? 's' : ''} d’absence, dont ${nJ.length} justifiée${nJ.length > 1 ? 's' : ''} ; ${abs.length - nA.length} retard${abs.length - nA.length > 1 ? 's' : ''}.</p>${abs.length ? `<p class="small">${abs.map(k => { const [d, h] = k.split('|'), i = db.absInfo[d + '|' + s.id] || {}; return esc(fD(d, { day: 'numeric', month: 'short' })) + ' ' + (h === 'M' ? 'matin' : 'après-midi') + (db.att[k] === 'R' ? ' (retard)' : '') + (i.motif ? ' : ' + esc(i.motif) : '') + (db.att[k] === 'A' && !i.just ? ' <span class="warn">non justifiée</span>' : ''); }).join(' ; ')}</p>` : ''}`)}
  ${sec('Administratif', lists.length ? `<ul class="plain">${lists.map(l => `<li><label><input type="checkbox" data-c="chk" data-l="${l.id}" data-s="${s.id}" ${l.checked[s.id] ? 'checked' : ''}> ${esc(l.title)}</label>${l.important ? '<span class="chip yel">important</span>' : ''}</li>`).join('')}</ul>` : '<p class="muted">Aucune liste. Créez-les dans Administratif.</p>')}`;
};
function noteForm(n, type) {
  openForm({ title: n ? 'Modifier la note' : type === 'incident' ? 'Nouvel incident' : 'Nouvelle observation', values: n || { type, date: TODAY(), time: new Date().toTimeString().slice(0, 5), sub: '' },
    fields: [{ k: 'type', label: 'Type', type: 'select', options: Object.entries(NOTE).map(([k, v]) => [k, v[1]]) }, { k: 'sub', label: 'Nature (pour un incident)', type: 'select', options: [['', 'Sans précision'], ...INC.map(x => [x, x])] }, { k: 'date', label: 'Date', type: 'date', req: 1 }, { k: 'time', label: 'Heure', type: 'time' }, { k: 'text', label: 'Note', type: 'textarea', rows: 4 }],
    onSave: v => upsert(db.notes, n && n.id, { ...v, sid: ui.stu }), onDelete: n ? () => remove(db.notes, n.id) : null });
}
A.newObs = () => noteForm(null, 'commentaire'); A.newInc = () => noteForm(null, 'incident'); A.editNote = d => noteForm(db.notes.find(n => n.id === d.id));
function resForm(r) {
  openForm({ title: r ? 'Modifier le résultat' : 'Nouveau résultat', values: r || { date: TODAY(), level: 'Atteint', subjectId: (db.subjects[0] || {}).id },
    fields: [{ k: 'title', label: 'Évaluation ou compétence', req: 1 }, { k: 'subjectId', label: 'Matière', type: 'select', options: db.subjects.map(s => [s.id, s.name]) }, { k: 'date', label: 'Date', type: 'date', req: 1 }, { k: 'level', label: 'Résultat', type: 'select', options: [['', 'Sans positionnement'], ...LEVELS.map(x => [x, x])] }, { k: 'score', label: 'Score (facultatif, ex. 8/10)' }, { k: 'comment', label: 'Commentaire', type: 'textarea' }],
    onSave: v => upsert(db.results, r && r.id, { ...v, sid: ui.stu }), onDelete: r ? () => remove(db.results, r.id) : null });
}
A.newRes = () => resForm(null); A.editRes = d => resForm(db.results.find(r => r.id === d.id));
function meetForm(m) {
  openForm({ title: m ? 'Modifier le compte rendu' : 'Réunion avec les parents', values: m || { date: TODAY() }, fields: [{ k: 'date', label: 'Date', type: 'date', req: 1 }, { k: 'who', label: 'Personnes présentes' }, { k: 'text', label: 'Compte rendu, décisions prises', type: 'textarea', rows: 6 }],
    onSave: v => upsert(db.meetings, m && m.id, { ...v, sid: ui.stu }), onDelete: m ? () => remove(db.meetings, m.id) : null });
}
A.newMeet = () => meetForm(null); A.editMeet = d => meetForm(db.meetings.find(m => m.id === d.id));

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
  return `<div class="row np" style="margin-bottom:10px"><label>Registre du mois <input type="month" value="${ui.month}" data-c="month"></label><button class="btn" data-a="register">Imprimer le registre du mois</button></div>
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
A.subSheet = () => {
  const c = ctx(), t = TODAY(), S = db.settings, days = [...S.days].sort();
  printDoc(visCls().map((cid, i) => {
    const k = cls(cid), st = db.students.filter(s => s.classId === cid).sort((a, b) => (a.last + a.first).localeCompare(b.last + b.first, 'fr')), tt = c.ttFor(t);
    const lists = db.lists.filter(l => l.important && (!l.classId || l.classId === cid));
    const prog = Object.entries(c.queues).filter(([q]) => q.startsWith(cid + '|')).map(([q, v]) => { const all = v.seqs.flatMap(x => x.sessions).filter(x => c.sess[x.id].o), past = all.filter(x => c.sess[x.id].o.date < t).slice(-2), next = all.filter(x => c.sess[x.id].o.date >= t).slice(0, 4); return `<tr><td><b>${esc(qname(q))}</b></td><td>${past.map(x => esc(x.title)).join('<br>') || '-'}</td><td>${next.map(x => `<b>${esc(fD(c.sess[x.id].o.date, { day: 'numeric', month: 'short' }))}</b> ${esc(x.title)}${x.obj ? ' : ' + esc(x.obj) : ''}`).join('<br>') || '-'}</td><td class="w"></td></tr>`; }).join('');
    return `<div class="${i ? 'pb' : ''}">${docHead('Feuille du remplaçant')}<p>Classe : <b>${esc(k.name)}</b> ${esc(k.level || '')}, ${st.length} élèves. Imprimée le ${esc(fD(t, { day: 'numeric', month: 'long', year: 'numeric' }))}. Remplaçant(e) : ............................ du ........... au ...........</p>
    <h2>Informations pratiques</h2>${S.subInfo ? `<p>${nl(S.subInfo)}</p>` : '<div class="blank"></div>'}
    <h2>Sécurité : incendie, confinement, évacuation</h2>${S.safety ? `<p>${nl(S.safety)}</p>` : '<div class="blank"></div>'}<p>Exercice ou alerte pendant le remplacement : ☐ non ☐ oui, le ........... Remarques : ........................................</p>
    <h2>Élèves à connaître</h2>${st.some(s => s.health || s.info) ? `<table><tbody>${st.filter(s => s.health || s.info).map(s => `<tr><td><b>${esc(sname(s))}</b></td><td>${nl(s.health)}${s.health && s.info ? '<br>' : ''}${nl(s.info)}</td></tr>`).join('')}</tbody></table>` : '<p>Aucune information particulière enregistrée.</p>'}
    ${lists.map(l => { const no = st.filter(s => !l.checked[s.id]); return `<p><b>${esc(l.title)}</b>, non coché pour : ${no.map(sname).map(esc).join(', ') || 'personne'}.</p>`; }).join('')}
    <h2>Emploi du temps</h2><table><thead><tr>${days.map(d => `<th>${DAYS[d]}</th>`).join('')}</tr></thead><tbody><tr>${days.map(d => `<td>${tt ? tt.slots.filter(s => s.day === d && s.classId === cid).sort((a, b) => a.start.localeCompare(b.start)).map(s => `${hm(s.start)}-${hm(s.end)} ${esc(subj(s.subjectId).name)}${s.sub ? ' › ' + esc(s.sub) : ''}`).join('<br>') : ''}</td>`).join('')}</tr></tbody></table>
    <h2>Programme : où en est la classe</h2><table><thead><tr><th>Matière</th><th>Dernières séances menées</th><th>Séances à venir</th><th>Ce que vous avez fait</th></tr></thead><tbody>${prog || '<tr><td colspan="4">Programmation non renseignée.</td></tr>'}</tbody></table>
    <h2 class="pb">Appel (A = absent, R = retard ; M = matin, AM = après-midi)</h2><table><thead><tr><th rowspan="2">Élève</th>${days.map(d => `<th colspan="2" class="c">${DAYS[d]} ......</th>`).join('')}<th rowspan="2">Motif, maladie</th></tr><tr>${days.map(() => '<th class="c">M</th><th class="c">AM</th>').join('')}</tr></thead><tbody>${st.map(s => `<tr><td>${esc(sname(s))}</td>${days.map(() => '<td></td><td></td>').join('')}<td style="width:26%"></td></tr>`).join('')}</tbody></table>
    <h2 class="pb">Observations par élève</h2><table><tbody>${st.map(s => `<tr><td style="width:28%">${esc(sname(s))}</td><td class="w"></td></tr>`).join('')}</tbody></table>
    <h2>Incidents, soins, échanges avec les familles (date, heure, élève, faits)</h2><div class="blank" style="min-height:110pt"></div>
    <h2>Mot pour l’enseignant(e)</h2><div class="blank" style="min-height:90pt"></div></div>`;
  }).join(''));
};

// ---------- Administratif ----------
// Des listes à cocher par élève (droit à l'image, coopérative, assurance…). Une liste « importante »
// s'affiche aussi en tête de la fiche de chaque élève et sur la feuille du remplaçant.
V.admin = () => {
  const st = visStu(), vc = visCls(), lists = db.lists.filter(l => !l.classId || vc.includes(l.classId));
  return `<div class="head"><h1>Administratif</h1><span class="sp"></span><div class="row np"><button class="btn pri" data-a="newList">Nouvelle liste à cocher</button></div></div>
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
   <ul class="plain">${db.subjects.map(s => `<li><span class="dot" style="background:${esc(s.color)}"></span><b>${esc(s.name)}</b><span class="small muted" style="flex:1">${esc((s.subs || []).join(', '))}</span><button class="btn sm" data-a="editSubj" data-id="${s.id}">Modifier</button></li>`).join('')}</ul></div>
  <div class="sheet"><h2>Calendrier</h2>${inp('start', 'Premier jour de classe', 'date')}${inp('end', 'Dernier jour de classe', 'date')}
   <p style="margin-bottom:2px" class="small muted">Jours de classe</p><div class="row">${[1, 2, 3, 4, 5, 6].map(d => `<label><input type="checkbox" data-c="sday" data-d="${d}" ${S.days.includes(d) ? 'checked' : ''}> ${DAYS[d]}</label>`).join('')}</div>
   <div class="row" style="margin-top:12px"><h3>Vacances</h3><span class="sp"></span><button class="btn sm" data-a="newHol">Ajouter une période</button></div>
   <ul class="plain">${S.holidays.map((h, i) => `<li><b>${esc(h.label)}</b><span class="small" style="flex:1">du ${esc(fD(h.from, { day: 'numeric', month: 'short', year: 'numeric' }))} au ${esc(fD(h.to, { day: 'numeric', month: 'short', year: 'numeric' }))} inclus</span><button class="btn sm" data-a="editHol" data-i="${i}">Modifier</button></li>`).join('')}</ul>
   <p class="small muted">Les jours fériés sont déjà comptés. Les dates dépendent de votre zone : vérifiez-les et ajoutez les vacances d’hiver et de printemps, sinon des séances seront placées pendant les vacances.</p></div>
  <div class="sheet"><h2>108 heures</h2><div class="row">${Object.keys(S.quotas).map(k => `<label class="fld" style="flex:1;min-width:120px"><span>${CATS[k][0]}</span><input type="number" min="0" data-c="quota" data-k="${k}" value="${esc(S.quotas[k])}" style="width:100%"></label>`).join('')}</div>
   <p class="small ${Object.values(S.quotas).reduce((a, b) => a + +b, 0) === 108 ? 'muted' : 'warn'}">Total : ${Object.values(S.quotas).reduce((a, b) => a + +b, 0)} h.</p></div>
  <div class="sheet"><h2>Option IA</h2><p class="small muted">Pour faire rédiger une fiche de préparation en un clic. Sans clé, le bouton propose de copier la demande dans l’IA de votre choix. La clé reste sur cet appareil et n’est jamais copiée dans les sauvegardes.</p>${inp('apiKey', 'Clé API Anthropic', 'password', 'sk-ant-…')}${inp('model', 'Modèle')}</div>
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
C.set = (d, el) => { db.settings[d.k] = el.value; if (d.r) done(); else saveSoon(); };
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
  openForm({ title: 'Préparer l’année suivante', ok: 'Créer la nouvelle année', intro: '<p>Sont <b>reportés</b> : classes, matières, emploi du temps, tous les blocs et leurs séances (fiches complètes), titres des listes administratives.<br>Sont <b>vidés</b> : appel, notes, résultats, réunions, agenda, créneaux non faits.</p><p class="warn">Enregistrez d’abord une sauvegarde de l’année qui se termine : elle servira d’archive.</p>',
    values: { year: y + '-' + (y + 1), start: y + '-09-01', end: (y + 1) + '-07-06', keep: false },
    fields: [{ k: 'year', label: 'Année scolaire', req: 1 }, { k: 'start', label: 'Premier jour de classe', type: 'date', req: 1 }, { k: 'end', label: 'Dernier jour de classe', type: 'date', req: 1 }, { k: 'keep', label: 'Garder les élèves (je suis ma classe)', type: 'checkbox' }],
    onSave: v => {
      const b = blank(), S = db.settings; Object.assign(S, { year: v.year, start: v.start, end: v.end, holidays: [] });
      const tt = ctx().ttFor(TODAY()) || db.timetables[0]; db.timetables = tt ? [{ ...tt, from: v.start, label: 'Emploi du temps de base' }] : [];
      db.sequences.forEach(q => { q.start = ''; q.sessions.forEach(s => { s.merge = s.skip = false; s.bilan = ''; }); });
      db.lists.forEach(l => { if (!v.keep) l.checked = {}; });
      if (!v.keep) db.students = [];
      for (const k of ['att', 'absInfo', 'calls', 'notes', 'results', 'meetings', 'events', 'ov', 'adhoc', 'remDone', 'dayNotes']) db[k] = b[k];
      ui.tt = null; toast('Nouvelle année créée. Saisissez les vacances dans les réglages.');
    } }); };

// Une petite classe d'exemple, pour découvrir l'appli.
A.demo = () => {
  if ((db.students.length || db.sequences.length) && !confirm('Ajouter les données d’exemple à vos données actuelles ?')) return;
  const S = n => { let s = db.subjects.find(x => x.name === n); if (!s) { s = { id: uid(), name: n, color: '#59607c', subs: [] }; db.subjects.push(s); } return s; };
  const k = db.classes[0]; k.name = 'CE1-CE2'; k.level = 'CE1, CE2';
  'Lina Morel,Hugo Petit,Inès Garcia,Noé Lambert,Jade Rousseau,Adam Fontaine,Zoé Chevalier,Liam Faure,Mila Girard,Sacha André'.split(',').forEach((n, i) => { const [first, last] = n.split(' '); db.students.push({ id: uid(), first, last, classId: k.id, level: i % 2 ? 'CE2' : 'CE1', birth: '', contacts: '', health: i === 2 ? 'PAI : allergie aux arachides, trousse dans le bureau' : '', info: '' }); });
  const fr = S('Français'), ma = S('Mathématiques'), qlm = S('Questionner le monde'), eps = S('EPS'), art = S('Arts plastiques');
  const slot = (day, start, end, sb, sub) => ({ id: uid(), day, start, end, subjectId: sb.id, sub: sub || '', classId: k.id });
  const slots = [];[1, 2, 4, 5].forEach(d => { slots.push(slot(d, '08:30', '09:15', fr, 'Lecture'), slot(d, '09:15', '10:00', ma, 'Calcul'), slot(d, '10:20', '11:05', fr, d % 2 ? 'Grammaire' : 'Orthographe'), slot(d, '13:30', '14:15', d === 1 || d === 4 ? qlm : eps), slot(d, '14:15', '15:00', d === 2 ? art : ma, d === 2 ? '' : 'Problèmes')); });
  db.timetables = [{ id: uid(), from: db.settings.start, label: 'Emploi du temps de base', slots }];
  const seq = (title, sb, sub, n, pre) => db.sequences.push({ id: uid(), title, subjectId: sb.id, sub, classId: k.id, level: '', start: '', sessions: Array.from({ length: n }, (_, i) => newSess(pre + ' ' + (i + 1))) });
  seq('L’addition posée avec retenue', ma, 'Calcul', 40, 'Addition posée, séance'); seq('La soustraction posée', ma, 'Calcul', 60, 'Soustraction, séance'); seq('Le groupe nominal', fr, 'Grammaire', 30, 'Groupe nominal, séance'); seq('Le cycle de l’eau', qlm, '', 30, 'Cycle de l’eau, séance'); seq('Lecture suivie', fr, 'Lecture', 150, 'Lecture, épisode');
  _c = null; const c = ctx(), first = db.sequences[db.sequences.length - 5].sessions.find(s => c.sess[s.id].o && c.sess[s.id].o.date >= TODAY());
  if (first) Object.assign(first, { title: 'Poser une addition à retenue', obj: 'Poser et calculer une addition de deux nombres à deux chiffres avec retenue.', mat: 'Ardoises, matériel de numération (barres et cubes), fiche d’exercices.', steps: [{ dur: 10, phase: 'Calcul mental', ens: 'Dicte les calculs, valide sur l’ardoise.', cons: '« Écrivez le résultat sur l’ardoise, levez au signal. »', att: 'Compléments à 10 mémorisés.', elv: 'Individuel, ardoise.' }, { dur: 20, phase: 'Recherche', ens: 'Pose le problème, observe les procédures, fait verbaliser.', cons: '« Avec les barres et les cubes, trouvez 37 + 25. »', att: 'Échange de 10 cubes contre une barre.', elv: 'En binômes, manipulation.' }, { dur: 15, phase: 'Entraînement', ens: 'Accompagne le groupe en difficulté.', cons: '« Posez et calculez les quatre additions de la fiche. »', att: 'Retenue écrite et comptée.', elv: 'Individuel, cahier.' }], rem: [{ id: uid(), type: 'imp', text: 'Fiche d’exercices, 24 copies', days: 1 }, { id: uid(), type: 'mat', text: 'Sortir les barres et les cubes', days: 0 }] });
  db.lists.push({ id: uid(), title: 'Droit à l’image accordé', classId: '', important: true, note: '', checked: Object.fromEntries(db.students.slice(0, 7).map(s => [s.id, 1])) }, { id: uid(), title: 'Coopérative scolaire', classId: '', important: false, note: '', checked: {} });
  const T = TODAY(); db.events.push({ id: uid(), title: 'Conseil d’école', cat: 'conseil', date: addD(T, 5), start: '18:00', end: '20:00', hours: 2, classId: '', note: '' }, { id: uid(), title: 'APC lecture', cat: 'apc', date: addD(T, -6), start: '11:45', end: '12:30', hours: .75, classId: '', note: '' }, { id: uid(), title: 'Animation pédagogique', cat: 'formation', date: addD(T, -20), start: '09:00', end: '12:00', hours: 3, classId: '', note: '' }, { id: uid(), title: 'Conseil de cycle', cat: 'equipe', date: addD(T, 2), start: '16:45', end: '18:15', hours: 1.5, classId: '', note: '' });
  done(); toast('Classe d’exemple chargée. « Tout effacer » dans les réglages la supprime.');
};

// ---------- Démarrage ----------
(function () { const h = location.hash.slice(1); if (V[h] && !PARENT[h]) ui.view = h; render(); })();
// Toutes les 5 minutes, on vérifie s'il est l'heure de proposer la sauvegarde (sans déranger une saisie en cours).
setInterval(() => { if (needBackup() && !$('.banner') && $('#modal').hidden && $('#voice').hidden && !/INPUT|TEXTAREA|SELECT/.test((document.activeElement || {}).tagName || '')) render(); }, 300000);
