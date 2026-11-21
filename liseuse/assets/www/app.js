'use strict';
/* Liseuse — bibliothèque, réglages, dialogues. Le lecteur est dans lecteur.js. */

const A = window.Android || null;
const $ = (s, r) => (r || document).querySelector(s);
const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));

const Store = {
  get(k, def) {
    try { const v = localStorage.getItem(k); return v == null ? def : JSON.parse(v); } catch (e) { return def; }
  },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* stockage indisponible */ } },
  del(k) { try { localStorage.removeItem(k); } catch (e) { /* rien */ } }
};

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

const CONVERSION_DEFAUT = { niveauChapitre: 1, detecterChapitres: true, sautsDePage: true, compresserImages: true, couvertureImage: true };
const APP_DEFAUT = { apparence: 'systeme', affichage: 'grille', reprendre: true, tri: 'recent', accent: '#b5543a', pdf: 'demander' };

const app = window.app = {
  livres: [],
  systemeSombre: false,
  ecran: 'biblio',
  fermables: [],
  conversion: Object.assign({}, CONVERSION_DEFAUT, Store.get('conversion', {})),
  prefs: Object.assign({}, APP_DEFAUT, Store.get('app', {})),
  livreActions: null,
  importEnCours: 0,
  dossier: null,
  selection: null,
  importDossier: null
};

// ================================================================ démarrage

function demarrer() {
  try {
    const s = A ? JSON.parse(A.systeme()) : { sombre: matchMedia('(prefers-color-scheme: dark)').matches, version: 'web' };
    app.systemeSombre = !!s.sombre;
    $('#version').textContent = s.version || '';
  } catch (e) { /* rien */ }
  if (A) A.options(JSON.stringify(app.conversion));
  appliquerApparence();
  chargerLivres();
  brancher();
  setInterval(() => { if (app.ecran === 'lecteur' && window.lecteur) lecteur.heure(); }, 20000);
  // reprendre la lecture si l'application a été fermée pendant la lecture
  const ouvert = Store.get('ouvert', null);
  if (/[?&]accueil=1/.test(location.search)) {
    // retour de secours (l'affichage s'était figé) : on ne rouvre pas le livre
    Store.del('ouvert');
  } else if (ouvert && app.prefs.reprendre && app.livres.some(l => l.id === ouvert)) {
    ouvrirLivre(ouvert);
  }
  if (A) A.pret();
}

app.systeme = function (sombre) {
  app.systemeSombre = !!sombre;
  appliquerApparence();
  if (app.ecran === 'lecteur' && window.lecteur) lecteur.appliquerTheme();
};

function interfaceSombre() {
  return app.prefs.apparence === 'sombre' || (app.prefs.apparence === 'systeme' && app.systemeSombre);
}

// ================================================================ couleur de l'application

const ACCENTS = [['#b5543a', 'Terre cuite'], ['#c2457a', 'Framboise'], ['#c27a12', 'Ambre'], ['#2e7d4f', 'Sapin'],
  ['#128a8a', 'Lagon'], ['#2f6db5', 'Océan'], ['#7b5bb5', 'Lavande'], ['#4a5a6a', 'Ardoise']];

