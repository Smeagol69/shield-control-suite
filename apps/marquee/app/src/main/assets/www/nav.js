/* Spatial D-pad navigation: arrow keys move focus to the nearest focusable
   element in that direction; Enter activates; Back/Esc calls the back handler. */
const Nav = (() => {
  let onBack = () => {};

  const focusables = () =>
    [...document.querySelectorAll('[data-focusable]')].filter((el) => {
      if (el.hidden || el.offsetParent === null) return false;
      const r = el.getBoundingClientRect();
      return r.width > 0 && r.height > 0;
    });

  const current = () => document.activeElement && document.activeElement.matches('[data-focusable]')
    ? document.activeElement : null;

  function focus(el) {
    if (!el) return;
    el.focus();
    el.scrollIntoView({ block: 'nearest', inline: 'center', behavior: 'smooth' });
  }

  // pick the best candidate in a direction using directional + perpendicular distance
  function move(dir) {
    const cur = current();
    const all = focusables();
    if (!all.length) return;
    if (!cur) { focus(all[0]); return; }
    const a = cur.getBoundingClientRect();
    const ax = a.left + a.width / 2, ay = a.top + a.height / 2;

    let best = null, bestScore = Infinity;
    for (const el of all) {
      if (el === cur) continue;
      const b = el.getBoundingClientRect();
      const bx = b.left + b.width / 2, by = b.top + b.height / 2;
      const dx = bx - ax, dy = by - ay;
      let along, cross;
      if (dir === 'left')  { if (dx >= -4) continue; along = -dx; cross = Math.abs(dy); }
      else if (dir === 'right') { if (dx <= 4) continue; along = dx; cross = Math.abs(dy); }
      else if (dir === 'up')   { if (dy >= -4) continue; along = -dy; cross = Math.abs(dx); }
      else { if (dy <= 4) continue; along = dy; cross = Math.abs(dx); }
      // heavily penalize perpendicular offset so we track rows/columns
      const score = along + cross * 2.5;
      if (score < bestScore) { bestScore = score; best = el; }
    }
    if (best) focus(best);
  }

  function onKey(e) {
    // let text inputs keep their own arrow/typing behavior
    const typing = document.activeElement && document.activeElement.tagName === 'INPUT';
    switch (e.key) {
      case 'ArrowLeft': if (typing) return; e.preventDefault(); move('left'); break;
      case 'ArrowRight': if (typing) return; e.preventDefault(); move('right'); break;
      case 'ArrowUp': e.preventDefault(); move('up'); break;
      case 'ArrowDown': e.preventDefault(); move('down'); break;
      case 'Enter': if (!typing) { e.preventDefault(); const c = current(); if (c) c.click(); } break;
      case 'Backspace': if (typing) return; // fallthrough
      case 'Escape': case 'GoBack': e.preventDefault(); onBack(); break;
      default: break;
    }
  }

  return {
    init(backHandler) { onBack = backHandler || (() => {}); window.addEventListener('keydown', onKey); },
    focus,
    focusFirst() { const f = focusables(); if (f.length) focus(f[0]); },
    // focus the first focusable inside a container (e.g. first poster of a screen)
    focusInto(container) {
      const f = container ? [...container.querySelectorAll('[data-focusable]')] : [];
      if (f.length) focus(f[0]); else this.focusFirst();
    },
  };
})();
