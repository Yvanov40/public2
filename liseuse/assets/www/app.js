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
const APP_DEFAUT = { apparence: 'systeme', affichage: 'grille', reprendre: true, tri: 'recent' };

const app = window.app = {
  livres: [],
  systemeSombre: false,
  ecran: 'biblio',
  fermables: [],
  conversion: Object.assign({}, CONVERSION_DEFAUT, Store.get('conversion', {})),
  prefs: Object.assign({}, APP_DEFAUT, Store.get('app', {})),
  livreActions: null,
  importEnCours: 0
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
  if (ouvert && app.prefs.reprendre && app.livres.some(l => l.id === ouvert)) {
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

function appliquerApparence() {
  const sombre = interfaceSombre();
  document.documentElement.classList.toggle('sombre', sombre);
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

function afficherBiblio() {
  const filtre = ($('#filtre').value || '').trim().toLowerCase();
  let liste = trier(app.livres);
  if (filtre) liste = liste.filter(l => (l.titre + ' ' + (l.auteur || '')).toLowerCase().includes(filtre));
  const grille = $('#grille');
  grille.classList.toggle('liste', app.prefs.affichage === 'liste');
  $('#vide').hidden = app.livres.length > 0;
  $('#tri-info').textContent = app.livres.length > 1 ? `${app.livres.length} livres · ${NOMS_TRI[app.prefs.tri] || ''}` : '';

  // continuer la lecture
  const enCours = app.livres
    .map(l => ({ l, p: position(l.id) }))
    .filter(x => x.p && x.p.t && x.p.p < 0.995)
    .sort((a, b) => b.p.t - a.p.t)[0];
  const cont = $('#continuer');
  if (enCours && !filtre) {
    const pc = Math.round(enCours.p.p * 100);
    cont.innerHTML = `<button class="continuer" data-id="${esc(enCours.l.id)}">
        ${htmlCouverture(enCours.l)}
        <div class="c-txt"><small>Continuer la lecture</small><strong>${esc(enCours.l.titre)}</strong>
        <div class="c-chap">${esc(enCours.p.chap || '')}</div>
        <div class="barre-prog"><i style="width:${pc}%"></i></div></div></button>`;
  } else cont.innerHTML = '';

  grille.innerHTML = liste.map(l => {
    const p = position(l.id);
    const pc = p ? Math.round(p.p * 100) : 0;
    const prog = p && p.t ? (pc >= 100 ? '<div class="prog-carte">Terminé ✓</div>'
      : `<div class="prog-carte"><div class="barre-prog"><i style="width:${pc}%"></i></div>${pc} %</div>`) : '';
    const nouveau = !p && Date.now() - (l.ajoute || 0) < 3 * 86400000 ? '<span class="nouveau">Nouveau</span>' : '';
    return `<div class="carte" data-id="${esc(l.id)}" role="button" tabindex="0">
      ${htmlCouverture(l)}
      <div class="txt"><div class="t">${esc(l.titre)}</div>${l.auteur ? `<div class="a">${esc(l.auteur)}</div>` : ''}${prog}</div>
      ${nouveau}
      <button class="plus" data-plus="${esc(l.id)}" aria-label="Options"><svg><use href="#i-plus-v"/></svg></button>
    </div>`;
  }).join('');
}

// ================================================================ écrans

function montrer(ecran) {
  $$('.ecran').forEach(e => e.classList.toggle('actif', e.id === ecran));
  app.ecran = ecran;
  if (ecran !== 'lecteur') couleursBarres();
}

function ouvrirLivre(id) {
  const l = app.livres.find(x => x.id === id);
  if (!l) return;
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
  if (e.type === 'debut') {
    app.importEnCours++;
    $('#import-texte').textContent = `Conversion de « ${e.nom} »…`;
    $('#import').classList.add('actif');
  } else if (e.type === 'fini' || e.type === 'reconverti' || e.type === 'erreur') {
    app.importEnCours = Math.max(0, app.importEnCours - 1);
    if (!app.importEnCours) $('#import').classList.remove('actif');
    chargerLivres();
    if (e.type === 'erreur') {
      dialogue('Impossible d\'ajouter ce fichier', `<p><strong>${esc(e.nom)}</strong></p><p>${esc(e.message)}</p>`);
    } else if (e.type === 'reconverti') {
      toast(`« ${e.titre} » a été reconverti`);
      Store.del('pages:' + e.id);
    } else {
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
  if (!$('#zone-cherche').hidden) { $('#zone-cherche').hidden = true; $('#filtre').value = ''; afficherBiblio(); return true; }
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
  Store.set('app', app.prefs);
  appliquerApparence();
  afficherBiblio();
}

// ================================================================ branchements

function importer() {
  if (A) A.importer();
  else toast('Import disponible dans l\'application Android');
}

function brancher() {
  $('#b-importer').onclick = importer;
  $('#b-importer-vide').onclick = importer;
  $('#b-reglages').onclick = afficherParametres;
  $('#b-tri').onclick = choisirTri;
  $('#b-cherche').onclick = () => {
    const z = $('#zone-cherche');
    z.hidden = !z.hidden;
    if (!z.hidden) $('#filtre').focus();
    else { $('#filtre').value = ''; afficherBiblio(); }
  };
  $('#filtre').oninput = afficherBiblio;
  $$('[data-retour]').forEach(b => b.onclick = () => app.retour());
  $$('#parametres select, #parametres input').forEach(i => i.onchange = sauverParametres);

  let appuiLong = null;
  const zone = $('#biblio-defile');
  zone.addEventListener('click', e => {
    const plus = e.target.closest('[data-plus]');
    if (plus) { e.stopPropagation(); actionsLivre(plus.dataset.plus); return; }
    if (appuiLong === 'fait') { appuiLong = null; return; }
    const c = e.target.closest('[data-id]');
    if (c) ouvrirLivre(c.dataset.id);
  });
  zone.addEventListener('contextmenu', e => {
    const c = e.target.closest('[data-id]');
    if (c) { e.preventDefault(); appuiLong = 'fait'; actionsLivre(c.dataset.id); }
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