function rgbHex(h) {
  h = String(h || '').replace('#', '');
  if (h.length === 3) h = h.split('').map(c => c + c).join('');
  const n = parseInt(h, 16);
  return isNaN(n) ? [181, 84, 58] : [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

function appliquerAccent(sombre) {
  let [r, g, b] = rgbHex(app.prefs.accent);
  if (sombre) { r += (255 - r) * 0.28; g += (255 - g) * 0.28; b += (255 - b) * 0.28; }
  const c = `rgb(${r | 0}, ${g | 0}, ${b | 0})`;
  const st = document.documentElement.style;
  st.setProperty('--accent', c);
  st.setProperty('--accent-doux', `rgba(${r | 0}, ${g | 0}, ${b | 0}, ${sombre ? .18 : .13})`);
  st.setProperty('--accent-ombre', `rgba(${r | 0}, ${g | 0}, ${b | 0}, .4)`);
  const clair = (0.299 * r + 0.587 * g + 0.114 * b) / 255 > 0.62;
  st.setProperty('--sur-accent', clair ? '#1b1210' : '#ffffff');
}

function afficherPersonnalisation() {
  const z = $('#couleurs-app');
  z.innerHTML = ACCENTS.map(([c, n]) => `<button class="pastille${c === app.prefs.accent ? ' actif' : ''}" data-c="${c}" style="background:${c}" aria-label="${n}"></button>`).join('')
    + `<label class="pastille autre${ACCENTS.some(a => a[0] === app.prefs.accent) ? '' : ' actif'}" aria-label="Autre couleur"><input type="color" value="${esc(app.prefs.accent)}">+</label>`;
  z.onclick = e => { const b = e.target.closest('[data-c]'); if (b) choisirAccent(b.dataset.c); };
  $('input', z).onchange = e => choisirAccent(e.target.value);
  const actuelle = A && A.icone ? A.icone() : 1;
  const noms = ['Classique', 'Océan', 'Forêt', 'Lavande', 'Nuit', 'Papier', 'Lettre', 'Rose'];
  $('#icones').innerHTML = noms.map((n, i) => `<button data-icone="${i + 1}" class="${i + 1 === actuelle ? 'actif' : ''}"><img src="/icone/${i + 1}" alt="">${n}</button>`).join('');
  $('#icones').onclick = async e => {
    const b = e.target.closest('[data-icone]');
    if (!b || b.classList.contains('actif')) return;
    const ok = await dialogue('Changer l\'icône ?', '<p>La nouvelle icône apparaît sur l\'écran d\'accueil après quelques secondes. Si l\'ancienne y était épinglée, il faudra peut-être la remettre depuis la liste des applications.</p>',
      [{ texte: 'Annuler', valeur: false }, { texte: 'Changer', classe: 'plein', valeur: true }]);
    if (!ok) return;
    if (A) A.changerIcone(+b.dataset.icone);
    $$('#icones button').forEach(x => x.classList.toggle('actif', x === b));
    toast('Icône changée');
  };
  $('#ligne-raccourci').hidden = !(A && A.raccourciPossible && A.raccourciPossible());
}

function choisirAccent(c) {
  app.prefs.accent = c;
  Store.set('app', app.prefs);
  appliquerApparence();
  afficherPersonnalisation();
}

async function raccourciPerso() {
  const nom = await dialogue('Raccourci personnalisé', `<label for="r-nom">Nom sous l'icône</label><input type="text" id="r-nom" value="Mes livres" maxlength="24">
    <p>Choisissez ensuite une image (photo, dessin…) : elle sera recadrée en carré.</p>`,
    [{ texte: 'Annuler', valeur: null }, { texte: 'Choisir l\'image', classe: 'plein', valeur: () => $('#r-nom').value.trim() || 'Liseuse' }]);
  if (nom && A) A.raccourciPerso(nom);
}

function appliquerApparence() {
  const sombre = interfaceSombre();
  document.documentElement.classList.toggle('sombre', sombre);
  appliquerAccent(sombre);
  if (app.ecran !== 'lecteur') couleursBarres();
}

function couleursBarres() {
  if (!A) return;
  const fond = getComputedStyle(document.documentElement).getPropertyValue('--fond').trim() || '#f7f5f0';
  A.couleurs(fond, interfaceSombre());
}

// ================================================================ bibliothèque

function chargerLivres() {
  try { app.livres = A ? JSON.parse(A.livres()) : (window.LIVRES_TEST || []); } catch (e) { app.livres = []; }
  afficherBiblio();
}

function position(id) { return Store.get('pos:' + id, null); }

function couleurLivre(titre) {
  let h = 0;
  for (const c of String(titre)) h = (h * 31 + c.charCodeAt(0)) | 0;
  const teintes = [[210, 45], [345, 45], [150, 38], [262, 30], [32, 50], [190, 35], [20, 55], [95, 30]];
  const t = teintes[Math.abs(h) % teintes.length];
  return `linear-gradient(150deg, hsl(${t[0]} ${t[1]}% 30%), hsl(${t[0]} ${t[1]}% 46%))`;
}

function htmlCouverture(l) {
  const img = l.couverture ? `<img loading="lazy" src="/couv/${esc(l.id)}?v=${l.convertiLe || 0}" alt="" onerror="this.remove()">` : '';
  return `<div class="couv"><div class="couv-gen" style="background:${couleurLivre(l.titre)}"><span>${esc(l.titre)}</span></div>${img}</div>`;
}

function trier(liste) {
  const t = app.prefs.tri;
  const l = liste.slice();
  const lu = x => (position(x.id) || {}).t || 0;
  const prog = x => (position(x.id) || {}).p || 0;
  const coll = new Intl.Collator('fr', { sensitivity: 'base', numeric: true });
  if (t === 'titre') l.sort((a, b) => coll.compare(a.titre, b.titre));
  else if (t === 'auteur') l.sort((a, b) => coll.compare(a.auteur || '~', b.auteur || '~') || coll.compare(a.titre, b.titre));
  else if (t === 'ajout') l.sort((a, b) => (b.ajoute || 0) - (a.ajoute || 0));
  else if (t === 'progression') l.sort((a, b) => prog(b) - prog(a));
  else l.sort((a, b) => Math.max(lu(b), b.ajoute || 0) - Math.max(lu(a), a.ajoute || 0));
  return l;
}

const NOMS_TRI = { recent: 'Lus récemment', ajout: 'Ajoutés récemment', titre: 'Titre (A → Z)', auteur: 'Auteur', progression: 'Progression' };

// ================================================================ dossiers

function dossiers() { return Store.get('dossiers', []); }
function sauverDossiers(l) { Store.set('dossiers', l); }
function rangement() { return Store.get('rangement', {}); }
function sauverRangement(r) { Store.set('rangement', r); }
function dossierDe(idLivre) {
  const r = rangement()[idLivre];
  return r && dossiers().some(d => d.id === r) ? r : null;
}
function dossierParId(id) { return dossiers().find(d => d.id === id) || null; }
function sousDossiers(parent) { return dossiers().filter(d => (d.parent || null) === (parent || null)); }
function descendants(id) {
  const r = [id];
  for (let i = 0; i < r.length; i++) for (const d of dossiers()) if (d.parent === r[i]) r.push(d.id);
  return r;
}
function nbLivresDans(id) {
  const ids = new Set(descendants(id));
  const ran = rangement();
  return app.livres.filter(l => ids.has(ran[l.id])).length;
}
function cheminDossier(id) {
  const r = [];
  let d = dossierParId(id);
  while (d) { r.unshift(d); d = d.parent ? dossierParId(d.parent) : null; }
  return r;
}
function nouvelIdDossier() { return 'd' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6); }

const COULEURS_DOSSIER = ['#c9893a', '#b5543a', '#c2457a', '#7b5bb5', '#2f6db5', '#128a8a', '#2e7d4f', '#5f6b78'];

async function creerDossier(parent) {
  const nom = await dialogue('Nouveau dossier', `<label for="n-dossier">Nom du dossier</label><input type="text" id="n-dossier" maxlength="60" placeholder="Romans, Cours, Travail…">`,
    [{ texte: 'Annuler', valeur: null }, { texte: 'Créer', classe: 'plein', valeur: () => $('#n-dossier').value.trim() }]);
  if (!nom) return null;
  const l = dossiers();
  const d = { id: nouvelIdDossier(), nom, parent: parent || null, couleur: COULEURS_DOSSIER[l.length % COULEURS_DOSSIER.length] };
  l.push(d);
  sauverDossiers(l);
  afficherBiblio();
  return d.id;
}

function ouvrirDossier(id) {
  app.dossier = id || null;
  $('#filtre').value = '';
  $('#zone-cherche').hidden = true;
  afficherBiblio();
  $('#biblio-defile').scrollTop = 0;
}

/** Choix d'un dossier de destination (renvoie un id, '' pour la racine, ou null si annulé). */
async function choisirDossier(titre, exclus) {
  const interdits = new Set();
  for (const id of exclus || []) for (const x of descendants(id)) interdits.add(x);
  const lignes = [];
  const parcourir = (parent, niveau) => {
    for (const d of sousDossiers(parent).sort((a, b) => a.nom.localeCompare(b.nom, 'fr'))) {
      if (interdits.has(d.id)) continue;
      lignes.push(`<button class="choix-dossier" data-d="${esc(d.id)}" style="padding-left:${14 + niveau * 22}px"><svg style="color:${esc(d.couleur)}"><use href="#i-dossier"/></svg>${esc(d.nom)}</button>`);
      parcourir(d.id, niveau + 1);
    }
  };
  parcourir(null, 1);
  return new Promise(resolve => {
    let fait = false;
    dialogue(titre, `<div class="liste-dossiers">
        <button class="choix-dossier" data-d=""><svg><use href="#i-livre"/></svg>Bibliothèque (aucun dossier)</button>
        ${lignes.join('')}
        <button class="choix-dossier nouveau" data-d="+"><svg><use href="#i-dossier-plus"/></svg>Nouveau dossier…</button>
      </div>`, [{ texte: 'Annuler', valeur: null }]).then(() => { if (!fait) resolve(null); });
    $('.liste-dossiers').onclick = async e => {
      const b = e.target.closest('[data-d]');
      if (!b) return;
      const id = b.dataset.d;
      // ferme le dialogue, puis renvoie le choix
      fait = true;
      $('#d-boutons .bouton').click();
      if (id === '+') resolve(await creerDossier(app.dossier));
      else resolve(id);
    };
  });
}

async function deplacerLivres(ids, idsDossiers) {
  const cible = await choisirDossier('Déplacer vers…', idsDossiers);
  if (cible === null) return false;
  const r = rangement();
  for (const id of ids) {
    if (cible) r[id] = cible;
    else delete r[id];
  }
  sauverRangement(r);
  if (idsDossiers && idsDossiers.length) {
    const l = dossiers();
    for (const d of l) if (idsDossiers.includes(d.id)) d.parent = cible || null;
    sauverDossiers(l);
  }
  const nom = cible ? (dossierParId(cible) || {}).nom : 'la bibliothèque';
  toast(`Déplacé dans « ${nom} »`);
  afficherBiblio();
  return true;
}

async function actionsDossier(id) {
  const d = dossierParId(id);
  if (!d) return;
  app.dossierEdite = id;
  const n = nbLivresDans(id);
  const v = await dialogue(d.nom, `<p>${n} livre${n > 1 ? 's' : ''} dans ce dossier.</p>
    <label>Couleur</label><div class="nuancier" id="couleurs-dossier">${COULEURS_DOSSIER.map(c => `<button class="pastille${c === d.couleur ? ' actif' : ''}" data-c="${c}" style="background:${c}"></button>`).join('')}</div>`,
    [{ texte: 'Supprimer', classe: 'lien danger-texte', valeur: 'suppr' }, { texte: 'Déplacer', valeur: 'depl' }, { texte: 'Renommer', valeur: 'renom' }, { texte: 'Fermer', classe: 'plein', valeur: null }]);
  if (v === 'renom') {
    const nom = await dialogue('Renommer le dossier', `<label for="n-dossier">Nom</label><input type="text" id="n-dossier" maxlength="60" value="${esc(d.nom)}">`,
      [{ texte: 'Annuler', valeur: null }, { texte: 'Enregistrer', classe: 'plein', valeur: () => $('#n-dossier').value.trim() }]);
    if (nom) { const l = dossiers(); l.find(x => x.id === id).nom = nom; sauverDossiers(l); }
  } else if (v === 'depl') {
    await deplacerLivres([], [id]);
  } else if (v === 'suppr') {
    const ok = await dialogue('Supprimer le dossier ?', `<p>Le dossier « ${esc(d.nom)} » est supprimé. <strong>Les livres ne sont pas effacés</strong> : ils remontent dans le dossier parent.</p>`,
      [{ texte: 'Annuler', valeur: false }, { texte: 'Supprimer le dossier', classe: 'danger', valeur: true }]);
    if (ok) supprimerDossiers([id]);
  }
  afficherBiblio();
}

function supprimerDossiers(ids) {
  let l = dossiers();
  const r = rangement();
  for (const id of ids) {
    const d = l.find(x => x.id === id);
    if (!d) continue;
    const parent = d.parent || null;
    for (const x of l) if (x.parent === id) x.parent = parent;
    for (const k in r) if (r[k] === id) { if (parent) r[k] = parent; else delete r[k]; }
    l = l.filter(x => x.id !== id);
  }
  sauverDossiers(l);
  sauverRangement(r);
  if (app.dossier && !l.some(x => x.id === app.dossier)) app.dossier = null;
}

// mise en couleur au toucher d'une pastille dans le dialogue d'un dossier
document.addEventListener('click', e => {
  const b = e.target.closest('#couleurs-dossier [data-c]');
  if (!b) return;
  const l = dossiers();
  const d = l.find(x => x.id === app.dossierEdite);
  if (d) { d.couleur = b.dataset.c; sauverDossiers(l); }
  $$('#couleurs-dossier .pastille').forEach(x => x.classList.toggle('actif', x === b));
});

// ================================================================ affichage de la bibliothèque

function htmlDossier(d) {
  const n = nbLivresDans(d.id);
  const sd = sousDossiers(d.id).length;
  const ran = rangement();
  const couvs = app.livres.filter(l => ran[l.id] === d.id && l.couverture).slice(0, 3);
  const minis = couvs.map(l => `<img src="/couv/${esc(l.id)}?v=${l.convertiLe || 0}" alt="" onerror="this.remove()">`).join('');
  const detail = (n ? `${n} livre${n > 1 ? 's' : ''}` : 'Vide') + (sd ? ` · ${sd} dossier${sd > 1 ? 's' : ''}` : '');
  const choisi = app.selection && app.selection.has('d:' + d.id) ? ' choisi' : '';
  return `<div class="carte dossier${choisi}" data-dossier="${esc(d.id)}" role="button" tabindex="0">
    <div class="couv dossier-couv" style="--dc:${esc(d.couleur || COULEURS_DOSSIER[0])}">
      <svg class="dossier-ico"><use href="#i-dossier"/></svg><div class="minis">${minis}</div><span class="nb">${n}</span>
    </div>
    <div class="txt"><div class="t">${esc(d.nom)}</div><div class="a">${detail}</div></div>
    <span class="coche"><svg><use href="#i-coche"/></svg></span>
    <button class="plus" data-plus-dossier="${esc(d.id)}" aria-label="Options du dossier"><svg><use href="#i-plus-v"/></svg></button>
  </div>`;
}

function htmlLivre(l, avecChemin) {
  const p = position(l.id);
  const pc = p ? Math.round(p.p * 100) : 0;
  const prog = p && p.t ? (pc >= 100 ? '<div class="prog-carte">Terminé ✓</div>'
    : `<div class="prog-carte"><div class="barre-prog"><i style="width:${pc}%"></i></div>${pc} %</div>`) : '';
  const nouveau = !p && Date.now() - (l.ajoute || 0) < 3 * 86400000 ? '<span class="nouveau">Nouveau</span>' : '';
  const formats = { docx: 'Word', pdf: l.original ? 'PDF tel quel' : 'PDF converti', epub: 'EPUB', txt: 'Texte' };
  const dos = avecChemin ? dossierDe(l.id) : null;
  const meta = [formats[l.format] || '', l.taille ? taille(l.taille) : '', dos ? '📁 ' + esc(cheminDossier(dos).map(d => d.nom).join(' › ')) : ''].filter(Boolean).join(' · ');
  const choisi = app.selection && app.selection.has(l.id) ? ' choisi' : '';
  return `<div class="carte${choisi}" data-id="${esc(l.id)}" role="button" tabindex="0">
    ${htmlCouverture(l)}
    <div class="txt"><div class="t">${esc(l.titre)}</div>${l.auteur ? `<div class="a">${esc(l.auteur)}</div>` : ''}<div class="m">${meta}</div>${prog}</div>
    ${nouveau}
    <span class="coche"><svg><use href="#i-coche"/></svg></span>
    <button class="plus" data-plus="${esc(l.id)}" aria-label="Options"><svg><use href="#i-plus-v"/></svg></button>
  </div>`;
}

function afficherBiblio() {
  const filtre = ($('#filtre').value || '').trim().toLowerCase();
  if (app.dossier && !dossierParId(app.dossier)) app.dossier = null;
  const ran = rangement();
  const valides = new Set(dossiers().map(d => d.id));
  let liste, dossiersVus;
  if (filtre) {
    liste = trier(app.livres).filter(l => (l.titre + ' ' + (l.auteur || '') + ' ' + (l.nomFichier || '')).toLowerCase().includes(filtre));
    dossiersVus = dossiers().filter(d => d.nom.toLowerCase().includes(filtre));
  } else {
    liste = trier(app.livres).filter(l => (valides.has(ran[l.id]) ? ran[l.id] : null) === app.dossier);
    dossiersVus = sousDossiers(app.dossier);
  }
  dossiersVus.sort((a, b) => a.nom.localeCompare(b.nom, 'fr', { numeric: true }));
  const grille = $('#grille');
  grille.classList.toggle('liste', app.prefs.affichage === 'liste');
  grille.classList.toggle('en-selection', !!app.selection);
  $('#vide').hidden = app.livres.length > 0 || dossiers().length > 0;
  $('#b-vue use').setAttribute('href', app.prefs.affichage === 'liste' ? '#i-grille' : '#i-liste');

  // titre et fil d'Ariane
  const chemin = app.dossier ? cheminDossier(app.dossier) : [];
  $('#titre-biblio').textContent = chemin.length ? chemin[chemin.length - 1].nom : 'Bibliothèque';
  $('#b-dossier-retour').hidden = !app.dossier;
  const nbIci = liste.length, nbD = dossiersVus.length;
  let info = '';
  if (filtre) info = `${nbIci} résultat${nbIci > 1 ? 's' : ''}`;
  else if (app.livres.length) {
    info = (chemin.length > 1 ? chemin.slice(0, -1).map(d => d.nom).join(' › ') + ' › ' : '')
      + (nbD ? `${nbD} dossier${nbD > 1 ? 's' : ''} · ` : '') + `${nbIci} livre${nbIci > 1 ? 's' : ''} · ${NOMS_TRI[app.prefs.tri] || ''}`;
  }
  $('#tri-info').textContent = info;

  // continuer la lecture (à la racine seulement)
  const cont = $('#continuer');
  const enCours = !filtre && !app.dossier && !app.selection ? app.livres
    .map(l => ({ l, p: position(l.id) }))
    .filter(x => x.p && x.p.t && x.p.p < 0.995)
    .sort((a, b) => b.p.t - a.p.t)[0] : null;
  if (enCours) {
    const pc = Math.round(enCours.p.p * 100);
    cont.innerHTML = `<button class="continuer" data-id="${esc(enCours.l.id)}">
        ${htmlCouverture(enCours.l)}
        <div class="c-txt"><small>Continuer la lecture</small><strong>${esc(enCours.l.titre)}</strong>
        <div class="c-chap">${esc(enCours.p.chap || '')}</div>
        <div class="barre-prog"><i style="width:${pc}%"></i></div></div></button>`;
  } else cont.innerHTML = '';

  let h = dossiersVus.map(htmlDossier).join('') + liste.map(l => htmlLivre(l, !!filtre)).join('');
  if (!h && !filtre && app.dossier) h = '<div class="vide-dossier">Ce dossier est vide.<br>Ajoutez un document ici, ou utilisez « Déplacer » sur des livres.</div>';
  if (!h && filtre) h = '<div class="vide-dossier">Aucun livre ne correspond.</div>';
  grille.innerHTML = h;
  majSelection();
}

// ================================================================ sélection multiple

function commencerSelection(cle) {
  app.selection = new Set(cle ? [cle] : []);
  fermerFeuilles();
  afficherBiblio();
}

function finirSelection() {
  app.selection = null;
  afficherBiblio();
}

function basculerChoix(cle) {
  if (!app.selection) return;
  if (app.selection.has(cle)) app.selection.delete(cle);
  else app.selection.add(cle);
  const el = cle.startsWith('d:') ? $(`[data-dossier="${CSS.escape(cle.slice(2))}"]`) : $(`.carte[data-id="${CSS.escape(cle)}"]`);
  if (el) el.classList.toggle('choisi', app.selection.has(cle));
  majSelection();
}

function majSelection() {
  const actif = !!app.selection;
  $('#barre-biblio').hidden = actif;
  $('#barre-selection').hidden = !actif;
  $('#b-importer').hidden = actif;
  if (!actif) return;
  const n = app.selection.size;
  $('#nb-sel').textContent = n ? `${n} sélectionné${n > 1 ? 's' : ''}` : 'Touchez pour choisir';
  $$('#barre-selection [data-sel]').forEach(b => { b.disabled = !n; });
}

function selectionLivres() {
  if (!app.selection) return [];
  const ids = new Set();
  for (const k of app.selection) {
    if (k.startsWith('d:')) {
      const ds = new Set(descendants(k.slice(2)));
      const ran = rangement();
      for (const l of app.livres) if (ds.has(ran[l.id])) ids.add(l.id);
    } else ids.add(k);
  }
  return [...ids];
}

function toutSelectionner() {
  if (!app.selection) return;
  const cles = [...$$('#grille [data-dossier]').map(e => 'd:' + e.dataset.dossier), ...$$('#grille .carte[data-id]').map(e => e.dataset.id)];
  const tout = cles.every(c => app.selection.has(c));
  app.selection = new Set(tout ? [] : cles);
  afficherBiblio();
}

async function actionSelection(action) {
  if (!app.selection || !app.selection.size) return;
  const livres = [...app.selection].filter(k => !k.startsWith('d:'));
  const dos = [...app.selection].filter(k => k.startsWith('d:')).map(k => k.slice(2));
  const tous = selectionLivres();
  if (action === 'deplacer') {
    if (await deplacerLivres(livres, dos)) finirSelection();
  } else if (action === 'supprimer') {
    const n = tous.length;
    const txt = (n ? `<p><strong>${n} livre${n > 1 ? 's' : ''}</strong> ${n > 1 ? 'seront effacés' : 'sera effacé'} du téléphone, avec position de lecture et marque-pages.</p>` : '')
      + (dos.length ? `<p>${dos.length} dossier${dos.length > 1 ? 's' : ''} ${dos.length > 1 ? 'seront supprimés' : 'sera supprimé'}.</p>` : '');
    const ok = await dialogue('Supprimer la sélection ?', txt, [{ texte: 'Annuler', valeur: false }, { texte: 'Supprimer', classe: 'danger', valeur: true }]);
    if (!ok) return;
    for (const id of tous) {
      if (A) A.supprimer(id);
      ['pos:', 'signets:', 'pages:'].forEach(k => Store.del(k + id));
    }
    const r = rangement();
    for (const id of tous) delete r[id];
    sauverRangement(r);
    supprimerDossiers(dos);
    app.selection = null;
    chargerLivres();
    toast('Supprimé');
  } else if (action === 'partager') {
    if (!tous.length) return toast('Aucun livre dans la sélection');
    if (A) A.partagerPlusieurs(JSON.stringify(tous));
    finirSelection();
  } else if (action === 'exporter') {
    if (!tous.length) return toast('Aucun livre dans la sélection');
    if (A) A.exporterPlusieurs(JSON.stringify(tous));
    finirSelection();
  } else if (action === 'lu' || action === 'nonlu') {
    for (const id of tous) {
      if (action === 'nonlu') Store.del('pos:' + id);
      else {
        const p = position(id) || { c: 0, f: 0 };
        Store.set('pos:' + id, Object.assign(p, { p: 1, t: Date.now() }));
      }
    }
    finirSelection();
    toast(action === 'lu' ? 'Marqué comme lu' : 'Marqué comme non lu');
  }
}

async function plusSelection() {
  const v = await dialogue('Sélection', `<div class="liste-actions dans-dialogue">
      <button data-a="partager"><svg><use href="#i-partage"/></svg>Partager les fichiers EPUB</button>
      <button data-a="exporter"><svg><use href="#i-dl"/></svg>Enregistrer les EPUB dans un dossier du téléphone</button>
      <button data-a="lu"><svg><use href="#i-coche"/></svg>Marquer comme lu</button>
      <button data-a="nonlu"><svg><use href="#i-refaire"/></svg>Marquer comme non lu</button>
    </div>`, [{ texte: 'Fermer', valeur: null }]);
  return v;
}
document.addEventListener('click', e => {
  const b = e.target.closest('.dans-dialogue [data-a]');
  if (!b) return;
  const a = b.dataset.a;
  $('#d-boutons .bouton').click();
  actionSelection(a);
});

async function menuBiblio() {
  const v = await dialogue('Bibliothèque · v' + ($('#version').textContent || '?'), `<div class="liste-actions dans-menu">
      <button data-m="dossier"><svg><use href="#i-dossier-plus"/></svg>Nouveau dossier${app.dossier ? ' ici' : ''}</button>
      <button data-m="selection"><svg><use href="#i-coche"/></svg>Sélectionner plusieurs livres</button>
      <button data-m="tri"><svg><use href="#i-tri"/></svg>Trier par… <small>${esc(NOMS_TRI[app.prefs.tri] || '')}</small></button>
      <button data-m="reglages"><svg><use href="#i-reglages"/></svg>Réglages et personnalisation</button>
    </div>`, [{ texte: 'Fermer', valeur: null }]);
  return v;
}
document.addEventListener('click', e => {
  const b = e.target.closest('.dans-menu [data-m]');
  if (!b) return;
  const m = b.dataset.m;
  $('#d-boutons .bouton').click();
  if (m === 'dossier') creerDossier(app.dossier);
  else if (m === 'selection') commencerSelection(null);
  else if (m === 'tri') choisirTri();
  else if (m === 'reglages') afficherParametres();
});

// ================================================================ écrans

function montrer(ecran) {
  $$('.ecran').forEach(e => e.classList.toggle('actif', e.id === ecran));
  app.ecran = ecran;
  if (ecran !== 'lecteur') couleursBarres();
}

// ================================================================ PDF : convertir ou garder tel quel

let fileChoixPdf = Promise.resolve();
function choisirModePdf(e) {
  fileChoixPdf = fileChoixPdf.then(async () => {
    let mode = app.prefs.pdf;
    if (mode !== 'convertir' && mode !== 'garder') {
      $('#import').classList.remove('actif');
      mode = await new Promise(resolve => {
        let choisi = null;
        dialogue(`« ${e.nom} »`, `<p style="margin:0 0 12px">Comment voulez-vous lire ce PDF ?</p>
          <div class="choix-pdf">
            <button data-mode="convertir"><svg><use href="#i-aa"/></svg><span><strong>Le convertir en livre</strong><small>Texte qui s'adapte à l'écran : taille, police et thème réglables. La mise en page d'origine est perdue.</small></span></button>
            <button data-mode="garder"><svg><use href="#i-livre"/></svg><span><strong>Le garder tel quel</strong><small>Pages identiques à l'original, zoom pour lire. Idéal pour magazines, plans, partitions, documents scannés.</small></span></button>
            <label><input type="checkbox" id="pdf-memo"> Toujours faire ce choix (modifiable dans les réglages)</label>
          </div>`, [{ texte: 'Annuler l\'ajout', valeur: null }]).then(() => resolve(choisi));
        $('.choix-pdf').onclick = ev => {
          const b = ev.target.closest('[data-mode]');
          if (!b) return;
          choisi = b.dataset.mode;
          if ($('#pdf-memo').checked) { app.prefs.pdf = choisi; Store.set('app', app.prefs); }
          $('#d-boutons .bouton').click();
        };
      });
      if (app.importEnCours) $('#import').classList.add('actif');
    }
    if (!A) return;
    if (mode === 'convertir' && e.taille > 80 * 1024 * 1024) {
      // trop gros pour être converti dans le téléphone (tout le fichier passerait en mémoire)
      await dialogue('PDF très volumineux', `<p>« ${esc(e.nom)} » fait ${Math.round(e.taille / 1048576)} Mo : trop lourd pour être converti en livre sur le téléphone. Il est gardé tel quel (pages d'origine, zoom).</p>`);
      mode = 'garder';
    }
    if (mode === 'convertir') A.pdfConvertir(e.jeton);
    else if (mode === 'garder') {
      $('#import-texte').textContent = `Ajout de « ${e.nom} »…`;
      A.pdfGarder(e.jeton);
    } else A.pdfEchec(e.jeton, 'Ajout annulé.');
  });
}

function ouvrirPdf(l) {
  if (app.ecran === 'lecteur-pdf') return;
  montrer('lecteur-pdf');
  Store.set('ouvert', l.id);
  visionneusePdf.ouvrir(l);
}

app.fermerPdf = function () {
  // l'écran d'accueil d'abord : même si la fermeture du lecteur échoue, on revient à la bibliothèque
  $('#lecteur-pdf').classList.remove('actif');
  $('#biblio').classList.add('actif');
  app.ecran = 'biblio';
  Store.del('ouvert');
  montrer('biblio');
  try { visionneusePdf.fermer(); } catch (e) { console.error(e); }
  try { appliquerApparence(); afficherBiblio(); } catch (e) { console.error(e); }
};

function ouvrirLivre(id) {
  const l = app.livres.find(x => x.id === id);
  if (!l) return;
  if (l.original) { ouvrirPdf(l); return; }
  montrer('lecteur');
  Store.set('ouvert', id);
  lecteur.ouvrir(l);
}

function fermerLivre() {
  lecteur.fermer();
  Store.del('ouvert');
  appliquerApparence();
  montrer('biblio');
  afficherBiblio();
}
app.fermerLivre = fermerLivre;

// ================================================================ feuilles, dialogues, messages

function ouvrirFeuille(el, opts) {
  fermerFeuilles(true);
  el.classList.add('ouvert');
  if (!(opts && opts.sansVoile)) $('#voile').classList.add('actif');
  const f = () => fermerFeuille(el);
  f.el = el;
  app.fermables.push(f);
}

function fermerFeuille(el) {
  el.classList.remove('ouvert');
  if (!$$('.feuille.ouvert, .tiroir.ouvert, .dialogue.ouvert').length) $('#voile').classList.remove('actif');
  app.fermables = app.fermables.filter(f => f.el !== el);
}

function fermerFeuilles(garderVoile) {
  $$('.feuille.ouvert, .tiroir.ouvert').forEach(e => e.classList.remove('ouvert'));
  if (!garderVoile) $('#voile').classList.remove('actif');
  app.fermables = [];
}
app.ouvrirFeuille = ouvrirFeuille;
app.fermerFeuilles = fermerFeuilles;

function dialogue(titre, corps, boutons) {
  return new Promise(resolve => {
    const d = $('#dialogue');
    $('#d-titre').textContent = titre;
    $('#d-corps').innerHTML = corps;
    const zone = $('#d-boutons');
    zone.innerHTML = '';
    const fermer = v => {
      d.classList.remove('ouvert');
      if (!$$('.feuille.ouvert, .tiroir.ouvert').length) $('#voile').classList.remove('actif');
      app.fermables = app.fermables.filter(f => f !== annuler);
      resolve(v);
    };
    const annuler = () => fermer(null);
    (boutons || [{ texte: 'OK', valeur: true, classe: 'plein' }]).forEach(b => {
      const btn = document.createElement('button');
      btn.className = 'bouton ' + (b.classe || 'lien');
      btn.textContent = b.texte;
      btn.onclick = () => fermer(typeof b.valeur === 'function' ? b.valeur() : b.valeur);
      zone.appendChild(btn);
    });
    d.classList.add('ouvert');
    $('#voile').classList.add('actif');
    app.fermables.push(annuler);
    const premier = $('input', d);
    if (premier) setTimeout(() => premier.focus(), 150);
  });
}
app.dialogue = dialogue;

let minuteToast;
function toast(msg, duree) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.add('actif');
  clearTimeout(minuteToast);
  minuteToast = setTimeout(() => t.classList.remove('actif'), duree || 2600);
}
app.toast = toast;

