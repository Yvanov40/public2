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
      if (!it.str || !it.str.trim()) continue; // les blancs sont recalculés d'après les positions
      const t = U.transform(vp.transform, it.transform);
      const taille = Math.hypot(t[2], t[3]);
      if (!taille || Math.abs(t[1]) > 0.15 * taille || Math.abs(t[2]) > 0.15 * taille) continue; // texte tourné
      morceaux.push({ x: t[4], y: t[5], w: (it.width || 0) * vp.scale, taille, str: it.str, f: police(it.fontName) });
    }
    const surface = vp.width * vp.height;

    // ---- dessins : images, traits (bordures de tableaux), formes (graphiques)
    const images = [], horiz = [], verti = [], formes = [];
    if (ops) {
      const O = pdfjsLib.OPS;
      const peintures = new Set([O.paintImageXObject, O.paintInlineImageXObject, O.paintJpegXObject].filter(v => v !== undefined));
      const traits = new Set([O.stroke, O.closeStroke].filter(v => v !== undefined));
      const remplis = new Set([O.fill, O.eoFill, O.fillStroke, O.eoFillStroke, O.closeFillStroke, O.closeEOFillStroke].filter(v => v !== undefined));
      let ctm = [1, 0, 0, 1, 0, 0];
      const pile = [];
      let chemin = null;
      const boite = (pts) => {
        const xs = pts.map(p => p[0]), ys = pts.map(p => p[1]);
        return { x0: Math.min(...xs), y0: Math.min(...ys), x1: Math.max(...xs), y1: Math.max(...ys) };
      };
      const ajouterSegment = (a, b) => {
        if (Math.abs(a[1] - b[1]) < 1.2 && Math.abs(a[0] - b[0]) > 3) horiz.push({ y: (a[1] + b[1]) / 2, x0: Math.min(a[0], b[0]), x1: Math.max(a[0], b[0]) });
        else if (Math.abs(a[0] - b[0]) < 1.2 && Math.abs(a[1] - b[1]) > 3) verti.push({ x: (a[0] + b[0]) / 2, y0: Math.min(a[1], b[1]), y1: Math.max(a[1], b[1]) });
      };
      for (let k = 0; k < ops.fnArray.length; k++) {
        const fn = ops.fnArray[k], a = ops.argsArray[k];
        if (fn === O.save) pile.push(ctm);
        else if (fn === O.restore) ctm = pile.pop() || [1, 0, 0, 1, 0, 0];
        else if (fn === O.transform) ctm = U.transform(ctm, a);
        else if (fn === O.paintFormXObjectBegin) { pile.push(ctm); if (a && a[0]) ctm = U.transform(ctm, a[0]); }
        else if (fn === O.paintFormXObjectEnd) ctm = pile.pop() || ctm;
        else if (peintures.has(fn)) {
          const m = U.transform(vp.transform, ctm);
          const b = boite([[0, 0], [1, 0], [0, 1], [1, 1]].map(p => U.applyTransform(p, m)));
          b.x0 = Math.max(0, b.x0); b.y0 = Math.max(0, b.y0); b.x1 = Math.min(vp.width, b.x1); b.y1 = Math.min(vp.height, b.y1);
          if (b.x1 - b.x0 > 30 && b.y1 - b.y0 > 30) images.push(b);
        } else if (fn === O.constructPath) {
          // a = [sous-opérations, coordonnées, minMax]
          const m = U.transform(vp.transform, ctm);
          const P = p => U.applyTransform(p, m);
          const sous = a[0], co = a[1];
          const segs = [], rects = [];
          let i = 0, cour = null, debut = null, courbe = false;
          for (const op of sous) {
            if (op === O.rectangle) {
              const x = co[i], y = co[i + 1], w = co[i + 2], h = co[i + 3];
              i += 4;
              const pts = [P([x, y]), P([x + w, y]), P([x + w, y + h]), P([x, y + h])];
              rects.push(boite(pts));
              segs.push([pts[0], pts[1]], [pts[1], pts[2]], [pts[2], pts[3]], [pts[3], pts[0]]);
            } else if (op === O.moveTo) { cour = debut = P([co[i], co[i + 1]]); i += 2; }
            else if (op === O.lineTo) { const p = P([co[i], co[i + 1]]); i += 2; if (cour) segs.push([cour, p]); cour = p; }
            else if (op === O.curveTo) { cour = P([co[i + 4], co[i + 5]]); i += 6; courbe = true; }
            else if (op === O.curveTo2 || op === O.curveTo3) { cour = P([co[i + 2], co[i + 3]]); i += 4; courbe = true; }
            else if (op === O.closePath) { if (cour && debut) segs.push([cour, debut]); cour = debut; }
          }
          const pts = [];
          for (const s of segs) pts.push(s[0], s[1]);
          for (const r of rects) pts.push([r.x0, r.y0], [r.x1, r.y1]);
          chemin = { segs, rects, courbe, b: pts.length ? boite(pts) : null };
        } else if (traits.has(fn) || remplis.has(fn)) {
          if (chemin && chemin.b) {
            const b = chemin.b, w = b.x1 - b.x0, h = b.y1 - b.y0;
            if (traits.has(fn) || fn === O.fillStroke || fn === O.eoFillStroke || fn === O.closeFillStroke) {
              for (const s of chemin.segs) ajouterSegment(s[0], s[1]);
            }
            if (remplis.has(fn)) {
              for (const r of chemin.rects) {
                const rw = r.x1 - r.x0, rh = r.y1 - r.y0;
                if (rh <= 3 && rw > 3) horiz.push({ y: (r.y0 + r.y1) / 2, x0: r.x0, x1: r.x1 });       // filet horizontal
                else if (rw <= 3 && rh > 3) verti.push({ x: (r.x0 + r.x1) / 2, y0: r.y0, y1: r.y1 }); // filet vertical
              }
            }
            if (w * h < surface * 0.8) formes.push({ x0: b.x0, y0: b.y0, x1: b.x1, y1: b.y1, fine: w <= 3 || h <= 3, horizontale: h <= 3, courbe: chemin.courbe });
          }
          chemin = null;
        } else if (fn === O.endPath) chemin = null;
      }
    }

    // ---- tableaux à bordures : réseaux de traits horizontaux et verticaux qui se croisent
    const fusionner = (liste, cle, a0, a1) => {
      liste.sort((p, q) => p[cle] - q[cle] || p[a0] - q[a0]);
      const r = [];
      for (const s of liste) {
        const d = r.find(t => Math.abs(t[cle] - s[cle]) < 1.5 && s[a0] <= t[a1] + 2 && s[a1] >= t[a0] - 2);
        if (d) { d[a0] = Math.min(d[a0], s[a0]); d[a1] = Math.max(d[a1], s[a1]); } else r.push(Object.assign({}, s));
      }
      return r;
    };
    const H = fusionner(horiz, 'y', 'x0', 'x1'), V = fusionner(verti, 'x', 'y0', 'y1');
    const parent = [...H.map((_, i) => i), ...V.map((_, i) => H.length + i)];
    const racine = i => { while (parent[i] !== i) i = parent[i] = parent[parent[i]]; return i; };
    H.forEach((h, i) => V.forEach((v, j) => {
      if (v.x >= h.x0 - 2.5 && v.x <= h.x1 + 2.5 && h.y >= v.y0 - 2.5 && h.y <= v.y1 + 2.5) parent[racine(i)] = racine(H.length + j);
    }));
    const groupes = new Map();
    H.forEach((h, i) => { const r = racine(i); if (!groupes.has(r)) groupes.set(r, { H: [], V: [] }); groupes.get(r).H.push(h); });
    V.forEach((v, j) => { const r = racine(H.length + j); if (!groupes.has(r)) groupes.set(r, { H: [], V: [] }); groupes.get(r).V.push(v); });
    const uniques = vals => {
      vals.sort((a, b) => a - b);
      const r = [];
      for (const v of vals) if (!r.length || v - r[r.length - 1] > 3) r.push(v);
      return r;
    };
    const dedans = (m, b, marge) => {
      const cx = m.x + m.w / 2, cy = m.y - m.taille * 0.35;
      return cx >= b.x0 - marge && cx <= b.x1 + marge && cy >= b.y0 - marge && cy <= b.y1 + marge;
    };
    const tableaux = [];
    for (const g of groupes.values()) {
      if (g.H.length < 2 || g.V.length < 2) continue;
      const ys = uniques(g.H.map(h => h.y)), xs = uniques(g.V.map(v => v.x));
      if (ys.length < 2 || xs.length < 2 || (ys.length - 1) * (xs.length - 1) < 2) continue;
      const b = { x0: xs[0], x1: xs[xs.length - 1], y0: ys[0], y1: ys[ys.length - 1] };
      if (b.x1 - b.x0 < 40 || b.y1 - b.y0 < 12) continue;
      tableaux.push({ b, xs, ys, H: g.H, V: g.V, type: 'grille' });
    }
    // un vrai tableau contient du texte dans une bonne part de ses cases (sinon : graphique, cadre…)
    for (let k = tableaux.length - 1; k >= 0; k--) {
      const t = tableaux[k], occ = new Set();
      for (const m of morceaux) {
        if (!dedans(m, t.b, 1)) continue;
        const cx = m.x + m.w / 2, cy = m.y - m.taille * 0.35;
        const c = t.xs.findIndex((x, i) => i < t.xs.length - 1 && cx >= x && cx <= t.xs[i + 1]);
        const r = t.ys.findIndex((y, i) => i < t.ys.length - 1 && cy >= y && cy <= t.ys[i + 1]);
        occ.add(r + ',' + c);
      }
      const cases = (t.xs.length - 1) * (t.ys.length - 1);
      if (occ.size < 2 || occ.size < cases * 0.3) tableaux.splice(k, 1);
    }


    // ---- graphiques vectoriels : amas de formes hors tableaux, rendus en image
    const libres = formes.filter(f => !tableaux.some(t => f.x0 >= t.b.x0 - 3 && f.x1 <= t.b.x1 + 3 && f.y0 >= t.b.y0 - 3 && f.y1 <= t.b.y1 + 3));
    const amas = [];
    for (const f of libres) {
      const a = { x0: f.x0, y0: f.y0, x1: f.x1, y1: f.y1, n: 1, fines: f.fine ? 1 : 0, horiz: f.horizontale ? 1 : 0, courbes: f.courbe ? 1 : 0 };
      amas.push(a);
    }
    for (let k = 0; k < amas.length; k++) {
      for (let j = k + 1; j < amas.length; j++) {
        const a = amas[k], b = amas[j];
        if (a.x0 < b.x1 + 8 && b.x0 < a.x1 + 8 && a.y0 < b.y1 + 8 && b.y0 < a.y1 + 8) {
          a.x0 = Math.min(a.x0, b.x0); a.y0 = Math.min(a.y0, b.y0); a.x1 = Math.max(a.x1, b.x1); a.y1 = Math.max(a.y1, b.y1);
          a.n += b.n; a.fines += b.fines; a.horiz += b.horiz; a.courbes += b.courbes;
          amas.splice(j, 1);
          j = k;
        }
      }
    }
    const taillesPage = morceaux.map(m => m.taille).sort((x, y) => x - y);
    const corpsPage = taillesPage.length ? taillesPage[Math.floor(taillesPage.length / 2)] : 12;
    const figures = [];
    for (const a of amas) {
      const s = (a.x1 - a.x0) * (a.y1 - a.y0);
      if (a.n < 3 || s < surface * 0.02) continue;
      if (a.horiz === a.n) continue;                 // simples filets ou soulignements
      // texte compris dans la figure : étiquettes proches incluses ; trop de texte = encadré, pas une figure
      const b = { x0: a.x0, y0: a.y0, x1: a.x1, y1: a.y1 };
      let texteDedans = 0;
      for (const m of morceaux) if (dedans(m, b, 0)) texteDedans += m.str.length;
      if (texteDedans > 250) continue;
      for (const m of morceaux) {
        if (m.str.trim().length <= 40 && m.taille <= corpsPage * 1.1 && dedans(m, b, m.taille * 1.8)) {
          b.x0 = Math.min(b.x0, m.x - 2); b.x1 = Math.max(b.x1, m.x + m.w + 2);
          b.y0 = Math.min(b.y0, m.y - m.taille - 2); b.y1 = Math.max(b.y1, m.y + m.taille * 0.3 + 2);
        }
      }
      b.x0 = Math.max(0, b.x0 - 4); b.y0 = Math.max(0, b.y0 - 4); b.x1 = Math.min(vp.width, b.x1 + 4); b.y1 = Math.min(vp.height, b.y1 + 4);
      // ne pas mordre sur le texte voisin (titre au-dessus, légende en dessous)
      for (const m of morceaux) {
        const cx = m.x + m.w / 2;
        if (cx < b.x0 || cx > b.x1) continue;
        const haut = m.y - m.taille, bas = m.y + m.taille * 0.3;
        const inclus = m.str.trim().length <= 40 && m.taille <= corpsPage * 1.1 && haut >= b.y0 - 1 && bas <= b.y1 + 1;
        if (inclus) continue;
        if (bas > b.y0 && haut < b.y0 + 2) b.y0 = bas + 1;
        if (haut < b.y1 && bas > b.y1 - 2) b.y1 = haut - 1;
      }
      figures.push(b);
    }

    // fusion des images (et figures) qui se chevauchent
    const toutes = images.concat(figures);
    for (let k = 0; k < toutes.length; k++) {
      for (let j = k + 1; j < toutes.length; j++) {
        const a = toutes[k], b = toutes[j];
        if (a.x0 < b.x1 + 4 && b.x0 < a.x1 + 4 && a.y0 < b.y1 + 4 && b.y0 < a.y1 + 4) {
          a.x0 = Math.min(a.x0, b.x0); a.y0 = Math.min(a.y0, b.y0);
          a.x1 = Math.max(a.x1, b.x1); a.y1 = Math.max(a.y1, b.y1);
          toutes.splice(j, 1);
          j = k;
        }
      }
    }
    const carTotal = morceaux.reduce((s, m) => s + m.str.trim().length, 0);
    const imgs = toutes.filter(b => {
      const s = (b.x1 - b.x0) * (b.y1 - b.y0);
      if (s < surface * 0.012) return false;               // petits ornements
      if (s > surface * 0.85 && carTotal > 200) return false; // fond de page derrière le texte
      return true;
    });

    // ---- répartition du texte : cellules de tableaux, intérieur des figures, texte courant
    const libre = [];
    for (const m of morceaux) {
      const t = tableaux.find(t => dedans(m, t.b, 1));
      if (t) { (t.morceaux = t.morceaux || []).push(m); continue; }
      if (imgs.some(b => dedans(m, b, 0) && (b.x1 - b.x0) * (b.y1 - b.y0) < surface * 0.85)) continue; // texte dessiné dans une figure
      libre.push(m);
    }

    // regroupement en lignes (avec repérage des colonnes séparées par de grands blancs)
    const lignes = [];
    let L = null;
    for (const m of libre) {
      const memeLigne = L && Math.abs(m.y - L.y) < Math.max(m.taille, L.taille) * 0.5 && m.x >= L.x2 - L.taille * 0.6;
      if (!memeLigne) {
        L = { x: m.x, x2: m.x, y: m.y, taille: m.taille, segs: [], cols: [{ x: m.x, x2: m.x, segs: [] }] };
        lignes.push(L);
      }
      const sup = L.segs.length > 0 && m.taille < L.taille * 0.8 && (L.y - m.y) > m.taille * 0.25;
      const ecart = m.x - L.x2;
      const dernier = L.segs.length ? L.segs[L.segs.length - 1].t : '';
      if (L.segs.length && ecart > 1.4 * m.taille && ecart > 10) L.cols.push({ x: m.x, x2: m.x, segs: [] });
      if (L.segs.length && ecart > 0.18 * m.taille && !/\s$/.test(dernier) && !/^\s/.test(m.str)) {
        L.segs.push({ t: ' ', b: false, i: false, sup: false });
      }
      const seg = { t: m.str, b: m.f.gras, i: m.f.ital, sup };
      L.segs.push(seg);
      const col = L.cols[L.cols.length - 1];
      if (col.segs.length && ecart > 0.18 * m.taille) col.segs.push({ t: ' ', b: false, i: false, sup: false });
      col.segs.push(seg);
      col.x2 = Math.max(col.x2, m.x + m.w);
      L.x2 = Math.max(L.x2, m.x + m.w);
      if (!sup) {
        const k = Math.round(m.taille * 2) / 2;
        L.poids = L.poids || {};
        L.poids[k] = (L.poids[k] || 0) + m.str.trim().length;
      }
    }
    for (const l of lignes) {
      // taille dominante de la ligne (un mot en gros ne fait pas un titre)
      if (l.poids) {
        let best = 0;
        for (const k in l.poids) if (l.poids[k] > best) { best = l.poids[k]; l.taille = +k; }
      }
    }
    let car = 0;
    let propres = [];
    for (const l of lignes) {
      l.texte = l.segs.map(s => s.t).join('').replace(/\s+/g, ' ').trim();
      if (!l.texte) continue;
      const vis = l.segs.filter(s => s.t.trim());
      l.gras = vis.length > 0 && vis.every(s => s.b);
      l.cols = l.cols.filter(c => c.segs.some(s => s.t.trim()));
      car += l.texte.length;
      propres.push(l);
    }

    // ---- tableaux sans bordure : plusieurs lignes de suite découpées en colonnes alignées
    const sansBord = [];
    for (let i = 0; i < propres.length;) {
      let j = i;
      const puce = l => P_PUCE_SEULE.test(l.cols[0].segs.map(s => s.t).join('').trim());
      const compatible = (r, l) => l.cols.every(c => r.cols.some(c0 => Math.abs(c0.x - c.x) < l.taille * 1.5));
      while (j < propres.length && propres[j].cols.length >= 2 && !puce(propres[j])
        && (j === i || (propres[j].y - propres[j - 1].y > 0 && propres[j].y - propres[j - 1].y < propres[j].taille * 3.2
          && compatible(propres[i], propres[j])))) j++;
      const bloc = propres.slice(i, j);
      if (bloc.length >= 2 && estTableauTexte(bloc)) {
        sansBord.push({ debut: i, fin: j });
        i = j;
      } else i = Math.max(j, i + 1);
    }
    const tabTexte = [];
    for (const z of sansBord.reverse()) {
      const bloc = propres.slice(z.debut, z.fin);
      const t = tableauTexte(bloc);
      propres.splice(z.debut, z.fin - z.debut, { tableau: t, y: bloc[0].y, x: bloc[0].x, x2: Math.max(...bloc.map(l => l.x2)), taille: bloc[0].taille, texte: '', segs: [], cols: [] });
      tabTexte.push(t);
    }

    // ---- tableaux à bordures : texte des cellules
    const tabs = tableaux.map(t => ({ y: t.b.y0, html: tableauGrille(t), b: t.b }));
    return { w: vp.width, h: vp.height, lignes: propres, images: imgs, tableaux: tabs, car: car + tabs.length * 20 };
  }

  // ---------------------------------------------------------------- construction des tableaux

  function htmlCellule(morceaux) {
    if (!morceaux || !morceaux.length) return '';
    morceaux.sort((a, b) => (Math.abs(a.y - b.y) < a.taille * 0.5 ? a.x - b.x : a.y - b.y));
    let h = '', prec = null;
    for (const m of morceaux) {
      if (prec) {
        const memeLigne = Math.abs(m.y - prec.y) < m.taille * 0.5;
        if (!memeLigne) h = /[a-zà-ÿ]-$/i.test(h) ? h.slice(0, -1) : h + ' ';
        else if (m.x - (prec.x + prec.w) > m.taille * 0.18 && !/\s$/.test(h) && !/^\s/.test(m.str)) h += ' ';
      }
      let t = esc(m.str);
      if (m.f.ital) t = `<i>${t}</i>`;
      if (m.f.gras) t = `<b>${t}</b>`;
      h += t;
      prec = m;
    }
    return h.replace(/<\/b><b>/g, '').replace(/<\/i><i>/g, '').replace(/\s+/g, ' ').trim();
  }

  function tableauGrille(t) {
    const { xs, ys, H, V } = t;
    const nl = ys.length - 1, nc = xs.length - 1;
    const bordV = (r, c) => V.some(v => Math.abs(v.x - xs[c]) < 2.5 && v.y0 <= ys[r] + 2.5 && v.y1 >= ys[r + 1] - 2.5);
    const bordH = (r, c) => H.some(h => Math.abs(h.y - ys[r]) < 2.5 && h.x0 <= xs[c] + 2.5 && h.x1 >= xs[c + 1] - 2.5);
    const pris = Array.from({ length: nl }, () => new Array(nc).fill(false));
    const cellules = Array.from({ length: nl }, () => []);
    for (let r = 0; r < nl; r++) {
      for (let c = 0; c < nc; c++) {
        if (pris[r][c]) continue;
        let cs = 1;
        while (c + cs < nc && !bordV(r, c + cs)) cs++;
        let rs = 1;
        while (r + rs < nl) {
          let ouvert = true;
          for (let k = c; k < c + cs; k++) if (bordH(r + rs, k)) { ouvert = false; break; }
          if (!ouvert) break;
          rs++;
        }
        for (let a = r; a < r + rs; a++) for (let k = c; k < c + cs; k++) pris[a][k] = true;
        cellules[r].push({ c, cs, rs, b: { x0: xs[c], x1: xs[c + cs], y0: ys[r], y1: ys[r + rs] }, m: [] });
      }
    }
    for (const m of t.morceaux || []) {
      const cx = m.x + m.w / 2, cy = m.y - m.taille * 0.35;
      for (const ligne of cellules) for (const cel of ligne) {
        if (cx >= cel.b.x0 - 1 && cx <= cel.b.x1 + 1 && cy >= cel.b.y0 - 1 && cy <= cel.b.y1 + 1) { cel.m.push(m); break; }
      }
    }
    let h = '<div class="tableau"><table>\n';
    for (const ligne of cellules) {
      if (!ligne.length) continue;
      h += '<tr>';
      for (const cel of ligne) {
        h += '<td' + (cel.cs > 1 ? ` colspan="${cel.cs}"` : '') + (cel.rs > 1 ? ` rowspan="${cel.rs}"` : '') + '>' + htmlCellule(cel.m) + '</td>';
      }
      h += '</tr>\n';
    }
    return h + '</table></div>\n';
  }

  /** Un bloc de lignes forme-t-il un tableau (colonnes alignées) ? */
  function estTableauTexte(bloc) {
    // listes à puces ou numérotées : pas un tableau
    if (bloc.every(l => P_PUCE_SEULE.test(l.cols[0].segs.map(s => s.t).join('').trim()))) return false;
    const debuts = [];
    for (const l of bloc) for (const c of l.cols) debuts.push(c.x);
    const cols = colonnes(debuts, bloc[0].taille);
    if (cols.length < 2) return false;
    // chaque ligne doit tomber dans les colonnes repérées
    let bonnes = 0;
    for (const l of bloc) if (l.cols.every(c => cols.some(x => Math.abs(x - c.x) < bloc[0].taille * 1.5))) bonnes++;
    return bonnes >= bloc.length * 0.8 && cols.filter(x => bloc.filter(l => l.cols.some(c => Math.abs(c.x - x) < l.taille * 1.5)).length >= bloc.length * 0.5).length >= 2;
  }

  function colonnes(xs, taille) {
    xs.sort((a, b) => a - b);
    const r = [];
    for (const x of xs) {
      const c = r.find(v => Math.abs(v.x - x) < taille * 1.5);
      if (c) { c.x = (c.x * c.n + x) / (c.n + 1); c.n++; } else r.push({ x, n: 1 });
    }
    return r.filter(c => c.n >= 2).map(c => c.x).sort((a, b) => a - b);
  }

  function tableauTexte(bloc) {
    const debuts = [];
    for (const l of bloc) for (const c of l.cols) debuts.push(c.x);
    const cols = colonnes(debuts, bloc[0].taille);
    let h = '<div class="tableau"><table>\n';
    for (const l of bloc) {
      const cases = cols.map(() => []);
      for (const c of l.cols) {
        let k = 0, d = Infinity;
        cols.forEach((x, i) => { const e = Math.abs(x - c.x); if (e < d) { d = e; k = i; } });
        if (c.x < cols[k] - l.taille * 1.5 && k > 0) k--;
        cases[k].push(c);
      }
      h += '<tr>' + cases.map(cs => '<td>' + cs.map(c => {
        let t = '';
        for (const s of c.segs) {
          let x = esc(s.t);
          if (s.sup) x = `<sup>${x}</sup>`;
          if (s.i) x = `<i>${x}</i>`;
          if (s.b) x = `<b>${x}</b>`;
          t += x;
        }
        return t.replace(/<\/b><b>/g, '').trim();
      }).join(' ') + '</td>').join('') + '</tr>\n';
    }
    return h + '</table></div>\n';
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
  const P_PUCE_SEULE = /^([•●▪◦‣■□➢➤✓\-–—*]|\d{1,3}[.)]?|[a-zA-Z][.)])$/;
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
      if (l.tableau) continue;
      const k = Math.round(l.taille * 2) / 2;
      tailles.set(k, (tailles.get(k) || 0) + l.texte.length);
    }
    const corps = mode(tailles) || 11;
    const ecarts = [];
    for (const p of pages) {
      const corpsP = p.lignes.filter(l => !l.tableau && Math.abs(l.taille - corps) < corps * 0.12);
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
        if (l.tableau || (l.y > p.h * 0.1 && l.y < p.h * 0.92)) continue;
        const k = cleRepetition(l.texte);
        if (deja.has(k)) continue;
        deja.add(k);
        vus.set(k, (vus.get(k) || 0) + 1);
      }
    }
    const seuil = Math.max(2, Math.ceil(n * 0.25));
    for (const p of pages) {
      p.lignes = p.lignes.filter(l => {
        if (l.tableau || (l.y > p.h * 0.1 && l.y < p.h * 0.92)) return true;
        if (P_NUM_PAGE.test(l.texte) && /[\divxlc]/i.test(l.texte) && l.texte.length < 16) return false;
        return !(n >= 2 && (vus.get(cleRepetition(l.texte)) || 0) >= seuil);
      });
    }

    // 4. niveaux de titres d'après la taille des caractères
    const grandes = new Map();
    for (const p of pages) for (const l of p.lignes) {
      if (l.tableau) continue;
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
      for (const t of (pg.tableaux || []).slice().sort((a, b) => a.y - b.y)) {
        let k = elems.findIndex(e => e.l && e.l.y > t.y);
        if (k < 0) k = elems.length;
        elems.splice(k, 0, { t });
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
        if (e.t || e.l.tableau) {
          fermerPara();
          ecrire(e.t ? e.t.html : e.l.tableau);
          prec = null;
          dernierTitre = null;
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
