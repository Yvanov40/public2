'use strict';
// =====================================================================================
//  vues-d.js — gestion de classe (plans de classe, groupes, tirage au sort) et devoirs.
// =====================================================================================

// ---------- Gestion de classe ----------
const GNAMES = ['🦊 Renards', '🐢 Tortues', '🦉 Hiboux', '🐳 Baleines', '🦋 Papillons', '🐝 Abeilles', '🦁 Lions', '🐙 Pieuvres', '🦜 Perroquets', '🐬 Dauphins', '🐼 Pandas', '🦔 Hérissons', '🐸 Grenouilles', '🦄 Licornes', '🐧 Manchots'];
const curCls = () => ui.cls || (db.classes[0] || {}).id;
const clsStu = (cid = curCls()) => db.students.filter(s => s.classId === cid).sort((a, b) => (a.first + a.last).localeCompare(b.first + b.last, 'fr'));
V.classe = () => {
  const tab = ui.ctab || 'tirage', tb = (k, l) => `<button class="btn ${tab === k ? 'pri' : ''}" data-a="ctab" data-t="${k}">${l}</button>`;
  const head = `<div class="head"><h1>Gestion de classe</h1><span class="sp"></span><div class="row np">${tb('tirage', '🎲 Tirage au sort')}${tb('groupes', '👥 Groupes')}${tb('plan', '🪑 Plan de classe')}</div></div>`;
  if (!clsStu().length) return head + '<div class="sheet"><p>Ajoutez d’abord vos élèves dans l’onglet Élèves.</p></div>';
  return head + (tab === 'plan' ? planHTML() : tab === 'groupes' ? groupsHTML() : drawHTML());
};
A.ctab = d => { ui.ctab = d.t; ui.gprev = null; ui.psel = null; render(); };

// --- Tirage au sort : un ou plusieurs élèves, un ordre de passage, un groupe. « Chacun son tour » évite
// de retomber sur les mêmes tant que toute la classe n'est pas passée.
function drawHTML() {
  const cid = curCls(), st = clsStu(cid), dr = db.drawn[cid] || [], sets = db.groupSets.filter(g => g.classId === cid), u = ui.draw ||= { n: 1, abs: true, turn: true, res: [] };
  return `<div class="sheet"><div class="row" style="align-items:flex-end">
    <label class="fld"><span>Combien ?</span><select data-c="drawN">${[1, 2, 3, 4, 5, 6].map(n => `<option ${u.n === n ? 'selected' : ''}>${n}</option>`).join('')}</select></label>
    <label class="chk"><input type="checkbox" data-c="drawO" data-k="abs" ${u.abs ? 'checked' : ''}> Sans les absents</label>
    <label class="chk"><input type="checkbox" data-c="drawO" data-k="turn" ${u.turn ? 'checked' : ''}> Chacun son tour (${dr.length}/${st.length} déjà passés${dr.length ? ` · <button class="lnk" data-a="drawReset">remettre à zéro</button>` : ''})</label></div>
    <div class="row"><button class="btn pri big" data-a="draw">🎲 Tirer au sort</button><button class="btn big" data-a="drawOrder">🔀 Ordre de passage</button>${sets.length ? `<button class="btn big" data-a="drawGroup">👥 Un groupe au hasard</button>` : ''}</div>
    <div class="roul" id="roul" aria-live="polite">${u.res.length ? u.res.map(x => `<span>${esc(x)}</span>`).join('') : '<span class="muted">Qui sera l’heureux élu ?</span>'}</div></div>`;
}
C.drawN = (d, el) => { ui.draw.n = +el.value; };
C.drawO = (d, el) => { ui.draw[d.k] = el.checked; };
A.drawReset = () => { db.drawn[curCls()] = []; done(); };
function pool() { const u = ui.draw; return clsStu().filter(s => !(u.abs && isAbsent(s.id))).map(s => s.id); }
// La roulette : les prénoms défilent de plus en plus lentement, puis s'arrêtent (instantané si l'appareil réduit les animations).
function roulette(final, label) {
  const el = $('#roul'), names = clsStu().map(s => s.first); ui.draw.res = final;
  if (!el || matchMedia('(prefers-reduced-motion: reduce)').matches) { render(); return; }
  let i = 0, wait = 40; const tick = () => { el.innerHTML = `<span class="spin">${esc(names[Math.floor(Math.random() * names.length)])}</span>`; i++; wait *= 1.13;
    if (wait < 420) setTimeout(tick, wait); else { el.innerHTML = (label ? `<small>${esc(label)}</small>` : '') + final.map(x => `<span class="win">${esc(x)}</span>`).join(''); } };
  tick();
}
A.draw = () => {
  const u = ui.draw, cid = curCls(); let p = pool(); if (!p.length) { toast('Personne à tirer au sort.'); return; }
  if (u.turn) { const dr = db.drawn[cid] ||= []; let left = p.filter(id => !dr.includes(id)); if (left.length < u.n) { db.drawn[cid] = left.slice(); left = p; toast('Tout le monde est passé : on recommence un tour.'); } p = left; }
  const pick = shuffle(p).slice(0, u.n); if (u.turn) db.drawn[cid].push(...pick); save(); roulette(pick.map(firstN));
};
A.drawOrder = () => { const p = pool(); ui.draw.res = shuffle(p).map((id, i) => (i + 1) + '. ' + firstN(id)); render(); };
A.drawGroup = () => { const sets = db.groupSets.filter(g => g.classId === curCls()), s = sets.find(x => x.durable) || sets[sets.length - 1], g = shuffle(s.groups)[0]; roulette([g.n + ' : ' + g.ids.map(firstN).join(', ')], s.name); };