// ================================================================ évènements venant d'Android

app.evenement = function (e) {
  if (e.type === 'pdf-choix') {
    choisirModePdf(e);
    return;
  }
  if (e.type === 'pdf') {
    ConversionPdf.ajouter(e);
    return;
  }
  if (e.type === 'debut') {
    app.importEnCours++;
    $('#import-texte').textContent = `Conversion de « ${e.nom} »…`;
    $('#import').classList.add('actif');
  } else if (e.type === 'fini' || e.type === 'reconverti' || e.type === 'erreur') {
    app.importEnCours = Math.max(0, app.importEnCours - 1);
    if (!app.importEnCours) { $('#import').classList.remove('actif'); setTimeout(() => { if (!app.importEnCours) app.importDossier = null; }, 0); }
    chargerLivres();
    if (e.type === 'erreur' && e.message === 'Ajout annulé.') {
      toast('Ajout annulé');
    } else if (e.type === 'erreur') {
      dialogue('Impossible d\'ajouter ce fichier', `<p><strong>${esc(e.nom)}</strong></p><p>${esc(e.message)}</p>`);
    } else if (e.type === 'reconverti') {
      toast(`« ${e.titre} » a été reconverti`);
      Store.del('pages:' + e.id);
    } else {
      if (app.importDossier && dossierParId(app.importDossier)) {
        const r = rangement();
        r[e.id] = app.importDossier;
        sauverRangement(r);
        afficherBiblio();
      }
      toast(`« ${e.titre} » ajouté à la bibliothèque`);
      if (e.ouvrir && !app.importEnCours) {
        if (app.ecran === 'lecteur') lecteur.fermer();
        ouvrirLivre(e.id);
      }
    }
  }
};

