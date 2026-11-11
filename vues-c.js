'use strict';
// =====================================================================================
//  vues-c.js — ajouts : noter vite (une note pour un ou plusieurs élèves), mots aux parents,
//  lecture d'une séance, gestion de classe (plan, groupes, tirage au sort), devoirs.
// =====================================================================================

const nowT = () => new Date().toTimeString().slice(0, 5);
const norm = s => String(s || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();   // pour chercher sans se soucier des accents
const shuffle = a => { a = [...a]; for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1));[a[i], a[j]] = [a[j], a[i]]; } return a; };
const firstN = id => (stu(id) || {}).first || '?';
const isAbsent = (id, d = TODAY()) => { const h = new Date().getHours() < 12 ? 'M' : 'S'; return db.att[d + '|' + h + '|' + id] === 'A'; };

// ---------- Noter vite ----------
// On choisit d'abord le type (observation, incident, difficulté, comportement), on écrit, puis on coche
// les élèves concernés. Une recherche par prénom filtre la liste ; Entrée coche le premier élève trouvé.
// Si on ferme sans enregistrer, la saisie est gardée : elle revient au prochain appui sur un bouton « Noter ».
function quickBar(small) {
  const B = db.settings.behav;
  return `<div class="qn ${small ? 'sm' : ''}"><div class="row">${['observation', 'incident', 'difficulte'].map(t => `<button class="qbig" data-a="cmpOpen" data-t="${t}"><span>${NOTE[t][0]}</span>${NOTE[t][1]}</button>`).join('')}</div>
    <div class="row" style="margin-top:8px">${B.map(b => `<button class="qb ${b.pos ? 'pos' : 'neg'}" data-a="cmpOpen" data-t="comportement" data-b="${b.id}">${b.pos ? '＋ ' : ''}${esc(b.l)}</button>`).join('')}</div></div>`;
}
A.cmpOpen = d => {
  const u = ui.cmp;
  if (u && !u.saved && (u.text || u.ids.size)) { if (d.t) u.type = d.t; if (d.b) u.beh = d.b; toast('Saisie précédente retrouvée.'); }
  else ui.cmp = { type: d.t || 'observation', beh: d.b || '', sub: '', text: '', date: TODAY(), time: nowT(), ids: new Set(ui.view === 'eleve' && ui.stu ? [ui.stu] : []), when: false };
  showCmp();
};
function showCmp() {
  const u = ui.cmp, B = db.settings.behav, st = visStu(), ids = [...u.ids];
  let extra = '';
  if (u.type === 'comportement') extra = `<div class="row">${B.map(b => `<button type="button" class="qb ${b.pos ? 'pos' : 'neg'}" data-a="cmpSet" data-k="beh" data-v="${b.id}" aria-pressed="${u.beh === b.id}">${b.pos ? '＋ ' : ''}${esc(b.l)}</button>`).join('')}</div>`;
  if (u.type === 'incident') extra = `<div class="row">${INC.map(x => `<button type="button" class="qb" data-a="cmpSet" data-k="sub" data-v="${esc(x)}" aria-pressed="${u.sub === x}">${esc(x)}</button>`).join('')}</div>`;
  if (u.type === 'difficulte') {
    const c = ctx(), list = pastOccs(ids.length ? ids : st.map(s => s.id));
    if (u.occ === undefined) u.occ = (list[0] || {}).key || '';
    extra = `<label class="fld"><span>Séance où la difficulté est apparue (la plus récente est proposée)</span><select data-c="cmpOcc">${list.map(x => `<option value="${x.key}" ${u.occ === x.key ? 'selected' : ''}>${esc(occLabel(x, c))}</option>`).join('')}<option value="" ${u.occ === '' ? 'selected' : ''}>Pas de séance précise</option></select></label>`;
  }
  const ph = { observation: 'Travail, réussite, évaluation, remarque…', incident: 'Ce qui s’est passé, ce qui a été fait…', difficulte: 'Ce qui bloque : la retenue, la consigne, le son [ou]…', comportement: 'Détail (facultatif)' }[u.type];
  showModal(`<div class="dlg wide cmp"><div class="row"><h2>Noter</h2><span class="sp"></span>${Object.entries(NOTE).map(([k, [ic, l]]) => `<button type="button" class="qb" data-a="cmpSet" data-k="type" data-v="${k}" aria-pressed="${u.type === k}">${ic} ${l}</button>`).join('')}</div>
    ${extra}
    <textarea id="cmpTxt" data-c="cmpTxt" rows="${u.type === 'comportement' ? 2 : 3}" placeholder="${ph}" style="margin-top:8px">${esc(u.text)}</textarea>
    ${u.type === 'difficulte' ? '' : u.when ? `<div class="row small" style="margin-top:4px">Le <input type="date" data-c="cmpW" data-k="date" value="${u.date}" aria-label="Date"> à <input type="time" data-c="cmpW" data-k="time" value="${u.time}" aria-label="Heure"></div>` : `<p class="small muted" style="margin:4px 0">Le ${esc(fS(u.date))} à ${hm(u.time)}. <button type="button" class="lnk" data-a="cmpWhen">Changer</button></p>`}
    <div class="row" style="margin-top:10px"><h3>Élèves</h3><span class="small muted" id="cmpN">${u.ids.size} sélectionné${u.ids.size > 1 ? 's' : ''}</span><span class="sp"></span><button type="button" class="btn sm" data-a="cmpAll">Toute la classe</button><button type="button" class="btn sm" data-a="cmpNone">Aucun</button></div>
    <input id="cmpQ" data-c="cmpQ" placeholder="🔎 Tapez un prénom puis Entrée" autocomplete="off" style="width:100%;margin:6px 0">
    <div class="scs">${st.map(s => `<button type="button" class="sc" data-a="cmpStu" data-id="${s.id}" data-n="${esc(norm(s.first + ' ' + s.last))}" aria-pressed="${u.ids.has(s.id)}">${esc(s.first)} <small>${esc((s.last || '')[0] || '')}.</small></button>`).join('') || '<p class="muted">Ajoutez d’abord vos élèves.</p>'}</div>
    <div class="row end"><button type="button" class="btn" data-a="cmpCancel">Annuler</button><span class="sp"></span><button type="button" class="btn pri" data-a="cmpSave" id="cmpOk">Enregistrer</button></div></div>`);
  cmpCount();
}
function cmpCount() { const n = ui.cmp.ids.size, el = $('#cmpN'), ok = $('#cmpOk'); if (el) el.textContent = n + ' sélectionné' + (n > 1 ? 's' : ''); if (ok) ok.textContent = n > 1 ? 'Enregistrer pour ' + n + ' élèves' : 'Enregistrer'; }
const cmpKeep = () => { const t = $('#cmpTxt'); if (t) ui.cmp.text = t.value; };
A.cmpSet = d => { cmpKeep(); const u = ui.cmp; if (d.k === 'type' && u.type !== d.v) { u.occ = undefined; } u[d.k] = u[d.k] === d.v && d.k !== 'type' ? '' : d.v; showCmp(); };
A.cmpWhen = () => { cmpKeep(); ui.cmp.when = true; showCmp(); };
C.cmpW = (d, el) => { ui.cmp[d.k] = el.value; };
C.cmpTxt = (d, el) => { ui.cmp.text = el.value; };
C.cmpOcc = (d, el) => { ui.cmp.occ = el.value; };
C.cmpQ = (d, el) => { const q = norm(el.value).trim(); document.querySelectorAll('.scs .sc').forEach(b => b.hidden = !!q && !b.dataset.n.split(' ').some(w => w.startsWith(q)) && !b.dataset.n.startsWith(q)); };
A.cmpStu = (d, el) => { const u = ui.cmp; u.ids.has(d.id) ? u.ids.delete(d.id) : u.ids.add(d.id); el.setAttribute('aria-pressed', u.ids.has(d.id)); cmpCount(); };
A.cmpAll = () => { visStu().forEach(s => ui.cmp.ids.add(s.id)); document.querySelectorAll('.scs .sc').forEach(b => b.setAttribute('aria-pressed', 'true')); cmpCount(); };
A.cmpNone = () => { ui.cmp.ids.clear(); document.querySelectorAll('.scs .sc').forEach(b => b.setAttribute('aria-pressed', 'false')); cmpCount(); };
A.cmpCancel = () => { ui.cmp = null; closeModal(); };
document.addEventListener('keydown', e => {
  if (e.target.id !== 'cmpQ' || e.key !== 'Enter') return; e.preventDefault();
  const b = [...document.querySelectorAll('.scs .sc')].find(x => !x.hidden); if (!b) return;
  if (b.getAttribute('aria-pressed') !== 'true') b.click(); e.target.value = ''; C.cmpQ({}, e.target); e.target.focus();
});
A.cmpSave = () => {
  cmpKeep(); const u = ui.cmp, ids = [...u.ids], text = u.text.trim();
  if (!ids.length) { toast('Cochez au moins un élève.'); return; }
  if (u.type === 'comportement' && !u.beh) { toast('Choisissez le comportement.'); return; }
  if (u.type !== 'comportement' && !text && !(u.type === 'incident' && u.sub)) { toast('Écrivez la note.'); $('#cmpTxt').focus(); return; }
  const base = { type: u.type, text, date: u.date, time: u.time, sub: u.sub || '' };
  if (u.type === 'comportement') { const b = db.settings.behav.find(x => x.id === u.beh); Object.assign(base, { sub: b.l, pos: b.pos }); }
  if (u.type === 'difficulte') base.occ = u.occ || '';
  addNotes(ids, base); u.saved = true; ui.cmp = null; closeModal(); done();
  toast(NOTE[u.type][1] + ' enregistré' + (u.type === 'incident' || u.type === 'comportement' ? '' : 'e') + ' pour ' + whoTxt(ids) + '.');
  if (u.type === 'incident') motOffer(ids, base);
};

