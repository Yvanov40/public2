'use strict';
/* Liseuse — moteur de lecture : pagination en colonnes, gestes, notes, images, recherche, marque-pages. */

const lecteur = window.lecteur = (() => {

  const DEFAUT = {
    taille: 19, theme: 'creme', police: 'literata', interligne: 1.55, marges: 22,
    alignement: 'justifie', paragraphes: 'retrait', mode: 'pages', images: 'document', imagesSombre: 'attenuer',
    cesure: true, gras: false, couleursDoc: true, deuxPages: true, animation: true, uneMain: false,
    volume: true, pleinEcran: true, ecranAllume: false, infos: true, luminosite: -1, liensExternes: true,
    chaud: 0, persoFond: '#f3ece0', persoTexte: '#2b2b2b'
  };

  const THEMES = {
    clair: { nom: 'Clair', fond: '#ffffff', texte: '#1d1d1d', doux: '#8a8a8a', lien: '#1a5fb4', menu: '#f5f5f5', sombre: false },
    creme: { nom: 'Crème', fond: '#faf6ee', texte: '#2b2620', doux: '#8f8574', lien: '#9c4a2f', menu: '#f0eadd', sombre: false },
    sepia: { nom: 'Sépia', fond: '#f0e2c6', texte: '#4a3a28', doux: '#8d7757', lien: '#8a4b1c', menu: '#e6d4b2', sombre: false },
    vert: { nom: 'Tilleul', fond: '#e2ecda', texte: '#25301f', doux: '#6d7f62', lien: '#2f6b3a', menu: '#d4e2c9', sombre: false },
    sombre: { nom: 'Nuit', fond: '#1d1d20', texte: '#d5d0c8', doux: '#86827b', lien: '#e2a585', menu: '#2a2a2e', sombre: true },
    noir: { nom: 'Noir', fond: '#000000', texte: '#b8b4ac', doux: '#6c6964', lien: '#d39575', menu: '#161616', sombre: true }
  };

  const POLICES = {
    literata: { nom: 'Literata', css: '"Literata", Georgia, serif', desc: 'Livre' },
    merriweather: { nom: 'Merriweather', css: '"Merriweather", Georgia, serif', desc: 'Robuste' },
    serif: { nom: 'Serif', css: '"Noto Serif", "Droid Serif", Georgia, serif', desc: 'Système' },
    atkinson: { nom: 'Atkinson', css: '"Atkinson Hyperlegible", system-ui, sans-serif', desc: 'Très lisible' },
    sans: { nom: 'Sans', css: 'Roboto, "Noto Sans", system-ui, sans-serif', desc: 'Système' },
    dys: { nom: 'Dyslexie', css: '"OpenDyslexic", sans-serif', desc: 'OpenDyslexic' },
    document: { nom: 'Document', css: null, desc: 'D\'origine' }
  };

  const FONTES = [['Literata', 'literata'], ['Merriweather', 'merriweather'], ['Atkinson Hyperlegible', 'atkinson-hyperlegible'], ['OpenDyslexic', 'opendyslexic']]
    .map(([f, fic]) => [['400', 'normal'], ['400', 'italic'], ['700', 'normal'], ['700', 'italic']]
      .map(([w, s]) => `@font-face{font-family:"${f}";font-style:${s};font-weight:${w};font-display:block;src:url(/app/polices/${fic}-latin-${w}-${s}.woff2) format("woff2")}`).join('\n')).join('\n');

  // les polices servent aussi aux aperçus du panneau
  const stylePolices = document.createElement('style');
  stylePolices.textContent = FONTES;
  document.head.appendChild(stylePolices);

  let R = Object.assign({}, DEFAUT, Store.get('reglages', {}));
  let livre = null, info = null, spine = [], cumul = [], total = 1;
  let chap = 0, page = 0, pages = 1;
  let doc = null, win = null, W = 0, H = 0, mx = 0, my = 0;
  let jeton = 0, menusOn = false, pretAffiche = false;
  let ancresToc = [];
  let cibleDefil = null, tCible = 0;
  let cacheTextes = new Map();

  const iframe = $('#page');
  const cadre = $('#cadre');
  const ecran = $('#lecteur');

  function theme() {
    if (R.theme === 'perso') return themePerso();
    return THEMES[R.theme] || THEMES.creme;
  }

  // ---------------------------------------------------------------- couleurs utilitaires
  function rgb(h) {
    h = String(h || '').replace('#', '');
    if (h.length === 3) h = h.split('').map(c => c + c).join('');
    const n = parseInt(h, 16);
    return isNaN(n) ? [0, 0, 0] : [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }
  function hex(c) { return '#' + c.map(v => Math.max(0, Math.min(255, Math.round(v))).toString(16).padStart(2, '0')).join(''); }
  function melange(a, b, t) { const x = rgb(a), y = rgb(b); return hex(x.map((v, i) => v + (y[i] - v) * t)); }
  function clarte(h) { const [r, g, b] = rgb(h); return (0.299 * r + 0.587 * g + 0.114 * b) / 255; }

  function themePerso() {
    const fond = R.persoFond, texte = R.persoTexte;
    const sombre = clarte(fond) < 0.45;
    return {
      nom: 'Perso', fond, texte, sombre,
      doux: melange(texte, fond, 0.45),
      lien: melange(texte, sombre ? '#e2a585' : '#9c4a2f', 0.6),
      menu: melange(fond, sombre ? '#ffffff' : '#000000', 0.06)
    };
  }

  // ---------------------------------------------------------------- luminosité (écran + voile extra-sombre + filtre chaud)
  function appliquerLumiere(actif) {
    const voile = $('#voile-lumiere'), chaud = $('#filtre-chaud');
    document.body.classList.toggle('lecture', !!actif);
    if (!actif) {
      if (A) A.luminosite(-1);
      voile.style.opacity = 0;
      chaud.style.opacity = 0;
      return;
    }
    let ecranLum = -1, opacite = 0;
    if (R.luminosite >= 0) {
      const v = R.luminosite * 100;
      if (v >= 20) ecranLum = 0.01 + (v - 20) / 80 * 0.99;
      else { ecranLum = 0.01; opacite = (20 - v) / 20 * 0.85; }
    }
    if (A) A.luminosite(ecranLum);
    voile.style.opacity = opacite;
    chaud.style.opacity = (R.chaud || 0) / 100 * 0.55;
  }

  function texteLumiere() {
    if (R.luminosite < 0) return 'auto';
    const v = Math.round(R.luminosite * 100);
    return v < 20 ? 'nuit ' + Math.round((20 - v) * 5) + ' %' : Math.round((v - 20) * 100 / 80) + ' %';
  }

  function sauverReglages() { Store.set('reglages', R); }

  // ================================================================ ouverture / fermeture

  function ouvrir(l) {
    livre = l;
    cacheTextes = new Map();
    try {
      info = A ? JSON.parse(A.livre(l.id)) : window.INFO_TEST(l.id);
    } catch (e) {
      info = { erreur: e.message };
    }
    if (!info || info.erreur) {
      app.toast('Ce livre ne peut pas être ouvert : ' + ((info && info.erreur) || 'erreur inconnue'), 4000);
      app.fermerLivre();
      return;
    }
    spine = info.spine;
    cumul = [0];
    spine.forEach((s, i) => cumul.push(cumul[i] + Math.max(1, s.taille)));
    total = cumul[cumul.length - 1] || 1;
    $('#l-titre').textContent = livre.titre || info.titre;
    $('#l-auteur').textContent = livre.auteur || info.auteur || '';
    construireToc();
    menus(false);
    appliquerTheme();
    systeme(true);
    const pos = Store.get('pos:' + l.id, null);
    let c = pos ? Math.min(spine.length - 1, Math.max(0, pos.c | 0)) : 0;
    charger(c, { fraction: pos ? pos.f || 0 : 0 });
  }

  function fermer() {
    sauver();
    systeme(false);
    jeton++;
    iframe.classList.remove('visible');
    iframe.removeAttribute('src');
    doc = win = null;
    livre = null;
    app.fermerFeuilles();
    fermerImage();
  }

  function systeme(actif) {
    if (!A) { appliquerLumiere(actif); return; }
    A.pleinEcran(actif && R.pleinEcran);
    A.ecranAllume(actif && R.ecranAllume);
    A.volume(actif && R.volume);
    appliquerLumiere(actif);
  }

  // ================================================================ chargement d'un chapitre

  function urlChapitre(i, html) {
    const chemin = spine[i].href.split('/').map(encodeURIComponent).join('/');
    return `/livre/${livre.id}/${chemin}${html ? '?html=1' : ''}`;
  }

  function charger(i, cible, html) {
    if (!livre) return;
    i = Math.max(0, Math.min(spine.length - 1, i));
    const mon = ++jeton;
    chap = i;
    pretAffiche = false;
    iframe.classList.remove('visible');
    const sablier = setTimeout(() => { if (mon === jeton) $('#chargement').classList.add('actif'); }, 200);
    iframe.onload = () => {
      clearTimeout(sablier);
      if (mon !== jeton) return;
      preparer(mon, cible, html).catch(e => {
        console.error(e);
        $('#chargement').classList.remove('actif');
        iframe.classList.add('visible');
      });
    };
    iframe.src = urlChapitre(i, html);
  }

  async function preparer(mon, cible, html) {
    doc = iframe.contentDocument;
    win = iframe.contentWindow;
    if (!doc || !doc.documentElement) return;
    // XHTML mal formé : on recharge en HTML tolérant
    if (!html && doc.getElementsByTagName('parsererror').length) {
      charger(chap, cible, true);
      return;
    }
    const racine = doc.documentElement;
    if (!racine.getAttribute('lang') && !racine.getAttributeNS('http://www.w3.org/XML/1998/namespace', 'lang')) {
      racine.setAttribute('lang', info.langue || 'fr');
    }
    if (!doc.head) racine.insertBefore(doc.createElementNS('http://www.w3.org/1999/xhtml', 'head'), racine.firstChild);
    const meta = doc.createElementNS('http://www.w3.org/1999/xhtml', 'meta');
    meta.setAttribute('name', 'viewport');
    meta.setAttribute('content', 'width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no');
    doc.head.appendChild(meta);
    for (const id of ['liseuse-theme', 'liseuse-page']) {
      const s = doc.createElementNS('http://www.w3.org/1999/xhtml', 'style');
      s.id = id;
      doc.head.appendChild(s);
    }
    styleLivre();
    brancherDoc();
    if (R.mode === 'defilement' && chap < spine.length - 1) ajouterSuite();
    mettreEnPage();
    // attendre les polices et les images avant de calculer les pages
    await Promise.race([
      Promise.all([doc.fonts ? doc.fonts.ready : null, imagesChargees()]),
      new Promise(r => setTimeout(r, 2500))
    ]);
    if (mon !== jeton) return;
    mettreEnPage();
    allerCible(cible);
    $('#chargement').classList.remove('actif');
    iframe.classList.add('visible');
    pretAffiche = true;
    majInfos();
    sauver();
  }

  function imagesChargees() {
    const imgs = Array.from(doc.images || []);
    return Promise.all(imgs.map(im => im.complete ? null : new Promise(r => {
      im.addEventListener('load', r, { once: true });
      im.addEventListener('error', r, { once: true });
    })));
  }

  function allerCible(c) {
    c = c || {};
    if (c.ancre) {
      if (!allerAncre(c.ancre)) page = 0;
    } else if (c.texte) {
      if (!allerTexte(c.texte, c.occ || 0)) page = 0;
    } else if (c.fin) {
      page = pages - 1;
    } else {
      page = Math.max(0, Math.min(pages - 1, Math.round((c.fraction || 0) * pages)));
    }
    afficherPage(false);
  }

  function ajouterSuite() {
    const d = doc.createElementNS('http://www.w3.org/1999/xhtml', 'div');
    d.id = 'liseuse-suite';
    d.textContent = 'Chapitre suivant ›';
    doc.body && doc.body.appendChild(d);
  }

  // ================================================================ styles injectés

  function styleLivre() {
    if (!doc) return;
    const t = theme();
    const p = POLICES[R.police] || POLICES.literata;
    const r = [];
    r.push(FONTES);
    r.push(`html { background: ${t.fond} !important; color: ${t.texte} !important; -webkit-text-size-adjust: 100% !important; }`);
    r.push(`body { background: transparent !important; color: ${t.texte} !important; font-size: ${R.taille}px !important; line-height: ${R.interligne} !important;
      -webkit-hyphens: ${R.cesure ? 'auto' : 'manual'} !important; hyphens: ${R.cesure ? 'auto' : 'manual'} !important; overflow-wrap: break-word;
      -webkit-user-select: text; ${p.css ? `font-family: ${p.css} !important;` : ''} ${R.gras ? '-webkit-text-stroke: .35px currentColor;' : ''} }`);
    if (p.css) r.push(`body *:not(pre):not(code):not(kbd):not(samp):not(tt) { font-family: inherit !important; }`);
    r.push(`body p, body li, body blockquote, body dd, body dt, body td, body th, body div { line-height: ${R.interligne} !important; }`);
    r.push(`body h1, body h2, body h3, body h4, body h5, body h6 { line-height: 1.3 !important; -webkit-hyphens: manual !important; hyphens: manual !important; }`);
    r.push(`body *:not(.coul):not(mark) { color: inherit !important; }`);
    r.push(`body *:not(.coul):not(td):not(th):not(tr):not(table):not(mark) { background-color: transparent !important; }`);
    if (!R.couleursDoc) r.push(`body .coul { color: inherit !important; background-color: transparent !important; }`);
    else if (t.sombre) r.push(`body .coul[style*="background"] { color: #161616 !important; }`);
    r.push(`body a[href]:not(.coul):not(mark), body a[href] *:not(.coul):not(mark) { color: ${t.lien} !important; }`);
    if (!R.liensExternes) {
      // liens vers Internet désactivés : affichés comme du texte normal
      const ext = ['http:', 'https:', 'mailto:', 'tel:', 'www.'].map(x => `body a[href^="${x}"]:not(.coul):not(mark)`);
      r.push(`${ext.join(', ')}, ${ext.map(x => x + ' *').join(', ')} { color: inherit !important; text-decoration: none !important; cursor: default; }`);
    }
    r.push(`body a.noteref, body a[href*="#n"] sup, body sup a { text-decoration: none !important; }`);
    if (R.alignement === 'justifie') r.push(`body p, body li, body blockquote, body dd { text-align: justify; }`);
    else if (R.alignement === 'gauche') r.push(`body p, body li, body blockquote, body dd { text-align: left; } body { text-align: left !important; }`);
    if (R.paragraphes === 'retrait') {
      r.push(`body p { text-indent: 1.3em; margin-top: 0; margin-bottom: 0; }`);
      r.push(`body :is(h1,h2,h3,h4,h5,h6,hr,.saut,.fig,blockquote,ul,ol,table,.tableau,.vide,header) + p, body li p, body td p, body th p, body blockquote p, body p:first-child { text-indent: 0; }`);
    } else if (R.paragraphes === 'espace') {
      r.push(`body p { text-indent: 0 !important; margin: 0 0 .75em !important; }`);
      r.push(`body p.vide { display: none; }`);
    }
    r.push(`img, svg, video { max-width: 100% !important; height: auto !important; object-fit: contain; }`);
    if (R.images === 'large') r.push(`body .fig img, body p > img:only-child, body div > img:only-child { width: 100% !important; }`);
    if (t.sombre && R.imagesSombre === 'attenuer') r.push(`img, svg image { filter: brightness(.78) contrast(1.08); }`);
    if (t.sombre && R.imagesSombre === 'inverser') r.push(`img, svg image { filter: invert(.9) hue-rotate(180deg); }`);
    r.push(`::selection { background: rgba(224, 134, 106, .35); }`);
    r.push(`mark.liseuse-trouve { background: #ffd54f !important; color: #111 !important; border-radius: 2px; }`);
    r.push(`hr { border: 0; border-top: 1px solid rgba(128,128,128,.4); }`);
    r.push(`hr.scene { border: 0 !important; text-align: center; height: auto; } hr.scene::after { content: "* * *"; letter-spacing: .4em; color: ${t.doux}; }`);
    r.push(`#liseuse-suite { margin: 2.5em 0 1em; padding: 1em; text-align: center; border-radius: 12px; border: 1px solid rgba(128,128,128,.35); color: ${t.lien} !important; font-weight: 600; text-indent: 0; }`);
    r.push(`table { max-width: 100%; }`);
    doc.getElementById('liseuse-theme').textContent = r.join('\n');
  }

  function mettreEnPage() {
    if (!doc || !doc.body) return;
    W = cadre.clientWidth;
    H = cadre.clientHeight;
    const deux = R.mode === 'pages' && R.deuxPages && W > H && W >= 600;
    mx = Math.max(R.marges, deux ? 20 : 0);
    if (R.mode === 'pages' && mx < 6) mx = 6;
    my = R.infos ? 10 : 26;
    const gap = Math.max(2 * mx, deux ? 40 : 0);
    if (deux) mx = gap / 2;
    const s = doc.getElementById('liseuse-page');
    if (R.mode === 'pages') {
      s.textContent = `
        html { height: ${H}px !important; width: ${W}px !important; max-width: none !important; overflow: hidden !important; margin: 0 !important; padding: 0 !important; }
        body { margin: 0 !important; height: ${H}px !important; min-height: 0 !important; max-height: none !important; width: auto !important; max-width: none !important;
          box-sizing: border-box !important; padding: ${my}px ${mx}px !important; overflow: visible !important;
          column-count: ${deux ? 2 : 1} !important; column-width: auto !important; column-gap: ${gap}px !important; column-fill: auto !important; }
        img, svg, video { max-height: ${H - 2 * my - 4}px !important; }
        .fig, figure { break-inside: avoid; }
        p:has(> img:only-child) { break-inside: avoid; }
        .saut { break-before: column !important; }
        body > *:first-child { margin-top: 0 !important; }`;
    } else {
      s.textContent = `
        html { height: auto !important; overflow-x: hidden !important; overflow-y: auto !important; margin: 0 !important; padding: 0 !important; }
        body { margin: 0 !important; height: auto !important; max-width: none !important; box-sizing: border-box !important;
          padding: ${my}px ${mx}px 40px !important; columns: auto !important; column-count: auto !important; overflow: visible !important; }
        img, svg, video { max-height: ${Math.round(H * 0.9)}px !important; }`;
    }
    const se = defileur();
    if (R.mode === 'pages') {
      pages = Math.max(1, Math.ceil((se.scrollWidth - mx - 1) / W));
    } else {
      pages = Math.max(1, Math.ceil((se.scrollHeight - 2) / H));
    }
    calculerAncresToc();
  }

  function defileur() { return doc.scrollingElement || doc.documentElement; }

  function relayout() {
    if (!doc || !pretAffiche) return;
    const f = fractionCourante();
    mettreEnPage();
    if (R.mode === 'pages') page = Math.max(0, Math.min(pages - 1, Math.round(f * pages)));
    afficherPage(false, f);
    majInfos();
  }

  function fractionCourante() {
    if (!doc) return 0;
    if (R.mode === 'pages') return pages ? page / pages : 0;
    const se = defileur();
    const max = se.scrollHeight - H;
    return max > 0 ? se.scrollTop / max : 0;
  }

  function afficherPage(anime, fractionDefil) {
    if (!doc) return;
    const se = defileur();
    if (R.mode === 'pages') {
      const x = page * W;
      if (anime && R.animation) se.scrollTo({ left: x, top: 0, behavior: 'smooth' });
      else { se.scrollLeft = x; se.scrollTop = 0; }
    } else if (fractionDefil != null || page != null) {
      const max = se.scrollHeight - H;
      if (fractionDefil != null) se.scrollTop = Math.round(fractionDefil * max);
      else if (anime === 'debut') se.scrollTop = 0;
      else se.scrollTop = Math.min(max, page * H);
    }
  }

  // ================================================================ navigation

  function tourner(dir) {
    if (!doc || !pretAffiche) return;
    effacerTrouve();
    if (R.mode === 'pages') {
      if (dir > 0) {
        if (page < pages - 1) { page++; afficherPage(true); }
        else if (chap < spine.length - 1) { charger(chap + 1, { fraction: 0 }); return; }
        else { app.toast('Vous avez terminé le livre 🎉'); sauver(true); return; }
      } else {
        if (page > 0) { page--; afficherPage(true); }
        else if (chap > 0) { charger(chap - 1, { fin: true }); return; }
        else return;
      }
    } else {
      const se = defileur();
      const max = se.scrollHeight - H;
      const base = (Date.now() - tCible < 500 && cibleDefil != null) ? cibleDefil : se.scrollTop;
      if (dir > 0 && base >= max - 4) {
        if (chap < spine.length - 1) { charger(chap + 1, { fraction: 0 }); return; }
        app.toast('Vous avez terminé le livre 🎉');
        sauver(true);
        return;
      }
      if (dir < 0 && base <= 2) {
        if (chap > 0) charger(chap - 1, { fin: true });
        return;
      }
      cibleDefil = Math.max(0, Math.min(max, base + dir * (H - 48)));
      tCible = Date.now();
      if (R.animation) animerDefil(se, cibleDefil);
      else se.scrollTop = cibleDefil;
    }
    setTimeout(majInfos, R.animation ? 350 : 0);
    sauverPlusTard();
  }

  /** Défilement animé « maison » : un toucher pendant l'animation reste un toucher. */
  let animId = 0;
  function animerDefil(se, dest) {
    const mon = ++animId, depart = se.scrollTop, t0 = performance.now(), duree = 240;
    const pas = now => {
      if (mon !== animId) return;
      const k = Math.min(1, (now - t0) / duree);
      se.scrollTop = depart + (dest - depart) * (1 - Math.pow(1 - k, 3));
      if (k < 1) requestAnimationFrame(pas);
    };
    requestAnimationFrame(pas);
  }

  function allerAncre(id) {
    let el = doc.getElementById(id);
    if (!el) el = doc.querySelector(`[name="${CSS.escape(id)}"]`);
    if (!el) return false;
    allerElement(el.getBoundingClientRect());
    return true;
  }

  function allerElement(rect) {
    const se = defileur();
    if (R.mode === 'pages') {
      const x = rect.left + se.scrollLeft;
      page = Math.max(0, Math.min(pages - 1, Math.floor((x + 1) / W)));
      afficherPage(false);
    } else {
      se.scrollTop = Math.max(0, rect.top + se.scrollTop - my);
    }
  }

  function aller(chemin, ancre) {
    const i = spine.findIndex(s => s.href === chemin);
    if (i < 0) return false;
    if (i === chap && doc) {
      if (ancre) allerAncre(ancre);
      else { page = 0; afficherPage(false, 0); }
      majInfos();
      sauver();
    } else {
      charger(i, ancre ? { ancre } : { fraction: 0 });
    }
    return true;
  }

  function allerPosition(v) {
    const cible = v * total;
    let i = 0;
    while (i < spine.length - 1 && cumul[i + 1] <= cible) i++;
    const f = Math.max(0, Math.min(0.999, (cible - cumul[i]) / (cumul[i + 1] - cumul[i])));
    if (i === chap && doc) {
      if (R.mode === 'pages') { page = Math.min(pages - 1, Math.floor(f * pages)); afficherPage(false); }
      else afficherPage(false, f);
      majInfos();
      sauver();
    } else charger(i, { fraction: f });
  }

  // ================================================================ gestes dans la page

  function brancherDoc() {
    let t0 = null;
    doc.addEventListener('touchstart', e => {
      if (e.touches.length > 1) { t0 = null; return; }
      const t = e.touches[0];
      t0 = { x: t.clientX, y: t.clientY, t: Date.now() };
    }, { passive: true });
    doc.addEventListener('touchend', e => {
      if (!t0) return;
      const t = e.changedTouches[0];
      const dx = t.clientX - t0.x, dy = t.clientY - t0.y, dt = Date.now() - t0.t;
      t0 = null;
      const sel = win.getSelection();
      if (sel && !sel.isCollapsed && sel.toString().trim()) { recaler(); return; }
      if (R.mode === 'pages' && Math.abs(dx) > 40 && Math.abs(dx) > Math.abs(dy) * 1.2 && dt < 900) {
        if (menusOn) menus(false);
        tourner(dx < 0 ? 1 : -1);
      } else if (Math.abs(dx) < 12 && Math.abs(dy) < 12 && dt < 400) {
        // toucher simple : traité ici pour ne pas être perdu pendant un défilement animé
        e.preventDefault();
        toucher(e.target, t.clientX);
      }
    });
    doc.addEventListener('click', e => {
      if (toucher(e.target, e.clientX)) e.preventDefault();
    });
    doc.addEventListener('keydown', clavier);
    doc.addEventListener('selectionchange', () => { });
    if (R.mode === 'defilement') {
      let attente;
      doc.addEventListener('scroll', () => {
        clearTimeout(attente);
        attente = setTimeout(() => { majInfos(); sauver(); }, 250);
      }, { passive: true });
    }
    doc.addEventListener('contextmenu', e => {
      if (e.target.closest && e.target.closest('img')) e.preventDefault();
    });
  }

  /** Toucher (ou clic) dans la page : lien, image, zones de navigation. Renvoie true si traité. */
  function toucher(cible, x) {
    if (document.querySelector('.feuille.ouvert, .tiroir.ouvert')) { app.fermerFeuilles(); return true; }
    const sel = win.getSelection();
    if (sel && !sel.isCollapsed && sel.toString().trim()) { sel.removeAllRanges(); return true; }
    if (cible && cible.nodeType !== 1) cible = cible.parentElement;
    const a = cible && cible.closest && cible.closest('a[href]');
    if (a && !(lienExterne(a) && !R.liensExternes)) { lien(a); return true; }
    if (menusOn) { menus(false); return true; }
    const img = cible && cible.closest && cible.closest('img, image');
    if (img) { ouvrirImage(img); return true; }
    if (cible && cible.id === 'liseuse-suite') { tourner(1); return true; }
    const largeur = win.innerWidth || W;
    if (x < largeur * 0.3) tourner(R.uneMain ? 1 : -1);
    else if (x > largeur * 0.7) tourner(1);
    else menus(true);
    return true;
  }

  /** Après une sélection de texte, la page peut avoir glissé : on la recale. */
  function recaler() {
    if (R.mode !== 'pages' || !doc) return;
    const se = defileur();
    const p = Math.round(se.scrollLeft / W);
    if (se.scrollLeft !== p * W) {
      setTimeout(() => {
        const sel = win.getSelection();
        if (sel && !sel.isCollapsed) return;
        page = Math.max(0, Math.min(pages - 1, p));
        afficherPage(false);
        majInfos();
      }, 600);
    }
  }

  function clavier(e) {
    if (app.ecran !== 'lecteur' || $('.feuille.ouvert, .tiroir.ouvert, .dialogue.ouvert')) return;
    const k = e.key;
    if (k === 'ArrowRight' || k === 'PageDown' || k === ' ') { e.preventDefault(); tourner(1); }
    else if (k === 'ArrowLeft' || k === 'PageUp') { e.preventDefault(); tourner(-1); }
  }
  document.addEventListener('keydown', clavier);

  function cheminDepuisUrl(u) {
    const base = location.origin + '/livre/' + livre.id + '/';
    if (!u.startsWith(base)) return null;
    const url = new URL(u);
    return {
      chemin: decodeURIComponent(url.pathname.slice(('/livre/' + livre.id + '/').length)),
      ancre: url.hash ? decodeURIComponent(url.hash.slice(1)) : ''
    };
  }

  /** Le lien sort-il du livre (Internet, courriel, téléphone) ? */
  function lienExterne(a) {
    const href = a.href && a.href.baseVal !== undefined ? a.href.baseVal : a.href;
    return !!href && !cheminDepuisUrl(String(href));
  }

  function lien(a) {
    const href = a.href && a.href.baseVal !== undefined ? a.href.baseVal : a.href;
    if (!href) return;
    const c = cheminDepuisUrl(String(href));
    if (!c) {
      if (/^(https?|mailto|tel):/i.test(href)) {
        app.dialogue('Ouvrir le lien ?', `<p style="word-break:break-all">${esc(href)}</p>`,
          [{ texte: 'Annuler', valeur: false }, { texte: 'Ouvrir', classe: 'plein', valeur: true }])
          .then(ok => { if (ok && A && R.liensExternes) A.lien(href); });
      }
      return;
    }
    const type = (a.getAttributeNS('http://www.idpf.org/2007/ops', 'type') || a.getAttribute('epub:type') || '') + ' ' + (a.getAttribute('role') || '') + ' ' + a.className;
    const estNote = /noteref|doc-noteref/.test(type) || (c.ancre && a.closest('sup') && a.textContent.trim().length <= 4)
      || (c.ancre && /^\[?\*?\d{1,3}\]?$/.test(a.textContent.trim()));
    if (estNote && c.ancre) afficherNote(c.chemin, c.ancre);
    else aller(c.chemin, c.ancre);
  }

  // ================================================================ notes

  async function documentDe(chemin) {
    const i = spine.findIndex(s => s.href === chemin);
    if (i === chap && doc) return doc;
    const url = `/livre/${livre.id}/${chemin.split('/').map(encodeURIComponent).join('/')}`;
    const txt = await (await fetch(url)).text();
    let d = new DOMParser().parseFromString(txt, 'application/xhtml+xml');
    if (d.getElementsByTagName('parsererror').length) d = new DOMParser().parseFromString(txt, 'text/html');
    return d;
  }

  async function afficherNote(chemin, ancre) {
    try {
      const d = await documentDe(chemin);
      let el = d.getElementById(ancre) || d.querySelector(`[name="${CSS.escape(ancre)}"]`);
      if (!el) { aller(chemin, ancre); return; }
      let bloc = el;
      if (/^(a|span|sup)$/i.test(el.localName)) bloc = el.closest('aside, li, p, div, section') || el.parentNode;
      const clone = bloc.cloneNode(true);
      clone.querySelectorAll('a[href]').forEach(x => {
        if (/^(\d+\.?|↩|\^|\[\d+\])$/.test(x.textContent.trim())) x.remove();
      });
      clone.querySelectorAll('img').forEach(im => {
        const src = im.getAttribute('src');
        if (src) im.src = new URL(src, location.origin + `/livre/${livre.id}/${chemin}`).href;
      });
      $('#note-corps').innerHTML = '';
      $('#note-corps').appendChild(document.importNode(clone, true));
      // retire la ponctuation laissée par le lien de retour (« 1. » → « . »)
      const w = document.createTreeWalker($('#note-corps'), NodeFilter.SHOW_TEXT);
      for (let n = w.nextNode(); n; n = w.nextNode()) {
        if (!n.textContent.trim()) continue;
        n.textContent = n.textContent.replace(/^[\s.:)\]]+/, '');
        break;
      }
      $('#note-aller').onclick = () => { app.fermerFeuilles(); aller(chemin, ancre); };
      app.ouvrirFeuille($('#note'));
    } catch (e) {
      aller(chemin, ancre);
    }
  }

  // ================================================================ visionneuse d'images

  const vis = $('#visionneuse'), vimg = $('#v-img');
  let vz = { s: 1, x: 0, y: 0, s0: 1 };

  function ouvrirImage(img) {
    const src = img.currentSrc || img.src || (img.href && img.href.baseVal);
    if (!src) return;
    vimg.onload = () => {
      const vw = innerWidth, vh = innerHeight;
      const s0 = Math.min(vw / vimg.naturalWidth, vh / vimg.naturalHeight);
      vz = { s: s0, s0, x: (vw - vimg.naturalWidth * s0) / 2, y: (vh - vimg.naturalHeight * s0) / 2 };
      appliquerZoom();
    };
    vimg.src = new URL(src, iframe.src).href;
    vis.classList.add('ouvert');
  }

  function fermerImage() {
    vis.classList.remove('ouvert');
    vimg.removeAttribute('src');
  }

  function appliquerZoom() {
    vimg.style.width = vimg.naturalWidth + 'px';
    vimg.style.height = vimg.naturalHeight + 'px';
    vimg.style.transform = `translate(${vz.x}px, ${vz.y}px) scale(${vz.s})`;
  }

  function borner() {
    const vw = innerWidth, vh = innerHeight;
    const w = vimg.naturalWidth * vz.s, h = vimg.naturalHeight * vz.s;
    vz.x = w <= vw ? (vw - w) / 2 : Math.min(0, Math.max(vw - w, vz.x));
    vz.y = h <= vh ? (vh - h) / 2 : Math.min(0, Math.max(vh - h, vz.y));
  }

  function zoomer(s, cx, cy) {
    s = Math.max(vz.s0, Math.min(vz.s0 * 8, s));
    vz.x = cx - (cx - vz.x) * (s / vz.s);
    vz.y = cy - (cy - vz.y) * (s / vz.s);
    vz.s = s;
    borner();
    appliquerZoom();
  }

  (function gestesVisionneuse() {
    let pts = null, dernierTap = 0, depart = null;
    const dist = t => Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY);
    vis.addEventListener('touchstart', e => {
      if (e.target.closest('.ico')) return;
      e.preventDefault();
      const t = e.touches;
      if (t.length === 2) pts = { d: dist(t), s: vz.s, cx: (t[0].clientX + t[1].clientX) / 2, cy: (t[0].clientY + t[1].clientY) / 2 };
      else if (t.length === 1) {
        pts = { x: t[0].clientX, y: t[0].clientY, vx: vz.x, vy: vz.y };
        depart = { x: t[0].clientX, y: t[0].clientY, t: Date.now() };
      }
    }, { passive: false });
    vis.addEventListener('touchmove', e => {
      e.preventDefault();
      const t = e.touches;
      if (!pts) return;
      if (t.length === 2 && pts.d) zoomer(pts.s * dist(t) / pts.d, pts.cx, pts.cy);
      else if (t.length === 1 && pts.x != null) {
        vz.x = pts.vx + t[0].clientX - pts.x;
        vz.y = pts.vy + t[0].clientY - pts.y;
        if (vz.s > vz.s0 * 1.01) borner();
        appliquerZoom();
      }
    }, { passive: false });
    vis.addEventListener('touchend', e => {
      if (e.target.closest('.ico')) return;
      const t = e.changedTouches[0];
      if (e.touches.length === 1) {
        pts = { x: e.touches[0].clientX, y: e.touches[0].clientY, vx: vz.x, vy: vz.y };
        return;
      }
      pts = null;
      if (depart && e.touches.length === 0) {
        const dx = t.clientX - depart.x, dy = t.clientY - depart.y, dt = Date.now() - depart.t;
        if (vz.s <= vz.s0 * 1.01 && Math.abs(dy) > 100 && Math.abs(dy) > Math.abs(dx)) { fermerImage(); return; }
        if (Math.abs(dx) < 10 && Math.abs(dy) < 10 && dt < 300) {
          if (Date.now() - dernierTap < 320) {
            zoomer(vz.s > vz.s0 * 1.2 ? vz.s0 : vz.s0 * 2.5, t.clientX, t.clientY);
            dernierTap = 0;
            return;
          }
          dernierTap = Date.now();
        }
      }
      borner();
      appliquerZoom();
    });
    vis.addEventListener('wheel', e => { e.preventDefault(); zoomer(vz.s * (e.deltaY < 0 ? 1.15 : 0.87), e.clientX, e.clientY); }, { passive: false });
    vis.addEventListener('dblclick', e => zoomer(vz.s > vz.s0 * 1.2 ? vz.s0 : vz.s0 * 2.5, e.clientX, e.clientY));
    $('[data-fermer]', vis).onclick = fermerImage;
  })();

  // ================================================================ informations, progression

  function progression() {
    if (!spine.length) return 0;
    const taille = cumul[chap + 1] - cumul[chap];
    let f;
    if (R.mode === 'pages') f = (page + 1) / pages;
    else {
      const se = doc ? defileur() : null;
      const max = se ? se.scrollHeight - H : 0;
      f = max > 0 ? (se.scrollTop + H) / se.scrollHeight : 1;
    }
    return Math.max(0, Math.min(1, (cumul[chap] + taille * f) / total));
  }

  function pageDefil() {
    if (!doc) return 0;
    return Math.min(pages - 1, Math.round(defileur().scrollTop / H));
  }

  function calculerAncresToc() {
    ancresToc = [];
    if (!info || !doc) return;
    info.toc.forEach((t, k) => {
      const [chemin, ancre] = t.href.split('#');
      if (chemin !== spine[chap].href) return;
      let p = 0;
      if (ancre) {
        const el = doc.getElementById(ancre);
        if (el) {
          const r = el.getBoundingClientRect();
          const se = defileur();
          p = R.mode === 'pages' ? Math.floor((r.left + se.scrollLeft + 1) / W) : Math.floor((r.top + se.scrollTop) / H);
        }
      }
      ancresToc.push({ k, p });
    });
  }

  /** Indice de l'entrée du sommaire correspondant à la position actuelle. */
  function entreeCourante() {
    const p = R.mode === 'pages' ? page : pageDefil();
    let meilleur = -1;
    for (const a of ancresToc) if (a.p <= p) meilleur = a.k;
    if (meilleur >= 0) return meilleur;
    // sinon : dernière entrée d'un chapitre précédent
    for (let k = info.toc.length - 1; k >= 0; k--) {
      const i = spine.findIndex(s => s.href === info.toc[k].href.split('#')[0]);
      if (i >= 0 && i < chap) return k;
    }
    return ancresToc.length ? ancresToc[0].k : -1;
  }

  function titreChapitre() {
    const k = info ? entreeCourante() : -1;
    return k >= 0 ? info.toc[k].titre : (info ? info.titre : '');
  }

  function majInfos() {
    if (!info || !doc) return;
    const p = progression();
    const pc = Math.round(p * 100);
    const numPage = (R.mode === 'pages' ? page : pageDefil()) + 1;
    $('#e-chap').textContent = titreChapitre();
    heure();
    $('#p-gauche').textContent = `${numPage} / ${pages}`;
    const reste = pages - numPage;
    $('#p-droite').textContent = (reste > 0 ? `${reste} page${reste > 1 ? 's' : ''} avant la fin du chapitre · ` : 'Fin du chapitre · ') + pc + ' %';
    $('#l-curseur').value = Math.round(p * 1000);
    $('#l-prog').textContent = `Chapitre ${chap + 1} sur ${spine.length} · page ${numPage} sur ${pages} · ${pc} %`;
    $('#l-signet').classList.toggle('actif', !!signetIci());
    $('#l-chap-prec').disabled = chap === 0;
    $('#l-chap-suiv').disabled = chap >= spine.length - 1;
  }

  function heure() {
    const d = new Date();
    $('#e-heure').textContent = d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
  }

  let minuteSauve;
  function sauverPlusTard() {
    clearTimeout(minuteSauve);
    minuteSauve = setTimeout(sauver, 500);
  }

  function sauver(fini) {
    if (!livre || !doc || !pretAffiche) return;
    Store.set('pos:' + livre.id, {
      c: chap, f: fractionCourante(), p: fini === true ? 1 : progression(), t: Date.now(), chap: titreChapitre()
    });
  }

  // ================================================================ menus

  function menus(v) {
    menusOn = v;
    ecran.classList.toggle('menus', v);
    if (v) majInfos();
  }

  // ================================================================ sommaire

  function construireToc() {
    const ol = $('#liste-toc');
    ol.innerHTML = info.toc.map((t, k) =>
      `<li class="n${Math.min(5, t.niveau || 1)}" data-k="${k}"><button>${esc(t.titre)}</button></li>`).join('');
    ol.onclick = e => {
      const li = e.target.closest('li');
      if (!li) return;
      const t = info.toc[+li.dataset.k];
      const [chemin, ancre] = t.href.split('#');
      app.fermerFeuilles();
      menus(false);
      if (!aller(chemin, ancre || '')) app.toast('Ce titre est introuvable dans le livre.');
    };
  }

  function ouvrirTiroir(onglet) {
    const k = entreeCourante();
    $$('#liste-toc li').forEach(li => li.classList.toggle('courant', +li.dataset.k === k));
    choisirOnglet(onglet || 'toc');
    app.ouvrirFeuille($('#tiroir'));
    const cur = $('#liste-toc li.courant');
    if (cur && (onglet || 'toc') === 'toc') cur.scrollIntoView({ block: 'center' });
  }

  function choisirOnglet(o) {
    $$('#tiroir [data-onglet]').forEach(b => b.classList.toggle('actif', b.dataset.onglet === o));
    $$('#tiroir [data-panneau]').forEach(p => p.hidden = p.dataset.panneau !== o);
    if (o === 'signets') afficherSignets();
    if (o === 'cherche') setTimeout(() => $('#q-cherche').focus(), 250);
  }

  // ================================================================ marque-pages

  function signets() { return Store.get('signets:' + livre.id, []); }

  function signetIci() {
    if (!livre) return null;
    const f = fractionCourante();
    return signets().find(s => s.c === chap && Math.abs(s.f - f) < (R.mode === 'pages' ? 0.5 / pages : 0.5 * H / Math.max(H, defileur().scrollHeight)));
  }

  function extraitPage() {
    try {
      const largeur = win.innerWidth, hauteur = win.innerHeight;
      for (let y = my + 6; y < hauteur * 0.7; y += 14) {
        for (const x of [mx + 6, largeur / 2]) {
          const r = doc.caretRangeFromPoint(x, y);
          if (!r || !r.startContainer || r.startContainer.nodeType !== 3) continue;
          const el = r.startContainer.parentElement;
          const b = el && el.getBoundingClientRect();
          if (!b || b.right < 0 || b.left > largeur) continue;
          let t = r.startContainer.textContent.slice(r.startOffset);
          let n = r.startContainer;
          while (t.length < 120 && n) {
            n = suivantTexte(n);
            if (n) t += ' ' + n.textContent;
          }
          t = t.replace(/\s+/g, ' ').trim();
          if (t.length > 3) return t.slice(0, 120) + (t.length > 120 ? '…' : '');
        }
      }
    } catch (e) { /* rien */ }
    return titreChapitre();
  }

  function suivantTexte(n) {
    const w = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT);
    w.currentNode = n;
    return w.nextNode();
  }

  function basculerSignet() {
    const s = signetIci();
    let l = signets();
    if (s) {
      l = l.filter(x => x !== s && !(x.c === s.c && x.f === s.f));
      app.toast('Marque-page retiré');
    } else {
      l.push({ c: chap, f: fractionCourante(), p: progression(), chap: titreChapitre(), texte: extraitPage(), t: Date.now() });
      l.sort((a, b) => a.p - b.p);
      app.toast('Marque-page ajouté');
    }
    Store.set('signets:' + livre.id, l);
    majInfos();
  }

  function afficherSignets() {
    const l = signets();
    const z = $('#liste-signets');
    if (!l.length) {
      z.innerHTML = '<div class="vide-liste">Aucun marque-page.<br>Touchez l\'icône <svg style="width:18px;height:18px;vertical-align:-4px"><use href="#i-signet"/></svg> en haut de l\'écran pour marquer une page.</div>';
      return;
    }
    z.innerHTML = l.map((s, k) => `<div class="signet"><button data-k="${k}"><small>${esc(s.chap || '')} · ${Math.round(s.p * 100)} %</small><span>${esc(s.texte || '')}</span></button>
      <button class="ico petit" data-suppr="${k}" aria-label="Supprimer"><svg><use href="#i-poubelle"/></svg></button></div>`).join('');
    z.onclick = e => {
      const sup = e.target.closest('[data-suppr]');
      const liste = signets();
      if (sup) {
        liste.splice(+sup.dataset.suppr, 1);
        Store.set('signets:' + livre.id, liste);
        afficherSignets();
        majInfos();
        return;
      }
      const b = e.target.closest('[data-k]');
      if (!b) return;
      const s = liste[+b.dataset.k];
      app.fermerFeuilles();
      menus(false);
      if (s.c === chap && doc) {
        if (R.mode === 'pages') { page = Math.min(pages - 1, Math.round(s.f * pages)); afficherPage(false); }
        else afficherPage(false, s.f);
        majInfos();
      } else charger(s.c, { fraction: s.f });
    };
  }

  // ================================================================ recherche

  const plier = s => {
    let r = '';
    for (const c of s) r += (c.normalize('NFD')[0] || c).toLowerCase();
    return r;
  };

  async function texteChapitre(i) {
    if (cacheTextes.has(i)) return cacheTextes.get(i);
    const d = await documentDe(spine[i].href);
    const t = d.body ? d.body.textContent : (d.documentElement.textContent || '');
    cacheTextes.set(i, t);
    return t;
  }

  let recherche = 0;
  async function chercher(q) {
    q = q.trim();
    const z = $('#resultats');
    if (q.length < 2) { z.innerHTML = '<div class="vide-liste">Tapez au moins deux lettres.</div>'; return; }
    const mon = ++recherche;
    const qp = plier(q);
    let n = 0;
    z.innerHTML = '<div class="vide-liste">Recherche…</div>';
    const frag = [];
    for (let i = 0; i < spine.length && n < 300; i++) {
      let t;
      try { t = await texteChapitre(i); } catch (e) { continue; }
      if (mon !== recherche) return;
      const tp = plier(t);
      let pos = tp.indexOf(qp), occ = 0;
      const titre = titreDuChapitre(i);
      while (pos >= 0 && n < 300) {
        const avant = t.slice(Math.max(0, pos - 50), pos).replace(/\s+/g, ' ');
        const mot = t.slice(pos, pos + q.length);
        const apres = t.slice(pos + q.length, pos + q.length + 70).replace(/\s+/g, ' ');
        frag.push(`<button class="resultat" data-c="${i}" data-o="${occ}"><small>${esc(titre)}</small><span>…${esc(avant)}<mark>${esc(mot)}</mark>${esc(apres)}…</span></button>`);
        n++; occ++;
        pos = tp.indexOf(qp, pos + qp.length);
      }
    }
    if (mon !== recherche) return;
    z.innerHTML = n ? `<div class="vide-liste" style="padding:10px">${n >= 300 ? 'Plus de 300' : n} résultat${n > 1 ? 's' : ''}</div>` + frag.join('')
      : '<div class="vide-liste">Aucun résultat.</div>';
    z.onclick = e => {
      const b = e.target.closest('.resultat');
      if (!b) return;
      app.fermerFeuilles();
      menus(false);
      const c = +b.dataset.c, o = +b.dataset.o;
      if (c === chap && doc) { allerTexte(q, o); majInfos(); }
      else charger(c, { texte: q, occ: o });
    };
  }

  function titreDuChapitre(i) {
    let t = '';
    for (const e of info.toc) {
      const j = spine.findIndex(s => s.href === e.href.split('#')[0]);
      if (j === i) { t = e.titre; break; }
      if (j >= 0 && j < i) t = e.titre;
    }
    return t || `Chapitre ${i + 1}`;
  }

  function allerTexte(q, occ) {
    const qp = plier(q);
    const w = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT);
    const noeuds = [];
    let tout = '';
    for (let n = w.nextNode(); n; n = w.nextNode()) { noeuds.push([n, tout.length]); tout += n.textContent; }
    const tp = plier(tout);
    let pos = -1;
    for (let k = 0, from = 0; k <= occ; k++) {
      pos = tp.indexOf(qp, from);
      if (pos < 0) return false;
      from = pos + qp.length;
    }
    const trouver = p => {
      for (let k = noeuds.length - 1; k >= 0; k--) if (noeuds[k][1] <= p) return [noeuds[k][0], p - noeuds[k][1]];
      return null;
    };
    const a = trouver(pos), b = trouver(pos + q.length - 1);
    if (!a || !b) return false;
    const r = doc.createRange();
    r.setStart(a[0], a[1]);
    r.setEnd(b[0], b[1] + 1);
    effacerTrouve();
    try {
      const m = doc.createElementNS('http://www.w3.org/1999/xhtml', 'mark');
      m.className = 'liseuse-trouve';
      r.surroundContents(m);
      allerElement(m.getBoundingClientRect());
    } catch (e) {
      allerElement(r.getBoundingClientRect());
    }
    return true;
  }

  function effacerTrouve() {
    if (!doc) return;
    doc.querySelectorAll('mark.liseuse-trouve').forEach(m => {
      const p = m.parentNode;
      while (m.firstChild) p.insertBefore(m.firstChild, m);
      p.removeChild(m);
      p.normalize();
    });
  }

  // ================================================================ thème et réglages

  function appliquerTheme() {
    const t = theme();
    const s = ecran.style;
    s.setProperty('--l-fond', t.fond);
    s.setProperty('--l-texte', t.texte);
    s.setProperty('--l-doux', t.doux);
    s.setProperty('--l-menu', t.menu);
    s.setProperty('--l-trait', t.sombre ? 'rgba(255,255,255,.1)' : 'rgba(0,0,0,.09)');
    s.setProperty('--l-trait-fort', t.sombre ? 'rgba(255,255,255,.25)' : 'rgba(0,0,0,.2)');
    s.setProperty('--l-bouton', t.sombre ? 'rgba(255,255,255,.08)' : 'rgba(0,0,0,.055)');
    s.setProperty('--l-police', (POLICES[R.police] || POLICES.literata).css || 'Georgia, serif');
    // les feuilles hors de #lecteur reprennent les couleurs du thème de lecture
    ['#note', '#panneau-aa', '#panneau-lum', '#tiroir'].forEach(sel => {
      const el = $(sel);
      ['--l-fond', '--l-texte', '--l-doux', '--l-menu', '--l-trait', '--l-trait-fort', '--l-bouton', '--l-police'].forEach(v => el.style.setProperty(v, s.getPropertyValue(v)));
    });
    ecran.classList.toggle('avec-infos', !!R.infos);
    document.documentElement.classList.toggle('sombre', t.sombre);
    if (A) A.couleurs(t.fond, t.sombre);
  }

  function changer(cle, valeur) {
    R[cle] = valeur;
    sauverReglages();
    const miseEnPage = ['liensExternes', 'taille', 'police', 'interligne', 'marges', 'alignement', 'paragraphes', 'mode', 'images', 'cesure', 'gras', 'deuxPages', 'infos', 'couleursDoc'];
    if (cle === 'theme' || cle === 'imagesSombre' || cle === 'couleursDoc' || cle === 'persoFond' || cle === 'persoTexte') appliquerTheme();
    if (cle === 'infos') appliquerTheme();
    if (['pleinEcran', 'ecranAllume', 'volume'].includes(cle)) systeme(true);
    if (cle === 'mode') {
      const f = fractionCourante();
      majPanneau();
      if (livre) charger(chap, { fraction: f });
      return;
    }
    if (doc) {
      styleLivre();
      if (miseEnPage.includes(cle)) {
        relayout();
        if (doc.fonts) doc.fonts.ready.then(() => relayout());
      }
    }
    majPanneau();
  }

  function construirePanneau() {
    $('#themes').innerHTML = Object.entries(THEMES).map(([k, t]) =>
      `<div><button class="theme" data-theme="${k}" style="background:${t.fond};color:${t.texte}">Aa</button><span class="theme-nom">${t.nom}</span></div>`).join('')
      + `<div><button class="theme" data-theme="perso">Aa</button><span class="theme-nom">Perso</span></div>`;
    $('#polices').innerHTML = Object.entries(POLICES).filter(([k]) => k !== 'document' || true).map(([k, p]) =>
      `<button class="police" data-police="${k}" style="font-family:${p.css ? p.css.replace(/"/g, '&quot;') : 'inherit'}">${p.nom}<small>${p.desc}</small></button>`).join('');
    $('#themes').onclick = e => { const b = e.target.closest('[data-theme]'); if (b) changer('theme', b.dataset.theme); };
    $('#polices').onclick = e => { const b = e.target.closest('[data-police]'); if (b) changer('police', b.dataset.police); };
    $$('[data-pas]').forEach(b => b.onclick = () => {
      const [cle, d] = b.dataset.pas.split(':');
      if (cle === 'taille') changer('taille', Math.max(12, Math.min(40, R.taille + (+d))));
    });
    $('#r-interligne').oninput = e => { $('#v-interligne').textContent = (e.target.value / 100).toFixed(2).replace('.', ','); };
    $('#r-interligne').onchange = e => changer('interligne', e.target.value / 100);
    $('#r-marges').oninput = e => { $('#v-marges').textContent = e.target.value; };
    $('#r-marges').onchange = e => changer('marges', +e.target.value);
    $$('#panneau-aa .choix').forEach(c => c.onclick = e => {
      const b = e.target.closest('[data-v]');
      if (b) changer(c.dataset.reglage, b.dataset.v);
    });
    $$('#panneau-aa [data-bool]').forEach(i => i.onchange = () => changer(i.dataset.bool, i.checked));
    $('#b-defaut').onclick = () => {
      const mode = R.mode;
      R = Object.assign({}, DEFAUT);
      sauverReglages();
      appliquerTheme();
      systeme(true);
      if (mode !== R.mode && livre) charger(chap, { fraction: fractionCourante() });
      else if (doc) { styleLivre(); relayout(); }
      majPanneau();
    };
    // luminosité
    $('#r-lum').oninput = e => {
      R.luminosite = e.target.value / 100;
      $('#c-lum-auto').checked = false;
      $('#v-lum').textContent = texteLumiere();
      appliquerLumiere(true);
    };
    $('#r-lum').onchange = sauverReglages;
    $('#c-lum-auto').onchange = e => {
      R.luminosite = e.target.checked ? -1 : $('#r-lum').value / 100;
      $('#v-lum').textContent = texteLumiere();
      appliquerLumiere(true);
      sauverReglages();
    };
    $('#r-chaud').oninput = e => {
      R.chaud = +e.target.value;
      $('#v-chaud').textContent = R.chaud ? R.chaud + ' %' : 'non';
      appliquerLumiere(true);
    };
    $('#r-chaud').onchange = sauverReglages;
    // thème personnalisé
    const PALETTE_FOND = ['#ffffff', '#f3ece0', '#efe3c8', '#e6efe0', '#dfe8f1', '#f3e1e6', '#e8e4f2', '#2a2622', '#1d2433', '#1f2a22', '#111111', '#000000'];
    const PALETTE_TEXTE = ['#111111', '#2b2b2b', '#3b2f22', '#1f3a2a', '#1c2e4a', '#4a2030', '#ece7df', '#d8d2c6', '#c9d6e6', '#d4e3c9', '#f2d9a0', '#b8b4ac'];
    const nuancier = (id, palette, cle) => {
      const z = $(id);
      z.innerHTML = palette.map(c => `<button class="pastille" data-c="${c}" style="background:${c}" aria-label="${c}"></button>`).join('')
        + `<label class="pastille autre" aria-label="Autre couleur"><input type="color" data-cle="${cle}">+</label>`;
      z.onclick = e => { const b = e.target.closest('[data-c]'); if (b) changer(cle, b.dataset.c); };
      $('input', z).onchange = e => changer(cle, e.target.value);
    };
    nuancier('#perso-fond', PALETTE_FOND, 'persoFond');
    nuancier('#perso-texte', PALETTE_TEXTE, 'persoTexte');
  }

  function majPanneau() {
    $('#v-taille').textContent = R.taille;
    $$('#themes [data-theme]').forEach(b => b.classList.toggle('actif', b.dataset.theme === R.theme));
    $$('#polices [data-police]').forEach(b => b.classList.toggle('actif', b.dataset.police === R.police));
    $('#r-interligne').value = Math.round(R.interligne * 100);
    $('#v-interligne').textContent = R.interligne.toFixed(2).replace('.', ',');
    $('#r-marges').value = R.marges;
    $('#v-marges').textContent = R.marges;
    $$('#panneau-aa .choix').forEach(c => $$('[data-v]', c).forEach(b => b.classList.toggle('actif', R[c.dataset.reglage] === b.dataset.v)));
    $$('#panneau-aa [data-bool]').forEach(i => { i.checked = !!R[i.dataset.bool]; });
    const lum = R.luminosite < 0 ? 60 : Math.round(R.luminosite * 100);
    $('#r-lum').value = lum;
    $('#v-lum').textContent = texteLumiere();
    $('#c-lum-auto').checked = R.luminosite < 0;
    $('#r-chaud').value = R.chaud || 0;
    $('#v-chaud').textContent = R.chaud ? R.chaud + ' %' : 'non';
    $('#bloc-perso').hidden = R.theme !== 'perso';
    $$('#perso-fond [data-c]').forEach(b => b.classList.toggle('actif', b.dataset.c.toLowerCase() === String(R.persoFond).toLowerCase()));
    $$('#perso-texte [data-c]').forEach(b => b.classList.toggle('actif', b.dataset.c.toLowerCase() === String(R.persoTexte).toLowerCase()));
    $$('#bloc-perso input[type=color]').forEach(i => { i.value = R[i.dataset.cle]; });
    const tp = themePerso();
    const bp = $('[data-theme=perso]');
    if (bp) { bp.style.background = tp.fond; bp.style.color = tp.texte; }
  }

  // ================================================================ boutons de l'interface

  function brancher() {
    construirePanneau();
    majPanneau();
    $('#l-retour').onclick = () => app.fermerLivre();
    $('#l-toc').onclick = () => ouvrirTiroir('toc');
    $('#l-cherche').onclick = () => ouvrirTiroir('cherche');
    $('#l-signet').onclick = basculerSignet;
    $('#l-aa').onclick = () => { majPanneau(); app.ouvrirFeuille($('#panneau-aa'), { sansVoile: true }); menus(false); };
    $('#l-lum').onclick = () => { majPanneau(); app.ouvrirFeuille($('#panneau-lum'), { sansVoile: true }); menus(false); };
    $('#l-chap-prec').onclick = () => { if (chap > 0) charger(chap - 1, { fraction: 0 }); };
    $('#l-chap-suiv').onclick = () => { if (chap < spine.length - 1) charger(chap + 1, { fraction: 0 }); };
    const cur = $('#l-curseur');
    cur.oninput = () => {
      const v = cur.value / 1000 * total;
      let i = 0;
      while (i < spine.length - 1 && cumul[i + 1] <= v) i++;
      $('#l-prog').textContent = `${titreDuChapitre(i)} · ${Math.round(cur.value / 10)} %`;
    };
    cur.onchange = () => allerPosition(cur.value / 1000);
    $$('#tiroir [data-onglet]').forEach(b => b.onclick = () => choisirOnglet(b.dataset.onglet));
    $$('[data-fermer]').forEach(b => { if (!b.closest('#visionneuse')) b.onclick = () => app.retour(); });
    $('#f-cherche').onsubmit = e => { e.preventDefault(); $('#q-cherche').blur(); chercher($('#q-cherche').value); };
    // zones vides autour de l'iframe (en-tête / pied) : mêmes gestes
    ['#entete', '#pied'].forEach(s => $(s).style.pointerEvents = 'none');
    let attente;
    window.addEventListener('resize', () => {
      clearTimeout(attente);
      attente = setTimeout(relayout, 160);
    });
    // panneaux sans voile : toucher la page les ferme
    cadre.addEventListener('pointerdown', () => { if ($('#panneau-aa.ouvert, #panneau-lum.ouvert')) app.fermerFeuilles(); }, true);
  }

  brancher();

  return {
    ouvrir, fermer, tourner, sauver, menus, heure, fermerImage, appliquerTheme,
    menusVisibles: () => menusOn,
    reglages: () => R
  };
})();