app.pause = function () {
  if (app.ecran === 'lecteur' && window.lecteur) lecteur.sauver();
  if (app.ecran === 'lecteur-pdf' && window.visionneusePdf) visionneusePdf.sauver();
  app.enPauseDepuis = Date.now();
};

/** Retour dans l'application : après une vraie absence, la luminosité repart en automatique. */
app.reprise = function () {
  if (app.enPauseDepuis && Date.now() - app.enPauseDepuis > 3 * 60000) lecteur.lumiereAuto();
  app.enPauseDepuis = 0;
};

app.retour = function () {
  if ($('#visionneuse').classList.contains('ouvert')) { lecteur.fermerImage(); return true; }
  if (app.fermables.length) { app.fermables.pop()(); if (!app.fermables.length) fermerFeuilles(); return true; }
  if (app.ecran === 'lecteur') {
    if (lecteur.menusVisibles()) { lecteur.menus(false); return true; }
    fermerLivre();
    return true;
  }
  if (app.ecran === 'parametres') { montrer('biblio'); return true; }
  if (app.ecran === 'lecteur-pdf') {
    app.fermerPdf();
    return true;
  }
  if (app.selection) { finirSelection(); return true; }
  if (!$('#zone-cherche').hidden) { $('#zone-cherche').hidden = true; $('#filtre').value = ''; afficherBiblio(); return true; }
  if (app.dossier) { ouvrirDossier((dossierParId(app.dossier) || {}).parent || null); return true; }
  return false;
};