// ---------- Mots aux parents ----------
// Des modèles tout faits. On remplit quelques champs, le texte se rédige ; on peut le retoucher,
// le copier (pour l'ENT) ou l'imprimer. Les mots écrits sont gardés dans un historique.
const sign = () => '\n\n' + (db.settings.teacher || 'L’enseignant(e)') + (db.settings.school ? '\n' + db.settings.school : '');
const dLong = d => d ? fD(d, { weekday: 'long', day: 'numeric', month: 'long' }) : '…';
const MOTS = {
  incident: { l: '🚑 Incident', f: [['eleve', 'Élève'], ['date', 'Date', 'date'], ['heure', 'Heure', 'time'], ['nature', 'Nature', 'select', INC], ['faits', 'Ce qui s’est passé', 'textarea'], ['suite', 'Ce qui a été fait', 'textarea']],
    t: v => {
      const quand = `le ${dLong(v.date)}${v.heure ? ' vers ' + hm(v.heure) : ''}`, f = v.faits ? '\n\n' + v.faits.trim() : '', s = v.suite ? '\n\n' + v.suite.trim() : '';
      const intro = { Blessure: `Je vous informe que ${v.eleve} s’est blessé(e) ${quand}.`, Maladie: `Je vous informe que ${v.eleve} ne s’est pas senti(e) bien ${quand}.`, Dispute: `Je souhaite vous informer d’un différend dans lequel ${v.eleve} a été impliqué(e) ${quand}.`, 'Communication avec la famille': `Je souhaite échanger avec vous au sujet de ${v.eleve}.` }[v.nature] || `Je souhaite vous informer d’un événement concernant ${v.eleve}, ${quand}.`;
      const fin = { Blessure: '\n\nN’hésitez pas à surveiller l’évolution et à consulter si nécessaire.', Maladie: '\n\nMerci de le (la) garder au repos si son état le nécessite.', Dispute: '\n\nUne discussion a eu lieu avec les élèves concernés. Je reste disponible pour en parler avec vous.' }[v.nature] || '\n\nJe reste disponible pour en parler avec vous.';
      return `Madame, Monsieur,\n\n${intro}${f}${s}${fin}\n\nCordialement,${sign()}\n\nSignature des parents :`;
    } },
  sortie: { l: '🚌 Sortie scolaire', f: [['lieu', 'Lieu, activité'], ['date', 'Date', 'date'], ['depart', 'Départ de l’école', 'time'], ['retour', 'Retour à l’école', 'time'], ['transport', 'Transport (car, à pied, train…)'], ['cout', 'Participation demandée (vide : gratuite)'], ['prevoir', 'À prévoir (pique-nique, tenue, gourde…)', 'textarea'], ['accomp', 'Accompagnateurs recherchés (nombre, vide : aucun)'], ['rendre', 'Coupon à rendre avant le', 'date']],
    t: v => `Madame, Monsieur,\n\nNotre classe se rendra ${v.lieu ? 'à ' + v.lieu : 'en sortie'} le ${dLong(v.date)}${v.transport ? ', en ' + v.transport.replace(/^en /, '') : ''}.\nDépart de l’école : ${v.depart ? hm(v.depart) : '…'}. Retour prévu : ${v.retour ? hm(v.retour) : '…'}.${v.cout ? '\nUne participation de ' + v.cout + ' vous est demandée.' : '\nCette sortie est gratuite pour les familles.'}${v.prevoir ? '\n\nÀ prévoir :\n' + v.prevoir.split('\n').filter(Boolean).map(x => '- ' + x.trim()).join('\n') : ''}${v.accomp ? '\n\nNous recherchons ' + v.accomp + ' parent(s) accompagnateur(s). Merci de vous signaler sur le coupon.' : ''}\n\nMerci de retourner le coupon ci-dessous${v.rendre ? ' avant le ' + dLong(v.rendre) : ''}.\n\nCordialement,${sign()}\n\n✂ - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -\nJe soussigné(e) ………………………………, responsable de l’élève ………………………………,\n☐ autorise  ☐ n’autorise pas mon enfant à participer à la sortie du ${dLong(v.date)}.${v.accomp ? '\n☐ Je peux accompagner la sortie. Téléphone : ……………………' : ''}\nDate et signature :` },
  materiel: { l: '🎒 Matériel à apporter', f: [['quoi', 'Quoi', 'textarea'], ['quand', 'Pour le', 'date'], ['pourquoi', 'Pour quelle activité']],
    t: v => `Madame, Monsieur,\n\n${v.pourquoi ? 'Pour ' + v.pourquoi + ', merci' : 'Merci'} de fournir à votre enfant pour le ${dLong(v.quand)} :\n${(v.quoi || '').split('\n').filter(Boolean).map(x => '- ' + x.trim()).join('\n')}\n\nMerci de votre aide.${sign()}` },
  document: { l: '📄 Document à rendre', f: [['doc', 'Document'], ['avant', 'Avant le', 'date']],
    t: v => `Madame, Monsieur,\n\nSauf erreur de ma part, je n’ai pas encore reçu : ${v.doc || '…'}.\nMerci de le remettre dans le cartable de votre enfant avant le ${dLong(v.avant)}.\n\nCordialement,${sign()}` },
  reunion: { l: '👥 Réunion ou rendez-vous', f: [['objet', 'Objet (réunion de rentrée, bilan…)'], ['date', 'Date', 'date'], ['heure', 'Heure', 'time'], ['lieu', 'Lieu', '', 'dans la classe']],
    t: v => `Madame, Monsieur,\n\nJe vous invite ${v.objet ? 'à la ' + v.objet.replace(/^(la |une )/i, '') : 'à une rencontre'} le ${dLong(v.date)}${v.heure ? ' à ' + hm(v.heure) : ''}, ${v.lieu || 'dans la classe'}.\n\nMerci de me confirmer votre présence en complétant le coupon.\n\nCordialement,${sign()}\n\n✂ - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -\nParents de ……………………………… : ☐ seront présents  ☐ ne pourront pas venir\nSignature :` },
  libre: { l: '✉️ Mot libre', f: [['texte', 'Texte', 'textarea']], t: v => `Madame, Monsieur,\n\n${v.texte || ''}\n\nCordialement,${sign()}` }
};
function motsHTML() {
  const h = [...db.mots].sort((a, b) => b.date.localeCompare(a.date)).slice(0, 12);
  return `<div class="sheet"><h2>Mots aux parents</h2><p class="small muted">Choisissez un modèle : le texte se rédige, vous le retouchez, puis vous le copiez dans l’ENT ou l’imprimez.</p>
    <div class="row">${Object.entries(MOTS).map(([k, m]) => `<button class="btn" data-a="motOpen" data-m="${k}">${m.l}</button>`).join('')}</div>
    ${h.length ? `<h3 style="margin-top:12px">Derniers mots</h3><ul class="plain">${h.map(m => `<li><span class="small muted">${esc(fS(m.date))}</span><button class="lnk" data-a="motAgain" data-id="${m.id}" style="flex:1">${esc(m.title)}</button></li>`).join('')}</ul>` : ''}</div>`;
}
A.motOpen = d => { ui.mot = { m: d.m, v: d.v ? JSON.parse(d.v) : { date: TODAY() }, text: '' }; showMot(); };
A.motAgain = d => { const m = db.mots.find(x => x.id === d.id); ui.mot = { m: m.model, v: m.v || {}, text: m.text, id: m.id }; showMot(); };
function showMot() {
  const u = ui.mot, M = MOTS[u.m]; if (!u.text) u.text = M.t(u.v);
  const fld = ([k, l, type, opts]) => type === 'select' ? `<label class="fld"><span>${l}</span><select data-c="motF" data-k="${k}">${opts.map(o => `<option ${u.v[k] === o ? 'selected' : ''}>${esc(o)}</option>`).join('')}</select></label>`
    : type === 'textarea' ? `<label class="fld"><span>${l}</span><textarea rows="2" data-c="motF" data-k="${k}">${esc(u.v[k] || '')}</textarea></label>`
    : `<label class="fld"><span>${l}</span><input ${type ? `type="${type}"` : ''} data-c="motF" data-k="${k}" value="${esc(u.v[k] || '')}"></label>`;
  showModal(`<div class="dlg wide"><h2>${M.l}</h2><div class="cols" style="grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:12px">
    <div>${M.f.map(fld).join('')}</div>
    <div><label class="fld"><span>Le mot (modifiable)</span><textarea id="motTxt" rows="16" data-c="motT">${esc(u.text)}</textarea></label></div></div>
    <div class="row end"><button class="btn" data-a="closeModal">Fermer</button><span class="sp"></span><button class="btn" data-a="motCopy">Copier pour l’ENT</button><button class="btn pri" data-a="motPrint">Imprimer</button></div></div>`);
}
C.motF = (d, el) => { const u = ui.mot; u.v[d.k] = el.value; u.text = MOTS[u.m].t(u.v); const t = $('#motTxt'); if (t) t.value = u.text; };
C.motT = (d, el) => { ui.mot.text = el.value; };
function motKeep() {
  const u = ui.mot, title = MOTS[u.m].l.replace(/^\S+\s/, '') + (u.v.eleve ? ', ' + u.v.eleve : u.v.lieu ? ', ' + u.v.lieu : u.v.objet ? ', ' + u.v.objet : '');
  if (u.id) Object.assign(db.mots.find(x => x.id === u.id) || {}, { text: u.text, v: u.v, title }); else { u.id = uid(); db.mots.push({ id: u.id, date: TODAY(), model: u.m, v: u.v, text: u.text, title }); }
  save();
}
A.motCopy = async () => { motKeep(); try { await navigator.clipboard.writeText(ui.mot.text); toast('Mot copié : collez-le dans l’ENT.'); } catch (e) { const t = $('#motTxt'); t.select(); toast('Sélectionné : faites Copier.'); } };
A.motPrint = () => { motKeep(); const n = Math.max(1, +(prompt('Combien d’exemplaires ? (2 par page)', ui.mot.m === 'sortie' || ui.mot.m === 'materiel' ? db.students.length || 1 : 1)) || 1);
  printDoc(`<div class="mots">${Array.from({ length: n }, () => `<div class="mot">${nl(ui.mot.text)}</div>`).join('')}</div>`); };
