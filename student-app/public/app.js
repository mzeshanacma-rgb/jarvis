/* Student Assignment App: browser client (PWA). No framework, so it stays fast on older phones. */
const I18N = window.I18N;
let me = null;
let lang = 'en';

// ---------- Madadgar ----------
function t(key, vars = {}) {
  const s = (I18N[lang] && I18N[lang][key]) ?? I18N.en[key] ?? key;
  return s.replace(/\{(\w+)\}/g, (_, k) => (vars[k] ?? ''));
}
function h(tag, attrs = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === 'class') el.className = v;
    else if (k === 'style') el.style.cssText = v; // CSSOM: allowed under the CSP
    else if (k === 'html') el.innerHTML = v; // only our own markup
    else if (v === true) el.setAttribute(k, '');
    else el.setAttribute(k, v);
  }
  for (const kid of kids.flat(Infinity)) {
    if (kid === null || kid === undefined || kid === false) continue;
    el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
  }
  return el;
}
const $main = () => document.getElementById('main');
// replaceChildren would print null as "null", so filter first
const clean = (kids) => kids.flat(Infinity).filter((k) => k !== null && k !== undefined && k !== false);
function fill(el, ...kids) { el.replaceChildren(...clean(kids)); }
function render(...nodes) {
  const m = $main();
  fill(m, nodes);
  m.focus({ preventScroll: true });
  window.scrollTo(0, 0);
}
let toastTimer;
function toast(msg) {
  const el = document.getElementById('toast');
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), 3500);
}
function errText(e) { return t('err.' + (e.code || 'server_error')); }
const fmtDate = (s) => (s ? new Date(s.includes('T') ? s : s.replace(' ', 'T') + 'Z').toLocaleString(lang === 'ur' ? 'ur-PK' : 'en-GB', { dateStyle: 'medium', timeStyle: 'short' }) : '');
const num = (n) => (n === null || n === undefined ? '–' : String(Math.round(n * 10) / 10));

class ApiError extends Error { constructor(status, code) { super(code); this.status = status; this.code = code; } }
async function api(method, url, body, { form } = {}) {
  const opts = { method, headers: { 'x-requested-with': 'app' }, credentials: 'same-origin' };
  if (form) opts.body = form;
  else if (body !== undefined) { opts.headers['content-type'] = 'application/json'; opts.body = JSON.stringify(body); }
  let res;
  try { res = await fetch(url, opts); } catch { throw new ApiError(0, 'offline'); }
  const data = (res.headers.get('content-type') || '').includes('json') ? await res.json() : null;
  if (!res.ok) {
    const code = data?.error || 'server_error';
    // Session ended (user was signed in): restart and show the sign-in screen
    if (res.status === 401 && me && ['login_required', 'mfa_required'].includes(code) && url !== '/api/me' && !url.startsWith('/api/login')) { me = null; boot(); }
    if (code === 'must_change_password') { location.hash = '#/password'; }
    throw new ApiError(res.status, code);
  }
  return data;
}
const GET = (u) => api('GET', u);
const POST = (u, b, o) => api('POST', u, b, o);
const PUT = (u, b) => api('PUT', u, b);

function setLang(l) {
  lang = l === 'ur' ? 'ur' : 'en';
  document.documentElement.lang = lang;
  document.documentElement.dir = lang === 'ur' ? 'rtl' : 'ltr';
  document.title = t('app.name');
}

function statusBadge(status) {
  const map = {
    todo: ['plain', 'status.todo'], submitted: ['info', 'status.submitted'], draft_ready: ['info', 'status.draft_ready'],
    ai_unavailable: ['warn', 'status.ai_unavailable'], approved: ['ok', 'status.approved'], returned: ['warn', 'status.returned'], queued: ['warn', 'status.queued'],
  };
  const [cls, key] = map[status] || ['plain', status];
  return h('span', { class: 'badge ' + cls }, t(key));
}
function flagBadge(type) { return h('span', { class: 'badge warn' }, '⚑ ', t('flag.' + type)); }

// ---------- Header ----------
function header() {
  const top = document.getElementById('top');
  const links = [];
  const route = location.hash || '#/';
  const link = (href, label) => h('a', { href, 'aria-current': route === href || (href !== '#/' && route.startsWith(href)) ? 'page' : null }, label);
  if (me && !me.mfaPending && !me.mustChangePassword) {
    links.push(link('#/', t('nav.home')));
    if (me.role === 'admin') links.push(link('#/admin/users', t('nav.users')), link('#/admin/classes', t('nav.classes')), link('#/admin/quality', t('nav.quality')), link('#/admin/audit', t('nav.audit')), link('#/admin/system', t('nav.system')));
    links.push(link('#/settings', t('nav.settings')));
  }
  if (me) links.push(h('button', { type: 'button', onclick: logout }, t('nav.logout')));
  fill(top, 
    h('a', { class: 'brand', href: '#/' }, h('img', { src: '/icon.svg', alt: '' }), t('app.name')),
    me ? h('span', { class: 'small' }, me.org.name) : null,
    h('nav', { 'aria-label': t('nav.label') }, links),
  );
}
async function logout() {
  await POST('/api/logout').catch(() => {});
  me = null;
  location.hash = '#/';
  boot();
}

// ---------- Offline outbox (IndexedDB) ----------
const DB_NAME = 'outbox';
function idb() {
  return new Promise((resolve, reject) => {
    const r = indexedDB.open(DB_NAME, 1);
    r.onupgradeneeded = () => r.result.createObjectStore('items', { keyPath: 'id', autoIncrement: true });
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}
async function outboxAdd(item) {
  const db = await idb();
  return new Promise((res, rej) => { const tx = db.transaction('items', 'readwrite'); tx.objectStore('items').add(item); tx.oncomplete = res; tx.onerror = () => rej(tx.error); });
}
async function outboxAll() {
  const db = await idb();
  return new Promise((res, rej) => { const r = db.transaction('items').objectStore('items').getAll(); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
}
async function outboxDelete(id) {
  const db = await idb();
  return new Promise((res) => { const tx = db.transaction('items', 'readwrite'); tx.objectStore('items').delete(id); tx.oncomplete = res; });
}
function toForm(item) {
  const f = new FormData();
  for (const [k, v] of Object.entries(item.fields)) f.append(k, v);
  for (const [k, b] of Object.entries(item.blobs || {})) f.append(k, b, k + (b.type === 'application/pdf' ? '.pdf' : '.jpg'));
  return f;
}
let flushing = false;
async function flushOutbox() {
  if (flushing || !navigator.onLine || !me || me.role !== 'student') return;
  flushing = true;
  try {
    for (const item of await outboxAll()) {
      if (item.userId !== me.id) continue;
      try {
        await POST(`/api/student/tasks/${item.taskId}/submit`, undefined, { form: toForm(item) });
        await outboxDelete(item.id);
        toast(t('student.queuedSent', { title: item.title }));
      } catch (e) {
        if (e.code === 'offline') break;
        await outboxDelete(item.id); // rejected by the server (e.g. already submitted): drop it from the queue
        toast(errText(e));
      }
    }
  } finally { flushing = false; }
  updateNet();
}
async function updateNet() {
  const el = document.getElementById('net');
  let pending = 0;
  try { pending = me ? (await outboxAll()).filter((i) => i.userId === me.id).length : 0; } catch { /* IndexedDB unavailable */ }
  if (!navigator.onLine) { el.hidden = false; el.textContent = t('net.offline') + (pending ? ' ' + t('net.pending', { n: pending }) : ''); }
  else if (pending) { el.hidden = false; el.textContent = t('net.sending', { n: pending }); }
  else el.hidden = true;
}
window.addEventListener('online', () => { updateNet(); flushOutbox(); });
window.addEventListener('offline', updateNet);

// ---------- Install as an app (iPhone, iPad, Mac, Windows, Android) ----------
let installPrompt = null;
let health = null;
window.addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); installPrompt = e; });
window.addEventListener('appinstalled', () => { installPrompt = null; toast(t('install.done')); });
const inAndroidShell = () => typeof window.AndroidApp !== 'undefined';
const isInstalled = () => inAndroidShell() || window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
function platform() {
  const ua = navigator.userAgent;
  const ios = /iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1);
  const safari = /Safari/.test(ua) && !/Chrome|CriOS|FxiOS|EdgiOS|Edg\//.test(ua);
  if (ios) return safari ? 'ios' : 'ios-other';
  if (/Android/.test(ua)) return 'android';
  if (/Macintosh/.test(ua) && safari) return 'mac-safari';
  return 'desktop';
}
function installCard() {
  if (isInstalled()) return null;
  const p = platform();
  const steps = (keys) => h('ol', {}, keys.map((k) => h('li', {}, t(k))));
  const body = [];
  if (installPrompt) {
    body.push(h('button', {
      class: 'btn', type: 'button',
      onclick: async () => { const e = installPrompt; installPrompt = null; e.prompt(); await e.userChoice.catch(() => {}); route(); },
    }, t('install.button')));
  }
  if (p === 'android' && health?.androidApkUrl) body.push(h('p', {}, h('a', { class: 'btn secondary', href: health.androidApkUrl }, t('install.apk'))));
  if (!installPrompt) {
    if (p === 'ios') body.push(h('p', { class: 'platform' }, t('install.ios')), steps(['install.ios1', 'install.ios2', 'install.ios3']));
    else if (p === 'ios-other') body.push(h('p', {}, t('install.iosOpenSafari')));
    else if (p === 'mac-safari') body.push(h('p', { class: 'platform' }, t('install.mac')), steps(['install.mac1', 'install.mac2']));
    else if (p === 'android') body.push(h('p', { class: 'platform' }, t('install.android')), steps(['install.android1', 'install.android2']));
    else body.push(h('p', { class: 'platform' }, t('install.desktop')), steps(['install.desktop1', 'install.desktop2']));
  }
  return h('div', { class: 'card install' }, h('h2', {}, t('install.title')), h('p', { class: 'muted small' }, t('install.intro')), body);
}