// ================================================================ actions sur un livre

function actionsLivre(id) {
  const l = app.livres.find(x => x.id === id);
  if (!l) return;
  app.livreActions = l;
  $('#a-couv').innerHTML = htmlCouverture(l);
  $('#a-titre').textContent = l.titre;
  $('#a-auteur').textContent = l.auteur || '';
  $('[data-action=reconvertir]').hidden = !l.sourceExt;
  $('[data-action=vers-epub]').hidden = !l.original;
  $('[data-action=autre-appli]').hidden = !(l.original || l.sourceExt === 'pdf');
  $('[data-action=pdf-origine]').hidden = !(l.sourceExt === 'pdf' && !l.original);
  ouvrirFeuille($('#actions-livre'));
}

function taille(o) {
  if (!o) return '—';
  if (o < 1024 * 1024) return Math.max(1, Math.round(o / 1024)) + ' Ko';
  return (o / 1024 / 1024).toFixed(1).replace('.', ',') + ' Mo';
}

function date(t) {
  if (!t) return '—';
  return new Date(t).toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' });
}

async function faireAction(action) {
  const l = app.livreActions;
  if (!l) return;
  fermerFeuilles();
  if (action === 'lire') ouvrirLivre(l.id);
  else if (action === 'deplacer') deplacerLivres([l.id], []);
  else if (action === 'pdf-origine') ouvrirPdf(l);
  else if (action === 'autre-appli') { if (A) A.ouvrirAilleurs(l.id); }
  else if (action === 'vers-epub') {
    const ok = await dialogue('Convertir en livre ?', '<p>Le PDF est converti en livre au texte adaptable. Le PDF d\'origine est gardé : vous pourrez toujours le revoir tel quel depuis le menu du livre.</p>',
      [{ texte: 'Annuler', valeur: false }, { texte: 'Convertir', classe: 'plein', valeur: true }]);
    if (ok && A) A.pdfVersEpub(l.id);
  }
  else if (action === 'selectionner') commencerSelection(l.id);
  else if (action === 'infos') {
    const p = position(l.id);
    const formats = { docx: 'Document Word (.docx)', epub: 'Livre EPUB', txt: 'Texte (.txt)' };
    await dialogue(l.titre, `<dl>
      <dt>Auteur</dt><dd>${esc(l.auteur || '—')}</dd>
      <dt>Fichier</dt><dd>${esc(l.nomFichier || '—')}</dd>
      <dt>Format</dt><dd>${esc(formats[l.format] || l.format || '—')}</dd>
      <dt>Chapitres</dt><dd>${l.chapitres || '—'}</dd>
      <dt>Taille</dt><dd>${taille(l.taille)}</dd>
      <dt>Ajouté le</dt><dd>${date(l.ajoute)}</dd>
      <dt>Lu le</dt><dd>${p && p.t ? date(p.t) : 'Pas encore ouvert'}</dd>
      <dt>Progression</dt><dd>${p ? Math.round(p.p * 100) + ' %' : '0 %'}</dd>
    </dl>`);
  } else if (action === 'renommer') {
    const v = await dialogue('Titre et auteur', `
      <label for="r-titre">Titre</label><input type="text" id="r-titre" value="${esc(l.titre)}">
      <label for="r-auteur">Auteur</label><input type="text" id="r-auteur" value="${esc(l.auteur || '')}">`,
      [{ texte: 'Annuler', valeur: null }, { texte: 'Enregistrer', classe: 'plein', valeur: () => ({ t: $('#r-titre').value.trim(), a: $('#r-auteur').value.trim() }) }]);
    if (v && v.t) {
      if (A) A.renommer(l.id, v.t, v.a);
      chargerLivres();
    }
  } else if (action === 'exporter') {
    if (A) A.exporter(l.id);
  } else if (action === 'partager') {
    if (A) A.partager(l.id);
  } else if (action === 'reconvertir') {
    const ok = await dialogue('Reconvertir ?', `<p>Le document d'origine sera de nouveau converti avec vos réglages de conversion actuels. Votre position de lecture est conservée.</p>`,
      [{ texte: 'Annuler', valeur: false }, { texte: 'Reconvertir', classe: 'plein', valeur: true }]);
    if (ok && A) A.reconvertir(l.id);
  } else if (action === 'debut') {
    Store.del('pos:' + l.id);
    afficherBiblio();
    toast('Le livre reprendra au début');
  } else if (action === 'supprimer') {
    const ok = await dialogue('Supprimer ce livre ?', `<p>« ${esc(l.titre)} » sera retiré de la bibliothèque, avec sa position de lecture et ses marque-pages.</p>`,
      [{ texte: 'Annuler', valeur: false }, { texte: 'Supprimer', classe: 'danger', valeur: true }]);
    if (ok) {
      if (A) A.supprimer(l.id);
      ['pos:', 'signets:', 'pages:'].forEach(k => Store.del(k + l.id));
      chargerLivres();
      toast('Livre supprimé');
    }
  }
}

