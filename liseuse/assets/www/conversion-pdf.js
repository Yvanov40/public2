'use strict';
/* Liseuse — conversion PDF → EPUB avec pdf.js.
   Le texte est remis en paragraphes (lignes recollées, mots coupés réparés), les titres sont repérés
   à la taille des caractères, les en-têtes / pieds de page / numéros de page sont retirés, et les
   images sont découpées dans le rendu de la page. Le livre est assemblé côté Android (EpubBuilder). */

const ConversionPdf = (() => {

  let pdfjsPret = null;
  function chargerPdfJs() {
    if (window.pdfjsLib) return Promise.resolve();
    if (pdfjsPret) return pdfjsPret;
    pdfjsPret = new Promise((ok, ko) => {
      const s = document.createElement('script');
      s.src = '/app/pdfjs/pdf.min.js';
      s.onload = () => {
        pdfjsLib.GlobalWorkerOptions.workerSrc = '/app/pdfjs/pdf.worker.min.js';
        ok();
      };
      s.onerror = () => { pdfjsPret = null; ko(new Error('Le lecteur PDF interne est introuvable.')); };
      document.head.appendChild(s);
    });
    return pdfjsPret;
  }

  // ---------------------------------------------------------------- file d'attente

  const file = [];
  let enCours = false;

  function ajouter(job) {
    file.push(job);
    if (!enCours) suivant();
  }

  async function suivant() {
    const job = file.shift();
    if (!job) { enCours = false; return; }
    enCours = true;
    try {
      await convertir(job);
    } catch (e) {
      console.error(e);
      if (A) A.pdfEchec(job.jeton, messageErreur(e));
    }
    suivant();
  }

  function messageErreur(e) {
    const n = e && e.name;
    if (n === 'PasswordException') return 'Ce PDF est protégé par un mot de passe.';
    if (n === 'InvalidPDFException') return 'Ce PDF est abîmé ou illisible.';
    if (n === 'MissingPDFException') return 'Le fichier PDF est introuvable.';
    return 'Conversion du PDF impossible : ' + ((e && e.message) || 'erreur inconnue');
  }

  function progres(job, i, n, etape) {
    const t = document.querySelector('#import-texte');
    if (t) t.textContent = `${etape} « ${job.nom} » — page ${i} / ${n}`;
  }

  const pause = () => new Promise(r => setTimeout(r, 0));

  // ---------------------------------------------------------------- lecture d'une page

  async function lirePage(page) {
    const U = pdfjsLib.Util;
    const vp = page.getViewport({ scale: 1 });
    const tc = await page.getTextContent();
    let ops = null;
    try { ops = await page.getOperatorList(); } catch (e) { /* page sans dessin lisible */ }

    const polices = {};
    const police = nom => {
      if (nom in polices) return polices[nom];
      let n = '';
      try {
        const f = page.commonObjs.get(nom);
        n = (f && (f.name || '')) || '';
      } catch (e) { /* police pas encore chargée */ }
      const st = tc.styles && tc.styles[nom];
      n += ' ' + ((st && st.fontFamily) || '');
      return (polices[nom] = { gras: /bold|black|heavy|semibold|demi|-bd\b/i.test(n), ital: /italic|oblique|-it\b/i.test(n) });
    };

    // morceaux de texte positionnés (coordonnées écran : y vers le bas, sur la ligne de base)
    const morceaux = [];
    for (const it of tc.items) {
      if (!it.str || it.str.length === 0) continue;
      const t = U.transform(vp.transform, it.transform);
      const taille = Math.hypot(t[2], t[3]);
      if (!taille || Math.abs(t[1]) > 0.15 * taille || Math.abs(t[2]) > 0.15 * taille) continue; // texte tourné
      morceaux.push({ x: t[4], y: t[5], w: (it.width || 0) * vp.scale, taille, str: it.str, f: police(it.fontName) });
    }

    // regroupement en lignes
    const lignes = [];
    let L = null;
    for (const m of morceaux) {
      const memeLigne = L && Math.abs(m.y - L.y) < Math.max(m.taille, L.taille) * 0.5 && m.x >= L.x2 - L.taille * 0.6;
      if (!memeLigne) {
        L = { x: m.x, x2: m.x, y: m.y, taille: m.taille, segs: [] };
        lignes.push(L);
      }
      const sup = L.segs.length > 0 && m.taille < L.taille * 0.8 && (L.y - m.y) > m.taille * 0.25;
      const dernier = L.segs.length ? L.segs[L.segs.length - 1].t : '';
      if (L.segs.length && m.x - L.x2 > 0.18 * m.taille && !/\s$/.test(dernier) && !/^\s/.test(m.str)) {
        L.segs.push({ t: ' ', b: false, i: false, sup: false });
      }
      L.segs.push({ t: m.str, b: m.f.gras, i: m.f.ital, sup });
      L.x2 = Math.max(L.x2, m.x + m.w);
      if (!sup && m.taille > L.taille) L.taille = m.taille;
    }
    let car = 0;
    const propres = [];
    for (const l of lignes) {
      l.texte = l.segs.map(s => s.t).join('').replace(/\s+/g, ' ').trim();
      if (!l.texte) continue;
      const vis = l.segs.filter(s => s.t.trim());
      l.gras = vis.length > 0 && vis.every(s => s.b);
      car += l.texte.length;
      propres.push(l);
    }

    // emplacements des images (suivi de la matrice de transformation)
    const images = [];
    if (ops) {
      const O = pdfjsLib.OPS;
      const peintures = new Set([O.paintImageXObject, O.paintInlineImageXObject, O.paintJpegXObject].filter(v => v !== undefined));
      let ctm = [1, 0, 0, 1, 0, 0];
      const pile = [];
      for (let k = 0; k < ops.fnArray.length; k++) {
        const fn = ops.fnArray[k], a = ops.argsArray[k];
        if (fn === O.save) pile.push(ctm);
        else if (fn === O.restore) ctm = pile.pop() || [1, 0, 0, 1, 0, 0];
        else if (fn === O.transform) ctm = U.transform(ctm, a);
        else if (fn === O.paintFormXObjectBegin) { pile.push(ctm); if (a && a[0]) ctm = U.transform(ctm, a[0]); }
        else if (fn === O.paintFormXObjectEnd) ctm = pile.pop() || ctm;
        else if (peintures.has(fn)) {
          const m = U.transform(vp.transform, ctm);
          const pts = [[0, 0], [1, 0], [0, 1], [1, 1]].map(p => U.applyTransform(p, m));
          const xs = pts.map(p => p[0]), ys = pts.map(p => p[1]);
          const b = {
            x0: Math.max(0, Math.min(...xs)), y0: Math.max(0, Math.min(...ys)),
            x1: Math.min(vp.width, Math.max(...xs)), y1: Math.min(vp.height, Math.max(...ys))
          };
          if (b.x1 - b.x0 > 30 && b.y1 - b.y0 > 30) images.push(b);
        }
      }
    }
    // fusion des images qui se chevauchent
    for (let k = 0; k < images.length; k++) {
      for (let j = k + 1; j < images.length; j++) {
        const a = images[k], b = images[j];
        if (a.x0 < b.x1 + 4 && b.x0 < a.x1 + 4 && a.y0 < b.y1 + 4 && b.y0 < a.y1 + 4) {
          a.x0 = Math.min(a.x0, b.x0); a.y0 = Math.min(a.y0, b.y0);
          a.x1 = Math.max(a.x1, b.x1); a.y1 = Math.max(a.y1, b.y1);
          images.splice(j, 1);
          j = k;
        }
      }
    }
    const surface = vp.width * vp.height;
    const imgs = images.filter(b => {
      const s = (b.x1 - b.x0) * (b.y1 - b.y0);
      if (s < surface * 0.012) return false;          // petits ornements
      if (s > surface * 0.85 && car > 200) return false; // fond de page derrière le texte
      return true;
    });
    return { w: vp.width, h: vp.height, lignes: propres, images: imgs, car };
  }

  // ---------------------------------------------------------------- rendu et découpe d'images

  async function rendre(pdf, num, echelle) {
    const page = await pdf.getPage(num);
    const vp = page.getViewport({ scale: echelle });
    const c = document.createElement('canvas');
    c.width = Math.max(1, Math.round(vp.width));
    c.height = Math.max(1, Math.round(vp.height));
    const ctx = c.getContext('2d');
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, c.width, c.height);
    await page.render({ canvasContext: ctx, viewport: vp }).promise;
    page.cleanup();
    return c;
  }

  function versImage(job, canvas, b, echelle) {
    let src = canvas;
    if (b) {
      const x = Math.floor(b.x0 * echelle), y = Math.floor(b.y0 * echelle);
      const w = Math.max(1, Math.ceil((b.x1 - b.x0) * echelle)), h = Math.max(1, Math.ceil((b.y1 - b.y0) * echelle));
      src = document.createElement('canvas');
      src.width = w;
      src.height = h;
      src.getContext('2d').drawImage(canvas, x, y, w, h, 0, 0, w, h);
    }
    const url = src.toDataURL('image/jpeg', 0.86);
    return A.pdfImage(job.jeton, url.slice(url.indexOf(',') + 1), 'jpg');
  }

  // ---------------------------------------------------------------- statistiques du document

  function mode(map) {
    let best = null, n = -1;
    for (const [k, v] of map) if (v > n) { n = v; best = k; }
    return best;
  }

  function cleRepetition(t) {
    return t.toLowerCase().replace(/\d+/g, '#').replace(/\s+/g, ' ').trim();
  }

  const P_NUM_PAGE = /^(page|p\.)?\s*[#\divxlc]+\s*([\/|]|sur|of|de)?\s*[#\d]*$/i;
  const P_PUCE = /^([•●▪◦‣■□➢➤✓\-–—*]|\d{1,3}[.)]|[a-zA-Z][.)])\s/;
  const P_CHAPITRE = /^(chapitre|chapter|cap[ií]tulo|partie|part|livre|book|tome|prologue|[ée]pilogue|epilogue|introduction|conclusion|avant-propos|pr[ée]face|postface|annexe|appendix)\b/i;

  // ---------------------------------------------------------------- conversion

  async function convertir(job) {
    if (!A) throw new Error('Android indisponible');
    await chargerPdfJs();
    const opt = Object.assign({ niveauChapitre: 1, detecterChapitres: true }, job.options || {});
    const doc = await pdfjsLib.getDocument({ url: '/import/' + job.jeton, isEvalSupported: false, disableAutoFetch: true }).promise;
    const n = doc.numPages;
    let meta = {};
    try { meta = (await doc.getMetadata()).info || {}; } catch (e) { /* sans métadonnées */ }

    // 1. lecture de toutes les pages
    const pages = [];
    for (let i = 1; i <= n; i++) {
      const page = await doc.getPage(i);
      pages.push(await lirePage(page));
      page.cleanup();
      if (i % 3 === 0 || i === n) { progres(job, i, n, 'Lecture de'); await pause(); }
    }

    // 2. statistiques : taille du texte courant, marges, interligne
    const tailles = new Map();
    for (const p of pages) for (const l of p.lignes) {
      const k = Math.round(l.taille * 2) / 2;
      tailles.set(k, (tailles.get(k) || 0) + l.texte.length);
    }
    const corps = mode(tailles) || 11;
    const ecarts = [];
    for (const p of pages) {
      const corpsP = p.lignes.filter(l => Math.abs(l.taille - corps) < corps * 0.12);
      const xs = corpsP.map(l => l.x).sort((a, b) => a - b);
      const x2s = corpsP.map(l => l.x2).sort((a, b) => a - b);
      p.gauche = xs.length ? xs[Math.floor(xs.length * 0.1)] : 0;
      p.droite = x2s.length ? x2s[Math.floor(x2s.length * 0.9)] : p.w;
      for (let k = 1; k < corpsP.length; k++) {
        const d = corpsP[k].y - corpsP[k - 1].y;
        if (d > corps * 0.8 && d < corps * 3) ecarts.push(d);
      }
    }
    ecarts.sort((a, b) => a - b);
    const interligne = ecarts.length ? ecarts[Math.floor(ecarts.length / 2)] : corps * 1.3;

    // 3. en-têtes et pieds de page répétés, numéros de page
    const vus = new Map();
    for (const p of pages) {
      const deja = new Set();
      for (const l of p.lignes) {
        if (l.y > p.h * 0.1 && l.y < p.h * 0.92) continue;
        const k = cleRepetition(l.texte);
        if (deja.has(k)) continue;
        deja.add(k);
        vus.set(k, (vus.get(k) || 0) + 1);
      }
    }
    const seuil = Math.max(2, Math.ceil(n * 0.25));
    for (const p of pages) {
      p.lignes = p.lignes.filter(l => {
        if (l.y > p.h * 0.1 && l.y < p.h * 0.92) return true;
        if (P_NUM_PAGE.test(l.texte) && /[\divxlc]/i.test(l.texte) && l.texte.length < 16) return false;
        return !(n >= 3 && (vus.get(cleRepetition(l.texte)) || 0) >= seuil);
      });
    }

    // 4. niveaux de titres d'après la taille des caractères
    const grandes = new Map();
    for (const p of pages) for (const l of p.lignes) {
      if (l.taille >= corps * 1.18 && l.texte.length < 160) {
        const k = Math.round(l.taille);
        if (!grandes.has(k)) grandes.set(k, new Set());
        grandes.get(k).add(pages.indexOf(p));
      }
    }
    const paliers = [...grandes.keys()].sort((a, b) => b - a);
    let palierChapitre = paliers.findIndex(k => grandes.get(k).size >= 2);
    if (palierChapitre < 0) palierChapitre = 0;
    const niveauDe = l => {
      if (l.taille < corps * 1.18 || l.texte.length >= 160) return 0;
      const i = paliers.indexOf(Math.round(l.taille));
      if (i < 0) return 0;
      if (i <= palierChapitre) return 1;
      return Math.min(3, 1 + i - palierChapitre);
    };
    const avecTitres = paliers.length > 0;

    // signets du PDF (si le document n'a pas de titres repérables)
    const signets = new Map();
    if (!avecTitres) {
      try {
        const plan = await doc.getOutline();
        for (const e of plan || []) {
          let dest = e.dest;
          if (typeof dest === 'string') dest = await doc.getDestination(dest);
          if (!dest || !dest[0]) continue;
          const idx = typeof dest[0] === 'object' ? await doc.getPageIndex(dest[0]) : dest[0];
          if (!signets.has(idx)) signets.set(idx, (e.title || '').trim());
        }
      } catch (e) { /* pas de signets */ }
    }

    // 5. assemblage
    const titre = (meta.Title && String(meta.Title).trim() && !/^(untitled|sans titre|microsoft word)/i.test(meta.Title)) ? String(meta.Title).trim() : '';
    A.pdfCommencer(job.jeton, titre, meta.Author ? String(meta.Author) : '', 'fr');

    // couverture : la première page
    try {
      const p1 = pages[0];
      const ech = Math.min(2, 900 / p1.w);
      const c = await rendre(doc, 1, ech);
      const href = versImage(job, c, null, ech);
      if (href) A.pdfCouverture(job.jeton, href);
    } catch (e) { /* couverture générée à la place */ }

    let tampon = '';
    let para = null;          // paragraphe en cours
    let prec = null;          // ligne précédente (et sa page)
    let precPage = null;
    let nbTitres = 0;
    let titreCourant = '';
    let dernierTitre = null;  // pour fusionner un titre écrit sur deux lignes
    let tocAttente = null;
    const validerToc = () => { if (tocAttente) { A.pdfToc(job.jeton, tocAttente.niv, tocAttente.texte, tocAttente.id); tocAttente = null; } };
    let pagesScannees = 0;

    const vider = () => { if (tampon) { A.pdfHtml(job.jeton, tampon); tampon = ''; } };
    const ecrire = h => { tampon += h; if (tampon.length > 60000) vider(); };

    const segsHtml = segs => {
      let h = '';
      for (const s of segs) {
        let t = esc(s.t);
        if (s.sup) t = `<sup>${t}</sup>`;
        if (s.i) t = `<i>${t}</i>`;
        if (s.b) t = `<b>${t}</b>`;
        h += t;
      }
      return h.replace(/<\/b><b>/g, '').replace(/<\/i><i>/g, '');
    };

    const fermerPara = () => {
      if (!para) return;
      let cls = '';
      const l = para.lignes[0];
      const pg = para.page;
      const largeurTexte = Math.max(1, pg.droite - pg.gauche);
      if (para.lignes.length === 1) {
        const centre = (l.x + l.x2) / 2, milieu = (pg.gauche + pg.droite) / 2;
        if (Math.abs(centre - milieu) < largeurTexte * 0.04 && (l.x2 - l.x) < largeurTexte * 0.75 && l.x > pg.gauche + corps) cls = ' class="c"';
      }
      let style = '';
      if (l.taille < corps * 0.86) style = ' style="font-size:.85em"';
      ecrire(`<p${cls}${style}>${para.html.trim()}</p>\n`);
      para = null;
    };

    const nouveauChapitre = t => {
      fermerPara();
      vider();
      validerToc();
      A.pdfChapitre(job.jeton, t || '');
      titreCourant = t || titreCourant;
    };

    const titreHtml = (niv, l, texte, pg) => {
      fermerPara();
      if (dernierTitre && dernierTitre.niv === niv && prec && dernierTitre.ligne === prec && l.y - prec.y < interligne * 2.2 && precPage === dernierTitre.page) {
        // suite d'un titre sur plusieurs lignes : on complète le précédent
        tampon = tampon.replace(new RegExp(`</h${niv}>\\n$`), ' ' + esc(texte) + `</h${niv}>\n`);
        dernierTitre.texte += ' ' + texte;
        dernierTitre.ligne = l;
        if (tocAttente && tocAttente.id === dernierTitre.id) tocAttente.texte = dernierTitre.texte;
        return;
      }
      validerToc();
      const coupe = opt.niveauChapitre > 0 && niv <= opt.niveauChapitre;
      if (coupe) nouveauChapitre(texte);
      else vider();
      nbTitres++;
      const id = 't' + nbTitres;
      tocAttente = { niv, texte, id };
      ecrire(`<h${niv} id="${id}">${esc(texte)}</h${niv}>\n`);
      dernierTitre = { niv, texte, ligne: l, page: pg, id };
    };

    const finCourte = (l, pg) => l.x2 < pg.droite - corps * 2.5;
    const finPhrase = t => /[.!?:;»"”…)\]]$/.test(t);

    const nouveauPara = (P, Pp, l, pg) => {
      if (Math.abs(l.taille - P.taille) > P.taille * 0.15) return true;
      if (P_PUCE.test(l.texte)) return true;
      if (Pp === pg) {
        const dy = l.y - P.y;
        if (dy > interligne * 1.5 * (l.taille / corps)) return true;
        if (dy < -corps) return finCourte(P, Pp) && finPhrase(P.texte); // nouvelle colonne
      }
      if (l.x > P.x + corps * 0.8 && l.x > pg.gauche + corps * 0.8) return true; // alinéa
      if (finCourte(P, Pp) && (finPhrase(P.texte) || /^[A-ZÀ-ÖØ-Þ«"“—–]/.test(l.texte))) return true;
      return false;
    };

    for (let pi = 0; pi < pages.length; pi++) {
      const pg = pages[pi];
      // page scannée (sans texte) : gardée en image entière
      if (pg.car < 25 && (pg.images.length > 0 || pg.lignes.length === 0)) {
        fermerPara();
        try {
          const ech = Math.min(2, 1600 / Math.max(pg.w, pg.h));
          const c = await rendre(doc, pi + 1, ech);
          const href = versImage(job, c, null, ech);
          if (href) ecrire(`<p class="fig"><img src="${href}" alt="Page ${pi + 1}" style="width:100%"/></p>\n`);
          pagesScannees++;
        } catch (e) { /* page ignorée */ }
        prec = null;
        if (pi % 2 === 0) { progres(job, pi + 1, n, 'Mise en page de'); await pause(); vider(); }
        continue;
      }
      if (signets.has(pi)) {
        const t = signets.get(pi);
        if (t) titreHtml(1, { y: 0, x: 0, x2: 0, taille: corps, texte: t }, t, pg);
        prec = null;
      }
      // éléments de la page : lignes dans l'ordre du document, images insérées selon leur hauteur
      const elems = pg.lignes.map(l => ({ l }));
      const imgs = pg.images.slice().sort((a, b) => a.y0 - b.y0);
      for (const b of imgs) {
        let k = elems.findIndex(e => e.l && e.l.y > b.y0 + corps * 0.5);
        if (k < 0) k = elems.length;
        elems.splice(k, 0, { b });
      }
      let rendu = null, echelle = 1;
      for (const e of elems) {
        if (e.b) {
          fermerPara();
          try {
            if (!rendu) {
              echelle = Math.min(2.5, 2000 / Math.max(pg.w, pg.h));
              rendu = await rendre(doc, pi + 1, echelle);
            }
            const href = versImage(job, rendu, e.b, echelle);
            const pct = Math.max(10, Math.min(100, Math.round((e.b.x1 - e.b.x0) / Math.max(1, pg.droite - pg.gauche) * 100)));
            if (href) ecrire(`<p class="fig"><img src="${href}" alt="" style="width:${pct >= 88 ? 100 : pct}%"/></p>\n`);
          } catch (er) { /* image ignorée */ }
          prec = null;
          continue;
        }
        const l = e.l;
        let niv = niveauDe(l);
        if (!niv && opt.detecterChapitres && l.texte.length < 70 && P_CHAPITRE.test(l.texte) && !avecTitres && !/[.,;]$/.test(l.texte)) niv = 1;
        if (!niv && l.gras && l.texte.length < 90 && !finPhrase(l.texte) && l.taille > corps * 0.9 && l.taille < corps * 1.2
          && (!prec || precPage !== pg || l.y - prec.y > interligne * 1.4) && !P_PUCE.test(l.texte)) niv = 3;
        if (niv) {
          titreHtml(niv, l, l.texte, pg);
          prec = l;
          precPage = pg;
          continue;
        }
        dernierTitre = null;
        if (para && prec && !nouveauPara(prec, precPage, l, pg)) {
          // même paragraphe : mot coupé en fin de ligne ?
          if (/[a-zà-öø-ÿ]-$/i.test(para.html) && /^[a-zà-öø-ÿ]/.test(l.texte)) para.html = para.html.slice(0, -1);
          else para.html += ' ';
          para.html += segsHtml(l.segs).trim();
          para.lignes.push(l);
        } else {
          fermerPara();
          if (!para && A.pdfTaille(job.jeton) + tampon.length > 150000) nouveauChapitre(titreCourant);
          para = { html: segsHtml(l.segs).trim(), lignes: [l], page: pg };
        }
        prec = l;
        precPage = pg;
      }
      vider();
      if (pi % 2 === 0) { progres(job, pi + 1, n, 'Mise en page de'); await pause(); }
    }
    fermerPara();
    vider();
    validerToc();
    await doc.destroy();
    if (pagesScannees > n / 2) app.toast('PDF scanné : les pages sont gardées en images.', 4500);
    A.pdfFinir(job.jeton);
  }

  return { ajouter };
})();
