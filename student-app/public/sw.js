// App shell cache: the app opens even on a weak connection. API calls are never cached.
const CACHE = 'shell-v3';
const SHELL = ['/', '/index.html', '/style.css', '/app.js', '/i18n.js', '/manifest.json', '/icon.svg', '/icons/icon-192.png', '/icons/apple-touch-icon.png',
  '/fonts/noto-nastaliq-urdu-arabic-400-normal.woff2', '/fonts/noto-nastaliq-urdu-arabic-600-normal.woff2'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.pathname.startsWith('/api/')) return;
  // Network first (fresh version), cache as fallback
  e.respondWith(
    fetch(e.request).then((res) => {
      if (res.ok && url.origin === location.origin) {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(e.request, copy));
      }
      return res;
    }).catch(() => caches.match(e.request).then((r) => r || caches.match('/index.html'))),
  );
});