// Après un incident : proposer le mot tout fait.
function motOffer(ids, base) {
  showModal(`<div class="dlg"><h2>Prévenir la famille ?</h2><p>Un mot tout fait est prêt pour ${esc(ids.length > 1 ? 'chaque famille (' + ids.length + ' élèves)' : 'la famille ' + deN(sname(stu(ids[0]))))}.</p>
    <div class="row">${ids.map(id => `<button class="btn" data-a="motOpen" data-m="incident" data-v="${esc(JSON.stringify({ eleve: sname(stu(id)), date: base.date, heure: base.time, nature: base.sub || 'Autre', faits: base.text }))}">✉️ ${esc(sname(stu(id)))}</button>`).join('')}</div>
    <div class="row end"><span class="sp"></span><button class="btn" data-a="closeModal">Pas maintenant</button></div></div>`);
}

// ---------- Lire une séance ----------
// Depuis l'emploi du temps, la programmation ou l'accueil : la séance s'ouvre en lecture.
// « Modifier » passe à la rédaction.
const stepsOrTxt = s => stepsTable(s) || (s.txt ? `<div class="txtd">${nl(s.txt)}</div>` : '');
A.readSess = d => {
  const ref = d.sid ? { sid: d.sid } : { key: d.key }, keep = ui.sess; ui.sess = ref; const k = curSess(); ui.sess = keep; if (!k) return;
  const { s, seq, o } = k, sb = subj(k.subjectId);
  ui.readRef = ref;
  showModal(`<div class="dlg wide read"><p style="margin:0"><span class="dot" style="background:${sb.color}"></span><b>${esc(sb.name)}</b>${k.sub ? ' › ' + esc(k.sub) : ''} ${cbadge(k.classId)}${seq ? ` <span class="muted small">, « ${esc(seq.title)} », séance ${seq.sessions.indexOf(s) + 1}/${seq.sessions.length}</span>` : ''}</p>
    <h2 style="margin:6px 0">${esc(s.title)}</h2><p class="small muted">${o ? esc(fD(o.date)) + ', ' + hm(o.start) + ' à ' + hm(o.end) : 'Pas de date'}</p>
    ${s.obj ? `<p><b>Objectif :</b> ${nl(s.obj)}</p>` : '<p class="muted">Objectif à rédiger.</p>'}${s.comp ? `<p><b>Compétences :</b> ${nl(s.comp)}</p>` : ''}${s.mat ? `<p><b>Matériel :</b> ${nl(s.mat)}</p>` : ''}
    ${stepsOrTxt(s) ? '<h3>Déroulé</h3>' + stepsOrTxt(s) : ''}
    ${s.lesson ? `<p><b>📘 Leçon :</b> ${esc(s.lesson)}</p>` : ''}${s.hw ? `<p><b>✏️ Devoirs pour la prochaine fois :</b> ${nl(s.hw)}</p>` : ''}
    ${s.end ? `<p>${endChip(s)}${s.end.why ? ' <span class="small muted">' + esc(s.end.why) + '</span>' : ''}${s.end.left ? '<br><b>Reste à faire :</b> ' + nl(s.end.left) : ''}</p>` : ''}${o ? carryBlock(o.key) + difBlock(o.key) : ''}${s.bilan ? `<p><b>Bilan :</b> ${nl(s.bilan)}</p>` : ''}
    <div class="row end"><button class="btn" data-a="closeModal">Fermer</button><span class="sp"></span>${o ? `<button class="btn" data-a="endOpen" ${seq ? `data-sid="${s.id}"` : `data-key="${ref.key}"`}>Non terminée ou écourtée…</button><button class="btn" data-a="occMenu" data-key="${o.key}">Non faite ou remplacée…</button>` : ''}<button class="btn" data-a="readPrint">Imprimer</button><button class="btn pri" data-a="readEdit">Modifier</button></div></div>`);
};
A.readEdit = () => { closeModal(); A.openSess(ui.readRef); };
A.readPrint = () => { const keep = ui.sess; ui.sess = ui.readRef; const h = ficheHTML(curSess()); ui.sess = keep; closeModal(); printDoc(h); };
// Un créneau de l'emploi du temps : la séance en lecture s'il y en a une, sinon le menu du créneau.
A.occRead = d => { const c = ctx(), o = c.byKey[d.key]; if (!o) return; const ss = c.occSess[o.key] || [];
  if (o.ov && o.ov.t === 'lost') return A.occMenu(d);
  if (ss.length) return A.readSess({ sid: ss[0].id }); if (db.adhoc[o.key]) return A.readSess({ key: o.key }); A.occMenu(d); };