// ---------- Boot and router ----------
async function boot() {
  if (!health) health = await GET('/api/health').catch(() => null);
  try {
    me = await GET('/api/me');
    setLang(me.lang);
  } catch {
    me = null;
    const saved = localStorage.getItem('lang');
    setLang(saved || 'en');
  }
  header();
  route();
  updateNet();
  flushOutbox();
}
window.addEventListener('hashchange', () => { header(); route(); });

async function route() {
  const hash = location.hash.slice(1) || '/';
  const parts = hash.split('/').filter(Boolean);
  try {
    if (parts[0] === 'reset') return viewReset(parts[1]);
    if (parts[0] === 'forgot') return viewForgot();
    if (!me) {
      const setup = await GET('/api/setup');
      return setup.needed ? viewSetup() : viewLogin();
    }
    if (me.mfaPending === 'enroll') return viewMfaEnroll();
    if (me.mfaPending === 'required') return viewMfaCode();
    if (me.mustChangePassword || parts[0] === 'password') return viewPassword();
    if (parts[0] === 'settings') return viewSettings();
    if (me.role === 'student') {
      if (parts[0] === 'task') return viewStudentTask(Number(parts[1]));
      return viewStudentHome();
    }
    if (me.role === 'teacher') {
      if (parts[0] === 'class' && parts[2] === 'new') return viewNewTask(Number(parts[1]));
      if (parts[0] === 'class') return viewClass(Number(parts[1]));
      if (parts[0] === 'task') return viewTaskDashboard(Number(parts[1]));
      if (parts[0] === 'review') return viewReview(Number(parts[1]));
      return viewTeacherHome();
    }
    if (me.role === 'admin') {
      const sub = parts[1];
      if (parts[0] === 'admin' && sub === 'classes') return viewAdminClasses();
      if (parts[0] === 'admin' && sub === 'quality') return viewAdminQuality();
      if (parts[0] === 'admin' && sub === 'audit') return viewAdminAudit();
      if (parts[0] === 'admin' && sub === 'system') return viewAdminSystem();
      if (parts[0] === 'admin' && sub === 'users') return viewAdminUsers();
      return viewAdminHome();
    }
  } catch (e) {
    render(h('div', { class: 'notice bad' }, errText(e)));
  }
}

// ---------- Auth screens ----------
function langPicker() {
  return h('div', { class: 'row' },
    h('button', { class: 'btn quiet small', type: 'button', onclick: () => { localStorage.setItem('lang', 'en'); setLang('en'); header(); route(); } }, 'English'),
    h('button', { class: 'btn quiet small ur', type: 'button', onclick: () => { localStorage.setItem('lang', 'ur'); setLang('ur'); header(); route(); } }, 'اردو'));
}
function formField(id, label, input, hint) {
  input.id = id;
  return h('div', { class: 'field' }, h('label', { for: id }, label, hint ? h('div', { class: 'hint' }, hint) : null), input);
}
function errorBox() { return h('div', { class: 'notice bad', role: 'alert', hidden: true }); }
function showErr(box, e) { box.hidden = false; box.textContent = typeof e === 'string' ? e : errText(e); }

function viewLogin() {
  const u = h('input', { type: 'text', autocomplete: 'username', required: true, autocapitalize: 'none', class: 'ltr' });
  const p = h('input', { type: 'password', autocomplete: 'current-password', required: true });
  const err = errorBox();
  const form = h('form', {
    class: 'card', onsubmit: async (ev) => {
      ev.preventDefault();
      try { await POST('/api/login', { username: u.value, password: p.value }); await boot(); } catch (e) { showErr(err, e); }
    },
  }, h('h1', {}, t('login.title')), err,
  formField('u', t('login.username'), u), formField('p', t('login.password'), p),
  h('div', { class: 'row' }, h('button', { class: 'btn', type: 'submit' }, t('login.submit')), h('a', { href: '#/forgot' }, t('login.forgot'))),
  h('p', { class: 'muted small' }, t('login.schoolAccounts')));
  render(h('div', { class: 'center' }, langPicker(), form, inAndroidShell() ? h('p', {}, h('button', { class: 'btn quiet small', type: 'button', onclick: () => window.AndroidApp.changeServer() }, t('app.changeServer'))) : null, installCard()));
}

function viewSetup() {
  const f = { orgName: h('input', { type: 'text', required: true }), name: h('input', { type: 'text', required: true }), email: h('input', { type: 'email', class: 'ltr' }), username: h('input', { type: 'text', required: true, class: 'ltr', autocapitalize: 'none' }), password: h('input', { type: 'password', required: true, autocomplete: 'new-password' }) };
  const dl = h('select', {}, h('option', { value: 'en' }, 'English'), h('option', { value: 'ur' }, 'اردو'));
  const err = errorBox();
  render(h('div', { class: 'center' }, langPicker(), h('form', {
    class: 'card', onsubmit: async (ev) => {
      ev.preventDefault();
      try {
        await POST('/api/setup', { orgName: f.orgName.value, name: f.name.value, email: f.email.value, username: f.username.value, password: f.password.value, defaultLang: dl.value });
        await boot();
      } catch (e) { showErr(err, e); }
    },
  }, h('h1', {}, t('setup.title')), h('p', { class: 'muted' }, t('setup.intro')), err,
  formField('s1', t('setup.orgName'), f.orgName), formField('s2', t('setup.defaultLang'), dl, t('setup.defaultLangHint')), formField('s3', t('setup.yourName'), f.name),
  formField('s4', t('setup.email'), f.email), formField('s5', t('login.username'), f.username, t('hint.username')), formField('s6', t('login.password'), f.password, t('hint.password')),
  h('button', { class: 'btn', type: 'submit' }, t('setup.submit')))));
}

async function viewMfaEnroll() {
  let s;
  try { s = await GET('/api/mfa/setup'); } catch (e) {
    if (e.code === 'already_enabled') { me = await GET('/api/me'); return route(); } // stale state
    throw e;
  }
  const code = h('input', { type: 'text', inputmode: 'numeric', autocomplete: 'one-time-code', maxlength: 6, class: 'ltr' });
  const err = errorBox();
  render(h('div', { class: 'center' }, h('form', {
    class: 'card', onsubmit: async (ev) => {
      ev.preventDefault();
      try { await POST('/api/mfa/enable', { code: code.value.trim() }); await boot(); } catch (e) { showErr(err, e); }
    },
  }, h('h1', {}, t('mfa.enrollTitle')), h('p', {}, t('mfa.enrollIntro')),
  h('img', { class: 'qr', src: s.qr, alt: t('mfa.qrAlt') }),
  h('p', { class: 'small' }, t('mfa.manual'), ' ', h('span', { class: 'code' }, s.secret)), err,
  formField('c', t('mfa.code'), code), h('button', { class: 'btn', type: 'submit' }, t('mfa.enable')))));
}
function viewMfaCode() {
  const code = h('input', { type: 'text', inputmode: 'numeric', autocomplete: 'one-time-code', maxlength: 6, class: 'ltr' });
  const err = errorBox();
  render(h('div', { class: 'center' }, h('form', {
    class: 'card', onsubmit: async (ev) => {
      ev.preventDefault();
      try { await POST('/api/login/totp', { code: code.value.trim() }); await boot(); } catch (e) { showErr(err, e); }
    },
  }, h('h1', {}, t('mfa.codeTitle')), h('p', {}, t('mfa.codeIntro')), err, formField('c', t('mfa.code'), code),
  h('div', { class: 'row' }, h('button', { class: 'btn', type: 'submit' }, t('mfa.verify')), h('button', { class: 'btn quiet', type: 'button', onclick: logout }, t('nav.logout'))),
  h('p', { class: 'muted small' }, t('mfa.lost')))));
}

