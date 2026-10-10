(function (root) {
  var CF = (root.CF = root.CF || {});

  var DEFAULTS = { thresholdDb: -40, minSilence: 0.4, padding: 0.1, minKeep: 0.12 };

  // Nivel RMS (dBFS) por janela de frameMs.
  function rmsDb(samples, sr, frameMs) {
    var fl = Math.max(1, Math.round(sr * (frameMs || 10) / 1000));
    var n = Math.floor(samples.length / fl), out = new Float32Array(n);
    var scale = samples instanceof Int16Array ? 1 / 32768 : 1;
    for (var f = 0; f < n; f++) {
      var sum = 0, base = f * fl;
      for (var i = 0; i < fl; i++) { var v = samples[base + i] * scale; sum += v * v; }
      var db = 10 * Math.log10(sum / fl + 1e-10);
      out[f] = db < -100 ? -100 : db;
    }
    return { db: out, frameSec: fl / sr };
  }

  function detect(db, frameSec, opts) {
    var o = Object.assign({}, DEFAULTS, opts || {});
    var total = db.length * frameSec, silences = [], rs = -1, i;
    function flush(endIdx) {
      if (rs < 0) return;
      var a = rs * frameSec, b = endIdx * frameSec;
      if (b - a >= o.minSilence) {
        var s = a === 0 ? 0 : a + o.padding, e = endIdx === db.length ? total : b - o.padding;
        if (e - s > 0.01) silences.push({ start: s, end: e });
      }
      rs = -1;
    }
    for (i = 0; i < db.length; i++) {
      if (db[i] < o.thresholdDb) { if (rs < 0) rs = i; } else flush(i);
    }
    flush(db.length);
    return { silences: silences, keep: complement(silences, total, o.minKeep), total: total };
  }

  function complement(silences, total, minKeep) {
    var keep = [], t = 0;
    silences.forEach(function (s) {
      if (s.start - t > 0.001) keep.push({ start: t, end: s.start });
      t = s.end;
    });
    if (total - t > 0.001) keep.push({ start: t, end: total });
    return keep.filter(function (k) { return k.end - k.start >= (minKeep || 0); });
  }

  function union(ranges) {
    var r = ranges.slice().sort(function (a, b) { return a.start - b.start; }), out = [];
    r.forEach(function (x) {
      var l = out[out.length - 1];
      if (l && x.start <= l.end + 1e-6) l.end = Math.max(l.end, x.end);
      else out.push({ start: x.start, end: x.end });
    });
    return out;
  }

  // Impede que um corte caia dentro de uma palavra transcrita.
  function protectWords(keep, words, pad, total) {
    var extra = words.map(function (w) {
      return { start: Math.max(0, w.start - (pad || 0)), end: Math.min(total, w.end + (pad || 0)) };
    });
    return union(keep.concat(extra));
  }

  // Gera segmentos com J-cut / L-cut. Audio alterna entre duas trilhas para permitir a sobreposicao.
  function jlSegments(keep, o) {
    o = Object.assign({ mode: 'none', lead: 0.25, tail: 0.25, total: Infinity }, o || {});
    var segs = [], t = 0, last = keep.length - 1;
    keep.forEach(function (k, i) {
      var len = k.end - k.start;
      var seg = {
        i: i, srcStart: k.start, srcEnd: k.end, tlStart: t, tlEnd: t + len,
        aSrcStart: k.start, aSrcEnd: k.end, aTlStart: t, aTlEnd: t + len,
        aTrack: o.mode === 'none' ? 0 : i % 2
      };
      var gapBefore = i === 0 ? k.start : k.start - keep[i - 1].end;
      var gapAfter = i === last ? o.total - k.end : keep[i + 1].start - k.end;
      var useJ = o.mode === 'j' || o.mode === 'both' || (o.mode === 'alt' && i % 2 === 0);
      var useL = o.mode === 'l' || o.mode === 'both' || (o.mode === 'alt' && i % 2 === 1);
      if (useJ && i > 0) {
        var lead = Math.min(o.lead, gapBefore, len * 0.5, (keep[i - 1].end - keep[i - 1].start) * 0.5);
        if (lead > 0.01) { seg.aSrcStart -= lead; seg.aTlStart -= lead; }
      }
      if (useL && i < last) {
        var tail = Math.min(o.tail, gapAfter, len * 0.5, (keep[i + 1].end - keep[i + 1].start) * 0.5);
        if (tail > 0.01) { seg.aSrcEnd += tail; seg.aTlEnd += tail; }
      }
      segs.push(seg);
      t += len;
    });
    return segs;
  }

  CF.silence = { DEFAULTS: DEFAULTS, rmsDb: rmsDb, detect: detect, complement: complement, union: union, protectWords: protectWords, jlSegments: jlSegments };
  if (typeof module !== 'undefined' && module.exports) module.exports = CF.silence;
})(typeof window !== 'undefined' ? window : globalThis);