// ---------- Séance non terminée ou écourtée ----------
// On note ce qui reste à faire, puis on choisit comment s'adapter :
//  « suite » : une séance de suite s'insère juste après, sur le prochain créneau de la matière (le reste se décale) ;
//  « report » : le reste est rappelé au début de la prochaine séance de la matière (rien ne bouge) ;
//  « none » : on le note seulement.
const ENDST = { unfinished: 'Non terminée', short: 'Écourtée' };
const ENDWHY = ['Manque de temps', 'Plus long que prévu', 'Consigne mal comprise', 'Imprévu', 'Agitation', 'Activité trop difficile'];
const endChip = s => s && s.end ? `<span class="chip yel" title="${esc(s.end.left || '')}">${ENDST[s.end.st]}${s.end.adapt === 'suite' ? ', suite ajoutée' : s.end.adapt === 'report' ? ', reste reporté' : ''}</span>` : '';
// Où se trouve une séance (sa séquence ou son créneau hors programmation) et son créneau.
function endRef(r) { const k = sessInfo(r); if (!k) return null; return { ...k, ref: r }; }
// Le prochain créneau de la même matière : là où le reste reporté est rappelé.
function nextSameQ(o) { const c = ctx(), q = o.hostQ || o.q; return c.occs.find(x => (x.hostQ || x.q) === q && !(x.ov && x.ov.t === 'lost') && x.date + x.start > o.date + o.start) || null; }
function carryMap() {
  const c = ctx(); if (c.carry) return c.carry; const m = {}, add = (s, o) => { const x = o && nextSameQ(o); if (x) (m[x.key] ??= []).push({ s, o }); };
  db.sequences.forEach(seq => seq.sessions.forEach(s => { if (s.end && s.end.adapt === 'report') add(s, (c.sess[s.id] || {}).o); }));
  for (const k in db.adhoc) { const s = db.adhoc[k]; if (s.end && s.end.adapt === 'report') add(s, c.byKey[k]); }
  return c.carry = m;
}
function carryBlock(key) {
  const l = carryMap()[key]; if (!l || !l.length) return '';
  return `<div class="difb" style="border-left-color:var(--encre)"><b>↩️ À reprendre de la séance précédente</b><ul class="plain">${l.map(({ s, o }) => `<li><span style="flex:1;min-width:180px"><b>${esc(s.title)}</b> <span class="small muted">(${esc(fS(o.date))}, ${ENDST[s.end.st].toLowerCase()})</span>${s.end.left ? '<br>' + nl(s.end.left) : ''}</span></li>`).join('')}</ul></div>`;
}
A.endOpen = d => {
  const r = d.sid ? { sid: d.sid } : { key: d.key }, k = endRef(r); if (!k) return;
  const e = k.s.end || {}, steps = (k.s.steps || []).map(p => p.phase).filter(Boolean);
  ui.end = { r, st: e.st || 'unfinished', why: e.why || '', left: e.left ?? (steps.length ? steps.map(x => '- ' + x).join('\n') : ''), adapt: e.adapt || (k.seq ? 'suite' : 'report'), had: !!k.s.end };
  showEnd();
};
function showEnd() {
  const u = ui.end, k = endRef(u.r); if (!k) return;
  const ta = $('#endLeft'); if (ta) u.left = ta.value;
  let warn = '';
  if (u.adapt === 'suite' && k.seq && !(k.s.end && k.s.end.suite)) { const out = preview(() => addSuite(k, u.left)); warn = out.length ? `<span class="warn">Sortira de l’année : ${esc(out.join(' ; '))}.</span>` : 'Aucune séance ne sort de l’année.'; }
  const nx = k.o && nextSameQ(k.o);
  const tile = (v, t, sub, dis) => `<button type="button" class="dt" style="--c:var(--encre)" data-a="endSet" data-k="adapt" data-v="${v}" aria-pressed="${u.adapt === v}" ${dis ? 'disabled' : ''}><b>${t}</b><small>${sub}</small></button>`;
  showModal(`<div class="dlg"><h2>${esc(k.s.title)}</h2><p class="small muted" style="margin-top:0">${k.o ? esc(fD(k.o.date)) + ', ' + hm(k.o.start) : ''}</p>
    <div class="row">${Object.entries(ENDST).map(([v, l]) => `<button type="button" class="qb" data-a="endSet" data-k="st" data-v="${v}" aria-pressed="${u.st === v}">${l}</button>`).join('')}</div>
    <h3 style="margin-top:12px">Pourquoi ? <span class="small muted">(facultatif)</span></h3><div class="row">${ENDWHY.map(w => `<button type="button" class="qb" data-a="endSet" data-k="why" data-v="${esc(w)}" aria-pressed="${u.why === w}">${esc(w)}</button>`).join('')}</div>
    <h3 style="margin-top:12px">Ce qui reste à faire</h3><textarea id="endLeft" rows="3" placeholder="ex. La mise en commun et l’entraînement">${esc(u.left)}</textarea>
    <h3 style="margin-top:12px">Comment s’adapter ?</h3>
    <div class="dts">${tile('suite', 'Séance de suite', 'insérée juste après', !k.seq)}${tile('report', 'Reprendre au début', nx ? 'de la séance du ' + esc(fS(nx.date)) : 'de la prochaine séance')}${tile('none', 'Ne rien changer', 'juste le noter')}</div>
    <p class="small">${u.adapt === 'suite' ? (k.seq ? `Une séance « ${esc(k.s.title)} (suite) » est ajoutée juste après, avec ce qui reste à faire. Elle prend le prochain créneau de la matière ; les séances suivantes reculent d’un cran. ${warn}` : 'Possible seulement pour une séance de la programmation.')
      : u.adapt === 'report' ? 'Rien ne bouge : ce qui reste s’affiche en tête de la prochaine séance de la matière (cahier journal, fiche, lecture), pour l’intégrer ou alléger la séance.' : 'La séance est marquée, sans autre changement.'}</p>
    <div class="row end">${u.had ? '<button class="btn danger" data-a="endDel">Retirer la mention</button>' : ''}<span class="sp"></span><button class="btn" data-a="closeModal">Annuler</button><button class="btn pri" data-a="endSave">Valider</button></div></div>`);
}
A.endSet = d => { const ta = $('#endLeft'); if (ta) ui.end.left = ta.value; ui.end[d.k] = d.k === 'why' && ui.end.why === d.v ? '' : d.v; showEnd(); };
// La séance de suite : même matière, même objectif, avec ce qui reste en déroulé.
function addSuite(k, left) {
  const s = k.s, n = newSess(s.title.replace(/ \(suite\)$/, '') + ' (suite)');
  Object.assign(n, { obj: s.obj || '', comp: s.comp || '', mat: s.mat || '', txt: left ? 'Reprise : ' + left : '', suiteOf: s.id });
  if (s.subjectId) Object.assign(n, { subjectId: s.subjectId, sub: s.sub || '' });
  const seq = db.sequences.find(x => x.id === k.seq.id); seq.sessions.splice(seq.sessions.findIndex(x => x.id === s.id) + 1, 0, n); return n;
}
function dropSuite(k) { const id = k.s.end && k.s.end.suite; if (!id) return; const f = findS(id); if (f) f.seq.sessions.splice(f.seq.sessions.indexOf(f.s), 1); }
A.endSave = () => {
  const u = ui.end, k = endRef(u.r); u.left = $('#endLeft').value.trim();
  if (u.adapt === 'suite' && !k.seq) { toast('Choisissez une autre façon de s’adapter.'); return; }
  const old = k.s.end || {}; let suite = old.suite;
  if (u.adapt !== 'suite' && suite) { dropSuite(k); suite = ''; }
  if (u.adapt === 'suite' && !suite) suite = addSuite(k, u.left).id;
  else if (u.adapt === 'suite' && suite) { const f = findS(suite); if (f && (!f.s.txt || f.s.txt.startsWith('Reprise : '))) f.s.txt = u.left ? 'Reprise : ' + u.left : ''; }
  k.s.end = { st: u.st, why: u.why, left: u.left, adapt: u.adapt, suite: suite || '' };
  closeModal(); save();
  const x = suite && ctx().sess[suite];
  toast(ENDST[u.st] + ' : ' + (u.adapt === 'suite' ? (x && x.o ? 'suite placée le ' + fS(x.o.date) + ' à ' + hm(x.o.start) + '.' : 'suite ajoutée, mais sans créneau avant la fin de l’année.') : u.adapt === 'report' ? 'le reste sera rappelé à la prochaine séance.' : 'noté.')); render();
};
A.endDel = () => { const k = endRef(ui.end.r); if (k.s.end && k.s.end.suite && !confirm('Retirer aussi la séance de suite ajoutée ?')) return; dropSuite(k); delete k.s.end; closeModal(); done(); toast('Mention retirée.'); };