// --- Groupes : durables (ajustés de temps en temps dans l'année) ou du jour. Générés au hasard avec des
// contraintes, puis retouchés à la main (touchez deux élèves pour les échanger).
const levelScore = (sid, subjectId) => {                // niveau d'un élève dans une matière, de 0 à 1, d'après ses évaluations
  const v = db.evals.filter(e => e.subjectId === subjectId).map(e => { const g = db.grades[e.id + '|' + sid]; if (!g || g.v === '' || g.v == null) return null; return e.mode === 'num' ? +g.v / (+e.max || 20) : FINE.indexOf(g.v) / (FINE.length - 1); }).filter(x => x != null && x >= 0);
  return v.length ? v.reduce((a, b) => a + b, 0) / v.length : null;
};
function groupsHTML() {
  const cid = curCls(), sets = db.groupSets.filter(g => g.classId === cid).sort((a, b) => (b.durable - a.durable) || b.date.localeCompare(a.date)), av = db.avoid.filter(p => stu(p[0]) && stu(p[0]).classId === cid);
  if (ui.gprev) return groupEditor();
  const card = s => `<div class="sheet flat"><div class="row"><h3>${esc(s.name)}</h3>${s.durable ? '<span class="chip ok">durable</span>' : `<span class="chip">${esc(fS(s.date))}</span>`}<span class="sp"></span><button class="btn sm np" data-a="gEdit" data-id="${s.id}">Ajuster</button><button class="btn sm np" data-a="gPrint" data-id="${s.id}">Imprimer</button></div>
    <div class="gcols">${s.groups.map(g => `<div class="gbox"><b>${esc(g.n)}</b><span>${g.ids.map(id => esc(firstN(id))).join(', ')}</span></div>`).join('')}</div>${s.note ? `<p class="small muted">${esc(s.note)}</p>` : ''}</div>`;
  return `<div class="sheet"><h2>Faire des groupes</h2><div class="row"><button class="btn pri" data-a="gNew" data-k="2">Binômes</button><button class="btn pri" data-a="gNew" data-k="3">Groupes de 3</button><button class="btn pri" data-a="gNew" data-k="4">Groupes de 4</button><button class="btn" data-a="gNew" data-k="0">Autre…</button></div>
    <p class="small muted">Au hasard, avec vos contraintes : mixité, élèves à ne pas associer, brassage, groupes hétérogènes ou homogènes dans une matière.</p></div>
    ${sets.filter(s => s.durable).map(card).join('')}
    ${sets.some(s => !s.durable) ? '<h2 style="margin:16px 0 8px">Groupes des derniers jours</h2>' + sets.filter(s => !s.durable).slice(0, 6).map(card).join('') : ''}
    <div class="sheet"><div class="row"><h2>À ne pas associer</h2><span class="sp"></span><button class="btn sm" data-a="avoidAdd">Ajouter</button></div>
    ${av.length ? `<ul class="plain">${av.map(p => `<li>${esc(sname(stu(p[0])))} ✕ ${esc(sname(stu(p[1])))}<span class="sp"></span><button class="btn sm danger" data-a="avoidDel" data-a1="${p[0]}" data-a2="${p[1]}" aria-label="Retirer">✕</button></li>`).join('')}</ul>` : '<p class="small muted">Aucune contrainte. Elles servent aux groupes et au placement dans le plan de classe.</p>'}</div>`;
}
A.avoidAdd = () => { const o = clsStu().map(s => [s.id, sname(s)]); openForm({ title: 'Ne pas associer', fields: [{ k: 'a', label: 'Élève', type: 'select', options: o }, { k: 'b', label: 'avec', type: 'select', options: o }], onSave: v => { if (v.a === v.b) { toast('Choisissez deux élèves différents.'); return false; } db.avoid.push([v.a, v.b]); } }); };
A.avoidDel = d => { db.avoid = db.avoid.filter(p => !(p[0] === d.a1 && p[1] === d.a2)); done(); };
A.gNew = d => {
  const o = { size: +d.k || 3, count: 0, mix: true, avoid: true, brass: true, comp: 'hasard', subj: (db.subjects[0] || {}).id, abs: true, durable: false, fun: true, name: '' };
  if (+d.k) return makeGroups(o);
  openForm({ title: 'Faire des groupes', values: { ...o, size: '3' }, fields: [
    { k: 'size', label: 'Élèves par groupe', type: 'select', options: [2, 3, 4, 5, 6].map(n => [n, n]) }, { k: 'count', label: 'Ou nombre de groupes (prioritaire si rempli)', type: 'number' },
    { k: 'comp', label: 'Composition', type: 'select', options: [['hasard', 'Au hasard'], ['hetero', 'Hétérogènes : niveaux mélangés'], ['homo', 'Homogènes : niveaux proches']] },
    { k: 'subj', label: 'Niveaux d’après la matière (pour hétérogène ou homogène)', type: 'select', options: db.subjects.map(s => [s.id, s.name]) },
    { k: 'mix', label: 'Mixité filles-garçons', type: 'checkbox' }, { k: 'avoid', label: 'Respecter « à ne pas associer »', type: 'checkbox' }, { k: 'brass', label: 'Brassage : éviter de remettre ensemble les mêmes élèves', type: 'checkbox' },
    { k: 'abs', label: 'Sans les absents du jour', type: 'checkbox' }, { k: 'durable', label: 'Groupes durables (gardés dans l’année)', type: 'checkbox' }, { k: 'fun', label: 'Noms d’équipes rigolos', type: 'checkbox' }, { k: 'name', label: 'Nom de cet ensemble (facultatif)' }],
    onSave: v => { setTimeout(() => makeGroups({ ...o, ...v, size: +v.size, count: +v.count || 0 })); } });
};
// Le cœur : on part de plusieurs répartitions au hasard et on échange des élèves tant que ça améliore le score.
function makeGroups(o) {
  const ids = clsStu().filter(s => !(o.abs && isAbsent(s.id))).map(s => s.id), n = ids.length; if (n < 2) { toast('Pas assez d’élèves.'); return; }
  const k = Math.max(1, o.count || Math.round(n / o.size)), sex = Object.fromEntries(ids.map(id => [id, (stu(id) || {}).sex || ''])), girls = ids.filter(id => sex[id] === 'F').length / n;
  const sc = o.comp !== 'hasard' ? Object.fromEntries(ids.map(id => [id, levelScore(id, o.subj)])) : {};
  const known = Object.values(sc).filter(x => x != null), mean = known.length ? known.reduce((a, b) => a + b, 0) / known.length : .5;
  ids.forEach(id => { if (o.comp !== 'hasard' && sc[id] == null) sc[id] = mean; });
  const av = new Set(db.avoid.flatMap(([a, b]) => [a + b, b + a])), hist = {};
  if (o.brass) db.groupSets.slice(-15).forEach(s => s.groups.forEach(g => g.ids.forEach(a => g.ids.forEach(b => { if (a < b) hist[a + b] = (hist[a + b] || 0) + 1; }))));
  const cost = G => { let c = 0;
    G.forEach(g => { for (let i = 0; i < g.length; i++) for (let j = i + 1; j < g.length; j++) { const a = g[i], b = g[j]; if (o.avoid && av.has(a + b)) c += 100; if (o.brass) c += 3 * (hist[a < b ? a + b : b + a] || 0); }
      if (o.mix) c += 4 * Math.abs(g.filter(id => sex[id] === 'F').length - girls * g.length);
      if (o.comp === 'homo') { const m = g.reduce((s, id) => s + sc[id], 0) / g.length; c += 30 * g.reduce((s, id) => s + (sc[id] - m) ** 2, 0); } });
    if (o.comp === 'hetero') { const ms = G.map(g => g.reduce((s, id) => s + sc[id], 0) / g.length); c += 60 * ms.reduce((s, m) => s + (m - mean) ** 2, 0); }
    return c; };
  let best = null, bc = Infinity;
  for (let r = 0; r < 25; r++) {
    const sh = shuffle(ids), G = Array.from({ length: k }, () => []); sh.forEach((id, i) => G[i % k].push(id)); let c = cost(G);
    for (let t = 0; t < 600; t++) { const a = Math.floor(Math.random() * k), b = Math.floor(Math.random() * k); if (a === b) continue; const i = Math.floor(Math.random() * G[a].length), j = Math.floor(Math.random() * G[b].length);
      [G[a][i], G[b][j]] = [G[b][j], G[a][i]]; const c2 = cost(G); if (c2 <= c) c = c2; else[G[a][i], G[b][j]] = [G[b][j], G[a][i]]; }
    if (c < bc) { bc = c; best = G.map(g => [...g]); }
  }
  const names = o.fun ? shuffle(GNAMES) : [];
  ui.gprev = { o, id: null, name: o.name || (o.durable ? 'Groupes de travail' : (o.size === 2 && !o.count ? 'Binômes' : 'Groupes') + ' du ' + fS(TODAY())), durable: !!o.durable, groups: best.map((g, i) => ({ n: names[i] || 'Groupe ' + (i + 1), ids: g })), warn: bc >= 100 };
  ui.ctab = 'groupes'; render();
}
function groupEditor() {
  const u = ui.gprev;
  return `<div class="sheet"><div class="row"><h2>${esc(u.name)}</h2>${u.durable ? '<span class="chip ok">durable</span>' : ''}<span class="sp"></span>${u.o ? '<button class="btn" data-a="gAgain">🎲 Refaire</button>' : ''}</div>
    ${u.warn ? '<p class="warn small">Impossible de respecter toutes les contraintes « à ne pas associer » : vérifiez les groupes.</p>' : ''}
    <p class="small muted">Touchez un élève puis un autre pour les échanger. Touchez un nom de groupe pour le renommer.</p>
    <div class="gcols">${u.groups.map((g, gi) => `<div class="gbox"><button class="lnk" data-a="gRen" data-g="${gi}"><b>${esc(g.n)}</b></button>${g.ids.map(id => `<button class="sc" data-a="gPick" data-id="${id}" aria-pressed="${ui.gsel === id}">${esc(firstN(id))}${stu(id).sex ? ` <small>${stu(id).sex === 'F' ? '♀' : '♂'}</small>` : ''}</button>`).join('')}</div>`).join('')}</div>
    <label class="chk"><input type="checkbox" data-c="gDur" ${u.durable ? 'checked' : ''}> Groupes durables (gardés pour l’année, à ajuster de temps en temps)</label>
    <div class="row end">${u.id ? '<button class="btn danger" data-a="gDel">Supprimer</button>' : ''}<span class="sp"></span><button class="btn" data-a="gCancel">Annuler</button><button class="btn pri" data-a="gSave">Enregistrer</button></div></div>`;
}
A.gAgain = () => makeGroups(ui.gprev.o);
A.gPick = d => { const u = ui.gprev; if (!ui.gsel) { ui.gsel = d.id; render(); return; } if (ui.gsel !== d.id) { const f = id => u.groups.find(g => g.ids.includes(id)), A1 = f(ui.gsel), B1 = f(d.id); A1.ids[A1.ids.indexOf(ui.gsel)] = d.id; B1.ids[B1.ids.indexOf(d.id)] = ui.gsel; } ui.gsel = null; render(); };
A.gRen = d => { const g = ui.gprev.groups[d.g], n = prompt('Nom du groupe', g.n); if (n) { g.n = n; render(); } };
C.gDur = (d, el) => { ui.gprev.durable = el.checked; };
A.gCancel = () => { ui.gprev = null; ui.gsel = null; render(); };
A.gSave = () => { const u = ui.gprev, v = { classId: curCls(), name: u.name, durable: u.durable, date: TODAY(), groups: u.groups, note: '' }; if (u.id) Object.assign(db.groupSets.find(x => x.id === u.id), v); else db.groupSets.push({ id: uid(), ...v }); ui.gprev = null; done(); toast('Groupes enregistrés.'); };
A.gEdit = d => { const s = db.groupSets.find(x => x.id === d.id); ui.gprev = { id: s.id, name: s.name, durable: s.durable, groups: JSON.parse(JSON.stringify(s.groups)) }; render(); };
A.gDel = () => { if (!confirm('Supprimer ces groupes ?')) return; remove(db.groupSets, ui.gprev.id); ui.gprev = null; done(); };
A.gPrint = d => { const s = db.groupSets.find(x => x.id === d.id); printDoc(docHead(s.name) + `<div class="gcols">${s.groups.map(g => `<div class="gbox"><b>${esc(g.n)}</b><span>${g.ids.map(id => esc(sname(stu(id)))).join('<br>')}</span></div>`).join('')}</div>`); };