function viewPassword() {
  const cur = h('input', { type: 'password', autocomplete: 'current-password' });
  const n1 = h('input', { type: 'password', autocomplete: 'new-password' });
  const n2 = h('input', { type: 'password', autocomplete: 'new-password' });
  const err = errorBox();
  render(h('div', { class: 'center' }, h('form', {
    class: 'card', onsubmit: async (ev) => {
      ev.preventDefault();
      if (n1.value !== n2.value) return showErr(err, t('err.password_mismatch'));
      try { await POST('/api/password/change', { current: cur.value, password: n1.value }); toast(t('password.changed')); location.hash = '#/'; await boot(); } catch (e) { showErr(err, e); }
    },
  }, h('h1', {}, t('password.title')), me?.mustChangePassword ? h('div', { class: 'notice' }, t('password.mustChange')) : null, err,
  formField('p0', t('password.current'), cur), formField('p1', t('password.new'), n1, t('hint.password')), formField('p2', t('password.repeat'), n2),
  h('button', { class: 'btn', type: 'submit' }, t('password.save')))));
}
function viewForgot() {
  const u = h('input', { type: 'text', class: 'ltr', autocapitalize: 'none' });
  const out = h('div');
  render(h('div', { class: 'center' }, h('form', {
    class: 'card', onsubmit: async (ev) => {
      ev.preventDefault();
      await POST('/api/password/forgot', { username: u.value }).catch(() => {});
      fill(out, h('div', { class: 'notice ok' }, t('forgot.sent')));
    },
  }, h('h1', {}, t('forgot.title')), h('p', { class: 'muted' }, t('forgot.intro')), out, formField('f', t('forgot.field'), u),
  h('div', { class: 'row' }, h('button', { class: 'btn', type: 'submit' }, t('forgot.submit')), h('a', { href: '#/' }, t('common.back'))))));
}
function viewReset(token) {
  const n1 = h('input', { type: 'password', autocomplete: 'new-password' });
  const err = errorBox();
  render(h('div', { class: 'center' }, h('form', {
    class: 'card', onsubmit: async (ev) => {
      ev.preventDefault();
      try { await POST('/api/password/reset', { token, password: n1.value }); toast(t('password.changed')); location.hash = '#/'; } catch (e) { showErr(err, e); }
    },
  }, h('h1', {}, t('reset.title')), err, formField('r', t('password.new'), n1, t('hint.password')), h('button', { class: 'btn', type: 'submit' }, t('password.save')))));
}

function viewSettings() {
  const l = h('select', {}, h('option', { value: 'en' }, 'English'), h('option', { value: 'ur' }, 'اردو'));
  l.value = me.lang;
  const fl = h('select', {}, h('option', { value: 'en' }, 'English'), h('option', { value: 'ur' }, 'اردو'));
  fl.value = me.feedbackLang;
  const sf = h('input', { type: 'checkbox', checked: me.scoreFirst });
  render(h('h1', {}, t('settings.title')), h('form', {
    class: 'card', onsubmit: async (ev) => {
      ev.preventDefault();
      const body = { lang: l.value, feedbackLang: fl.value };
      if (me.role === 'teacher') body.scoreFirst = sf.checked;
      await PUT('/api/me/settings', body);
      toast(t('common.saved'));
      await boot();
    },
  }, formField('l', t('settings.appLang'), l, t('settings.appLangHint')),
  me.role === 'student' ? formField('fl', t('settings.feedbackLang'), fl) : null,
  me.role === 'teacher' ? h('div', { class: 'field' }, h('label', { class: 'check' }, sf, t('settings.scoreFirst')), h('div', { class: 'hint' }, t('settings.scoreFirstHint'))) : null,
  h('button', { class: 'btn', type: 'submit' }, t('common.save'))),
  h('div', { class: 'card' }, h('h2', {}, t('settings.security')), h('a', { class: 'btn secondary', href: '#/password' }, t('password.title')),
    h('p', { class: 'muted small' }, t('settings.code', { code: me.code }))),
  inAndroidShell() ? h('div', { class: 'card' }, h('h2', {}, t('app.server')), h('p', { class: 'muted small ltr' }, location.origin),
    h('button', { class: 'btn quiet', type: 'button', onclick: () => window.AndroidApp.changeServer() }, t('app.changeServer'))) : null,
  installCard());
}

// ---------- Student ----------
async function viewStudentHome() {
  const tasks = await GET('/api/student/tasks');
  const outbox = (await outboxAll().catch(() => [])).filter((i) => i.userId === me.id);
  const code = h('input', { type: 'text', class: 'ltr', maxlength: 8, autocapitalize: 'characters' });
  const err = errorBox();
  render(
    h('h1', {}, t('student.home', { name: me.name })),
    h('div', { class: 'card' }, tasks.length ? h('ul', { class: 'list' }, tasks.map((tk) => {
      const queued = outbox.some((o) => o.taskId === tk.id);
      return h('li', {}, h('a', { class: 'item', href: `#/task/${tk.id}` },
        h('div', { class: 'spacer' }, h('b', {}, tk.title), h('div', { class: 'muted small' }, tk.className, tk.dueAt ? ' · ' + t('student.due', { d: tk.dueAt }) : '')),
        tk.status === 'approved' ? h('span', { class: 'badge ok ltr' }, `${num(tk.total)} / ${tk.max}`) : null,
        statusBadge(queued ? 'queued' : tk.status)));
    })) : h('p', { class: 'muted' }, t('student.noTasks'))),
    h('form', {
      class: 'card', onsubmit: async (ev) => {
        ev.preventDefault();
        try { const r = await POST('/api/student/join', { code: code.value }); toast(t('student.joined', { name: r.className })); route(); } catch (e) { showErr(err, e.code === 'not_found' ? t('err.class_not_found') : e); }
      },
    }, h('h2', {}, t('student.joinTitle')), h('p', { class: 'muted small' }, t('student.joinHint')), err,
    h('div', { class: 'row' }, code, h('button', { class: 'btn secondary', type: 'submit' }, t('student.join')))),
  );
}

// Photo: rotate, downscale, blur check, crop the name area
function loadImage(file) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = reject;
    img.src = URL.createObjectURL(file);
  });
}
function drawScaled(img, rotation, maxSide = 1600) {
  const rot = ((rotation % 360) + 360) % 360;
  const w0 = img.naturalWidth, h0 = img.naturalHeight;
  const scale = Math.min(1, maxSide / Math.max(w0, h0));
  const w = Math.round(w0 * scale), hh = Math.round(h0 * scale);
  const c = document.createElement('canvas');
  const swap = rot === 90 || rot === 270;
  c.width = swap ? hh : w; c.height = swap ? w : hh;
  const ctx = c.getContext('2d');
  ctx.translate(c.width / 2, c.height / 2);
  ctx.rotate((rot * Math.PI) / 180);
  ctx.drawImage(img, -w / 2, -hh / 2, w, hh);
  return c;
}
function sharpness(canvas) {
  // Variance of the Laplacian on a small grayscale copy. Low = blurry photo.
  const W = 320, H = Math.max(1, Math.round((canvas.height / canvas.width) * W));
  const c = document.createElement('canvas'); c.width = W; c.height = H;
  const ctx = c.getContext('2d'); ctx.drawImage(canvas, 0, 0, W, H);
  const d = ctx.getImageData(0, 0, W, H).data;
  const g = new Float32Array(W * H);
  for (let i = 0; i < W * H; i++) g[i] = 0.299 * d[i * 4] + 0.587 * d[i * 4 + 1] + 0.114 * d[i * 4 + 2];
  let sum = 0, sum2 = 0, n = 0;
  for (let y = 1; y < H - 1; y++) for (let x = 1; x < W - 1; x++) {
    const i = y * W + x;
    const lap = g[i - W] + g[i + W] + g[i - 1] + g[i + 1] - 4 * g[i];
    sum += lap; sum2 += lap * lap; n++;
  }
  const mean = sum / n;
  return sum2 / n - mean * mean;
}
const toBlob = (canvas, q = 0.82) => new Promise((r) => canvas.toBlob(r, 'image/jpeg', q));
function cropTop(canvas, pct) {
  const cut = Math.round(canvas.height * pct / 100);
  const c = document.createElement('canvas');
  c.width = canvas.width; c.height = canvas.height - cut;
  c.getContext('2d').drawImage(canvas, 0, cut, canvas.width, c.height, 0, 0, c.width, c.height);
  return c;
}

