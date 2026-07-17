/* TMDB client — key is stored locally (settings screen), never hardcoded. */
const TMDB = (() => {
  const BASE = 'https://api.themoviedb.org/3';
  const IMG = 'https://image.tmdb.org/t/p';

  const cfg = {
    get key() { return localStorage.getItem('tmdb_key') || ''; },
    set key(v) { localStorage.setItem('tmdb_key', (v || '').trim()); },
    get region() { return localStorage.getItem('region') || 'US'; },
    set region(v) { localStorage.setItem('region', v || 'US'); },
  };

  async function api(path, params = {}) {
    if (!cfg.key) throw new Error('no-key');
    const u = new URL(BASE + path);
    u.searchParams.set('api_key', cfg.key);
    for (const [k, v] of Object.entries(params)) if (v != null) u.searchParams.set(k, v);
    const r = await fetch(u.toString());
    if (r.status === 401) throw new Error('bad-key');
    if (!r.ok) throw new Error('http-' + r.status);
    return r.json();
  }

  const img = (p, size = 'w342') => (p ? `${IMG}/${size}${p}` : '');
  const backdrop = (p) => (p ? `${IMG}/w780${p}` : '');

  // normalize a movie/tv result to a common card shape
  const norm = (x) => {
    const media = x.media_type || (x.title ? 'movie' : 'tv');
    return {
      id: x.id,
      media,
      title: x.title || x.name || '',
      year: (x.release_date || x.first_air_date || '').slice(0, 4),
      poster: img(x.poster_path),
      backdrop: backdrop(x.backdrop_path),
      overview: x.overview || '',
      rating: x.vote_average ? x.vote_average.toFixed(1) : '',
    };
  };
  const list = (res) => (res.results || []).filter((x) => (x.poster_path || x.profile_path)).map(norm);

  // TMDB watch-provider id → Android TV package (for the deep-link / launch)
  const PROVIDER_PKG = {
    8: 'com.netflix.ninja',                              // Netflix
    337: 'com.disney.disneyplus',                        // Disney+
    9: 'com.amazon.amazonvideo.livingroom',              // Prime Video
    119: 'com.amazon.amazonvideo.livingroom',            // Amazon (alt)
    1899: 'com.wbd.stream',                              // Max
    384: 'com.wbd.stream',                               // HBO Max (legacy id)
    15: 'com.hulu.livingroomplus',                       // Hulu (Android TV build)
    2: 'com.apple.atve.androidtv.appletv',               // Apple TV
    350: 'com.apple.atve.androidtv.appletv',             // Apple TV+
    531: 'com.cbs.ott',                                  // Paramount+
    386: 'com.peacocktv.peacockandroid',                 // Peacock
    283: 'com.crunchyroll.crunchyroid',                  // Crunchyroll
    257: 'tv.fubo.mobile',                               // fuboTV
    387: 'com.peacocktv.peacockandroid',                 // Peacock Premium
    1770: 'com.paramount.android.pplus',                 // Paramount+ (alt)
  };

  return {
    cfg,
    img,
    backdrop,
    PROVIDER_PKG,
    trending: () => api('/trending/all/week').then(list),
    trendingMovies: () => api('/trending/movie/week').then(list),
    trendingTv: () => api('/trending/tv/week').then(list),
    popularMovies: () => api('/movie/popular').then(list),
    popularTv: () => api('/tv/popular').then(list),
    topRated: () => api('/movie/top_rated').then(list),
    searchTitles: (q) => api('/search/multi', { query: q }).then((r) => list(r).filter((x) => x.media !== 'person')),
    searchPeople: (q) => api('/search/person', { query: q }).then((r) =>
      (r.results || []).filter((p) => p.profile_path).map((p) => ({
        id: p.id, name: p.name, photo: img(p.profile_path, 'w185'),
        known: (p.known_for || []).map((k) => k.title || k.name).slice(0, 3).join(', '),
      }))),
    personCredits: (id) => api(`/person/${id}/combined_credits`).then((r) => {
      const seen = new Set();
      return (r.cast || [])
        .filter((x) => x.poster_path && !seen.has(x.id) && seen.add(x.id))
        .sort((a, b) => (b.popularity || 0) - (a.popularity || 0))
        .map(norm);
    }),
    details: (media, id) => api(`/${media}/${id}`, { append_to_response: 'external_ids,credits' }),
    recommendations: (media, id) => api(`/${media}/${id}/recommendations`).then(list),
    watchProviders: (media, id) => api(`/${media}/${id}/watch/providers`).then((r) => r.results || {}),
  };
})();
