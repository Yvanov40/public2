'use strict';
/* Liseuse — lecture d'un PDF gardé tel quel : pages d'origine, défilement, zoom au pincement. */

const visionneusePdf = window.visionneusePdf = (() => {

  const ecran = $('#lecteur-pdf');
  const zone = $('#pdf-zone');
  const interne = $('#pdf-interne');
  const ESPACE = 10;

  let doc = null, livre = null, n = 0, jeton = 0;
  let tailles = [];          // [largeur, hauteur] de chaque page en points
  let zoom = 1;              // 1 = largeur de l'écran
  let elems = [];
  let rendus = new Map();    // index -> { canvas, echelle }
  let observateur = null;
  let barre = false;
  let natif = false;         // pages rendues par le moteur PDF d'Android (gros fichiers)
  let attenteInfos = null;
  let generation = 0;        // numéro de session donné par Android (réponses périmées ignorées)
  const nuit = () => !!Store.get('pdfNuit', false);

  function largeurBase() { return zone.clientWidth - 2 * 8; }

  // ---------------------------------------------------------------- ouverture

  async function ouvrir(l) {
    const mon = ++jeton;
    livre = l;
    $('#pdf-titre').textContent = l.titre || 'PDF';
    $('#pdf-num').textContent = '';
    interne.innerHTML = '';
    elems = [];
    rendus = new Map();
    zoom = 1;
    barre = false;
    ecran.classList.remove('barre');
    ecran.classList.toggle('nuit', nuit());
    $('#pdf-chargement').classList.add('actif');
    const R = lecteur.reglages();
    if (A) {
      A.pleinEcran(!!R.pleinEcran);
      A.ecranAllume(!!R.ecranAllume);
      A.couleurs(nuit() ? '#111111' : '#3a3a3c', true);
    }
    lecteur.lumiere(true);
    doc = null;
    natif = false;
    // 1. moteur PDF d'Android : lit les pages une à une dans le fichier, sans tout charger en mémoire.
    //    La réponse arrive de façon asynchrone (visionneusePdf.infos) : l'interface ne se fige jamais.
    if (A && A.pdfOuvrir) {
      const infos = await new Promise(resolve => {
        attenteInfos = resolve;
        A.pdfOuvrir(l.id);
        setTimeout(() => { if (attenteInfos === resolve) { attenteInfos = null; resolve({ erreur: 'délai dépassé' }); } }, 30000);
      });
      if (mon !== jeton) return;
      if (infos && !infos.erreur && infos.pages > 0) {
        natif = true;
        n = infos.pages;
        tailles = Array.from({ length: n }, () => [infos.l, infos.h]);
      } else if (infos && infos.erreur === 'délai dépassé') {
        $('#pdf-chargement').classList.remove('actif');
        app.toast('Ce PDF met trop de temps à s\'ouvrir.', 4000);
        app.fermerPdf();
        return;
      }
    }
    // 2. sinon pdf.js (PDF que le moteur d'Android ne sait pas ouvrir)
    if (!natif) {
      try {
        await ConversionPdf.chargerPdfJs();
        doc = await pdfjsLib.getDocument({ url: '/pdf/' + l.id, isEvalSupported: false }).promise;
      } catch (e) {
        $('#pdf-chargement').classList.remove('actif');
        app.toast('Ce PDF ne peut pas être ouvert : ' + ((e && e.message) || 'erreur'), 4000);
        app.fermerPdf();
        return;
      }
      if (mon !== jeton) return;
      n = doc.numPages;
      const p1 = await doc.getPage(1);
      const v1 = p1.getViewport({ scale: 1 });
      tailles = Array.from({ length: n }, () => [v1.width, v1.height]);
    }
    for (let i = 0; i < n; i++) {
      const d = document.createElement('div');
      d.className = 'pdf-page';
      d.dataset.i = i;
      d.innerHTML = `<span class="pdf-n">${i + 1}</span>`;
      interne.appendChild(d);
      elems.push(d);
    }
    dimensionner();
    // tailles réelles des pages, en arrière-plan (pdf.js seulement)
    if (!natif) (async () => {
      for (let i = 1; i < n && mon === jeton; i++) {
        try {
          const pg = await doc.getPage(i + 1);
          const v = pg.getViewport({ scale: 1 });
          if (Math.abs(v.width / v.height - tailles[i][0] / tailles[i][1]) > 0.01) {
            const ancre = pageCourante();
            tailles[i] = [v.width, v.height];
            dimensionner();
            if (i <= ancre.i) allerA(ancre.i, ancre.f);
          }
        } catch (e) { /* page illisible */ }
      }
    })();
    observer();
    const pos = Store.get('pos:' + l.id, null);
    allerA(pos ? Math.min(n - 1, pos.c | 0) : 0, pos ? pos.f || 0 : 0);
    $('#pdf-chargement').classList.remove('actif');
    majNumero();
    montrerNumero();
  }

  function fermer() {
    sauver();
    jeton++;
    if (observateur) observateur.disconnect();
    observateur = null;
    rendus.forEach(r => { if (r.canvas) r.canvas.width = 0; });
    rendus = new Map();
    interne.innerHTML = '';
    elems = [];
    if (doc) { try { doc.destroy(); } catch (e) { /* rien */ } }
    doc = null;
    if (A && A.pdfFermer) A.pdfFermer();
    attenteInfos = null;
    natif = false;
    livre = null;
    lecteur.lumiere(false);
    if (A) { A.pleinEcran(false); A.ecranAllume(false); }
  }

  // ---------------------------------------------------------------- mise en page

  function dimensionner() {
    const base = largeurBase() * zoom;
    for (let i = 0; i < elems.length; i++) {
      const [w, h] = tailles[i];
      elems[i].style.width = base + 'px';
      elems[i].style.height = Math.round(base * h / w) + 'px';
    }
    interne.style.width = (base + 16) + 'px';
  }

  function observer() {
    if (observateur) observateur.disconnect();
    observateur = new IntersectionObserver(entrees => {
      for (const e of entrees) {
        const i = +e.target.dataset.i;
        if (e.isIntersecting) rendre(i);
      }
      liberer();
    }, { root: zone, rootMargin: '150% 0px' });
    elems.forEach(e => observateur.observe(e));
  }

  /** Page rendue par Android, sous forme d'image (netteté adaptée au zoom). */
  function rendreNatif(i) {
    const el = elems[i];
    if (!el || !livre) return;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const w = Math.min(2000, Math.max(200, Math.ceil(el.clientWidth * dpr / 200) * 200));
    if (+el.dataset.w === w || +el.dataset.demande === w) return;
    el.dataset.demande = w;
    el.classList.add('en-cours');
    el.classList.remove('echec');
    rendus.set(i, { natif: true });
    envoyerVoulues();
    A.pdfPage(livre.id, i, w);
  }

  /** Réponse d'Android : la page i est prête (ou abandonnée / illisible). */
  function pagePrete(g, i, w, r) {
    if (g !== generation || !natif) return;
    const el = elems[i];
    if (!el || +el.dataset.demande !== w) return;
    delete el.dataset.demande;
    el.classList.remove('en-cours');
    if (!r) return; // abandonnée : la page n'est plus à l'écran
    if (r.erreur) {
      el.classList.add('echec');
      el.querySelector('.pdf-n').textContent = `${i + 1} — page illisible`;
      return;
    }
    // vraie taille de la page (si elle diffère de la première)
    if (r.l && r.h && Math.abs(r.l / r.h - tailles[i][0] / tailles[i][1]) > 0.01) {
      const ancre = pageCourante();
      tailles[i] = [r.l, r.h];
      const base = largeurBase() * zoom;
      el.style.height = Math.round(base * r.h / r.l) + 'px';
      if (i < ancre.i) allerA(ancre.i, ancre.f);
    }
    const img = new Image();
    img.alt = '';
    img.decoding = 'async';
    img.onload = () => {
      if (!el.isConnected) return;
      el.querySelectorAll('img').forEach(x => { if (x !== img) x.remove(); });
      el.appendChild(img);
      el.dataset.w = w;
    };
    img.src = r.url;
  }

  /** Indique à Android les pages utiles (visibles et voisines). */
  let minuteVoulues;
  function envoyerVoulues() {
    clearTimeout(minuteVoulues);
    minuteVoulues = setTimeout(() => {
      if (!natif || !A || !A.pdfVoulues) return;
      const { i } = pageCourante();
      const l = [];
      for (let k = Math.max(0, i - 2); k <= Math.min(n - 1, i + 3); k++) l.push(k);
      A.pdfVoulues(JSON.stringify(l));
    }, 0);
  }

  async function rendre(i) {
    if (natif) { rendreNatif(i); return; }
    if (!doc) return;
    const el = elems[i];
    const cssL = el.clientWidth;
    const dpr = Math.min(2.5, window.devicePixelRatio || 1);
    const echelle = cssL / tailles[i][0] * dpr;
    const deja = rendus.get(i);
    if (deja && Math.abs(deja.echelle - echelle) < 0.01) return;
    const mon = jeton;
    const page = await doc.getPage(i + 1);
    if (mon !== jeton) return;
    const vp = page.getViewport({ scale: Math.min(echelle, 6) });
    const c = document.createElement('canvas');
    c.width = Math.round(vp.width);
    c.height = Math.round(vp.height);
    const ctx = c.getContext('2d');
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, c.width, c.height);
    rendus.set(i, { canvas: c, echelle, enCours: true });
    try {
      await page.render({ canvasContext: ctx, viewport: vp }).promise;
    } catch (e) { return; }
    if (mon !== jeton || rendus.get(i) === undefined || rendus.get(i).canvas !== c) return;
    const ancien = el.querySelector('canvas');
    if (ancien) { ancien.width = 0; ancien.remove(); }
    el.appendChild(c);
    rendus.get(i).enCours = false;
  }

  /** Libère la mémoire des pages éloignées. */
  function liberer() {
    const { i } = pageCourante();
    for (const [k, r] of rendus) {
      if (Math.abs(k - i) > 4 && !r.enCours) {
        if (r.natif) {
          const el = elems[k];
          if (el) {
            el.querySelectorAll('img').forEach(x => x.remove());
            delete el.dataset.w;
            delete el.dataset.demande;
            el.classList.remove('en-cours');
          }
        } else {
          r.canvas.width = 0;
          r.canvas.remove();
        }
        rendus.delete(k);
      }
    }
  }

  function pageCourante() {
    const y = zone.scrollTop + zone.clientHeight * 0.3;
    let a = 0, b = elems.length - 1, i = 0;
    while (a <= b) {
      const m = (a + b) >> 1;
      if (elems[m].offsetTop <= y) { i = m; a = m + 1; } else b = m - 1;
    }
    const el = elems[i];
    const f = el ? Math.max(0, Math.min(1, (zone.scrollTop - el.offsetTop) / Math.max(1, el.offsetHeight))) : 0;
    return { i, f };
  }

  function allerA(i, f) {
    const el = elems[i];
    if (!el) return;
    zone.scrollTop = el.offsetTop + (f || 0) * el.offsetHeight - (f ? 0 : 4);
  }

  // ---------------------------------------------------------------- numéro, sauvegarde

  let minuteNum, minuteSauve;
  function majNumero() {
    const { i } = pageCourante();
    $('#pdf-num').textContent = `${i + 1} / ${n}`;
    $('#pdf-pastille').textContent = `${i + 1} / ${n}`;
  }
  function montrerNumero() {
    const p = $('#pdf-pastille');
    p.classList.add('actif');
    clearTimeout(minuteNum);
    minuteNum = setTimeout(() => p.classList.remove('actif'), 1400);
  }
  function sauver() {
    if (!livre || !n) return;
    const { i, f } = pageCourante();
    Store.set('pos:' + livre.id, { c: i, f, p: (i + 1) / n, t: Date.now(), chap: `Page ${i + 1} sur ${n}` });
  }

  zone.addEventListener('scroll', () => {
    if (!livre || !elems.length) return;
    majNumero();
    montrerNumero();
    if (natif) envoyerVoulues();
    clearTimeout(minuteSauve);
    minuteSauve = setTimeout(sauver, 400);
  }, { passive: true });

  // ---------------------------------------------------------------- zoom au pincement, double toucher

  function changerZoom(z, cx, cy) {
    z = Math.max(1, Math.min(5, z));
    const r = zone.getBoundingClientRect();
    cx = cx == null ? r.width / 2 : cx - r.left;
    cy = cy == null ? r.height / 2 : cy - r.top;
    const fx = (zone.scrollLeft + cx) / zoom, fy = (zone.scrollTop + cy) / zoom;
    zoom = z;
    dimensionner();
    zone.scrollLeft = fx * zoom - cx;
    zone.scrollTop = fy * zoom - cy;
    elems.forEach((e, k) => {
      const b = e.getBoundingClientRect();
      if (b.bottom > -r.height && b.top < r.height * 2) rendre(k);
    });
  }

  let pince = null, dernierTap = 0, glisse = false;
  const dist = t => Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY);
  let ignorer = false;
  zone.addEventListener('touchstart', e => {
    glisse = false;
    ignorer = false;
    if (document.querySelector('.feuille.ouvert, .tiroir.ouvert')) {
      app.fermerFeuilles();
      ignorer = true;
    }
    if (e.touches.length === 2) {
      pince = { d: dist(e.touches), z: zoom, cx: (e.touches[0].clientX + e.touches[1].clientX) / 2, cy: (e.touches[0].clientY + e.touches[1].clientY) / 2, s: 1 };
      interne.style.transformOrigin = `${pince.cx - zone.getBoundingClientRect().left + zone.scrollLeft}px ${pince.cy - zone.getBoundingClientRect().top + zone.scrollTop}px`;
    }
  }, { passive: true });
  zone.addEventListener('touchmove', e => {
    glisse = true;
    if (pince && e.touches.length === 2) {
      e.preventDefault();
      pince.s = Math.max(1 / pince.z, Math.min(5 / pince.z, dist(e.touches) / pince.d));
      interne.style.transform = `scale(${pince.s})`;
    }
  }, { passive: false });
  zone.addEventListener('touchend', e => {
    if (pince && e.touches.length < 2) {
      const p = pince;
      pince = null;
      interne.style.transform = '';
      changerZoom(p.z * p.s, p.cx, p.cy);
      glisse = true;
      return;
    }
    if (ignorer) { if (!e.touches.length) ignorer = false; return; }
    if (e.touches.length || glisse || !e.changedTouches.length) return;
    const t = e.changedTouches[0];
    const maintenant = Date.now();
    if (maintenant - dernierTap < 300) {
      dernierTap = 0;
      clearTimeout(minuteTap);
      changerZoom(zoom > 1.2 ? 1 : 2.5, t.clientX, t.clientY);
      return;
    }
    dernierTap = maintenant;
    minuteTap = setTimeout(() => basculerBarre(), 300);
  });
  let minuteTap;
  zone.addEventListener('click', e => {
    if ('ontouchstart' in window) return;
    if (document.querySelector('.feuille.ouvert')) { app.fermerFeuilles(); return; }
    basculerBarre();
  });
  zone.addEventListener('dblclick', e => { if (!('ontouchstart' in window)) changerZoom(zoom > 1.2 ? 1 : 2.5, e.clientX, e.clientY); });

  function basculerBarre(v) {
    barre = v === undefined ? !barre : v;
    ecran.classList.toggle('barre', barre);
    majNumero();
  }

  // ---------------------------------------------------------------- outils

  async function allerPage() {
    const { i } = pageCourante();
    const v = await app.dialogue('Aller à la page', `<label for="n-page">Page (1 à ${n})</label><input type="text" inputmode="numeric" id="n-page" value="${i + 1}">`,
      [{ texte: 'Annuler', valeur: null }, { texte: 'Aller', classe: 'plein', valeur: () => parseInt($('#n-page').value, 10) }]);
    if (v && v >= 1 && v <= n) { allerA(v - 1, 0); sauver(); }
  }

  async function sommaire() {
    if (!doc) {
      // le sommaire vient de pdf.js, qui doit lire tout le fichier : seulement pour les PDF raisonnables
      const taille = A && A.pdfTailleFichier ? A.pdfTailleFichier(livre.id) : 0;
      if (taille > 60 * 1024 * 1024) { app.toast('Sommaire indisponible pour un PDF aussi volumineux. Utilisez « Aller à… ».', 3500); return; }
      try {
        app.toast('Lecture du sommaire…', 1500);
        await ConversionPdf.chargerPdfJs();
        doc = await pdfjsLib.getDocument({ url: '/pdf/' + livre.id, isEvalSupported: false }).promise;
      } catch (e) { app.toast('Ce PDF n\'a pas de sommaire lisible.'); return; }
    }
    let plan = [];
    try { plan = (await doc.getOutline()) || []; } catch (e) { /* sans signets */ }
    if (!plan.length) { app.toast('Ce PDF n\'a pas de sommaire.'); return; }
    const lignes = [];
    const parcourir = (l, niv) => { for (const e of l) { lignes.push({ e, niv }); if (e.items && e.items.length && niv < 3) parcourir(e.items, niv + 1); } };
    parcourir(plan, 0);
    const html = `<div class="liste-dossiers">${lignes.map((x, k) => `<button class="choix-dossier" data-k="${k}" style="padding-left:${14 + x.niv * 20}px">${esc(x.e.title || '…')}</button>`).join('')}</div>`;
    let fait = false;
    app.dialogue('Sommaire', html, [{ texte: 'Fermer', valeur: null }]).then(() => { fait = true; });
    $('#d-corps .liste-dossiers').onclick = async e => {
      const b = e.target.closest('[data-k]');
      if (!b || fait) return;
      const it = lignes[+b.dataset.k].e;
      $('#d-boutons .bouton').click();
      try {
        let dest = it.dest;
        if (typeof dest === 'string') dest = await doc.getDestination(dest);
        if (!dest) return;
        const idx = typeof dest[0] === 'object' ? await doc.getPageIndex(dest[0]) : dest[0];
        allerA(idx, 0);
        sauver();
      } catch (er) { /* destination introuvable */ }
    };
  }

  function basculerNuit() {
    Store.set('pdfNuit', !nuit());
    ecran.classList.toggle('nuit', nuit());
    if (A) A.couleurs(nuit() ? '#111111' : '#3a3a3c', true);
  }

  $('#pdf-retour').onclick = () => app.fermerPdf();
  $('#pdf-page').onclick = allerPage;
  $('#pdf-toc').onclick = sommaire;
  $('#pdf-nuit').onclick = basculerNuit;
  $('#pdf-ajuster').onclick = () => changerZoom(1);
  $('#pdf-lum').onclick = () => { basculerBarre(false); lecteur.panneauLumiere(); };
  window.addEventListener('resize', () => {
    if (!livre || !elems.length) return;
    const p = pageCourante();
    dimensionner();
    allerA(p.i, p.f);
    elems.forEach((e, k) => { const b = e.getBoundingClientRect(); if (b.bottom > 0 && b.top < innerHeight) rendre(k); });
  });

  function infos(g, r) {
    generation = g;
    if (attenteInfos) { const f = attenteInfos; attenteInfos = null; f(r); }
  }

  return { ouvrir, fermer, sauver, infos, pagePrete, barreVisible: () => barre, cacherBarre: () => basculerBarre(false) };
})();