async function viewStudentTask(id) {
  const data = await GET(`/api/student/tasks/${id}`);
  const tk = data.task, sub = data.submission;
  const outbox = (await outboxAll().catch(() => [])).filter((i) => i.userId === me.id && i.taskId === id);
  const head = [
    h('a', { href: '#/' }, '← ', t('common.back')),
    h('h1', {}, tk.title),
    h('div', { class: 'card' },
      h('div', { class: 'muted small' }, tk.className, tk.dueAt ? ' · ' + t('student.due', { d: tk.dueAt }) : ''),
      h('p', { style: 'white-space:pre-wrap' }, tk.instructions),
      tk.extraTimeNote ? h('div', { class: 'notice' }, tk.extraTimeNote) : null,
      h('h3', {}, t('task.rubric')),
      h('ul', {}, tk.rubric.map((c) => h('li', {}, `${c.name} (${c.max})`, c.description ? h('span', { class: 'muted' }, ' – ', c.description) : null)))),
  ];

  if (outbox.length) return render(head, h('div', { class: 'notice warn' }, t('student.inQueue')));

  if (sub && sub.status === 'approved') {
    const recheckReason = h('textarea', { rows: 3 });
    const err = errorBox();
    return render(head, h('div', { class: 'card' },
      h('div', { class: 'row' }, h('h2', {}, t('student.result')), h('span', { class: 'spacer' }), h('span', { class: 'badge ok ltr' }, `${num(sub.total)} / ${tk.rubric.reduce((a, c) => a + c.max, 0)}`)),
      h('table', {}, h('tbody', {}, tk.rubric.map((c) => h('tr', {}, h('td', {}, c.name), h('td', { class: 'ltr' }, `${num(sub.scores.find((s) => s.id === c.id)?.score)} / ${c.max}`))))),
      h('h3', {}, t('student.feedback')),
      h('p', { style: 'white-space:pre-wrap' }, sub.feedback),
      h('p', { class: 'muted small' }, sub.aiAssisted ? t('student.aiNotice') : t('student.teacherNotice')),
      sub.recheckStatus === 'requested' ? h('div', { class: 'notice' }, t('student.recheckPending'))
        : sub.recheckStatus === 'done' ? h('div', { class: 'notice ok' }, t('student.recheckDone'))
          : h('details', {}, h('summary', {}, t('student.recheck')), h('form', {
            onsubmit: async (ev) => {
              ev.preventDefault();
              try { await POST(`/api/student/submissions/${sub.id}/recheck`, { reason: recheckReason.value }); toast(t('student.recheckSent')); route(); } catch (e) { showErr(err, e); }
            },
          }, err, formField('rr', t('student.recheckReason'), recheckReason), h('button', { class: 'btn secondary', type: 'submit' }, t('student.recheckSend'))))));
  }
  if (sub && sub.status !== 'returned') {
    return render(head, h('div', { class: 'notice ok' }, t('student.received', { d: fmtDate(sub.submittedAt) })),
      sub.text ? h('div', { class: 'card' }, h('h3', {}, t('student.yourAnswer')), h('p', { style: 'white-space:pre-wrap' }, sub.text)) : null,
      data.files.length ? h('div', { class: 'card' }, data.files.map((f) => f.mime.startsWith('image/') ? h('img', { class: 'scan', src: f.url, alt: t('student.yourPhoto') }) : h('a', { href: f.url, target: '_blank', rel: 'noopener' }, f.name))) : null);
  }

  // Submission form
  const modes = tk.mode === 'any' ? ['typed', 'photo', 'file'] : [tk.mode];
  let mode = modes[0];
  const area = h('div');
  const tabs = h('div', { class: 'row', role: 'tablist' }, modes.map((m) => h('button', {
    class: 'btn quiet small', type: 'button', role: 'tab', 'aria-selected': String(m === mode),
    onclick: () => { mode = m; [...tabs.children].forEach((b, i) => b.setAttribute('aria-selected', String(modes[i] === m))); drawMode(); },
  }, t('submit.' + m))));
  const err = errorBox();
  const sendBtn = h('button', { class: 'btn', type: 'submit' }, t('submit.send'));
  let state = {};

  function drawMode() {
    state = { mode };
    if (mode === 'typed') {
      const ta = h('textarea', { rows: 12, lang: tk.answerLang === 'ur' ? 'ur' : 'en', dir: tk.answerLang === 'ur' ? 'rtl' : 'auto', class: tk.answerLang === 'ur' ? 'ur' : '' });
      const log = { startedAt: new Date().toISOString(), keystrokes: 0, pastes: [], t0: Date.now() };
      const key = `draft:${me.id}:${id}`;
      try { ta.value = localStorage.getItem(key) || ''; } catch { /* storage blocked */ }
      ta.addEventListener('keydown', () => { log.keystrokes++; });
      ta.addEventListener('paste', (e) => { log.pastes.push({ at: Date.now() - log.t0, chars: (e.clipboardData?.getData('text') || '').length }); });
      ta.addEventListener('input', () => { try { localStorage.setItem(key, ta.value); } catch { /* */ } });
      state.ta = ta; state.log = log; state.key = key;
      fill(area, formField('ans', t('submit.typedLabel'), ta, t('submit.typedHint')));
    } else if (mode === 'photo') {
      const input = h('input', { type: 'file', accept: 'image/*', capture: 'environment' });
      const preview = h('div');
      const crop = h('input', { type: 'range', min: 0, max: 40, value: tk.headerCropPct });
      let img = null, rotation = 0;
      const redraw = () => {
        if (!img) return;
        const c = drawScaled(img, rotation);
        state.canvas = c;
        const sharp = sharpness(c);
        const tooSmall = Math.min(c.width, c.height) < 600;
        state.quality = { sharp, tooSmall, bad: sharp < 40 || tooSmall };
        const band = h('div', { class: 'crop-band', style: `height:${crop.value}%` }, t('submit.cropBand'));
        fill(preview, 
          h('div', { class: 'photo-wrap' }, c, band),
          state.quality.bad ? h('div', { class: 'notice warn', role: 'alert' }, tooSmall ? t('submit.photoSmall') : t('submit.photoBlurry')) : h('div', { class: 'notice ok' }, t('submit.photoOk')),
          h('div', { class: 'row' },
            h('button', { class: 'btn quiet small', type: 'button', onclick: () => { rotation -= 90; redraw(); } }, '↺ ', t('submit.rotate')),
            h('button', { class: 'btn quiet small', type: 'button', onclick: () => { rotation += 90; redraw(); } }, '↻ ', t('submit.rotate'))),
          formField('crop', t('submit.cropLabel', { pct: crop.value }), crop, t('submit.cropHint')),
          state.quality.bad ? h('label', { class: 'check' }, state.useAnyway = h('input', { type: 'checkbox' }), t('submit.useAnyway')) : null,
        );
      };
      crop.addEventListener('input', redraw);
      input.addEventListener('change', async () => { if (input.files[0]) { img = await loadImage(input.files[0]); rotation = 0; redraw(); } });
      state.crop = crop;
      fill(area, formField('ph', t('submit.photoLabel'), input, tk.handwrittenOnly ? t('submit.handwrittenOnly') : t('submit.photoHint')), preview);
    } else {
      const input = h('input', { type: 'file', accept: 'application/pdf,image/jpeg,image/png' });
      state.fileInput = input;
      fill(area, formField('fi', t('submit.fileLabel'), input, t('submit.fileHint')));
    }
  }
  drawMode();

  const form = h('form', {
    class: 'card', onsubmit: async (ev) => {
      ev.preventDefault();
      err.hidden = true;
      const item = { userId: me.id, taskId: id, title: tk.title, fields: { kind: mode }, blobs: {} };
      if (mode === 'typed') {
        if (!state.ta.value.trim()) return showErr(err, t('err.empty_answer'));
        item.fields.text = state.ta.value;
        item.fields.writingLog = JSON.stringify({ startedAt: state.log.startedAt, durationMs: Date.now() - state.log.t0, keystrokes: state.log.keystrokes, pastes: state.log.pastes });
      } else if (mode === 'photo') {
        if (!state.canvas) return showErr(err, t('err.file_required'));
        if (state.quality?.bad && !state.useAnyway?.checked) return showErr(err, t('submit.retake'));
        item.blobs.original = await toBlob(state.canvas);
        item.blobs.answer = await toBlob(cropTop(state.canvas, Number(state.crop.value)));
      } else {
        const f = state.fileInput.files[0];
        if (!f) return showErr(err, t('err.file_required'));
        if (f.size > 8 * 1024 * 1024) return showErr(err, t('err.upload_limit_file_size'));
        item.blobs.original = f;
      }
      if (!confirm(t('submit.confirm'))) return;
      sendBtn.disabled = true;
      try {
        await POST(`/api/student/tasks/${id}/submit`, undefined, { form: toForm(item) });
        if (state.key) try { localStorage.removeItem(state.key); } catch { /* */ }
        toast(t('submit.done'));
        route();
      } catch (e) {
        if (e.code === 'offline') {
          await outboxAdd(item);
          if (state.key) try { localStorage.removeItem(state.key); } catch { /* */ }
          toast(t('submit.queued'));
          updateNet();
          route();
        } else { showErr(err, e); sendBtn.disabled = false; }
      }
    },
  }, h('h2', {}, t('submit.title')), sub?.status === 'returned' ? h('div', { class: 'notice warn' }, t('student.returned'), sub.note ? ': ' + sub.note : '') : null,
  modes.length > 1 ? tabs : null, area, err, h('div', { class: 'row end' }, sendBtn));
  render(head, form);
}

