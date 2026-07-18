/* Marquee — TMDB/Trakt TV discovery. Picks a title, opens it in the right provider app. */
(() => {
  const main = document.getElementById('main');
  const detailEl = document.getElementById('detail');
  const toastEl = document.getElementById('toast');
  let toastTimer = null;

  // ---- native bridge (present only inside the Android WebView shell) ----
  const Bridge = {
    has: () => typeof window.Android !== 'undefined',
    installed: (() => {
      let cache = null;
      return () => {
        if (cache) return cache;
        try { cache = new Set(JSON.parse(window.Android.installedPackages())); }
        catch { cache = new Set(); }
        return cache;
      };
    })(),
    isInstalled(pkg) { return Bridge.has() ? Bridge.installed().has(pkg) : false; },
    open(pkg) {
      if (Bridge.has()) { try { return window.Android.openPackage(pkg); } catch { return false; } }
      toast('(preview) would open ' + pkg);
      return false;
    },
  };

  function toast(msg) {
    toastEl.textContent = msg;
    toastEl.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => (toastEl.hidden = true), 2600);
  }

  // ---- routing ----
  const routes = { home, search, actor, settings };
  let route = 'home';

  function setActiveNav(r) {
    document.querySelectorAll('.navbtn').forEach((b) => b.classList.toggle('active', b.dataset.route === r));
  }

  async function go(r) {
    route = r;
    setActiveNav(r);
    main.innerHTML = '<div class="loading">Loading…</div>';
    try { await routes[r](); }
    catch (e) {
      if (e.message === 'no-key' || e.message === 'bad-key') return needKey(e.message === 'bad-key');
      main.innerHTML = `<div class="loading err">Something went wrong: ${e.message}</div>`;
    }
    Nav.focusInto(main);
  }

  function needKey(bad) {
    main.innerHTML = `<div class="setup">
      <h1>${bad ? 'That TMDB key was rejected' : 'Welcome to Marquee'}</h1>
      <p>Enter your free TMDB API key to start. Get one at themoviedb.org → Settings → API.</p>
      <button class="btn" data-focusable id="goSettings">Open Settings</button>
    </div>`;
    document.getElementById('goSettings').addEventListener('click', () => go('settings'));
    Nav.focusInto(main);
  }

  // ---- rows / cards ----
  function row(title, items, opts = {}) {
    if (!items || !items.length) return '';
    const cards = items.slice(0, 24).map((it) => card(it, opts)).join('');
    return `<section class="rowsec"><h2>${title}</h2><div class="row">${cards}</div></section>`;
  }

  function card(it, opts = {}) {
    const img = it.poster || it.photo || '';
    const label = it.title || it.name || '';
    const sub = it.year || it.known || '';
    const badge = it.rating ? `<span class="badge">★ ${it.rating}</span>` : '';
    const data = opts.person
      ? `data-person="${it.id}"`
      : `data-media="${it.media}" data-id="${it.id}"`;
    return `<button class="card" data-focusable ${data} title="${label.replace(/"/g, '')}">
      ${img ? `<img loading="lazy" src="${img}" alt="">` : `<div class="noimg">${label}</div>`}
      ${badge}
      <div class="cap"><b>${label}</b><span>${sub}</span></div>
    </button>`;
  }

  // click delegation for cards
  main.addEventListener('click', (e) => {
    const c = e.target.closest('.card');
    if (!c) return;
    if (c.dataset.person) openPerson(c.dataset.person);
    else openDetail(c.dataset.media, c.dataset.id);
  });

  // ---- screens ----
  async function home() {
    const [trend, movies, tv, top] = await Promise.all([
      TMDB.trending(), TMDB.popularMovies(), TMDB.popularTv(), TMDB.topRated(),
    ]);
    main.innerHTML =
      row('Trending this week', trend) +
      row('Popular movies', movies) +
      row('Popular TV', tv) +
      row('Top rated', top);
  }

  async function search() {
    main.innerHTML = `<div class="searchbar">
      <input id="q" data-focusable placeholder="Search movies & shows…" autocomplete="off" />
    </div><div id="results"></div>`;
    const q = document.getElementById('q');
    const results = document.getElementById('results');
    let t = null;
    q.addEventListener('input', () => {
      clearTimeout(t);
      const term = q.value.trim();
      if (!term) { results.innerHTML = ''; return; }
      t = setTimeout(async () => {
        try {
          const items = await TMDB.searchTitles(term);
          results.innerHTML = items.length
            ? `<div class="grid">${items.map((it) => card(it)).join('')}</div>`
            : '<div class="loading">No matches</div>';
        } catch (e) { results.innerHTML = `<div class="loading err">${e.message}</div>`; }
      }, 350);
    });
  }

  async function actor() {
    main.innerHTML = `<div class="searchbar">
      <input id="pq" data-focusable placeholder="Search by actor or director…" autocomplete="off" />
    </div><div id="presults"></div>`;
    const q = document.getElementById('pq');
    const results = document.getElementById('presults');
    let t = null;
    q.addEventListener('input', () => {
      clearTimeout(t);
      const term = q.value.trim();
      if (!term) { results.innerHTML = ''; return; }
      t = setTimeout(async () => {
        try {
          const people = await TMDB.searchPeople(term);
          results.innerHTML = people.length
            ? `<div class="people">${people.map((p) => `
                <button class="person" data-focusable data-person="${p.id}">
                  <img loading="lazy" src="${p.photo}" alt=""><b>${p.name}</b><span>${p.known}</span>
                </button>`).join('')}</div>`
            : '<div class="loading">No people found</div>';
        } catch (e) { results.innerHTML = `<div class="loading err">${e.message}</div>`; }
      }, 350);
    });
  }

  async function openPerson(id) {
    main.innerHTML = '<div class="loading">Loading filmography…</div>';
    const credits = await TMDB.personCredits(id);
    main.innerHTML = `<div class="grid">${credits.map((it) => card(it)).join('')}</div>`;
    Nav.focusInto(main);
  }

  // ---- detail overlay: providers + "more like this" ----
  async function openDetail(media, id) {
    detailEl.hidden = false;
    detailEl.innerHTML = '<div class="loading">Loading…</div>';
    Nav.focusInto(detailEl);
    let d, provs, recs;
    try {
      [d, provs, recs] = await Promise.all([
        TMDB.details(media, id),
        TMDB.watchProviders(media, id),
        TMDB.recommendations(media, id),
      ]);
    } catch (e) { detailEl.innerHTML = `<div class="loading err">${e.message}</div>`; return; }

    const title = d.title || d.name;
    const year = (d.release_date || d.first_air_date || '').slice(0, 4);
    const region = provs[TMDB.cfg.region] || provs.US || {};
    const seen = new Set();
    const offers = [...(region.flatrate || []), ...(region.free || []), ...(region.ads || []),
                    ...(region.rent || []), ...(region.buy || [])]
      .filter((p) => !seen.has(p.provider_id) && seen.add(p.provider_id));

    const provBtns = offers.map((p) => {
      const pkg = TMDB.PROVIDER_PKG[p.provider_id];
      const installed = pkg && Bridge.isInstalled(pkg);
      const cls = 'prov' + (installed ? ' on' : '') + (pkg ? '' : ' noapp');
      return `<button class="${cls}" data-focusable ${pkg ? `data-pkg="${pkg}"` : 'disabled'}
        title="${p.provider_name}">
        <img src="${TMDB.img(p.logo_path, 'w92')}" alt="">
        <span>${p.provider_name}${installed ? ' ·  ▶' : (pkg ? '' : ' · not on Shield')}</span>
      </button>`;
    }).join('');

    detailEl.innerHTML = `
      <div class="dbg" style="background-image:linear-gradient(180deg,rgba(12,14,17,.4),rgba(12,14,17,.97)),url('${TMDB.backdrop(d.backdrop_path)}')"></div>
      <div class="dcontent">
        <button class="dclose" data-focusable id="dclose">✕ Back</button>
        <h1>${title} <small>${year}</small></h1>
        <div class="dmeta">${d.vote_average ? `★ ${d.vote_average.toFixed(1)}` : ''} ${
          d.genres ? '· ' + d.genres.slice(0, 3).map((g) => g.name).join(', ') : ''}</div>
        <p class="dover">${d.overview || ''}</p>
        <h3>Where to watch</h3>
        <div class="provs">${provBtns || '<span class="dim">No streaming providers listed for your region.</span>'}</div>
        ${recs && recs.length ? `<h3>More like this</h3><div class="row">${recs.map((it) => card(it)).join('')}</div>` : ''}
      </div>`;

    document.getElementById('dclose').addEventListener('click', closeDetail);
    detailEl.querySelectorAll('.prov[data-pkg]').forEach((b) => {
      b.addEventListener('click', () => {
        const pkg = b.dataset.pkg;
        if (Bridge.isInstalled(pkg)) { Bridge.open(pkg); toast('Opening ' + b.title + '…'); }
        else toast(b.title + ' is not installed on the Shield');
      });
    });
    detailEl.querySelectorAll('.card').forEach((c) => c.addEventListener('click', () => {
      openDetail(c.dataset.media, c.dataset.id);
    }));
    Nav.focusInto(detailEl);
  }

  function closeDetail() { detailEl.hidden = true; detailEl.innerHTML = ''; Nav.focusInto(main); }

  // ---- settings ----
  async function settings() {
    main.innerHTML = `<div class="setup">
      <h1>Settings</h1>
      <label>TMDB API key</label>
      <input id="key" data-focusable value="${TMDB.cfg.key}" placeholder="paste your TMDB v3 API key" autocomplete="off" />
      <label>Region (for "where to watch")</label>
      <input id="region" data-focusable value="${TMDB.cfg.region}" placeholder="US" maxlength="2" />
      <button class="btn" data-focusable id="save">Save</button>
      <p class="dim">Get a free key at themoviedb.org → Settings → API → API Read Access. Trakt (watchlist) coming next.</p>
      <p class="dim">${Bridge.has() ? 'Running inside the Shield app — provider deep-links are live.' : 'Browser preview — provider launching is simulated.'}</p>
    </div>`;
    document.getElementById('save').addEventListener('click', () => {
      TMDB.cfg.key = document.getElementById('key').value;
      TMDB.cfg.region = document.getElementById('region').value.toUpperCase() || 'US';
      toast('Saved');
      go('home');
    });
  }

  // ---- boot ----
  document.querySelectorAll('.navbtn').forEach((b) =>
    b.addEventListener('click', () => go(b.dataset.route)));
  Nav.init(() => {
    if (!detailEl.hidden) closeDetail();
    else if (route !== 'home') go('home');
  });
  go(TMDB.cfg.key ? 'home' : 'settings');
})();
