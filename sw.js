// =====================================================================================
//  sw.js — le service hors connexion de « Cahier de classe »
//  Il garde une copie des fichiers de l'appli pour qu'elle s'ouvre sans wifi.
//  Règle : on essaie d'abord le réseau (pour avoir la dernière version) ; s'il ne répond
//  pas, on sert la copie gardée. Les données de la classe ne passent jamais par ici.
// =====================================================================================
const CACHE = "classe-v1";
const FICHIERS = ["./", "index.html", "core.js", "vues-a.js", "vues-b.js", "icone.svg", "icone-192.png", "manifest.webmanifest"];
self.addEventListener("install", (e) => { e.waitUntil(caches.open(CACHE).then((c) => c.addAll(FICHIERS))); self.skipWaiting(); });
self.addEventListener("activate", (e) => { e.waitUntil(self.clients.claim()); });
self.addEventListener("fetch", (e) => {
  if (e.request.method !== "GET" || new URL(e.request.url).origin !== location.origin) return;   // l'option IA et les polices ne sont pas concernées
  e.respondWith(fetch(e.request).then((r) => { const copie = r.clone(); caches.open(CACHE).then((c) => c.put(e.request, copie)); return r; }).catch(() => caches.match(e.request, { ignoreSearch: true })));
});