// ---------- Teacher ----------
async function viewTeacherHome() {
  const classes = await GET('/api/teacher/classes');
  render(h('h1', {}, t('teacher.classes')),
    classes.length ? classes.map((c) => h('div', { class: 'card' },
      h('div', { class: 'row' }, h('h2', { style: 'margin:0' }, c.name), h('span', { class: 'spacer' }),
        h('span', { class: 'badge plain' }, t('teacher.students', { n: c.students })),
        h('span', { class: 'badge info' }, t('teacher.classCode'), ' ', h('span', { class: 'ltr' }, c.code))),
      h('div', { class: 'row', style: 'margin-top:10px' },
        h('a', { class: 'btn', href: `#/class/${c.id}` }, t('teacher.openClass')),
        h('label', { class: 'check' }, h('input', {
          type: 'checkbox', checked: !!c.ai_enabled,
          onchange: async (e) => { await PUT(`/api/teacher/classes/${c.id}`, { aiEnabled: e.target.checked }); toast(e.target.checked ? t('teacher.aiOn') : t('teacher.aiOff')); },
        }), t('teacher.aiForClass'))))) : h('p', { class: 'muted' }, t('teacher.noClasses')));
}

async function viewClass(classId) {
  const tasks = await GET(`/api/teacher/classes/${classId}/tasks`);
  render(h('a', { href: '#/' }, '← ', t('common.back')),
    h('div', { class: 'row' }, h('h1', {}, t('teacher.tasks')), h('span', { class: 'spacer' }), h('a', { class: 'btn', href: `#/class/${classId}/new` }, '+ ', t('teacher.newTask'))),
    h('div', { class: 'card' }, tasks.length ? h('ul', { class: 'list' }, tasks.map((tk) => h('li', {}, h('a', { class: 'item', href: `#/task/${tk.id}` },
      h('div', { class: 'spacer' }, h('b', {}, tk.title), h('div', { class: 'muted small' }, t('lang.' + tk.answer_lang), tk.due_at ? ' · ' + tk.due_at : '')),
      h('span', { class: 'badge plain' }, t('teacher.submittedN', { n: tk.submitted })),
      h('span', { class: 'badge ok' }, t('teacher.approvedN', { n: tk.approved })),
      tk.rechecks ? h('span', { class: 'badge warn' }, t('teacher.rechecksN', { n: tk.rechecks })) : null)))) : h('p', { class: 'muted' }, t('teacher.noTasks'))));
}

function viewNewTask(classId) {
  const title = h('input', { type: 'text', required: true });
  const instr = h('textarea', { rows: 5, required: true });
  const answerLang = h('select', {}, ['en', 'ur', 'ur-roman'].map((l) => h('option', { value: l }, t('lang.' + l))));
  const fbLang = h('select', {}, h('option', { value: '' }, t('task.fbStudentChoice')), h('option', { value: 'en' }, 'English'), h('option', { value: 'ur' }, 'اردو'));
  const mode = h('select', {}, ['any', 'typed', 'photo', 'file'].map((m) => h('option', { value: m }, t('mode.' + m))));
  const hw = h('input', { type: 'checkbox' });
  const crop = h('input', { type: 'number', min: 0, max: 40, value: 15 });
  const due = h('input', { type: 'date' });
  const extra = h('input', { type: 'text' });
  const rubricBox = h('div');
  const samplesBox = h('div');
  const addCrit = (name = '', max = '', desc = '') => {
    const row = h('div', { class: 'criterion' },
      h('div', { class: 'grid two' },
        h('div', {}, h('label', {}, t('task.critName')), h('input', { type: 'text', value: name, 'data-k': 'name' })),
        h('div', {}, h('label', {}, t('task.critMax')), h('input', { type: 'number', min: 1, max: 100, value: max, 'data-k': 'max' }))),
      h('label', {}, t('task.critDesc')), h('textarea', { rows: 2, 'data-k': 'description' }, desc),
      h('button', { class: 'btn quiet small', type: 'button', onclick: () => row.remove() }, t('common.remove')));
    rubricBox.append(row);
  };
  const addSample = () => {
    const row = h('div', { class: 'criterion' },
      h('label', {}, t('task.sampleLevel')), h('select', { 'data-k': 'level' }, ['high', 'medium', 'low'].map((l) => h('option', { value: l }, t('level.' + l)))),
      h('label', {}, t('task.sampleText')), h('textarea', { rows: 4, 'data-k': 'text' }),
      h('button', { class: 'btn quiet small', type: 'button', onclick: () => row.remove() }, t('common.remove')));
    samplesBox.append(row);
  };
  addCrit(); addCrit(); addSample();
  const err = errorBox();
  const collect = (box) => [...box.children].map((row) => Object.fromEntries([...row.querySelectorAll('[data-k]')].map((el) => [el.dataset.k, el.value])));
  render(h('a', { href: `#/class/${classId}` }, '← ', t('common.back')), h('h1', {}, t('teacher.newTask')), h('form', {
    class: 'card', onsubmit: async (ev) => {
      ev.preventDefault();
      try {
        const r = await POST('/api/teacher/tasks', {
          classId, title: title.value, instructions: instr.value, answerLang: answerLang.value, feedbackLang: fbLang.value || null,
          mode: mode.value, handwrittenOnly: hw.checked, headerCropPct: Number(crop.value), dueAt: due.value, extraTimeNote: extra.value,
          rubric: collect(rubricBox).filter((c) => c.name).map((c) => ({ ...c, max: Number(c.max) })), samples: collect(samplesBox),
        });
        toast(t('common.saved'));
        location.hash = `#/task/${r.id}`;
      } catch (e) { showErr(err, e); }
    },
  }, err,
  formField('t1', t('task.title'), title), formField('t2', t('task.instructions'), instr),
  h('div', { class: 'grid two' }, formField('t3', t('task.answerLang'), answerLang), formField('t4', t('task.feedbackLang'), fbLang, t('task.feedbackLangHint'))),
  h('div', { class: 'grid two' }, formField('t5', t('task.mode'), mode), formField('t6', t('task.due'), due)),
  h('div', { class: 'field' }, h('label', { class: 'check' }, hw, t('task.handwrittenOnly')), h('div', { class: 'hint' }, t('task.handwrittenHint'))),
  formField('t7', t('task.crop'), crop, t('task.cropHint')),
  formField('t8', t('task.extraTime'), extra, t('task.extraTimeHint')),
  h('h2', {}, t('task.rubric')), h('p', { class: 'muted small' }, t('task.rubricHint')), rubricBox,
  h('button', { class: 'btn quiet small', type: 'button', onclick: () => addCrit() }, '+ ', t('task.addCriterion')),
  h('h2', {}, t('task.samples')), h('p', { class: 'muted small' }, t('task.samplesHint')), samplesBox,
  h('button', { class: 'btn quiet small', type: 'button', onclick: addSample }, '+ ', t('task.addSample')),
  h('div', { class: 'row end', style: 'margin-top:16px' }, h('button', { class: 'btn', type: 'submit' }, t('task.create')))));
}