// --- Plan de classe : plusieurs aménagements par classe (rangées, îlots, U, autobus). Touchez une table
// puis une autre pour échanger ; touchez une table puis un élève non placé pour l'installer.
const LAYOUTS = { rangs: 'Rangées de 2', ilots: 'Îlots de 4', u: 'En U', bus: 'Tables seules', libre: 'À dessiner' };
function buildLayout(kind, n) {
  const d = {}; let cols, rows;
  if (kind === 'rangs') { cols = 8; const per = 6; rows = Math.ceil(n / per); for (let r = 0; r < rows; r++) for (const c of [0, 1, 3, 4, 6, 7]) d[r * 2 + ',' + c] = ''; rows = rows * 2 - 1; }
  else if (kind === 'ilots') { cols = 8; const isl = Math.ceil(n / 4), per = 3; rows = Math.ceil(isl / per) * 3 - 1; for (let i = 0; i < isl; i++) { const R = Math.floor(i / per) * 3, Cc = (i % per) * 3; for (const [a, b] of [[0, 0], [0, 1], [1, 0], [1, 1]]) d[(R + a) + ',' + (Cc + b)] = ''; } }
  else if (kind === 'u') { cols = 9; rows = Math.max(3, Math.ceil((n - cols) / 2) + 1); for (let c = 0; c < cols; c++) d[(rows - 1) + ',' + c] = ''; for (let r = 0; r < rows - 1; r++) { d[r + ',0'] = ''; d[r + ',' + (cols - 1)] = ''; } }
  else if (kind === 'bus') { cols = 9; rows = Math.ceil(n / 5) * 2 - 1; for (let r = 0; r < rows; r += 2) for (let c = 0; c < cols; c += 2) d[r + ',' + c] = ''; }
  else { cols = 8; rows = 6; }
  return { cols, rows, desks: d };
}
const curPlan = () => { const ps = db.plans.filter(p => p.classId === curCls()); return ps.find(p => p.id === ui.plan) || ps.find(p => p.active) || ps[0]; };
function planHTML() {
  const ps = db.plans.filter(p => p.classId === curCls()), p = curPlan();
  const bar = `<div class="row np" style="margin-bottom:10px">${ps.map(x => `<button class="btn ${p && x.id === p.id ? 'pri' : ''}" data-a="planSel" data-id="${x.id}">${esc(x.name)}</button>`).join('')}<button class="btn" data-a="planNew">＋ Nouvel aménagement</button></div>`;
  if (!p) return bar + '<div class="sheet"><p>Créez un aménagement : rangées, îlots, en U… Vous pourrez en garder plusieurs et passer de l’un à l’autre.</p></div>';
  const placed = new Set(Object.values(p.desks).filter(Boolean)), free = clsStu().filter(s => !placed.has(s.id)), av = new Set(db.avoid.flatMap(([a, b]) => [a + b, b + a]));
  const bad = k => { const sid = p.desks[k]; if (!sid) return false; const [r, c] = k.split(',').map(Number); return [[r, c - 1], [r, c + 1]].some(([a, b]) => { const o = p.desks[a + ',' + b]; return o && av.has(sid + o); }); };
  let cells = ''; for (let r = 0; r < p.rows; r++) for (let c = 0; c < p.cols; c++) { const k = r + ',' + c, has = k in p.desks, sid = p.desks[k];
    cells += `<button class="desk ${has ? (sid ? 'full' : 'empty') : 'void'} ${ui.psel === k ? 'sel' : ''} ${bad(k) ? 'bad' : ''}" data-a="planCell" data-k="${k}" ${!has && !ui.pdraw ? 'tabindex="-1"' : ''}>${sid ? esc(firstN(sid)) + (stu(sid) ? `<small>${esc((stu(sid).last || '')[0] || '')}.</small>` : '') : ''}</button>`; }
  return bar + `<div class="sheet flat"><div class="row np"><h2>${esc(p.name)}</h2><span class="small muted">${LAYOUTS[p.kind] || ''}</span><span class="sp"></span>
    <button class="btn sm" data-a="planRand">🎲 Placer au hasard</button><button class="btn sm ${ui.pdraw ? 'pri' : ''}" data-a="planDraw">${ui.pdraw ? 'Terminer la disposition' : 'Modifier la disposition'}</button><button class="btn sm" data-a="planEmpty">Vider</button><button class="btn sm" data-a="planRen">Renommer</button><button class="btn sm danger" data-a="planDel">Supprimer</button></div>
    <p class="small muted np">${ui.pdraw ? 'Touchez une case pour ajouter ou retirer une table.' : 'Touchez une table puis une autre pour échanger les élèves, ou une table puis un élève non placé. En rouge : deux élèves à ne pas associer côte à côte.'}</p>
    <div class="board">Tableau</div><div class="plan" style="--c:${p.cols}">${cells}</div>
    ${free.length ? `<div class="np"><h3 style="margin-top:12px">Non placés</h3><div class="scs">${free.map(s => `<button class="sc" data-a="planStu" data-id="${s.id}">${esc(s.first)} <small>${esc((s.last || '')[0] || '')}.</small></button>`).join('')}</div></div>` : ''}</div>`;
}
A.planSel = d => { ui.plan = d.id; db.plans.forEach(x => x.active = x.id === d.id); ui.psel = null; done(); };
A.planNew = () => openForm({ title: 'Nouvel aménagement', values: { kind: 'ilots', name: '' }, fields: [{ k: 'name', label: 'Nom (ex. Îlots période 2, Évaluations)', req: 1 }, { k: 'kind', label: 'Disposition', type: 'select', options: Object.entries(LAYOUTS) }, { k: 'fill', label: 'Placer les élèves au hasard tout de suite', type: 'checkbox' }],
  onSave: v => { const p = { id: uid(), classId: curCls(), name: v.name, kind: v.kind, ...buildLayout(v.kind, clsStu().length) }; db.plans.push(p); ui.plan = p.id; db.plans.forEach(x => x.active = x.id === p.id); if (v.fill) randPlan(p); } });