async function choisirTri() {
  const corps = Object.keys(NOMS_TRI).map(k =>
    `<label class="ligne"><span>${NOMS_TRI[k]}</span><input type="radio" name="tri" value="${k}" ${app.prefs.tri === k ? 'checked' : ''}></label>`).join('');
  const v = await dialogue('Trier par', corps, [{ texte: 'Annuler', valeur: null }, { texte: 'OK', classe: 'plein', valeur: () => ($('input[name=tri]:checked') || {}).value }]);
  if (v) {
    app.prefs.tri = v;
    Store.set('app', app.prefs);
    afficherBiblio();
  }
}

// ================================================================ réglages généraux

function afficherParametres() {
  $('#o-niveau').value = String(app.conversion.niveauChapitre);
  $('#o-detecter').checked = app.conversion.detecterChapitres;
  $('#o-sauts').checked = app.conversion.sautsDePage;
  $('#o-compresser').checked = app.conversion.compresserImages;
  $('#o-couv').checked = app.conversion.couvertureImage;
  $('#o-apparence').value = app.prefs.apparence;
  $('#o-affichage').value = app.prefs.affichage;
  $('#o-reprendre').checked = app.prefs.reprendre;
  $('#o-pdf').value = app.prefs.pdf || 'demander';
  afficherPersonnalisation();
  montrer('parametres');
}

