'use strict';
// =====================================================================================
//  vues-a.js — les pages « pédagogie » : aujourd'hui, cahier journal, fiche de séance,
//  emploi du temps, programmation annuelle.
// =====================================================================================

// ---------- Aujourd'hui ----------
const remLi = (r, t) => `<li><label><input type="checkbox" data-c="remDone" data-k="${r.key}" ${db.remDone[r.key] ? 'checked' : ''}> ${REM[r.r.type][0]} <b>${REM[r.r.type][1]}</b>${r.r.text ? ' : ' + esc(r.r.text) : ''}</label>
  <span class="small ${r.date < t && !db.remDone[r.key] ? 'warn' : 'muted'}">${r.date < t ? 'à faire depuis ' : 'à faire '}${fS(r.date)}, pour « ${esc(r.s.title)} » (${esc(r.label)}, ${fS(r.o.date)})</span></li>`;
C.remDone = (d, el) => { if (el.checked) db.remDone[d.k] = 1; else delete db.remDone[d.k]; save(); };

V.home = () => {
  const c = ctx(), t = TODAY(), vc = visCls();
  const occ = (c.byDate[t] || []).filter(o => vc.includes(o.classId));
  const rems = reminders().filter(r => !db.remDone[r.key] && r.date <= addD(t, 7) && vc.includes(r.o.classId));
  const al = Object.entries(c.queues).filter(([q, v]) => vc.includes(q.split('|')[0]) && (v.late > 0 || v.lost > 0));
  const start = !db.students.length && !db.timetables.some(x => x.slots.length);
  const H = h108(), tot = Object.values(H).reduce((a, x) => a + x.done + x.plan, 0);
  return `<div class="head"><h1>${esc(fD(t))}</h1></div>
  ${start ? `<div class="sheet"><h2>Pour commencer</h2>
    <p>1. Dans <button class="lnk" data-a="go" data-v="reglages">Réglages</button>, nommez votre classe, vérifiez les matières et les vacances de votre zone.<br>
    2. Dans <button class="lnk" data-a="go" data-v="edt">Emploi du temps</button>, posez vos créneaux de la semaine.<br>
    3. Dans <button class="lnk" data-a="go" data-v="prog">Programmation</button>, créez vos blocs de séances : les dates se calculent toutes seules.</p>
    <p class="np"><button class="btn" data-a="demo">Charger une classe d’exemple pour essayer</button></p></div>` : ''}
  <div class="cols">
  <div class="sheet"><h2>Séances du jour</h2>${occ.length ? `<ul class="plain">${occ.map(o => { const ss = c.occSess[o.key] || [], ad = db.adhoc[o.key]; return `<li><span class="muted small">${hm(o.start)}</span><span class="dot" style="background:${subj((o.hostQ || o.q).split('|')[1]).color}"></span><b>${esc(qname(o.hostQ || o.q))}</b> ${o.ov && o.ov.t === 'lost' ? '<span class="chip red">non fait</span>' : esc(ss.map(s => s.title).join(' + ') || (ad ? ad.title : ''))}</li>`; }).join('')}</ul>
    <p class="np"><button class="btn" data-a="toJournal" data-d="${t}">Ouvrir le cahier journal</button></p>` : `<p class="muted">${esc(c.why(t) || 'Aucun créneau à l’emploi du temps aujourd’hui.')}</p>`}</div>
  <div class="sheet"><h2>À préparer cette semaine</h2>${rems.length ? `<ul class="plain">${rems.map(r => remLi(r, t)).join('')}</ul>` : '<p class="muted">Aucun rappel en attente. Les rappels se créent dans chaque séance (impression, matériel, mot aux parents, équipement).</p>'}</div>
  <div class="sheet"><h2>Avancée du programme</h2>${al.length ? `<ul class="plain">${al.map(([q, v]) => `<li><b>${esc(qname(q))}</b> ${v.late > 0 ? `<span class="chip yel">${v.late} séance${v.late > 1 ? 's' : ''} de retard</span>` : ''} ${v.lost ? `<span class="chip red">${v.lost} hors année</span>` : ''}</li>`).join('')}</ul><p class="np"><button class="btn" data-a="go" data-v="prog">Voir la programmation</button></p>` : '<p class="muted">Rien à signaler : aucune matière en retard.</p>'}</div>
  <div class="sheet"><h2>108 heures</h2><p><b>${fmtH(tot)}</b> inscrites sur 108 h</p><div class="bar">${Object.entries(H).map(([k, x]) => `<i style="width:${Math.min(100, (x.done + x.plan) / 108 * 100)}%;background:${CATS[k][1]}"></i>`).join('')}</div>
    <p class="np"><button class="btn" data-a="go" data-v="hors">Ouvrir Hors classe</button></p></div>
  </div>`;
};
A.toJournal = d => { closeModal(); ui.date = d.d; go('journal'); };