function randPlan(p) {
  const ks = Object.keys(p.desks), av = new Set(db.avoid.flatMap(([a, b]) => [a + b, b + a])); let best, bc = Infinity;
  for (let t = 0; t < 200; t++) { const ids = shuffle(clsStu().map(s => s.id)), d = {}; ks.forEach((k, i) => d[k] = ids[i] || ''); let c = 0;
    ks.forEach(k => { const [r, cc] = k.split(',').map(Number), o = d[r + ',' + (cc + 1)]; if (d[k] && o && av.has(d[k] + o)) c++; }); if (c < bc) { bc = c; best = d; } if (!c) break; }
  p.desks = best; if (ks.length < clsStu().length) toast('Il manque ' + (clsStu().length - ks.length) + ' table(s) : ajoutez-en avec « Modifier la disposition ».');
}
A.planRand = () => { randPlan(curPlan()); done(); };
A.planDraw = () => { ui.pdraw = !ui.pdraw; ui.psel = null; render(); };
A.planEmpty = () => { const p = curPlan(); Object.keys(p.desks).forEach(k => p.desks[k] = ''); done(); };
A.planRen = () => { const p = curPlan(), n = prompt('Nom de l’aménagement', p.name); if (n) { p.name = n; done(); } };
A.planDel = () => { if (confirm('Supprimer cet aménagement ?')) { remove(db.plans, curPlan().id); ui.plan = null; done(); } };
A.planCell = d => {
  const p = curPlan(), k = d.k;
  if (ui.pdraw) { if (k in p.desks) delete p.desks[k]; else p.desks[k] = ''; done(); return; }
  if (!(k in p.desks)) return;
  if (!ui.psel) { ui.psel = k; render(); return; }
  if (ui.psel !== k) [p.desks[ui.psel], p.desks[k]] = [p.desks[k], p.desks[ui.psel]];
  ui.psel = null; done();
};
A.planStu = d => { const p = curPlan(); const k = ui.psel || Object.keys(p.desks).find(x => !p.desks[x]); if (!k) { toast('Plus de table libre : ajoutez-en avec « Modifier la disposition ».'); return; } p.desks[k] = d.id; ui.psel = null; done(); };