function sauverParametres() {
  app.conversion = {
    niveauChapitre: parseInt($('#o-niveau').value, 10),
    detecterChapitres: $('#o-detecter').checked,
    sautsDePage: $('#o-sauts').checked,
    compresserImages: $('#o-compresser').checked,
    couvertureImage: $('#o-couv').checked
  };
  Store.set('conversion', app.conversion);
  if (A) A.options(JSON.stringify(app.conversion));
  app.prefs.apparence = $('#o-apparence').value;
  app.prefs.affichage = $('#o-affichage').value;
  app.prefs.reprendre = $('#o-reprendre').checked;
  app.prefs.pdf = $('#o-pdf').value;
  Store.set('app', app.prefs);
  appliquerApparence();
  afficherBiblio();
}

// ================================================================ branchements

function importer() {
  app.importDossier = app.dossier;
  if (A) A.importer();
  else toast('Import disponible dans l\'application Android');
}

function brancher() {
  $('#b-importer').onclick = importer;
  $('#b-importer-vide').onclick = importer;
  $('#b-menu').onclick = menuBiblio;
  $('#b-vue').onclick = () => {
    app.prefs.affichage = app.prefs.affichage === 'liste' ? 'grille' : 'liste';
    Store.set('app', app.prefs);
    afficherBiblio();
  };
  $('#b-dossier-retour').onclick = () => app.retour();
  $('#b-sel-fin').onclick = finirSelection;
  $('#b-sel-tout').onclick = toutSelectionner;
  $('#b-sel-plus').onclick = plusSelection;
  $$('#barre-selection [data-sel]').forEach(b => b.onclick = () => actionSelection(b.dataset.sel));
  $('#b-raccourci').onclick = raccourciPerso;
  $('#b-cherche').onclick = () => {
    const z = $('#zone-cherche');
    z.hidden = !z.hidden;
    if (!z.hidden) $('#filtre').focus();
    else { $('#filtre').value = ''; afficherBiblio(); }
  };
  $('#filtre').oninput = afficherBiblio;
  $$('[data-retour]').forEach(b => b.onclick = () => app.retour());
  $$('#parametres select, #parametres input').forEach(i => i.onchange = sauverParametres);

  // toucher, appui long (sélection), boutons « ⋮ »
  let appuiLong = false, minuteur = null, depart = null;
  const zone = $('#biblio-defile');
  const cleDe = el => el.dataset.dossier ? 'd:' + el.dataset.dossier : el.dataset.id;
  const appui = el => {
    appuiLong = true;
    if (navigator.vibrate) try { navigator.vibrate(25); } catch (er) { /* rien */ }
    if (!app.selection) commencerSelection(cleDe(el));
    else basculerChoix(cleDe(el));
  };
  zone.addEventListener('touchstart', e => {
    const el = e.target.closest('#grille .carte');
    appuiLong = false;
    if (!el || e.target.closest('.plus')) return;
    depart = { x: e.touches[0].clientX, y: e.touches[0].clientY };
    minuteur = setTimeout(() => appui(el), 480);
  }, { passive: true });
  zone.addEventListener('touchmove', e => {
    if (depart && Math.hypot(e.touches[0].clientX - depart.x, e.touches[0].clientY - depart.y) > 10) clearTimeout(minuteur);
  }, { passive: true });
  zone.addEventListener('touchend', () => clearTimeout(minuteur));
  zone.addEventListener('contextmenu', e => {
    e.preventDefault();
    const el = e.target.closest('#grille .carte');
    if (el && !appuiLong && !('ontouchstart' in window)) appui(el);
  });
  zone.addEventListener('click', e => {
    if (appuiLong) { appuiLong = false; return; }
    const plusD = e.target.closest('[data-plus-dossier]');
    if (plusD) { e.stopPropagation(); if (app.selection) basculerChoix('d:' + plusD.dataset.plusDossier); else actionsDossier(plusD.dataset.plusDossier); return; }
    const plus = e.target.closest('[data-plus]');
    if (plus) { e.stopPropagation(); if (app.selection) basculerChoix(plus.dataset.plus); else actionsLivre(plus.dataset.plus); return; }
    const d = e.target.closest('[data-dossier]');
    if (d) { if (app.selection) basculerChoix('d:' + d.dataset.dossier); else ouvrirDossier(d.dataset.dossier); return; }
    const c = e.target.closest('[data-id]');
    if (!c) return;
    if (app.selection && c.classList.contains('carte')) basculerChoix(c.dataset.id);
    else if (!app.selection) ouvrirLivre(c.dataset.id);
  });

  $('#voile').onclick = () => { while (app.fermables.length) app.fermables.pop()(); fermerFeuilles(); };
  $$('#actions-livre [data-action]').forEach(b => b.onclick = () => faireAction(b.dataset.action));

  // glisser vers le bas pour fermer une feuille
  $$('.feuille').forEach(f => {
    let y0 = null;
    const p = $('.poignee', f);
    if (!p) return;
    f.addEventListener('touchstart', e => { if (e.target === p || e.target.closest('.actions-tete')) y0 = e.touches[0].clientY; }, { passive: true });
    f.addEventListener('touchmove', e => {
      if (y0 == null) return;
      const dy = Math.max(0, e.touches[0].clientY - y0);
      f.style.transition = 'none';
      f.style.transform = `translateY(${dy}px)`;
    }, { passive: true });
    f.addEventListener('touchend', e => {
      if (y0 == null) return;
      const dy = e.changedTouches[0].clientY - y0;
      f.style.transition = '';
      f.style.transform = '';
      y0 = null;
      if (dy > 80) app.retour();
    });
  });
}

document.addEventListener('DOMContentLoaded', demarrer);
