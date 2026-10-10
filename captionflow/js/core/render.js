(function (root) {
  var CF = (root.CF = root.CF || {});
  var U = CF.util || require('./util.js');
  var C = CF.captions || require('./captions.js');

  function easeOut(p) { return 1 - Math.pow(1 - p, 3); }
  function easeBack(p) { var c1 = 1.70158, c3 = c1 + 1; return 1 + c3 * Math.pow(p - 1, 3) + c1 * Math.pow(p - 1, 2); }

  var ENTRIES = ['none', 'fade', 'slideUp', 'slideDown', 'slideLeft', 'pop', 'zoom'];
  var EXITS = ['none', 'fade', 'slideUp', 'slideDown', 'zoom'];
  var HIGHLIGHTS = ['none', 'color', 'scale', 'box', 'fill'];

  // dx/dy em unidades de "em" (tamanho da fonte). Mesma funcao alimenta preview e keyframes.
  function blockTransform(a, tRel, dur) {
    var tr = { alpha: 1, dx: 0, dy: 0, scale: 1 };
    if (!a) return tr;
    var inD = Math.max(a.inDur || 0.2, 0.01), outD = Math.max(a.outDur || 0.2, 0.01);
    var p = U.clamp(tRel / inD, 0, 1), e = easeOut(p);
    switch (a.entry) {
      case 'fade': tr.alpha *= e; break;
      case 'slideUp': tr.alpha *= e; tr.dy += (1 - e) * 0.6; break;
      case 'slideDown': tr.alpha *= e; tr.dy -= (1 - e) * 0.6; break;
      case 'slideLeft': tr.alpha *= e; tr.dx += (1 - e) * 1.5; break;
      case 'pop': tr.alpha *= Math.min(1, p * 3); tr.scale *= 0.6 + 0.4 * easeBack(p); break;
      case 'zoom': tr.alpha *= e; tr.scale *= 0.85 + 0.15 * e; break;
    }
    var q = U.clamp((dur - tRel) / outD, 0, 1), x = easeOut(q);
    switch (a.exit) {
      case 'fade': tr.alpha *= x; break;
      case 'slideUp': tr.alpha *= x; tr.dy -= (1 - x) * 0.6; break;
      case 'slideDown': tr.alpha *= x; tr.dy += (1 - x) * 0.6; break;
      case 'zoom': tr.alpha *= x; tr.scale *= 0.85 + 0.15 * x; break;
    }
    return tr;
  }

  function activeIndex(block, t) {
    var a = -1;
    for (var i = 0; i < block.words.length; i++) if (block.words[i].start <= t + 1e-6) a = i;
    return a;
  }

  // Estados visuais distintos de um bloco (um por palavra ativa quando ha destaque).
  function states(block, preset) {
    var end = block.showEnd != null ? block.showEnd : block.end;
    if (!preset.highlight || preset.highlight.mode === 'none') return [{ active: -1, start: block.start, end: end }];
    var out = [];
    for (var i = 0; i < block.words.length; i++) {
      var s = i === 0 ? block.start : block.words[i].start;
      var e = i === block.words.length - 1 ? end : block.words[i + 1].start;
      if (e - s > 0.001) out.push({ active: i, start: s, end: e });
    }
    return out.length ? out : [{ active: -1, start: block.start, end: end }];
  }

  function fontString(p, k) {
    return (p.italic ? 'italic ' : '') + p.weight + ' ' + Math.round(p.size * k * 100) / 100 + 'px ' + p.family + ', sans-serif';
  }

  function roundRect(ctx, x, y, w, h, r) {
    r = Math.min(r, w / 2, h / 2);
    ctx.beginPath(); ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath();
  }

  // opts: {steady:boolean (sem animacao), active:number (forca a palavra ativa), layout:{maxChars,maxLines}, bg:image}
  function draw(ctx, W, H, block, t, preset, opts) {
    opts = opts || {};
    ctx.clearRect(0, 0, W, H);
    if (opts.bg) ctx.drawImage(opts.bg, 0, 0, W, H);
    if (!block || !block.words.length) return;
    var k = H / 1080, dur = (block.showEnd != null ? block.showEnd : block.end) - block.start;
    var tr = opts.steady ? { alpha: 1, dx: 0, dy: 0, scale: 1 } : blockTransform(preset.anim, t - block.start, dur);
    if (tr.alpha <= 0.002) return;
    var hl = preset.highlight || { mode: 'none' };
    var active = opts.active != null ? opts.active : (hl.mode === 'none' ? -1 : activeIndex(block, t));
    var size = preset.size * k, lh = size * preset.lineHeight;
    var layout = opts.layout || C.DEFAULTS;
    var lines = C.fitLines(block.words, layout.maxChars, layout.maxLines) || [[0, block.words.length]];

    ctx.save();
    ctx.font = fontString(preset, k);
    ctx.textBaseline = 'middle'; ctx.textAlign = 'left';
    var txt = function (w) { return preset.uppercase ? w.text.toUpperCase() : w.text; };
    var space = ctx.measureText(' ').width;
    var metrics = lines.map(function (r) {
      var xs = [], x = 0;
      for (var i = r[0]; i < r[1]; i++) {
        var wd = ctx.measureText(txt(block.words[i])).width;
        xs.push({ i: i, x: x, w: wd }); x += wd + space;
      }
      return { items: xs, width: Math.max(0, x - space) };
    });
    var bw = Math.max.apply(null, metrics.map(function (m) { return m.width; }));
    var bh = lines.length * lh;
    var cx = preset.x * W, cy = preset.y * H, left = cx - bw / 2, top = cy - bh / 2;

    ctx.globalAlpha = U.clamp(tr.alpha, 0, 1);
    ctx.translate(cx + tr.dx * size, cy + tr.dy * size);
    ctx.scale(tr.scale, tr.scale);
    ctx.translate(-cx, -cy);

    if (preset.bg && preset.bg.enabled) {
      var pd = preset.bg.padding * k;
      ctx.fillStyle = U.rgba(preset.bg.color, preset.bg.alpha);
      roundRect(ctx, left - pd, top - pd * 0.6, bw + pd * 2, bh + pd * 1.2, preset.bg.radius * k);
      ctx.fill();
    }

    metrics.forEach(function (m, li) {
      var ly = top + (li + 0.5) * lh;
      var ox = preset.align === 'left' ? 0 : preset.align === 'right' ? bw - m.width : (bw - m.width) / 2;
      m.items.forEach(function (it) {
        var word = block.words[it.i], isActive = it.i === active, wx = left + ox + it.x;
        var color = preset.fill;
        if (isActive && (hl.mode === 'color' || hl.mode === 'scale')) color = hl.color;
        if (hl.mode === 'fill' && active >= 0 && it.i <= active) color = hl.color;
        ctx.save();
        if (isActive && hl.mode === 'box') {
          var bp = size * 0.18;
          ctx.fillStyle = hl.boxColor || '#ffd400';
          roundRect(ctx, wx - bp, ly - size * 0.55, it.w + bp * 2, size * 1.1, size * 0.22); ctx.fill();
          color = hl.color || color;
        }
        if (isActive && hl.mode === 'scale') {
          var sc = hl.scale || 1.15, mx = wx + it.w / 2;
          ctx.translate(mx, ly); ctx.scale(sc, sc); ctx.translate(-mx, -ly);
        }
        var sh = preset.shadow, hasStroke = preset.stroke && preset.stroke.width > 0;
        function applyShadow() {
          if (sh && sh.enabled) {
            ctx.shadowColor = U.rgba(sh.color, sh.alpha); ctx.shadowBlur = sh.blur * k;
            ctx.shadowOffsetX = sh.x * k; ctx.shadowOffsetY = sh.y * k;
          }
        }
        function clearShadow() { ctx.shadowColor = 'rgba(0,0,0,0)'; ctx.shadowBlur = 0; ctx.shadowOffsetX = 0; ctx.shadowOffsetY = 0; }
        var s = txt(word);
        if (hasStroke) {
          applyShadow();
          ctx.lineJoin = 'round'; ctx.miterLimit = 2;
          ctx.lineWidth = preset.stroke.width * k * 2; ctx.strokeStyle = preset.stroke.color;
          ctx.strokeText(s, wx, ly); clearShadow();
        } else applyShadow();
        ctx.fillStyle = color; ctx.fillText(s, wx, ly);
        ctx.restore();
      });
    });
    ctx.restore();
  }

  // Keyframes (tempo relativo ao inicio do clip) para o modo raster no Premiere.
  // pos: 0..1 normalizado do frame; scale/opacity em porcentagem.
  function keyframes(preset, W, H, dur, hasIn, hasOut) {
    var a = preset.anim || {}, k = H / 1080, em = preset.size * k, keys = [];
    function add(t, tr) {
      keys.push({ t: U.round(t, 4), prop: 'Opacity', v: U.round(tr.alpha * 100, 2) });
      keys.push({ t: U.round(t, 4), prop: 'Scale', v: U.round(tr.scale * 100, 2) });
      keys.push({ t: U.round(t, 4), prop: 'Position', v: [U.round(0.5 + tr.dx * em / W, 5), U.round(0.5 + tr.dy * em / H, 5)] });
    }
    var inD = a.inDur || 0.2, outD = a.outDur || 0.2;
    if (hasIn && a.entry && a.entry !== 'none') {
      var steps = a.entry === 'pop' ? 4 : 1;
      for (var s = 0; s <= steps; s++) {
        var tt = inD * s / steps;
        add(tt, blockTransform({ entry: a.entry, inDur: inD, outDur: outD }, tt, 1e6));
      }
    }
    if (hasOut && a.exit && a.exit !== 'none') {
      add(Math.max(0, dur - outD), blockTransform({ exit: a.exit, inDur: inD, outDur: outD }, dur - outD, dur));
      add(dur, blockTransform({ exit: a.exit, inDur: inD, outDur: outD }, dur, dur));
    }
    return keys;
  }

  CF.anim = { ENTRIES: ENTRIES, EXITS: EXITS, HIGHLIGHTS: HIGHLIGHTS, blockTransform: blockTransform };
  CF.render = { draw: draw, states: states, activeIndex: activeIndex, keyframes: keyframes };
  if (typeof module !== 'undefined' && module.exports) module.exports = CF.render;
})(typeof window !== 'undefined' ? window : globalThis);