// ---------- Devoirs ----------
// Trois sources : les séances (devoirs pour la prochaine fois, leçon à revoir), les devoirs ajoutés à la main,
// et les devoirs réguliers (ex. les mots de la dictée, donnés chaque vendredi). Tout est recalculé quand la
// programmation bouge. Une leçon peut être revue : à la prochaine séance, chaque jour jusqu'à la prochaine
// séance, ou en révisions espacées jusqu'à la fin de l'année (courbe de l'oubli : J+1, J+3, J+7, J+14, J+30…).
const LESSON = { next: 'Pour la prochaine séance', daily: 'Chaque jour jusqu’à la prochaine séance', spaced: 'Révisions espacées jusqu’à la fin de l’année' };
const SPACED = [1, 3, 7, 14, 30, 60, 90, 120, 180];
function nextSchool(d) { const c = ctx(); for (let i = 0; i < 30; i++) { if (c.school(d)) return d; d = addD(d, 1); } return d; }
function nextOccQ(o) { const c = ctx(), q = o.hostQ || o.q; return c.occs.find(x => (x.hostQ || x.q) === q && !(x.ov && x.ov.t === 'lost') && x.date + x.start > o.date + o.start) || null; }
function hwItems() {
  const c = ctx(); if (c.hw) return c.hw; const out = [], end = db.settings.end;
  const fromSess = (s, o) => {
    const sb = subj((o.hostQ || o.q).split('|')[1]).name, nx = nextOccQ(o), cid = o.classId;
    if (s.hw && nx) out.push({ given: o.date, due: nx.date, text: s.hw, subj: sb, src: 'séance', cid, ref: s.id });
    if (s.lesson) {
      const L = 'Revoir la leçon : ' + s.lesson, m = s.lmode || 'next';
      if (m === 'next' && nx) out.push({ given: o.date, due: nx.date, text: L, subj: sb, src: 'leçon', cid, ref: s.id });
      if (m === 'daily' && nx) { for (let d = nextSchool(addD(o.date, 1)); d <= nx.date; d = nextSchool(addD(d, 1))) out.push({ given: o.date, due: d, text: L, subj: sb, src: 'leçon', cid, ref: s.id }); }
      if (m === 'spaced') { const seen = new Set(); SPACED.forEach(n => { const d = nextSchool(addD(o.date, n)); if (d <= end && !seen.has(d)) { seen.add(d); out.push({ given: o.date, due: d, text: L + (n > 7 ? ' (révision)' : ''), subj: sb, src: 'leçon', cid, ref: s.id }); } }); }
    }
  };
  for (const seq of db.sequences) for (const s of seq.sessions) { const x = c.sess[s.id]; if (x && x.o && (s.hw || s.lesson)) fromSess(s, x.o); }
  for (const k in db.adhoc) { const s = db.adhoc[k], o = c.byKey[k]; if (o && (s.hw || s.lesson)) fromSess(s, o); }
  db.hw.forEach(h => out.push({ given: h.given, due: h.due, text: h.text, subj: h.subjectId ? subj(h.subjectId).name : '', src: 'ajouté', cid: h.classId, id: h.id }));
  db.hwRec.forEach(r => { for (let d = db.settings.start; d <= end; d = addD(d, 1)) { if (wd(d) !== +r.give || !c.school(d) || (r.from && d < r.from)) continue;
    let due = addD(d, 1); while (wd(due) !== +r.due) due = addD(due, 1); due = nextSchool(due); out.push({ given: d, due, text: r.text, subj: r.subjectId ? subj(r.subjectId).name : '', src: 'régulier', cid: r.classId, rec: r.id }); } });
  return c.hw = out.sort((a, b) => a.due.localeCompare(b.due) || a.subj.localeCompare(b.subj));
}
const visHw = () => { const vc = visCls(); return hwItems().filter(h => !h.cid || vc.includes(h.cid)); };
const hwLi = h => `<li><span class="chip">${esc(h.subj || 'Divers')}</span><span style="flex:1;min-width:150px">${nl(h.text)}</span><span class="small muted">${h.src === 'ajouté' ? `<button class="lnk" data-a="hwEdit" data-id="${h.id}">${esc(h.src)}</button>` : h.src === 'régulier' ? `<button class="lnk" data-a="hwRecEdit" data-id="${h.rec}">${esc(h.src)}</button>` : h.ref ? `<button class="lnk" data-a="readSess" data-sid="${h.ref}">${esc(h.src)}</button>` : esc(h.src)}</span></li>`;
V.devoirs = () => {
  const t = TODAY(), all = visHw(), tab = ui.htab || 'semaine', tb = (k, l) => `<button class="btn ${tab === k ? 'pri' : ''}" data-a="htab" data-t="${k}">${l}</button>`;
  const head = `<div class="head"><h1>Devoirs</h1><span class="sp"></span><div class="row np">${tb('semaine', 'Cette semaine')}${tb('histo', 'Historique')}${tb('reg', 'Devoirs réguliers')}</div></div>
    <div class="row np" style="margin-bottom:10px"><button class="btn pri" data-a="hwNew">＋ Ajouter un devoir</button><span class="small muted">Les devoirs des séances se notent dans chaque séance (devoirs pour la prochaine fois, leçon donnée).</span></div>`;
  if (tab === 'reg') return head + `<div class="sheet"><div class="row"><h2>Devoirs réguliers</h2><span class="sp"></span><button class="btn sm" data-a="hwRecNew">＋ Ajouter</button></div>
    ${db.hwRec.length ? `<ul class="plain">${db.hwRec.map(r => `<li><b style="flex:1">${esc(r.text)}</b><span class="small">donné le ${DAYS[r.give]}, pour le ${DAYS[r.due]}</span><button class="btn sm" data-a="hwRecEdit" data-id="${r.id}">Modifier</button></li>`).join('')}</ul>` : '<p class="muted">Par exemple : « Apprendre les mots de la dictée », donné chaque vendredi pour le jeudi.</p>'}</div>`;
  if (tab === 'histo') { const past = all.filter(h => h.given <= t).sort((a, b) => b.given.localeCompare(a.given)), by = {}; past.forEach(h => (by[h.given] ??= []).push(h));
    return head + (Object.keys(by).length ? Object.entries(by).slice(0, 40).map(([d, l]) => `<div class="sheet"><div class="mg">${esc(fD(d, { day: 'numeric', month: 'short' }))}</div><h3>Donnés le ${esc(fD(d))}</h3><ul class="plain">${l.map(h => hwLi(h).replace('</li>', `<span class="small muted">pour le ${esc(fS(h.due))}</span></li>`)).join('')}</ul></div>`).join('') : '<div class="sheet"><p class="muted">Aucun devoir donné pour l’instant.</p></div>'); }
  const days = [0, 1, 2, 3, 4, 5, 6].map(i => addD(ui.week, i)).filter(d => ctx().school(d) || all.some(h => h.due === d));
  return head + weekNav() + `<div class="row np" style="margin-bottom:10px"><button class="btn" data-a="hwCopy">Copier la semaine pour l’ENT</button><button class="btn" data-a="printPage">Imprimer</button></div>`
    + days.map(d => { const due = all.filter(h => h.due === d);
      return `<div class="sheet" ${d === t ? 'style="border-color:var(--encre);border-width:2px"' : ''}><div class="mg">${esc(fD(d, { weekday: 'short' }))}<br>${esc(fD(d, { day: 'numeric', month: 'short' }))}</div>
        <h3>Pour le ${esc(fD(d))}</h3>${due.length ? `<ul class="plain">${due.map(hwLi).join('')}</ul>` : '<p class="small muted">Rien à faire pour ce jour.</p>'}
        <p class="np" style="margin:6px 0 0"><button class="btn sm" data-a="hwNew" data-d="${d}">＋ Ajouter un devoir pour ce jour</button></p></div>`; }).join('');
};
A.htab = d => { ui.htab = d.t; render(); };
A.hwCopy = async () => { const all = visHw(), txt = [0, 1, 2, 3, 4, 5, 6].map(i => addD(ui.week, i)).map(d => { const l = all.filter(h => h.due === d); return l.length ? 'Pour le ' + fD(d) + ' :\n' + l.map(h => '- ' + (h.subj ? h.subj + ' : ' : '') + h.text).join('\n') : ''; }).filter(Boolean).join('\n\n');
  try { await navigator.clipboard.writeText(txt || 'Pas de devoirs cette semaine.'); toast('Devoirs de la semaine copiés.'); } catch (e) { toast('Copie impossible sur cet appareil.'); } };