async function viewTaskDashboard(id) {
  const d = await GET(`/api/teacher/tasks/${id}`);
  render(h('a', { href: `#/class/${d.task.class_id}` }, '← ', t('common.back')), h('h1', {}, d.task.title),
    h('div', { class: 'stats' },
      h('div', { class: 'stat' }, h('b', { class: 'ltr' }, d.stats.submitted, ' / ', d.stats.enrolled), t('dash.submitted')),
      h('div', { class: 'stat' }, h('b', {}, d.stats.approved), t('dash.approved')),
      h('div', { class: 'stat' }, h('b', { class: 'ltr' }, num(d.stats.average), ' / ', d.max), t('dash.average'))),
    h('p', { class: 'muted small' }, t('dash.finalOnly')),
    h('div', { class: 'card' }, h('h2', {}, t('dash.queue')), h('p', { class: 'muted small' }, t('dash.queueHint')),
      d.queue.length ? h('ul', { class: 'list' }, d.queue.map((q) => h('li', {}, h('a', { class: 'item', href: `#/review/${q.id}` },
        h('div', { class: 'spacer' }, h('b', {}, q.student_name), ' ', h('span', { class: 'muted small ltr' }, q.student_code), h('div', { class: 'muted small' }, t('submit.' + q.kind), ' · ', fmtDate(q.submitted_at))),
        q.recheck_status === 'requested' ? h('span', { class: 'badge warn' }, t('dash.recheck')) : null,
        q.flags.slice(0, 3).map(flagBadge),
        q.status === 'approved' ? h('span', { class: 'badge ok ltr' }, `${num(q.final_total)} / ${d.max}`) : statusBadge(q.status))))) : h('p', { class: 'muted' }, t('dash.empty'))),
    d.notSubmitted.length ? h('div', { class: 'card' }, h('h2', {}, t('dash.notSubmitted')), h('p', {}, d.notSubmitted.map((s) => s.name).join('، '))) : null);
}

function markUnclear(text) {
  const out = h('div', { class: 'transcript' });
  String(text || '').split(/(\[unclear\])/g).forEach((part) => out.append(part === '[unclear]' ? h('mark', { class: 'unclear' }, '[unclear]') : part));
  return out;
}

async function viewReview(id) {
  const opened = Date.now();
  const d = await GET(`/api/teacher/submissions/${id}`);
  const s = d.submission, task = d.task, draft = d.draft;
  const max = task.rubric.reduce((a, c) => a + c.max, 0);
  const back = h('a', { href: `#/task/${task.id}` }, '← ', t('common.back'));
  const title = h('h1', {}, d.student.name, ' ', h('span', { class: 'muted small ltr' }, d.student.code));

  // Answer panel
  const answer = h('div', { class: 'card' }, h('h2', {}, t('review.answer')),
    s.text ? h('div', { class: 'transcript', dir: 'auto' }, s.text) : null,
    d.files.filter((f) => f.role === 'original').map((f) => f.mime.startsWith('image/') ? h('img', { class: 'scan', src: f.url, alt: t('review.scanAlt') }) : h('a', { href: f.url, target: '_blank', rel: 'noopener' }, t('review.openFile'))),
    draft?.transcription ? [h('h3', {}, t('review.transcription')), markUnclear(draft.transcription)] : null,
    s.writing_log ? h('details', {}, h('summary', {}, t('review.writingLog')), h('ul', { class: 'small' },
      h('li', {}, t('review.duration', { m: Math.round(s.writing_log.durationMs / 60000) })),
      h('li', {}, t('review.keystrokes', { n: s.writing_log.keystrokes })),
      h('li', {}, t('review.pastes', { n: s.writing_log.pastes.length, max: s.writing_log.largestPaste || 0 }))),
    h('p', { class: 'muted small' }, t('review.writingLogHint'))) : null);

  // Score first
  if (d.draftHidden === 'score_first') {
    const est = h('input', { type: 'number', min: 0, max, step: 0.5 });
    return render(back, title, h('div', { class: 'grid side' }, answer, h('form', {
      class: 'card', onsubmit: async (ev) => {
        ev.preventDefault();
        if (est.value === '') return;
        await POST(`/api/teacher/submissions/${id}/estimate`, { total: Number(est.value) });
        route();
      },
    }, h('h2', {}, t('review.scoreFirstTitle')), h('p', {}, t('review.scoreFirstIntro')), formField('est', t('review.yourEstimate', { max }), est),
    h('button', { class: 'btn', type: 'submit' }, t('review.showDraft')))));
  }

  const err = errorBox();
  const inputs = {};
  const checks = {};
  const prev = s.final_scores || [];
  const crits = task.rubric.map((c) => {
    const ds = draft?.scores.find((x) => x.id === c.id);
    const start = prev.find((x) => x.id === c.id)?.score ?? ds?.score ?? '';
    inputs[c.id] = h('input', { type: 'number', min: 0, max: c.max, step: 0.5, value: start, 'aria-label': `${c.name} (0–${c.max})` });
    checks[c.id] = h('input', {
      type: 'checkbox', checked: !!s.checked[c.id],
      onchange: async (e) => { await POST(`/api/teacher/submissions/${id}/check`, { criterionId: c.id, checked: e.target.checked }); },
    });
    const conf = ds?.confidence;
    return h('div', { class: 'criterion' },
      h('div', { class: 'head' }, h('b', {}, c.name), h('span', { class: 'spacer' }), h('span', { class: 'ltr row' }, inputs[c.id], h('span', {}, '/ ', c.max))),
      ds ? h('div', { class: 'small' },
        ds.score !== null && ds.score !== undefined ? h('span', { class: 'badge info' }, t('review.aiSuggests', { n: num(ds.score) })) : null, ' ',
        conf ? h('span', { class: 'badge ' + (conf === 'low' ? 'warn' : 'plain') }, t('conf.' + conf)) : null,
        h('p', { class: 'muted', dir: 'auto' }, ds.reason)) : null,
      d.needsChecks ? h('label', { class: 'check' }, checks[c.id], t('review.checked')) : null);
  });
  const fb = h('textarea', { rows: 7, dir: 'auto' }, s.final_feedback || draft?.feedbackStudent || '');

  const panel = h('form', {
    class: 'card', onsubmit: async (ev) => {
      ev.preventDefault();
      const scores = task.rubric.map((c) => ({ id: c.id, score: inputs[c.id].value === '' ? NaN : Number(inputs[c.id].value) }));
      if (scores.some((x) => Number.isNaN(x.score))) return showErr(err, t('err.score_out_of_range'));
      try {
        const r = await POST(`/api/teacher/submissions/${id}/approve`, { scores, feedback: fb.value, reviewMs: Date.now() - opened });
        toast(t('review.released', { total: num(r.total) }));
        location.hash = `#/task/${task.id}`;
      } catch (e) { showErr(err, e); }
    },
  },
  h('h2', {}, t('review.scoreAndFeedback')),
  d.draftHidden === 'blind_sample' ? h('div', { class: 'notice' }, t('review.blind')) : null,
  s.status === 'ai_unavailable' ? h('div', { class: 'notice warn' }, t('review.noDraft')) : null,
  s.status === 'submitted' ? h('div', { class: 'notice' }, t('review.drafting')) : null,
  draft ? h('div', { class: 'notice' }, t('review.draftNotice', { model: draft.model }), draft.tier === 'beta' ? h('div', { class: 'small' }, t('review.betaTier')) : null) : null,
  draft?.flags.length ? h('div', { class: 'notice warn' }, h('b', {}, t('review.flags')), h('ul', {}, draft.flags.map((f) => h('li', {}, t('flag.' + f.type), f.note ? h('span', { class: 'muted small', dir: 'auto' }, ' – ', f.note) : null)))) : null,
  s.recheck_status === 'requested' ? h('div', { class: 'notice warn' }, h('b', {}, t('review.recheckReason')), ' ', s.recheck_reason) : null,
  d.needsChecks ? h('p', { class: 'small' }, t('review.checkEach')) : null,
  crits,
  formField('fb', t('review.feedbackForStudent'), fb, t('review.feedbackHint')),
  draft && draft.feedbackTeacher && draft.feedbackTeacher !== draft.feedbackStudent ? h('details', { open: true }, h('summary', {}, t('review.inYourLang')), h('p', { dir: 'auto', class: 'transcript' }, draft.feedbackTeacher)) : null,
  draft?.oralQuestions?.length ? h('details', {}, h('summary', {}, t('review.oral')), h('ol', {}, draft.oralQuestions.map((q) => h('li', { dir: 'auto' }, q)))) : null,
  err,
  h('div', { class: 'row end' },
    s.status !== 'approved' ? h('button', {
      class: 'btn quiet', type: 'button', onclick: async () => {
        const note = prompt(t('review.returnNote'));
        if (note === null) return;
        await POST(`/api/teacher/submissions/${id}/return`, { note });
        toast(t('review.returnedToast'));
        location.hash = `#/task/${task.id}`;
      },
    }, t('review.return')) : null,
    ['ai_unavailable'].includes(s.status) ? h('button', { class: 'btn quiet', type: 'button', onclick: async () => { await POST(`/api/teacher/submissions/${id}/redraft`); toast(t('review.redraftQueued')); route(); } }, t('review.redraft')) : null,
    h('button', { class: 'btn', type: 'submit', disabled: s.status === 'approved' && s.recheck_status !== 'requested' }, s.recheck_status === 'requested' ? t('review.approveRecheck') : t('review.approve'))),
  s.status === 'approved' && s.recheck_status !== 'requested' ? h('p', { class: 'muted small' }, t('review.alreadyApproved')) : null);

  render(back, title, h('div', { class: 'grid side' }, answer, panel));
}