// ---------- Cahier journal ----------
// Le cahier journal ne montre que l'objectif de chaque séance. Le détail (déroulé, étapes) est dans la
// fiche de préparation, qui s'imprime à part. Les colonnes du déroulé se choisissent dans les réglages.
const stepsTable = s => { const cs = cols(); return !(s.steps || []).length ? '' : `<div class="tw"><table class="steps"><thead><tr><th>Durée</th>${cs.map(c => `<th>${esc(c.l)}</th>`).join('')}</tr></thead><tbody>${s.steps.map(p => `<tr><td data-l="Durée">${esc(p.dur || '')} min</td>${cs.map(c => `<td data-l="${esc(c.l)}">${nl(p[c.k])}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`; };
const sessBody = (s, ref) => `<h3><button class="lnk" data-a="openSess" ${ref}>${esc(s.title)}</button></h3>
  ${s.obj ? `<p><b>Objectif :</b> ${nl(s.obj)}</p>` : '<p class="muted small np">Objectif à rédiger.</p>'}${s.bilan ? `<p class="small"><b>Bilan :</b> ${nl(s.bilan)}</p>` : ''}`;

function occCard(o, c) {
  const q = o.hostQ || o.q, sb = subj(q.split('|')[1]), sub = q.split('|')[2], ss = c.occSess[o.key] || [], ad = db.adhoc[o.key], lost = o.ov && o.ov.t === 'lost';
  let body;
  if (lost) body = `<p class="warn">${esc(o.ov.why || 'Créneau non fait')}</p><p class="np"><button class="btn sm" data-a="restoreOcc" data-key="${o.key}">Rétablir ce créneau</button></p>`;
  else {
    body = ss.map(s => sessBody(s, `data-sid="${s.id}"`)).join('<hr>') || (ad ? sessBody(ad, `data-key="${o.key}"`) : '<p class="muted">Aucune séance programmée sur ce créneau.</p>');
    body += `<p class="np row">${!ss.length && !ad ? `<button class="btn sm" data-a="adhoc" data-key="${o.key}">Écrire une séance</button>` : ''}<button class="btn sm" data-a="missed" data-key="${o.key}">Séance non faite…</button><button class="btn sm" data-a="occMenu" data-key="${o.key}">Déplacer ou remplacer…</button></p>`;
  }
  return `<div class="sheet"><div class="mg">${hm(o.start)}<br>${hm(o.end)}</div>
    <p style="margin:0"><span class="dot" style="background:${sb.color}"></span><b>${esc(sb.name)}</b>${sub ? ' › ' + esc(sub) : ''} ${o.hostQ ? `<span class="chip yel">à la place de ${esc(subj(o.subjectId).name)}</span>` : ''}${o.from ? ` <span class="chip">déplacé du ${esc(fS(o.from.date))} ${hm(o.from.start)}</span>` : ''}${o.extra ? ' <span class="chip">créneau ponctuel</span>' : ''} ${cbadge(o.classId)}</p>${body}</div>`;
}
V.journal = () => {
  const c = ctx(), d = ui.date, vc = visCls(), occ = (c.byDate[d] || []).filter(o => vc.includes(o.classId));
  return `<div class="head"><h1>Cahier journal</h1><span class="sp"></span><div class="row np"><button class="btn" data-a="day" data-n="-1" aria-label="Jour de classe précédent">‹</button><input type="date" value="${d}" data-c="jdate" aria-label="Date"><button class="btn" data-a="day" data-n="1" aria-label="Jour de classe suivant">›</button><button class="btn" data-a="toJournal" data-d="${TODAY()}">Aujourd’hui</button></div></div>
  <h2 class="cap">${esc(fD(d, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }))}</h2>
  ${occ.length ? occ.map(o => occCard(o, c)).join('') : `<div class="sheet"><p class="muted">${esc(c.why(d) || 'Aucun créneau ce jour-là. Posez vos créneaux dans l’emploi du temps.')}</p></div>`}
  <div class="sheet"><h3>Bilan de la journée</h3><textarea rows="3" data-c="dayNote" placeholder="Ce qui a marché, ce qui est à reprendre…">${esc(db.dayNotes[d] || '')}</textarea>
  <p class="np row">${occ.length ? '<button class="btn sm" data-a="printDayFiches">Imprimer les fiches de préparation du jour</button>' : ''}${occ.some(o => !o.ov) ? `<button class="btn sm danger" data-a="missDay">Journée non faite : tout décaler</button>` : ''}</p>
  <p class="small muted np">« Imprimer » en haut de page imprime le cahier journal (objectifs seulement). Les fiches de préparation détaillées s’impriment avec le bouton ci-dessus, ou une par une depuis chaque séance.</p></div>`;
};
C.jdate = (d, el) => { if (el.value) { ui.date = el.value; render(); } };
C.dayNote = (d, el) => { db.dayNotes[ui.date] = el.value; saveSoon(); };
A.day = d => { const c = ctx(); let x = ui.date; for (let i = 0; i < 90; i++) { x = addD(x, +d.n); if (c.school(x)) break; } ui.date = x; render(); };
A.adhoc = d => { db.adhoc[d.key] = newSess('Séance'); save(); A.openSess({ key: d.key }); };
A.restoreOcc = d => { restoreOcc(d.key); closeModal(); done(); toast('Créneau rétabli : la programmation s’est recalée.'); };
A.printDayFiches = () => {
  const c = ctx(), vc = visCls(), refs = (c.byDate[ui.date] || []).filter(o => vc.includes(o.classId) && !(o.ov && o.ov.t === 'lost')).flatMap(o => (c.occSess[o.key] || []).map(s => ({ sid: s.id })).concat(db.adhoc[o.key] && !(c.occSess[o.key] || []).length ? [{ key: o.key }] : []));
  if (!refs.length) { toast('Aucune séance à imprimer ce jour-là.'); return; }
  printDoc(refs.map((r, i) => `<div class="${i ? 'pb' : ''}">${ficheHTML(sessInfo(r))}</div>`).join(''));
};
A.missDay = () => {
  const c = ctx(), vc = visCls(), occ = (c.byDate[ui.date] || []).filter(o => vc.includes(o.classId) && !o.ov), keys = occ.map(o => o.key);
  const out = preview(() => keys.forEach(k => db.ov[k] = { t: 'lost' }));
  openForm({ title: 'Journée non faite', ok: 'Tout décaler', intro: `<p>Les ${keys.length} créneaux du ${esc(fD(ui.date))} seront marqués non faits. Chaque matière reprend au créneau suivant.</p>${out.length ? `<p class="warn">Séances qui sortiront de l’année :</p><ul>${out.map(x => `<li>${esc(x)}</li>`).join('')}</ul>` : ''}`,
    fields: [{ k: 'why', label: 'Ce qui a lieu ce jour-là (s’affichera dans l’emploi du temps)', ph: 'Sortie, absence, grève…' }], onSave: v => { keys.forEach(k => db.ov[k] = { t: 'lost', why: v.why }); } });
};

// Séance non faite : on demande seulement comment rattraper. Le motif, lui, se note dans l'emploi du
// temps (« Remplacer ce jour-là ») : il s'y affiche à la place de la séance.
A.missed = d => {
  const c = ctx(), o = c.byKey[d.key]; if (!o) return;
  const q = o.hostQ || o.q, ss = c.occSess[o.key] || [], s = ss[0], why = d.why || '';
  if (!s) { db.ov[o.key] = { t: 'lost', why }; done(); toast('Créneau marqué non fait.'); return; }
  const all = c.queues[q].seqs.flatMap(x => x.sessions).filter(x => !x.skip), nx = all[all.indexOf(ss[ss.length - 1]) + 1], nxId = nx ? nx.id : '', nxT = nx ? nx.title : '', titles = ss.map(x => x.title).join(' + ');
  const cand = c.occs.filter(x => x.classId === o.classId && (x.hostQ || x.q) !== q && !x.ov && x.date + x.start > o.date + o.start && x.date >= TODAY()).slice(0, 40)
    .map(x => `<option value="${x.key}">${esc(fS(x.date))} ${hm(x.start)}, ${esc(qname(x.q))}${(c.occSess[x.key] || []).length ? ' (« ' + esc(c.occSess[x.key][0].title) + ' »)' : ''}</option>`).join('');
  const head = `<b>${esc(qname(q))}</b>, ${esc(fS(o.date))} à ${hm(o.start)}`;
  ui.miss = { key: o.key, sid: s.id, q, why };
  const out = preview(() => { db.ov[o.key] = { t: 'lost' }; });
  showModal(`<form class="dlg" id="missf"><h2>${why ? esc(why) + ' : que devient la séance ?' : 'Séance non faite'}</h2><p>${head}<br>« ${esc(titles)} »</p>
   <label class="opt"><input type="radio" name="m" value="same" checked> <b>Décaler dans la même matière</b><small>Toutes les séances suivantes reculent d’un créneau.${out.length ? ` <span class="warn">Sortiront de l’année : ${esc(out.join(' ; '))}.</span> Vous pourrez changer l’ordre dans la programmation pour choisir lesquelles sacrifier.` : ' Aucune séance ne sort de l’année.'}</small></label>
   <label class="opt"><input type="radio" name="m" value="other" ${cand ? '' : 'disabled'}> <b>Rattraper sur le créneau d’une autre matière</b><small>La matière remplacée se décale à son tour.</small>
     <select name="host" style="margin:6px 0 0 1.7em;max-width:calc(100% - 1.7em)"><option value="">Choisir le créneau…</option>${cand}</select></label>
   <label class="opt"><input type="radio" name="m" value="compact" ${nxId ? '' : 'disabled'}> <b>Compacter avec la séance suivante</b><small>${nxId ? `Cette séance et « ${esc(nxT)} » partageront le prochain créneau. Rien d’autre ne bouge.` : 'Il n’y a pas de séance suivante.'}</small></label>
   <label class="opt"><input type="radio" name="m" value="drop"> <b>Abandonner cette séance</b><small>Elle reste dans la programmation, marquée abandonnée. Le reste ne bouge pas.</small></label>
   <input type="hidden" name="nx" value="${nxId}">
   <div class="row end"><span class="sp"></span><button type="button" class="btn" data-a="closeModal">Annuler</button><button type="button" class="btn pri" data-a="missApply">Appliquer</button></div></form>`);
};
A.missApply = () => {
  const f = $('#missf'), m = f.elements.m.value, { key, sid, q, why } = ui.miss, ov = { t: 'lost', why }, before = lostList().length;
  if (m === 'other') { const h = f.elements.host.value; if (!h) { toast('Choisissez le créneau de rattrapage dans la liste.'); return; } ov.host = h; db.ov[h] = { t: 'host', q }; }
  if (m === 'compact') { const nx = f.elements.nx.value; findS(nx).s.merge = true; ov.merged = nx; }
  if (m === 'drop') { findS(sid).s.skip = true; ov.skipped = sid; }
  db.ov[key] = ov; closeModal(); save();
  const n = lostList().length - before, x = ctx().sess[sid];
  toast((x && x.o ? 'Séance reportée au ' + fS(x.o.date) + '. ' : 'Programmation recalée. ') + (n > 0 ? n + ' séance(s) sortent de l’année : voir la programmation.' : '')); render();
};

// ---------- Fiche de séance ----------
function sessInfo(r) {
  if (!r) return null; const c = ctx();
  if (r.sid) { const f = findS(r.sid); if (!f) return null; const x = c.sess[r.sid] || {}; return { s: f.s, seq: f.seq, o: x.o, subjectId: f.seq.subjectId, sub: f.seq.sub, classId: f.seq.classId }; }
  const o = c.byKey[r.key], s = db.adhoc[r.key]; if (!s) return null;
  return { s, o, subjectId: o ? o.subjectId : '', sub: o ? o.sub : '', classId: o ? o.classId : '' };
}
const curSess = () => sessInfo(ui.sess);
A.openSess = d => { ui.sess = d.sid ? { sid: d.sid } : { key: d.key }; if (ui.view !== 'seance') ui.back = ui.view; go('seance'); };
const totDur = s => s.steps.reduce((a, p) => a + (+p.dur || 0), 0);
const remDate = (r, o) => r.date || (o ? addD(o.date, -(+r.days || 0)) : '');       // anciens rappels « x jours avant » : convertis en date
V.seance = () => {
  const k = curSess(); if (!k) return `<div class="sheet"><p>Cette séance n’existe plus.</p><button class="btn" data-a="go" data-v="prog">Retour à la programmation</button></div>`;
  const { s, seq, o } = k, slot = o ? mins(o.end) - mins(o.start) : 0, cs = cols();
  const ta = (f, l, rows = 2, ph = '') => `<label class="fld"><span>${l}</span><textarea rows="${rows}" data-c="sf" data-f="${f}" placeholder="${ph}">${esc(s[f])}</textarea></label>`;
  const st = (p, i, f, l) => `<div><label>${esc(l)}<textarea rows="2" data-c="st" data-i="${i}" data-f="${f}">${esc(p[f])}</textarea></label></div>`;
  return `<div class="head"><h1>Fiche de préparation</h1><span class="sp"></span><div class="row np"><button class="btn" data-a="go" data-v="${ui.back}">‹ Retour</button><button class="btn" data-a="ai">Rédiger avec l’IA</button><button class="btn" data-a="printFiche">Imprimer la fiche</button></div></div>
  <div class="sheet"><div class="mg">${o ? fD(o.date, { day: 'numeric', month: 'short' }) + '<br>' + hm(o.start) : ''}</div>
   <p style="margin:0"><span class="dot" style="background:${subj(k.subjectId).color}"></span><b>${esc(subj(k.subjectId).name)}</b>${k.sub ? ' › ' + esc(k.sub) : ''} ${cbadge(k.classId)} ${seq ? `<span class="muted">, bloc « ${esc(seq.title)} », séance ${seq.sessions.indexOf(s) + 1} sur ${seq.sessions.length}</span>` : '<span class="chip">hors programmation</span>'}</p>
   <p class="small muted">${o ? 'Prévue le ' + esc(fD(o.date)) + ', ' + hm(o.start) + ' à ' + hm(o.end) + ' (' + slot + ' min)' : s.skip ? 'Séance abandonnée' : 'Pas de créneau disponible avant la fin de l’année'}</p>
   <label class="fld"><span>Titre</span><input data-c="sf" data-f="title" value="${esc(s.title)}"></label>
   ${ta('obj', 'Objectif de la séance', 2)}${ta('comp', 'Compétences travaillées', 2)}${ta('mat', 'Matériel', 2)}</div>
  <div class="sheet"><h2>Déroulé</h2><p class="small muted">Durée totale des étapes : <b id="tot">${totDur(s)}</b> min${slot ? ' pour un créneau de ' + slot + ' min' : ''}. Les rubriques se choisissent et se renomment dans <button class="lnk" data-a="go" data-v="reglages">Réglages › Fiche de préparation</button>.</p>
   ${s.steps.map((p, i) => `<div class="step"><div><label>Durée (min)<input type="number" min="0" data-c="st" data-i="${i}" data-f="dur" value="${esc(p.dur)}" style="width:100%"></label></div>${cs.map(c => st(p, i, c.k, c.l)).join('')}
     <div class="x np">${i ? `<button class="btn sm" data-a="mvStep" data-i="${i}">Monter</button> ` : ''}<button class="btn sm danger" data-a="delStep" data-i="${i}">Retirer l’étape</button></div></div>`).join('')}
   <p class="np"><button class="btn" data-a="addStep">Ajouter une étape</button></p></div>
  <div class="sheet"><h2>Rappels</h2><p class="small muted">Chaque rappel s’inscrit dans l’agenda à la date que vous choisissez.</p>
   ${s.rem.map((r, i) => { const rd = remDate(r, o); return `<div class="row" style="margin:6px 0"><select data-c="rm" data-i="${i}" data-f="type" aria-label="Type de rappel">${Object.entries(REM).map(([a, [ic, l]]) => `<option value="${a}" ${r.type === a ? 'selected' : ''}>${ic} ${l}</option>`).join('')}</select><input data-c="rm" data-i="${i}" data-f="text" value="${esc(r.text)}" placeholder="Quoi ?" style="flex:1;min-width:120px" aria-label="Détail"><input type="date" data-c="rm" data-i="${i}" data-f="date" value="${esc(rd)}" aria-label="Date du rappel">${o && rd > o.date ? '<span class="chip red">après la séance</span>' : ''}<button class="btn sm danger np" data-a="delRem" data-i="${i}" aria-label="Retirer le rappel">✕</button></div>`; }).join('')}
   <p class="np"><button class="btn" data-a="addRem">Ajouter un rappel</button></p></div>
  <div class="sheet">${ta('bilan', 'Bilan après la séance', 2, 'Ce qui est acquis, ce qui est à reprendre…')}
   ${seq ? `<label class="fld" style="max-width:260px"><span>Date choisie : ne pas faire avant le (facultatif)</span><input type="date" data-c="sf" data-f="pin" data-r="1" value="${esc(s.pin || '')}"></label>
   <label class="chk"><input type="checkbox" data-c="sf" data-f="merge" ${s.merge ? 'checked' : ''}> Compactée : partage le créneau de la séance précédente</label><label class="chk"><input type="checkbox" data-c="sf" data-f="skip" ${s.skip ? 'checked' : ''}> Séance abandonnée (elle n’occupe plus de créneau)</label>` : ''}
   <p class="np row"><button class="btn" data-a="exportSess">Exporter pour réutiliser</button>${seq ? '<button class="btn" data-a="dupSess">Dupliquer</button>' : ''}<button class="btn danger" data-a="delSess">Supprimer la séance</button></p></div>`;
};
C.sf = (d, el) => { const s = curSess().s; if (el.type === 'checkbox') { s[d.f] = el.checked; done(); } else { s[d.f] = el.value; if (d.r) done(); else saveSoon(); } };
C.st = (d, el) => { const s = curSess().s; s.steps[d.i][d.f] = el.value; if (d.f === 'dur') $('#tot').textContent = totDur(s); saveSoon(); };
C.rm = (d, el) => { curSess().s.rem[d.i][d.f] = el.value; saveSoon(); };
A.addStep = () => { curSess().s.steps.push({ dur: 10 }); done(); };
A.delStep = d => { curSess().s.steps.splice(d.i, 1); done(); };
A.mvStep = d => { const st = curSess().s.steps, i = +d.i;[st[i - 1], st[i]] = [st[i], st[i - 1]]; done(); };
A.addRem = () => { const k = curSess(); k.s.rem.push({ id: uid(), type: 'imp', text: '', date: k.o ? addD(k.o.date, -1) : TODAY() }); done(); };
A.delRem = d => { curSess().s.rem.splice(d.i, 1); done(); };
A.delSess = () => { if (!confirm('Supprimer cette séance ?')) return; const k = curSess(); if (k.seq) remove(k.seq.sessions, k.s.id); else delete db.adhoc[ui.sess.key]; ui.sess = null; save(); go(ui.back); };
// Copie « propre » d'une séance, pour la réutiliser : sans date, sans bilan, sans rappels datés.
function cleanSess(s) { const n = JSON.parse(JSON.stringify(s)); n.id = uid(); n.merge = n.skip = false; n.bilan = ''; n.pin = ''; n.rem = (n.rem || []).map(r => ({ id: uid(), type: r.type, text: r.text, date: '' })); n.steps ??= []; return n; }
A.dupSess = () => { const k = curSess(), n = cleanSess(k.s); n.title += ' (copie)'; k.seq.sessions.splice(k.seq.sessions.indexOf(k.s) + 1, 0, n); ui.sess = { sid: n.id }; done(); toast('Séance dupliquée, placée juste après.'); };
A.exportSess = () => { const k = curSess(); saveFile('seance-' + k.s.title.replace(/[^\p{L}\d]+/gu, '-').slice(0, 40) + '.json', JSON.stringify({ app: 'cahier-classe', type: 'seance', session: cleanSess(k.s) })); };
// La fiche de préparation imprimée : le détail complet de la séance.
function ficheHTML(k) {
  const { s, o, seq } = k, rem = s.rem.filter(r => r.text || remDate(r, o));
  return docHead('Fiche de préparation') + `<h2>${esc(subj(k.subjectId).name)}${k.sub ? ' › ' + esc(k.sub) : ''} : ${esc(s.title)}</h2>
  <p>${cls(k.classId) ? esc(cls(k.classId).name + (cls(k.classId).level ? ' (' + cls(k.classId).level + ')' : '')) : ''}${o ? ', ' + esc(fD(o.date)) + ', ' + hm(o.start) + ' à ' + hm(o.end) : ''}${seq ? ', bloc « ' + esc(seq.title) + ' », séance ' + (seq.sessions.indexOf(s) + 1) + '/' + seq.sessions.length : ''}</p>
  <p><b>Objectif :</b> ${nl(s.obj)}</p><p><b>Compétences :</b> ${nl(s.comp)}</p><p><b>Matériel :</b> ${nl(s.mat)}</p><h2>Déroulé</h2>${stepsTable(s) || '<div class="blank"></div>'}
  ${rem.length ? `<h2>À prévoir</h2><ul>${rem.map(r => `<li>${REM[r.type][1]} : ${esc(r.text)}${remDate(r, o) ? ' (' + esc(fS(remDate(r, o))) + ')' : ''}</li>`).join('')}</ul>` : ''}<h2>Bilan</h2>${s.bilan ? `<p>${nl(s.bilan)}</p>` : '<div class="blank"></div>'}`;
}
A.printFiche = () => printDoc(ficheHTML(curSess()));

// Option IA : rédige la fiche, avec les rubriques choisies dans les réglages. Deux façons : en un clic avec
// une clé API, ou en copiant la demande dans une IA de son choix puis en collant la réponse.
function aiPrompt(extra) {
  const k = curSess(), { s, seq, o } = k, c = cls(k.classId), dur = o ? mins(o.end) - mins(o.start) : 45, cs = cols();
  return `Tu es conseiller pédagogique pour l'école primaire en France. Rédige la fiche de préparation de cette séance, conforme aux programmes en vigueur.
Niveau : ${(seq && seq.level) || (c && c.level) || 'non précisé'}
Matière : ${subj(k.subjectId).name}${k.sub ? ' > ' + k.sub : ''}
${seq ? `Séquence : ${seq.title} (séance ${seq.sessions.indexOf(s) + 1} sur ${seq.sessions.length} ; séances de la séquence : ${seq.sessions.map(x => x.title).join(' / ')})\n` : ''}Titre de la séance : ${s.title}
Objectif déjà noté : ${s.obj || 'à proposer'}
Durée : ${dur} minutes
${extra ? 'Précisions de l’enseignant : ' + extra + '\n' : ''}
Réponds uniquement par un objet JSON, sans aucun texte autour, de cette forme exacte :
{"objectif":"","competences":"","materiel":"","etapes":[{"duree":10,${cs.map(x => `"${x.k}":""`).join(',')}}],"rappels":[{"type":"imp","texte":"","joursAvant":1}]}
"etapes" : 3 à 6 étapes dont les durées totalisent ${dur} minutes. Sens des rubriques de chaque étape : ${cs.map(x => `"${x.k}" = ${x.l}`).join(' ; ')}.
"rappels" : seulement ce qui est utile ; "type" vaut imp (impression), mat (matériel), mot (mot aux parents) ou equ (équipement des élèves).`;
}
A.ai = () => showModal(`<div class="dlg"><h2>Rédiger la fiche avec l’IA</h2>
  <label class="fld"><span>Précisions (facultatif) : niveau réel des élèves, contraintes, ce que vous voulez…</span><textarea id="aiextra" rows="2"></textarea></label>
  ${db.settings.apiKey ? '<p><button class="btn pri" data-a="aiRun">Rédiger maintenant</button></p>' : '<p class="small muted">Pour rédiger en un clic, ajoutez une clé API Anthropic dans les réglages. Sans clé, utilisez la méthode ci-dessous.</p>'}
  <hr><p class="small"><b>Sans clé :</b> copiez la demande, collez-la dans l’IA de votre choix, puis collez sa réponse ici.</p>
  <p><button class="btn" data-a="aiCopy">Copier la demande</button></p>
  <textarea id="aipaste" rows="4" placeholder="Collez ici la réponse de l’IA"></textarea>
  <div class="row end"><span class="sp"></span><button class="btn" data-a="closeModal">Fermer</button><button class="btn pri" data-a="aiPaste">Remplir la fiche</button></div></div>`);
A.aiCopy = async () => { const t = aiPrompt($('#aiextra').value); try { await navigator.clipboard.writeText(t); toast('Demande copiée.'); } catch (e) { $('#aipaste').value = t; toast('Copie impossible : la demande est affichée dans la zone de texte.'); } };
A.aiPaste = () => applyAI($('#aipaste').value);
A.aiRun = async (d, el) => {
  el.disabled = true; el.textContent = 'Rédaction en cours…';
  try {
    const r = await fetch('https://api.anthropic.com/v1/messages', { method: 'POST', headers: { 'content-type': 'application/json', 'x-api-key': db.settings.apiKey, 'anthropic-version': '2023-06-01', 'anthropic-dangerous-direct-browser-access': 'true' }, body: JSON.stringify({ model: db.settings.model, max_tokens: 3000, messages: [{ role: 'user', content: aiPrompt($('#aiextra').value) }] }) });
    const j = await r.json(); if (!r.ok) throw new Error((j.error && j.error.message) || 'erreur ' + r.status);
    applyAI(j.content.map(b => b.text || '').join(''));
  } catch (e) { toast('L’IA n’a pas pu répondre : ' + e.message); el.disabled = false; el.textContent = 'Rédiger maintenant'; }
};
function applyAI(text) {
  let j; try { j = JSON.parse(text.slice(text.indexOf('{'), text.lastIndexOf('}') + 1)); } catch (e) { toast('Réponse illisible : il faut le texte complet entre accolades { }.'); return; }
  const k = curSess(), s = k.s;
  if (s.steps.length && !confirm('Remplacer le déroulé déjà écrit ?')) return;
  s.obj = j.objectif || s.obj; s.comp = j.competences || s.comp; s.mat = j.materiel || s.mat;
  s.steps = (j.etapes || []).map(e => { const p = { dur: +e.duree || 0 }; cols().forEach(c => p[c.k] = e[c.k] || ''); return p; });
  (j.rappels || []).forEach(r => { if (REM[r.type]) s.rem.push({ id: uid(), type: r.type, text: r.texte || '', date: k.o ? addD(k.o.date, -(+r.joursAvant || 0)) : '' }); });
  closeModal(); done(); toast('Fiche remplie. Relisez et ajustez : c’est une proposition.');
}

// ---------- Emploi du temps ----------
// Deux vues : la semaine réelle (avec les séances calculées) et le modèle (les créneaux fixes).
// Le modèle peut avoir plusieurs versions, chacune valable à partir d'une date (piscine en période 3…).
// Dans la semaine réelle, chaque créneau peut être modifié pour ce jour-là seulement : remplacé
// (le motif s'affiche à sa place), déplacé à une autre heure ou un autre jour, ou ajouté ponctuellement.
// opt.fold : des plages horaires à resserrer (le temps de classe, dans l'agenda hors classe) pour gagner de la place.
// Chaque plage est [début, fin, hauteur en pixels] ; sans hauteur, elle est repliée en une simple bande.
// opt.shade : des zones hachurées dans une colonne ({ col, a, b }), pour montrer « en classe » là où la plage reste ouverte.
const FOLD_H = 24;                                       // hauteur d'une plage repliée, en pixels
function grid(cols, blocks, addable, opt = {}) {
  const P = 1.25; let lo = opt.lo ?? 540, hi = opt.hi ?? 960;
  blocks.forEach(b => { lo = Math.min(lo, mins(b.start)); hi = Math.max(hi, mins(b.end)); });
  lo = Math.floor(lo / 60) * 60; hi = Math.ceil(hi / 60) * 60;
  const F = (opt.fold || []).filter(([a, b]) => a >= lo && b <= hi).sort((x, y) => x[0] - y[0]);
  // Y : à quelle hauteur se trouve une heure donnée (en minutes), en tenant compte des plages repliées.
  const fh = f => f[2] ?? FOLD_H;
  const Y = m => { let cur = lo, y = 0; for (const f of F) { const [a, b] = f; if (m <= a) break; y += (a - cur) * P; if (m < b) return y + fh(f) * (m - a) / (b - a); y += fh(f); cur = b; } return y + (m - cur) * P; };
  ui.gmap = { lo, P, F };
  cols.forEach((c, ci) => {                             // créneaux simultanés (plusieurs classes) : côte à côte
    const bs = blocks.filter(b => b.col === ci).sort((a, b) => mins(a.start) - mins(b.start)), ends = [];
    bs.forEach(b => { let l = ends.findIndex(e => e <= mins(b.start)); if (l < 0) l = ends.length; ends[l] = mins(b.end); b.lane = l; });
    bs.forEach(b => b.lanes = ends.length);
  });
  const W = `(100% - 40px) / ${cols.length || 1}`;
  let h = `<div class="gridw"><div class="grid" style="height:${Y(hi) + 40}px">`;
  cols.forEach((c, i) => h += `<div class="col ${c.off ? 'off' : ''}" style="left:calc(40px + ${W} * ${i});width:calc(${W})" ${addable ? `data-a="${addable}" data-day="${c.day}" data-lo="${lo}" data-p="${P}"` : ''}><div class="colh">${esc(c.label)}</div>${c.off ? `<div class="offl">${esc(c.off)}</div>` : ''}</div>`);
  for (let m = lo; m <= hi; m += 60) if (!F.some(f => fh(f) === FOLD_H && m > f[0] && m < f[1])) h += `<div class="hr" style="top:${34 + Y(m)}px"><span>${m / 60} h</span></div>`;
  const hh = m => hm(toT(m)).replace(/^0/, '');
  F.filter(f => fh(f) === FOLD_H).forEach(([a, b]) => h += `<div class="foldb" style="top:${34 + Y(a)}px;height:${FOLD_H}px"><span>${esc(opt.foldLabel || 'Replié')}, ${hh(a)} à ${hh(b)}</span></div>`);
  (opt.shade || []).forEach(s => h += `<div class="foldc" style="left:calc(40px + ${W} * ${s.col});width:calc(${W});top:${34 + Y(s.a)}px;height:${Y(s.b) - Y(s.a)}px"><span>${esc(opt.foldLabel || '')}</span></div>`);
  blocks.forEach(b => h += `<button class="blk ${b.cls || ''}" style="--c:${b.color};left:calc(40px + ${W} * ${b.col} + (${W} - 4px) * ${b.lane} / ${b.lanes} + 2px);width:calc((${W} - 4px) / ${b.lanes} - 2px);top:${35 + Y(mins(b.start))}px;height:${Math.max(14, Y(mins(b.end)) - Y(mins(b.start)) - 2)}px" ${b.attrs}>${b.html}</button>`);
  return h + '</div></div>';
}
const clickT = (d, el, e) => {
  if (!d.lo) return 510;
  const { lo, P, F } = ui.gmap; let y = e.clientY - el.getBoundingClientRect().top - 34, cur = lo, m = null;
  for (const [a, b, fh = FOLD_H] of F) { const seg = (a - cur) * P; if (y <= seg) { m = cur + y / P; break; } y -= seg; if (y <= fh) { m = fh === FOLD_H ? b : a + y / fh * (b - a); break; } y -= fh; cur = b; }
  if (m == null) m = cur + y / P;
  return Math.max(lo, Math.round(m / 15) * 15);
};
const cmark = id => { const c = cls(id); return !c || db.classes.length < 2 ? '' : db.settings.mark === 'icon' ? esc(c.icon || '') + ' ' : `<i class="dot" style="background:${esc(c.color)};border-radius:50%;margin-right:3px"></i>`; };
V.edt = () => {
  if (!db.timetables.length) db.timetables.push({ id: uid(), from: db.settings.start, label: 'Emploi du temps de base', slots: [] });
  const c = ctx(), vc = visCls(), days = [...db.settings.days].sort(), t = TODAY();
  const tabs = `<div class="row np"><button class="btn ${ui.edt === 'week' ? 'pri' : ''}" data-a="edtMode" data-m="week">Semaine</button><button class="btn ${ui.edt === 'model' ? 'pri' : ''}" data-a="edtMode" data-m="model">Modifier les créneaux fixes</button></div>`;
  if (ui.edt === 'week') {
    const cols = days.map(d => { const date = addD(ui.week, d - 1); return { date, label: fD(date, { weekday: 'short', day: 'numeric', month: 'short' }), off: c.why(date) }; });
    const blocks = cols.flatMap((col, i) => (c.byDate[col.date] || []).filter(o => vc.includes(o.classId)).map(o => {
      const q = o.hostQ || o.q, sb = subj(q.split('|')[1]), ss = c.occSess[o.key] || [], ad = db.adhoc[o.key], lost = o.ov && o.ov.t === 'lost';
      return { col: i, start: o.start, end: o.end, color: sb.color, cls: lost ? 'lost' : o.date < t ? 'past' : '', attrs: `data-a="occMenu" data-key="${o.key}"`,
        html: lost ? `<b>${esc(o.ov.why || 'Non fait')}</b>à la place de ${esc(sb.name)}` : `<b>${cmark(o.classId)}${esc(sb.name)}${o.from ? ' ↪' : ''}${o.extra ? ' ＋' : ''}</b>${esc(q.split('|')[2])}${q.split('|')[2] ? '<br>' : ''}${esc(ss.map(s => s.title).join(' + ') || (ad ? ad.title : ''))}` };
    }));
    return `<div class="head"><h1>Emploi du temps</h1><span class="sp"></span>${tabs}</div>
     <div class="row" style="margin-bottom:10px"><button class="btn np" data-a="week" data-n="-7" aria-label="Semaine précédente">‹</button><b>Semaine du ${esc(fD(ui.week, { day: 'numeric', month: 'long', year: 'numeric' }))}</b><button class="btn np" data-a="week" data-n="7" aria-label="Semaine suivante">›</button><button class="btn np" data-a="week" data-n="0">Cette semaine</button><span class="sp"></span><button class="btn np" data-a="addExtra">Ajouter un créneau ponctuel</button></div>
     ${grid(cols, blocks)}<p class="small muted np">Touchez un créneau pour le modifier ce jour-là seulement : le <b>remplacer</b> (sortie, intervenant… le motif s’affiche à sa place et la séance se décale), le <b>déplacer</b> à une autre heure ou un autre jour, ou l’ouvrir dans le cahier journal. ↪ = créneau déplacé, ＋ = créneau ponctuel.</p>`;
  }
  const tts = [...db.timetables].sort((a, b) => a.from < b.from ? -1 : 1), tt = tts.find(x => x.id === ui.tt) || c.ttFor(t) || tts[0]; ui.tt = tt.id;
  const cols = days.map(d => ({ day: d, label: DAYS[d] }));
  const blocks = tt.slots.filter(s => vc.includes(s.classId) && days.includes(s.day)).map(s => ({ col: days.indexOf(s.day), start: s.start, end: s.end, color: subj(s.subjectId).color, attrs: `data-a="editSlot" data-id="${s.id}"`, html: `<b>${cmark(s.classId)}${esc(subj(s.subjectId).name)}</b>${esc(s.sub || '')}<br>${hm(s.start)}-${hm(s.end)}` }));
  return `<div class="head"><h1>Emploi du temps</h1><span class="sp"></span>${tabs}</div>
   <div class="row np" style="margin-bottom:10px"><label>Version <select data-c="ttSel">${tts.map(x => `<option value="${x.id}" ${x.id === tt.id ? 'selected' : ''}>${esc(x.label)}, à partir du ${fD(x.from, { day: 'numeric', month: 'short', year: 'numeric' })}</option>`).join('')}</select></label>
   <button class="btn" data-a="editTT">Renommer, changer la date</button><button class="btn" data-a="newTT">Nouvelle version à partir d’une date</button><button class="btn pri" data-a="addSlot" data-day="${days[0]}">Ajouter un créneau</button></div>
   ${grid(cols, blocks, 'addSlot')}
   <p class="small muted np">Touchez une colonne pour y ajouter un créneau, ou un créneau pour le modifier. Dès qu’un créneau change, toutes les séances de la programmation se replacent d’elles-mêmes. Pour un changement durable (piscine, décloisonnement), créez une nouvelle version valable à partir de la date voulue : les semaines d’avant gardent l’ancienne. Pour un changement d’un seul jour, passez par la vue Semaine.</p>`;
};
A.edtMode = d => { ui.edt = d.m; render(); };
A.week = d => { ui.week = +d.n ? addD(ui.week, +d.n) : monday(TODAY()); render(); };
C.ttSel = (d, el) => { ui.tt = el.value; render(); };
const curTT = () => db.timetables.find(x => x.id === ui.tt);
function slotForm(s, isNew) {
  const tt = curTT();
  openForm({ title: isNew ? 'Nouveau créneau' : 'Modifier le créneau', values: { ...s, what: s.subjectId + '|' + (s.sub || '') },
    fields: [{ k: 'day', label: 'Jour', type: 'select', options: [...db.settings.days].sort().map(d => [d, DAYS[d]]) }, { k: 'start', label: 'Début', type: 'time', req: 1 }, { k: 'end', label: 'Fin', type: 'time', req: 1 },
    { k: 'what', label: 'Matière', type: 'select', options: whatOpts() }, ...(db.classes.length > 1 ? [{ k: 'classId', label: 'Classe', type: 'select', options: clsOpts() }] : [])],
    onSave: v => { if (v.end <= v.start) { toast('L’heure de fin doit être après le début.'); return false; } const [subjectId, sub] = v.what.split('|'); const n = { day: +v.day, start: v.start, end: v.end, subjectId, sub, classId: v.classId || s.classId }; if (isNew) tt.slots.push({ id: uid(), ...n }); else Object.assign(tt.slots.find(x => x.id === s.id), n); },
    onDelete: isNew ? null : () => remove(tt.slots, s.id) });
}
A.addSlot = (d, el, e) => { const start = clickT(d, el, e); slotForm({ day: +d.day, start: toT(start), end: toT(start + 45), subjectId: (db.subjects[0] || {}).id, sub: '', classId: defCls() }, true); };
A.editSlot = d => slotForm(curTT().slots.find(x => x.id === d.id), false);
A.newTT = () => openForm({ title: 'Nouvelle version de l’emploi du temps', intro: '<p class="small">Elle reprend les créneaux de la version affichée. Vous les modifierez ensuite.</p>', values: { from: TODAY(), label: '' },
  fields: [{ k: 'label', label: 'Nom (ex. Période 3, piscine)', req: 1 }, { k: 'from', label: 'Valable à partir du', type: 'date', req: 1 }],
  onSave: v => { const n = { id: uid(), label: v.label, from: v.from, slots: JSON.parse(JSON.stringify(curTT().slots)) }; db.timetables.push(n); ui.tt = n.id; } });
A.editTT = () => { const tt = curTT(); openForm({ title: 'Version de l’emploi du temps', values: tt, fields: [{ k: 'label', label: 'Nom', req: 1 }, { k: 'from', label: 'Valable à partir du', type: 'date', req: 1 }], onSave: v => Object.assign(tt, v), onDelete: db.timetables.length > 1 ? () => { remove(db.timetables, tt.id); ui.tt = null; } : null }); };

// Modifier un créneau pour un seul jour.
A.occMenu = d => {
  const c = ctx(), o = c.byKey[d.key]; if (!o) return;
  const q = o.hostQ || o.q, ss = c.occSess[o.key] || [], lost = o.ov && o.ov.t === 'lost';
  showModal(`<div class="dlg"><h2>${esc(qname(q))}</h2><p>${esc(fD(o.date))}, ${hm(o.start)} à ${hm(o.end)}${ss.length ? '<br>« ' + esc(ss.map(s => s.title).join(' + ')) + ' »' : ''}${o.from ? `<br><span class="chip">déplacé du ${esc(fS(o.from.date))} ${hm(o.from.start)}</span>` : ''}</p>
   <p><button class="btn" data-a="toJournal" data-d="${o.date}">Ouvrir dans le cahier journal</button></p>
   ${lost ? `<p class="warn">${esc(o.ov.why || 'Créneau non fait')}</p><p><button class="btn pri" data-a="restoreOcc" data-key="${o.key}">Rétablir ce créneau</button></p>` : `
   <hr><h3>Remplacer ce jour-là</h3><p class="small muted">Sortie, intervenant, photo de classe, absence… Ce que vous écrivez s’affiche dans l’emploi du temps à la place de la séance.</p>
   <div class="row"><input id="occWhy" placeholder="Ce qui a lieu à la place" style="flex:1;min-width:150px"><button class="btn pri" data-a="occReplace" data-key="${o.key}">Remplacer</button></div>
   <hr><h3>Déplacer ce jour-là</h3><p class="small muted">La séance garde sa place dans la programmation, seul ce créneau change de moment.</p>
   <div class="row"><input type="date" id="mvD" value="${o.date}" aria-label="Nouveau jour"><input type="time" id="mvS" value="${o.start}" aria-label="Début"><input type="time" id="mvE" value="${o.end}" aria-label="Fin"><button class="btn pri" data-a="occMove" data-key="${o.key}">Déplacer</button></div>
   ${o.from ? `<p><button class="btn sm" data-a="restoreOcc" data-key="${o.key}">Remettre à sa place d’origine</button></p>` : ''}
   ${o.extra ? `<hr><p><button class="btn danger" data-a="delExtra" data-id="${o.extra}" data-key="${o.key}">Supprimer ce créneau ponctuel</button></p>` : ''}`}
   <div class="row end"><span class="sp"></span><button class="btn" data-a="closeModal">Fermer</button></div></div>`);
};
A.occReplace = d => { const why = $('#occWhy').value.trim(); if (!why) { toast('Écrivez ce qui a lieu à la place : c’est ce qui s’affichera dans l’emploi du temps.'); return; } closeModal(); A.missed({ key: d.key, why }); };
A.occMove = d => {
  const date = $('#mvD').value, start = $('#mvS').value, end = $('#mvE').value;
  if (!date || !start || !end || end <= start) { toast('Indiquez le jour, puis une heure de fin après le début.'); return; }
  if (!ctx().school(date) && !confirm('Ce jour n’est pas un jour de classe. Déplacer quand même ?')) return;
  db.ov[d.key] = { t: 'mv', date, start, end }; closeModal(); done(); toast('Créneau déplacé au ' + fS(date) + ' à ' + hm(start) + '.');
};
A.addExtra = () => openForm({ title: 'Créneau ponctuel', intro: '<p class="small">Un créneau en plus, pour un seul jour. La matière avance d’une séance grâce à lui.</p>', values: { date: ui.view === 'journal' ? ui.date : ui.week, start: '15:00', end: '15:45', what: (db.subjects[0] || {}).id + '|', classId: defCls() },
  fields: [{ k: 'date', label: 'Jour', type: 'date', req: 1 }, { k: 'start', label: 'Début', type: 'time', req: 1 }, { k: 'end', label: 'Fin', type: 'time', req: 1 }, { k: 'what', label: 'Matière', type: 'select', options: whatOpts() }, ...(db.classes.length > 1 ? [{ k: 'classId', label: 'Classe', type: 'select', options: clsOpts() }] : [])],
  onSave: v => { if (v.end <= v.start) { toast('L’heure de fin doit être après le début.'); return false; } const [subjectId, sub] = v.what.split('|'); db.extra.push({ id: uid(), date: v.date, start: v.start, end: v.end, subjectId, sub, classId: v.classId || defCls() }); ui.week = monday(v.date); } });
A.delExtra = d => { remove(db.extra, d.id); restoreOcc(d.key); delete db.adhoc[d.key]; closeModal(); done(); };

// ---------- Programmation annuelle ----------
V.prog = () => {
  const c = ctx(), vc = visCls(), t = TODAY(), multi = db.sequences.length > 1;
  const seqs = db.sequences.filter(s => vc.includes(s.classId) && (!ui.lvl || !s.level || s.level === ui.lvl)), qs = [...new Set(seqs.map(qk))];
  const row = (s, i) => {
    const x = c.sess[s.id]; let st, chip;
    if (x.skip) { st = ''; chip = '<span class="chip">abandonnée</span>'; }
    else if (!x.o) { st = 'out'; chip = '<span class="chip red">hors année</span>'; }
    else { st = x.o.date < t ? 'done' : ''; chip = `<span class="small ${x.o.date === t ? 'warn' : 'muted'}">${x.o.date === t ? 'aujourd’hui' : esc(fS(x.o.date))}</span>${s.merge ? ' <span class="chip">compactée</span>' : ''}${s.pin ? ' <span class="chip" title="Date choisie à la main">📌</span>' : ''}${x.bo && x.bo.date !== x.o.date && x.o.date >= t ? ` <span class="chip yel" title="Date prévue au départ">prévue ${esc(fD(x.bo.date, { day: 'numeric', month: 'short' }))}</span>` : ''}`; }
    return `<li class="srow ${st}"><span class="n">${st === 'done' ? '✓' : i + 1}</span><span class="t"><button class="lnk" data-a="openSess" data-sid="${s.id}">${esc(s.title)}</button>${s.rem.length ? ' <span class="small muted">' + s.rem.map(r => REM[r.type][0]).join('') + '</span>' : ''}</span>${chip}
      <span class="np"><button class="btn sm" data-a="mvSess" data-sid="${s.id}" data-n="-1" aria-label="Monter">↑</button> <button class="btn sm" data-a="mvSess" data-sid="${s.id}" data-n="1" aria-label="Descendre">↓</button></span></li>`;
  };
  return `<div class="head"><h1>Programmation</h1><span class="sp"></span><div class="row np"><button class="btn pri" data-a="newSeq">Nouveau bloc de séances</button><button class="btn" data-a="planSeqs">Choisir les dates des blocs</button><button class="btn" data-a="importBloc">Importer un bloc ou une séance</button></div></div>
  ${qs.length ? '' : '<div class="sheet"><p>La programmation se fait en une fois : créez un bloc par séquence, listez ses séances dans l’ordre. Les dates se calculent d’après l’emploi du temps, et se recalculent à chaque imprévu.</p></div>'}
  ${qs.map(q => {
    const v = c.queues[q];
    return `<h2 style="margin:18px 0 8px"><span class="dot" style="background:${subj(q.split('|')[1]).color}"></span>${esc(qname(q))} ${v.late > 0 ? `<span class="chip yel">${v.late} séance${v.late > 1 ? 's' : ''} de retard</span>` : v.late < 0 ? `<span class="chip ok">${-v.late} d’avance</span>` : '<span class="chip ok">à jour</span>'} ${v.lost ? `<span class="chip red">${v.lost} hors année</span>` : ''}</h2>
    <div class="bar" style="margin-bottom:6px" title="${v.done} faites sur ${v.total}"><i style="width:${v.total ? v.done / v.total * 100 : 0}%;background:var(--ok)"></i></div>
    <p class="small muted">${v.done} séance${v.done > 1 ? 's' : ''} passée${v.done > 1 ? 's' : ''} sur ${v.total}, ${v.slots} créneaux dans l’année.${v.slots ? '' : ' <span class="warn">Aucun créneau de cette matière dans l’emploi du temps : ajoutez-en un pour que les dates se calculent.</span>'}${v.lost ? ' <span class="warn">Les séances « hors année » n’ont plus de créneau : montez celles à garder, ou compactez.</span>' : ''}</p>
    ${v.seqs.filter(s => seqs.includes(s)).map(seq => `<div class="sheet"><div class="mg">${seq.sessions.length} séance${seq.sessions.length > 1 ? 's' : ''}</div>
      <div class="row"><h3>${esc(seq.title)}</h3>${seq.level ? `<span class="chip">${esc(seq.level)}</span>` : ''}${cbadge(seq.classId)}${seq.start ? `<span class="small muted">pas avant le ${esc(fS(seq.start))}</span>` : ''}<span class="sp"></span>
      <span class="np row"><button class="btn sm" data-a="addSess" data-id="${seq.id}">Ajouter des séances</button><button class="btn sm" data-a="editSeq" data-id="${seq.id}">Modifier</button><button class="btn sm" data-a="exportSeq" data-id="${seq.id}">Exporter</button>${multi ? `<button class="btn sm" data-a="mvSeq" data-id="${seq.id}" data-n="-1" aria-label="Monter le bloc">↑</button><button class="btn sm" data-a="mvSeq" data-id="${seq.id}" data-n="1" aria-label="Descendre le bloc">↓</button>` : ''}</span></div>
      <ul class="plain">${seq.sessions.map(row).join('') || '<li class="muted">Aucune séance. Ajoutez-en.</li>'}</ul></div>`).join('')}`;
  }).join('')}`;
};
function seqForm(seq) {
  openForm({ title: seq ? 'Modifier le bloc' : 'Nouveau bloc de séances', values: seq ? { ...seq, what: seq.subjectId + '|' + (seq.sub || '') } : { classId: defCls(), level: ui.lvl },
    fields: [{ k: 'title', label: 'Titre (ex. La soustraction posée)', req: 1 }, { k: 'what', label: 'Matière', type: 'select', options: whatOpts() }, ...(db.classes.length > 1 ? [{ k: 'classId', label: 'Classe', type: 'select', options: clsOpts() }] : []),
    { k: 'level', label: 'Niveau (facultatif, utile en multi-niveaux)' }, { k: 'start', label: 'Ne pas commencer avant le (facultatif)', type: 'date' },
    ...(seq ? [] : [{ k: 'list', label: 'Titres des séances, un par ligne', type: 'textarea', rows: 6 }])],
    onSave: v => { const [subjectId, sub] = v.what.split('|'), n = { title: v.title, subjectId, sub, classId: v.classId || (seq ? seq.classId : defCls()), level: v.level, start: v.start }; if (seq) Object.assign(seq, n); else db.sequences.push({ id: uid(), ...n, sessions: v.list.split('\n').map(x => x.trim()).filter(Boolean).map(newSess) }); },
    onDelete: seq ? () => remove(db.sequences, seq.id) : null });
}
// Choisir la date de départ de chaque bloc, d'un coup : utile en début d'année pour replanifier les blocs repris.
A.planSeqs = () => {
  const c = ctx(), vc = visCls(), seqs = db.sequences.filter(s => vc.includes(s.classId));
  if (!seqs.length) { toast('Aucun bloc à planifier pour l’instant.'); return; }
  showModal(`<form class="dlg" id="planf" style="width:min(720px,100%)"><h2>Dates des blocs</h2><p class="small">Pour chaque bloc, choisissez la date à partir de laquelle il commence. Sans date, il démarre dès que la matière a un créneau libre, à la suite du bloc précédent. Pour fixer la date d’une séance précise, ouvrez sa fiche.</p>
   <div class="tw"><table><thead><tr><th>Bloc</th><th>Commence le</th><th>Dates calculées</th></tr></thead><tbody>${seqs.map(q => { const ds = q.sessions.map(s => c.sess[s.id] && c.sess[s.id].o).filter(Boolean); return `<tr><td><b>${esc(q.title)}</b><br><span class="small muted">${esc(qname(qk(q)))}, ${q.sessions.length} séances</span></td><td><input type="date" name="${q.id}" value="${esc(q.start || '')}" aria-label="Début de ${esc(q.title)}"></td><td class="small">${ds.length ? esc(fS(ds[0].date)) + ' → ' + esc(fS(ds[ds.length - 1].date)) : '<span class="warn">pas de créneau</span>'}${ds.length < q.sessions.filter(s => !s.skip).length ? ' <span class="chip red">déborde de l’année</span>' : ''}</td></tr>`; }).join('')}</tbody></table></div>
   <div class="row end"><span class="sp"></span><button type="button" class="btn" data-a="closeModal">Fermer</button><button type="button" class="btn pri" data-a="planSave">Enregistrer et recalculer</button></div></form>`);
};
A.planSave = () => { const f = $('#planf'); db.sequences.forEach(q => { if (f.elements[q.id]) q.start = f.elements[q.id].value; }); save(); render(); A.planSeqs(); toast('Dates recalculées.'); };
A.newSeq = () => seqForm(null);
A.editSeq = d => seqForm(db.sequences.find(s => s.id === d.id));
A.addSess = d => openForm({ title: 'Ajouter des séances', fields: [{ k: 'list', label: 'Titres, un par ligne', type: 'textarea', rows: 6 }], onSave: v => { db.sequences.find(s => s.id === d.id).sessions.push(...v.list.split('\n').map(x => x.trim()).filter(Boolean).map(newSess)); } });
A.mvSess = d => { const { s, seq } = findS(d.sid), i = seq.sessions.indexOf(s), j = i + +d.n; if (j < 0 || j >= seq.sessions.length) return;[seq.sessions[i], seq.sessions[j]] = [seq.sessions[j], seq.sessions[i]]; done(); };
A.mvSeq = d => { const a = db.sequences, i = a.findIndex(s => s.id === d.id), q = qk(a[i]); let j = i + +d.n; while (j >= 0 && j < a.length && qk(a[j]) !== q) j += +d.n; if (j < 0 || j >= a.length) return;[a[i], a[j]] = [a[j], a[i]]; done(); };
A.exportSeq = d => { const seq = db.sequences.find(s => s.id === d.id); saveFile('bloc-' + seq.title.replace(/[^\p{L}\d]+/gu, '-').slice(0, 40) + '.json', JSON.stringify({ app: 'cahier-classe', type: 'bloc', subject: subj(seq.subjectId).name, sub: seq.sub, title: seq.title, level: seq.level, sessions: seq.sessions.map(cleanSess) })); };
A.importBloc = () => pickFile(j => {
  if (j.app !== 'cahier-classe') { toast('Ce fichier ne vient pas de Cahier de classe.'); return; }
  if (j.type === 'bloc') {
    let sb = db.subjects.find(s => s.name === j.subject); if (!sb) { sb = { id: uid(), name: j.subject || 'Matière importée', color: '#59607c', subs: [] }; db.subjects.push(sb); }
    if (j.sub && !sb.subs.includes(j.sub)) sb.subs.push(j.sub);
    db.sequences.push({ id: uid(), title: j.title, subjectId: sb.id, sub: j.sub || '', classId: defCls(), level: j.level || '', start: '', sessions: j.sessions.map(cleanSess) }); done(); toast('Bloc « ' + j.title + ' » importé avec ' + j.sessions.length + ' séances.');
  } else if (j.type === 'seance') {
    if (!db.sequences.length) { toast('Créez d’abord un bloc pour y ranger la séance.'); return; }
    openForm({ title: 'Importer « ' + j.session.title + ' »', fields: [{ k: 'seq', label: 'Dans quel bloc ?', type: 'select', options: db.sequences.map(s => [s.id, qname(qk(s)) + ' : ' + s.title]) }], onSave: v => { db.sequences.find(s => s.id === v.seq).sessions.push(cleanSess(j.session)); } });
  } else toast('Ce fichier est une sauvegarde complète : restaurez-la depuis les réglages.');
});