// Un devoir ajouté à la main, pour une date précise, avec ou sans matière (sortie, mot à signer, matériel à apporter…).
function prevSchool(d) { const c = ctx(); let x = addD(d, -1); for (let i = 0; i < 30 && !c.school(x); i++) x = addD(x, -1); return x; }
function hwForm(h, due) { due = due || nextSchool(addD(TODAY(), 1)); const given = prevSchool(due) < TODAY() ? TODAY() : prevSchool(due);
  openForm({ title: h ? 'Modifier le devoir' : 'Devoir pour le ' + fD(due), values: h || { due, given, subjectId: '', classId: defCls() },
  fields: [{ k: 'due', label: 'Pour le', type: 'date', req: 1 }, { k: 'text', label: 'Devoir', type: 'textarea', req: 1 }, { k: 'subjectId', label: 'Matière (facultatif)', type: 'select', options: [['', 'Hors matière']].concat(db.subjects.map(s => [s.id, s.name])) }, { k: 'given', label: 'Donné le', type: 'date', req: 1 }, ...(db.classes.length > 1 ? [{ k: 'classId', label: 'Classe', type: 'select', options: clsOpts() }] : [])],
  onSave: v => { if (!v.classId) v.classId = h ? h.classId : defCls(); upsert(db.hw, h && h.id, v); }, onDelete: h ? () => remove(db.hw, h.id) : null }); }