// ---------- Admin ----------
async function viewAdminHome() {
  const [org, usage] = await Promise.all([GET('/api/admin/org'), GET('/api/admin/usage')]);
  const dl = h('select', {}, h('option', { value: 'en' }, 'English'), h('option', { value: 'ur' }, 'اردو'));
  dl.value = org.default_lang;
  const ret = h('input', { type: 'number', min: 30, max: 3650, value: org.retention_days });
  const lim = h('input', { type: 'number', min: 0, value: org.ai_monthly_limit });
  const tiers = {};
  const tierRows = ['en', 'ur', 'ur-roman'].map((l) => {
    tiers[l] = h('select', {}, ['full', 'beta', 'interface'].map((x) => h('option', { value: x }, t('tier.' + x))));
    tiers[l].value = org.lang_tiers[l] || 'interface';
    return formField('tier-' + l, t('lang.' + l), tiers[l]);
  });
  const thisMonth = usage.months[0];
  render(h('h1', {}, org.name),
    org.plan === 'trial' ? h('div', { class: 'notice' }, t('admin.trial', { d: fmtDate(org.trial_ends_at) })) : null,
    usage.aiMode === 'demo' ? h('div', { class: 'notice warn' }, t('admin.demoMode')) : null,
    h('div', { class: 'stats' },
      h('div', { class: 'stat' }, h('b', { class: 'ltr' }, thisMonth?.drafts || 0, ' / ', usage.limit), t('admin.draftsThisMonth'))),
    h('form', {
      class: 'card', onsubmit: async (ev) => {
        ev.preventDefault();
        await PUT('/api/admin/org', { defaultLang: dl.value, retentionDays: Number(ret.value), aiMonthlyLimit: Number(lim.value), langTiers: Object.fromEntries(Object.entries(tiers).map(([k, v]) => [k, v.value])) });
        toast(t('common.saved'));
      },
    }, h('h2', {}, t('admin.settings')),
    formField('a1', t('admin.defaultLang'), dl, t('admin.defaultLangHint')),
    formField('a2', t('admin.retention'), ret, t('admin.retentionHint')),
    formField('a3', t('admin.aiLimit'), lim, t('admin.aiLimitHint')),
    h('h3', {}, t('admin.tiers')), h('p', { class: 'muted small' }, t('admin.tiersHint')), tierRows,
    h('button', { class: 'btn', type: 'submit' }, t('common.save'))));
}

async function viewAdminUsers() {
  const users = await GET('/api/admin/users');
  const out = h('div');
  const showCreds = (rows) => fill(out, h('div', { class: 'notice ok' }, h('b', {}, t('admin.credsTitle')), h('p', { class: 'small' }, t('admin.credsHint')),
    h('div', { class: 'table-wrap' }, h('table', {}, h('thead', {}, h('tr', {}, h('th', {}, t('login.username')), h('th', {}, t('admin.tempPassword')))),
      h('tbody', {}, rows.map((r) => h('tr', {}, h('td', { class: 'ltr' }, r.username), h('td', { class: 'code' }, r.tempPassword || r.error))))))));
  const role = h('select', {}, ['student', 'teacher', 'admin'].map((r) => h('option', { value: r }, t('role.' + r))));
  const name = h('input', { type: 'text' }), username = h('input', { type: 'text', class: 'ltr', autocapitalize: 'none' }), email = h('input', { type: 'email', class: 'ltr' });
  const band = h('select', {}, ['under18', 'under13', 'adult'].map((b) => h('option', { value: b }, t('age.' + b))));
  const err = errorBox();
  const csv = h('textarea', { rows: 6, class: 'ltr', placeholder: 'role,name,username,email,age_band,class_code\nstudent,Ayesha Khan,ayesha.k,,under18,ABC123' });
  const csvFile = h('input', { type: 'file', accept: '.csv,text/csv' });
  csvFile.addEventListener('change', async () => { if (csvFile.files[0]) csv.value = await csvFile.files[0].text(); });
  render(h('h1', {}, t('nav.users')), out,
    h('div', { class: 'grid two' },
      h('form', {
        class: 'card', onsubmit: async (ev) => {
          ev.preventDefault();
          try { const r = await POST('/api/admin/users', { role: role.value, name: name.value, username: username.value.toLowerCase(), email: email.value, ageBand: band.value }); showCreds([r]); name.value = username.value = email.value = ''; } catch (e) { showErr(err, e); }
        },
      }, h('h2', {}, t('admin.addUser')), err, formField('u1', t('admin.role'), role), formField('u2', t('admin.name'), name),
      formField('u3', t('login.username'), username, t('hint.username')), formField('u4', t('setup.email'), email, t('admin.emailHint')), formField('u5', t('admin.age'), band),
      h('button', { class: 'btn', type: 'submit' }, t('admin.create'))),
      h('form', {
        class: 'card', onsubmit: async (ev) => {
          ev.preventDefault();
          const r = await POST('/api/admin/import-csv', { csv: csv.value });
          showCreds(r.results);
        },
      }, h('h2', {}, t('admin.csvTitle')), h('p', { class: 'muted small' }, t('admin.csvHint')), csvFile, formField('csv', 'CSV', csv), h('button', { class: 'btn', type: 'submit' }, t('admin.import')))),
    h('div', { class: 'card table-wrap' }, h('table', {},
      h('thead', {}, h('tr', {}, h('th', {}, t('admin.name')), h('th', {}, t('login.username')), h('th', {}, t('admin.role')), h('th', {}, t('admin.status')), h('th', {}, ''))),
      h('tbody', {}, users.map((u) => h('tr', {},
        h('td', {}, u.name, h('div', { class: 'muted small ltr' }, u.code)), h('td', { class: 'ltr' }, u.username), h('td', {}, t('role.' + u.role)),
        h('td', {}, u.active ? h('span', { class: 'badge ok' }, t('admin.active')) : h('span', { class: 'badge plain' }, t('admin.inactive')), u.role !== 'student' && u.totp_enabled ? h('span', { class: 'badge info' }, '2FA') : null),
        h('td', {}, h('div', { class: 'row' },
          h('button', { class: 'btn quiet small', type: 'button', onclick: async () => { const r = await POST(`/api/admin/users/${u.id}/reset-link`); prompt(r.emailed ? t('admin.linkEmailed') : t('admin.linkCopy'), r.link); } }, t('admin.resetLink')),
          u.role !== 'student' && u.totp_enabled ? h('button', { class: 'btn quiet small', type: 'button', onclick: async () => { if (confirm(t('admin.resetMfaConfirm'))) { await POST(`/api/admin/users/${u.id}/reset-mfa`); toast(t('common.saved')); route(); } } }, t('admin.resetMfa')) : null,
          u.id !== me.id ? h('button', { class: 'btn quiet small', type: 'button', onclick: async () => { await POST(`/api/admin/users/${u.id}/active`, { active: !u.active }); route(); } }, u.active ? t('admin.deactivate') : t('admin.activate')) : null,
          u.role === 'student' && u.active ? h('button', {
            class: 'btn danger small', type: 'button', onclick: async () => {
              if (!confirm(t('admin.deleteConfirm', { name: u.name }))) return;
              const r = await api('DELETE', `/api/admin/students/${u.id}/data`);
              alert(t('admin.deleted', { proof: r.proof }));
              route();
            },
          }, t('admin.deleteData')) : null))))))));
}

