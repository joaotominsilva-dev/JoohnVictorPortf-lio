(function (root) {
  var CF = (root.CF = root.CF || {});
  var U = CF.util || (typeof require !== 'undefined' ? require('./util.js') : null);

  var STOP = {};
  ('a o as os um uma de da do das dos em na no nas nos e ou que com por para pra se ' +
    'an the of to in on at and or but for with by el la los las un una del y').split(' ')
    .forEach(function (w) { STOP[w] = 1; });
  function isStop(t) { return !!STOP[t.toLowerCase().replace(/[^\p{L}]/gu, '')]; }

  var DEFAULTS = {
    maxChars: 32,      // caracteres por linha
    maxLines: 2,
    maxWps: 3.5,       // palavras por segundo (velocidade de leitura)
    minDuration: 0.8,
    maxDuration: 5,
    pauseBreak: 0.6,   // pausa entre palavras que fecha o bloco
    holdGap: 0.04      // folga ate o proximo bloco
  };

  function lineLen(words, i, j) {
    var n = 0;
    for (var k = i; k < j; k++) n += words[k].text.length;
    return n + Math.max(0, j - i - 1);
  }

  // Quebra balanceada em K linhas por programacao dinamica.
  function fitSegment(words, maxChars, maxLines) {
    var n = words.length;
    if (!n) return [];
    var total = lineLen(words, 0, n);
    for (var K = 1; K <= maxLines; K++) {
      if (K === 1) { if (total <= maxChars || n === 1) return [[0, n]]; continue; }
      if (n < K) break;
      var target = total / K, INF = 1e9, cost = [], prev = [], k, i, j;
      for (k = 0; k <= K; k++) {
        cost.push([]); prev.push([]);
        for (i = 0; i <= n; i++) { cost[k].push(INF); prev[k].push(-1); }
      }
      cost[0][0] = 0;
      for (k = 1; k <= K; k++) {
        for (i = k; i <= n; i++) {
          for (j = k - 1; j < i; j++) {
            if (cost[k - 1][j] >= INF) continue;
            var len = lineLen(words, j, i);
            if (len > maxChars && i - j > 1) continue;
            var c = Math.pow(len - target, 2), last = words[i - 1].text;
            if (i < n) {
              if (/[.,;:!?…]$/.test(last)) c -= 6;
              else if (isStop(last)) c += 8;
            }
            var tot = cost[k - 1][j] + c;
            if (tot < cost[k][i]) { cost[k][i] = tot; prev[k][i] = j; }
          }
        }
      }
      if (cost[K][n] < INF) {
        var out = [], e = n;
        for (k = K; k >= 1; k--) { var s = prev[k][e]; out.unshift([s, e]); e = s; }
        return out;
      }
    }
    return null;
  }

  // Retorna lista de [inicio, fim) em indices de palavra, ou null se nao couber.
  function fitLines(words, maxChars, maxLines) {
    var hasBr = words.some(function (w) { return w.br; });
    if (!hasBr) return fitSegment(words, maxChars, maxLines);
    var lines = [], start = 0;
    for (var i = 0; i < words.length; i++) {
      if (words[i].br || i === words.length - 1) {
        var seg = fitSegment(words.slice(start, i + 1), maxChars, maxLines);
        if (!seg) return null;
        seg.forEach(function (r) { lines.push([r[0] + start, r[1] + start]); });
        start = i + 1;
      }
    }
    return lines.length <= maxLines ? lines : null;
  }

  function linesText(block, o) {
    o = o || DEFAULTS;
    var ls = fitLines(block.words, o.maxChars, o.maxLines) || [[0, block.words.length]];
    return ls.map(function (r) {
      return block.words.slice(r[0], r[1]).map(function (w) { return w.text; }).join(' ');
    });
  }

  function normalizeWords(raw) {
    return (raw || []).map(function (w) {
      return { text: String(w.text != null ? w.text : w.word).trim(), start: +w.start, end: +w.end };
    }).filter(function (w) { return w.text && isFinite(w.start) && isFinite(w.end); })
      .map(function (w) { if (w.end < w.start) w.end = w.start; return w; });
  }

  function mk(words) { return { id: U.uid(), words: words }; }

  function buildBlocks(words, opts) {
    var o = Object.assign({}, DEFAULTS, opts || {});
    words = normalizeWords(words);
    var blocks = [], cur = [];
    function close() { if (cur.length) { blocks.push(mk(cur)); cur = []; } }
    for (var i = 0; i < words.length; i++) {
      var w = words[i], prev = cur[cur.length - 1];
      if (prev) {
        var trial = cur.concat([w]);
        var dur = w.end - cur[0].start;
        if (w.start - prev.end > o.pauseBreak || dur > o.maxDuration || !fitLines(trial, o.maxChars, o.maxLines)) close();
      }
      cur.push(w);
      var cap = o.maxChars * o.maxLines, used = lineLen(cur, 0, cur.length);
      if (/[.!?…]$/.test(w.text) && used >= cap * 0.35) close();
      else if (/[,;:]$/.test(w.text) && used >= cap * 0.7) close();
    }
    close();
    finalizeTiming(blocks, o);
    return blocks;
  }

  function finalizeTiming(blocks, opts) {
    var o = Object.assign({}, DEFAULTS, opts || {});
    blocks.forEach(function (b, i) {
      b.start = b.words[0].start;
      b.end = b.words[b.words.length - 1].end;
      var need = Math.max(o.minDuration, b.words.length / o.maxWps);
      var next = blocks[i + 1];
      var limit = next ? next.words[0].start - o.holdGap : Infinity;
      var show = Math.max(b.end, b.start + need);
      b.showEnd = Math.max(b.end, Math.min(show, limit));
      b.warn = b.words.length / Math.max(b.showEnd - b.start, 0.001) > o.maxWps + 1e-6;
    });
    return blocks;
  }

  function dropEmpty(blocks) {
    for (var i = blocks.length - 1; i >= 0; i--) if (!blocks[i].words.length) blocks.splice(i, 1);
  }

  // ---- operacoes de edicao rapida (atalhos) ----
  function sendLastWordForward(blocks, i) {
    var b = blocks[i]; if (!b || !b.words.length) return blocks;
    var w = b.words.pop(); delete w.br;
    if (blocks[i + 1]) blocks[i + 1].words.unshift(w); else blocks.splice(i + 1, 0, mk([w]));
    dropEmpty(blocks); return blocks;
  }
  function sendFirstWordBack(blocks, i) {
    var b = blocks[i]; if (!b || !b.words.length) return blocks;
    var w = b.words.shift();
    if (blocks[i - 1]) { delete blocks[i - 1].words[blocks[i - 1].words.length - 1].br; blocks[i - 1].words.push(w); }
    else blocks.splice(i, 0, mk([w]));
    dropEmpty(blocks); return blocks;
  }
  function pullFirstWordFromNext(blocks, i) {
    var n = blocks[i + 1]; if (!n || !blocks[i]) return blocks;
    blocks[i].words.push(n.words.shift());
    dropEmpty(blocks); return blocks;
  }
  function pullLastWordFromPrev(blocks, i) {
    var p = blocks[i - 1]; if (!p || !blocks[i]) return blocks;
    var w = p.words.pop(); delete w.br;
    blocks[i].words.unshift(w);
    dropEmpty(blocks); return blocks;
  }
  function splitBlock(blocks, i, at) {
    var b = blocks[i]; if (!b || at <= 0 || at >= b.words.length) return blocks;
    var tail = b.words.splice(at);
    delete b.words[b.words.length - 1].br;
    blocks.splice(i + 1, 0, mk(tail));
    return blocks;
  }
  function mergeWithNext(blocks, i) {
    var b = blocks[i], n = blocks[i + 1]; if (!b || !n) return blocks;
    delete b.words[b.words.length - 1].br;
    b.words = b.words.concat(n.words);
    blocks.splice(i + 1, 1); return blocks;
  }

  // Aplica texto editado mantendo tempos por palavra quando possivel.
  function setBlockText(block, text) {
    var toks = String(text).split(/\s+/).filter(Boolean);
    if (!toks.length) return block;
    var old = block.words;
    if (toks.length === old.length) {
      toks.forEach(function (t, k) { old[k].text = t; });
      return block;
    }
    var s = old[0].start, e = old[old.length - 1].end, span = Math.max(e - s, 0.01);
    var weights = toks.map(function (t) { return Math.max(t.length, 1); });
    var sum = weights.reduce(function (a, b) { return a + b; }, 0), t0 = s;
    block.words = toks.map(function (t, k) {
      var d = span * weights[k] / sum, w = { text: t, start: t0, end: t0 + d };
      t0 += d; return w;
    });
    return block;
  }

  // Reposiciona palavras quando o rough cut remove trechos (tempos relativos ao clip).
  function remapToCuts(blocks, keep) {
    function map(t) {
      var off = 0;
      for (var i = 0; i < keep.length; i++) {
        var k = keep[i];
        if (t >= k.start - 1e-6 && t <= k.end + 1e-6) return off + (t - k.start);
        off += k.end - k.start;
      }
      return null;
    }
    var out = [];
    blocks.forEach(function (b) {
      var ws = [];
      b.words.forEach(function (w) {
        var c = map((w.start + w.end) / 2);
        if (c == null) return;
        var d = w.end - w.start;
        ws.push({ text: w.text, start: Math.max(0, c - d / 2), end: c + d / 2, br: w.br });
      });
      if (ws.length) out.push({ id: b.id, words: ws });
    });
    return out;
  }

  function ts(s) {
    s = Math.max(0, s);
    var ms = Math.round(s * 1000), h = Math.floor(ms / 3600000); ms -= h * 3600000;
    var m = Math.floor(ms / 60000); ms -= m * 60000;
    var sec = Math.floor(ms / 1000); ms -= sec * 1000;
    function p(n, l) { n = String(n); while (n.length < l) n = '0' + n; return n; }
    return p(h, 2) + ':' + p(m, 2) + ':' + p(sec, 2) + ',' + p(ms, 3);
  }
  function toSRT(blocks, opts) {
    return blocks.map(function (b, i) {
      return (i + 1) + '\n' + ts(b.start) + ' --> ' + ts(b.showEnd != null ? b.showEnd : b.end) + '\n' +
        linesText(b, opts).join('\n') + '\n';
    }).join('\n');
  }

  CF.captions = {
    DEFAULTS: DEFAULTS, fitLines: fitLines, linesText: linesText, normalizeWords: normalizeWords,
    buildBlocks: buildBlocks, finalizeTiming: finalizeTiming,
    sendLastWordForward: sendLastWordForward, sendFirstWordBack: sendFirstWordBack,
    pullFirstWordFromNext: pullFirstWordFromNext, pullLastWordFromPrev: pullLastWordFromPrev,
    splitBlock: splitBlock, mergeWithNext: mergeWithNext, setBlockText: setBlockText,
    remapToCuts: remapToCuts, toSRT: toSRT
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = CF.captions;
})(typeof window !== 'undefined' ? window : globalThis);