A.hwNew = d => hwForm(null, d.d); A.hwEdit = d => hwForm(db.hw.find(h => h.id === d.id));
function hwRecForm(r) { const dd = [...db.settings.days].sort().map(x => [x, DAYS[x]]); openForm({ title: r ? 'Devoir régulier' : 'Nouveau devoir régulier', values: r || { give: 5, due: 4, classId: defCls() },
  fields: [{ k: 'text', label: 'Devoir (ex. Apprendre les mots de la dictée)', req: 1 }, { k: 'subjectId', label: 'Matière', type: 'select', options: [['', 'Hors matière']].concat(db.subjects.map(s => [s.id, s.name])) }, { k: 'give', label: 'Donné chaque', type: 'select', options: dd }, { k: 'due', label: 'Pour le', type: 'select', options: dd }, { k: 'from', label: 'À partir du (facultatif)', type: 'date' }, ...(db.classes.length > 1 ? [{ k: 'classId', label: 'Classe', type: 'select', options: clsOpts() }] : [])],
  onSave: v => { v.give = +v.give; v.due = +v.due; if (!v.classId) v.classId = r ? r.classId : defCls(); upsert(db.hwRec, r && r.id, v); }, onDelete: r ? () => remove(db.hwRec, r.id) : null }); }
A.hwRecNew = () => hwRecForm(null); A.hwRecEdit = d => hwRecForm(db.hwRec.find(r => r.id === d.id));
// Les devoirs d'une journée, pour l'accueil et le cahier journal.
const hwGiven = (d, cid) => visHw().filter(h => h.given === d && (!cid || h.cid === cid));
const hwDue = d => visHw().filter(h => h.due === d);