async function viewAdminClasses() {
  const [classes, users] = await Promise.all([GET('/api/admin/classes'), GET('/api/admin/users')]);
  const teachers = users.filter((u) => u.role === 'teacher' && u.active);
  const students = users.filter((u) => u.role === 'student' && u.active);
  const name = h('input', { type: 'text' });
  const teacher = h('select', {}, teachers.map((u) => h('option', { value: u.id }, u.name)));
  const err = errorBox();
  render(h('h1', {}, t('nav.classes')),
    h('form', {
      class: 'card', onsubmit: async (ev) => {
        ev.preventDefault();
        try { await POST('/api/admin/classes', { name: name.value, teacherId: Number(teacher.value) }); route(); } catch (e) { showErr(err, e); }
      },
    }, h('h2', {}, t('admin.newClass')), err, teachers.length ? null : h('div', { class: 'notice warn' }, t('admin.needTeacher')),
    h('div', { class: 'grid two' }, formField('c1', t('admin.className'), name), formField('c2', t('admin.teacher'), teacher)), h('button', { class: 'btn', type: 'submit' }, t('admin.create'))),
    classes.map((c) => {
      const sel = h('select', { multiple: true, size: 6 }, students.map((s) => h('option', { value: s.id }, `${s.name} (${s.username})`)));
      return h('div', { class: 'card' },
        h('div', { class: 'row' }, h('h2', { style: 'margin:0' }, c.name), h('span', { class: 'spacer' }), h('span', { class: 'badge info' }, t('teacher.classCode'), ' ', h('span', { class: 'ltr' }, c.code))),
        h('p', { class: 'muted small' }, t('admin.classInfo', { teacher: c.teacher_name, n: c.students })),
        h('details', {}, h('summary', {}, t('admin.enroll')), sel, h('button', {
          class: 'btn secondary small', type: 'button', style: 'margin-top:8px',
          onclick: async () => { const ids = [...sel.selectedOptions].map((o) => Number(o.value)); const r = await POST(`/api/admin/classes/${c.id}/enroll`, { studentIds: ids }); toast(t('admin.enrolled', { n: r.enrolled })); route(); },
        }, t('admin.enrollBtn'))));
    }));
}

function gateRow(label, value, ok, target) {
  return h('tr', {}, h('td', {}, label), h('td', {}, value === null ? '–' : value + '%'), h('td', {}, target), h('td', {}, value === null ? h('span', { class: 'badge plain' }, t('quality.noData')) : ok === null ? h('span', { class: 'badge plain' }, t('quality.measureOnly')) : ok ? h('span', { class: 'badge ok' }, t('quality.pass')) : h('span', { class: 'badge bad' }, t('quality.notYet'))));
}
async function viewAdminQuality() {
  const q = await GET('/api/admin/quality');
  const block = (key, r) => h('div', { class: 'card' },
    h('div', { class: 'row' }, h('h2', { style: 'margin:0' }, key === 'all' ? t('quality.all') : t('lang.' + key)), h('span', { class: 'spacer' }),
      h('span', { class: 'badge plain' }, t('quality.answers', { n: r.answers })), r.gate1.pass ? h('span', { class: 'badge ok' }, t('quality.gate1Pass')) : h('span', { class: 'badge warn' }, t('quality.gate1Not'))),
    h('div', { class: 'table-wrap' }, h('table', {},
      h('thead', {}, h('tr', {}, h('th', {}, t('quality.metric')), h('th', {}, t('quality.value')), h('th', {}, t('quality.target')), h('th', {}, ''))),
      h('tbody', {},
        gateRow(t('quality.within10'), r.within10Pct, r.gate1.within10, '≥ 90%'),
        gateRow(t('quality.over20'), r.over20Pct, r.gate1.over20, '< 3%'),
        gateRow(t('quality.flagHealth'), r.flagHealthPct, r.gate1.flagHealth, '≥ 70%'),
        gateRow(t('quality.edited'), r.editedPct, null, '–'),
        gateRow(t('quality.timeSaved'), r.timeSavedPct, r.timeSavedPct !== null && r.timeSavedPct >= 30, '≥ 30% (Gate 2)')))),
    !r.gate1.enoughAnswers ? h('p', { class: 'muted small' }, t('quality.need100')) : null);
  render(h('h1', {}, t('nav.quality')), h('p', { class: 'muted' }, t('quality.intro')),
    ['all', 'en', 'ur', 'ur-roman'].map((k) => block(k, q[k])));
}

async function viewAdminAudit() {
  const rows = await GET('/api/admin/audit?limit=300');
  render(h('h1', {}, t('nav.audit')), h('p', { class: 'muted' }, t('audit.intro')),
    h('div', { class: 'card table-wrap' }, h('table', {},
      h('thead', {}, h('tr', {}, h('th', {}, t('audit.when')), h('th', {}, t('audit.who')), h('th', {}, t('audit.what')), h('th', {}, t('audit.detail')))),
      h('tbody', {}, rows.map((r) => h('tr', {}, h('td', { class: 'small' }, fmtDate(r.at)), h('td', {}, r.user_name || '–'),
        h('td', {}, t('audit.' + r.action) === 'audit.' + r.action ? r.action : t('audit.' + r.action), r.entity ? h('div', { class: 'muted small' }, `${r.entity} #${r.entity_id}`) : null),
        h('td', { class: 'small ltr' }, r.detail ? r.detail.slice(0, 160) : '')))))));
}

async function viewAdminSystem() {
  const s = await GET('/api/admin/system');
  render(h('h1', {}, t('nav.system')),
    h('div', { class: 'card' }, h('h2', {}, t('system.backups')), h('p', { class: 'muted small' }, t('system.backupsHint')),
      h('div', { class: 'row' },
        h('button', { class: 'btn secondary', type: 'button', onclick: async () => { try { await POST('/api/admin/backup-now'); toast(t('common.saved')); route(); } catch (e) { toast(errText(e)); } } }, t('system.backupNow')),
        h('button', { class: 'btn secondary', type: 'button', onclick: async () => { try { const r = await POST('/api/admin/restore-test'); toast(r.ok ? t('system.restoreOk') : t('system.restoreBad')); route(); } catch (e) { toast(errText(e)); } } }, t('system.restoreTest'))),
      h('div', { class: 'table-wrap' }, h('table', {}, h('tbody', {}, s.events.map((e) => h('tr', {}, h('td', {}, fmtDate(e.at)), h('td', {}, t('system.' + e.type)), h('td', {}, e.ok ? h('span', { class: 'badge ok' }, 'OK') : h('span', { class: 'badge bad' }, t('system.failed'))), h('td', { class: 'small ltr' }, (e.detail || '').slice(0, 120)))))))),
    h('div', { class: 'card' }, h('h2', {}, t('system.deletions')), h('p', { class: 'muted small' }, t('system.deletionsHint')),
      s.deletions.length ? h('div', { class: 'table-wrap' }, h('table', {}, h('tbody', {}, s.deletions.map((d) => h('tr', {}, h('td', {}, fmtDate(d.at)), h('td', {}, t('system.reason.' + d.reason)), h('td', { class: 'small ltr' }, d.summary), h('td', { class: 'code small' }, d.proof.slice(0, 16) + '…')))))) : h('p', { class: 'muted' }, '–')),
    s.failedJobs.length ? h('div', { class: 'card' }, h('h2', {}, t('system.failedJobs')), h('ul', {}, s.failedJobs.map((j) => h('li', { class: 'small' }, `#${j.ref_id}: ${j.last_error}`)))) : null);
}

// ---------- Shuru ----------
if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
boot();
