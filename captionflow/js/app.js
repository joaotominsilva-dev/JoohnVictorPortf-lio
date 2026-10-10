(function () {
  var CF = window.CF, U = CF.util, C = CF.captions, S = CF.silence, R = CF.render, P = CF.presets;
  var cs = new CSInterface();
  var HAS_NODE = typeof require === 'function';
  var fs = HAS_NODE ? require('fs') : null, path = HAS_NODE ? require('path') : null, os = HAS_NODE ? require('os') : null;

  var $ = function (id) { return document.getElementById(id); };
  var KEY = 'captionflow.settings.v1';
  var st = {
    ctx: null, words: [], blocks: [], preset: null, sel: 0, t: 0, playing: false, bg: null,
    db: null, frameSec: 0.01, cut: null, cutDone: false, lang: 'pt'
  };
  var settings = {
    provider: 'openai', language: 'pt', apiKey: '', whisperBin: '', whisperModel: '', ffmpegPath: '', whisperPrompt: '', chunkSec: 600,
    thr: -40, minSil: 0.4, pad: 0.1, jlMode: 'alt', lead: 0.25, tail: 0.25, protect: true, followCut: true,
    maxChars: 32, maxLines: 2, maxWps: 3.5, pause: 0.6, presetId: 'clean', applyMode: 'raster', mogrtPath: '', mogrtParam: '', vTrack: 2, baseMode: 'clip'
  };
  try { Object.assign(settings, JSON.parse(localStorage.getItem(KEY) || '{}')); } catch (e) {}
  function saveSettings() { try { localStorage.setItem(KEY, JSON.stringify(settings)); } catch (e) {} }

  // ---------- utilidades de UI ----------
  function log(msg, kind) { var l = $('log'); l.textContent = msg; l.className = kind || ''; }
  function host(fn, args) {
    return new Promise(function (resolve, reject) {
      if (!cs.isHost()) return reject(new Error('Disponivel apenas dentro do Premiere Pro.'));
      var a = (args || []).map(function (x) { return JSON.stringify(x); }).join(',');
      cs.evalScript(fn + '(' + a + ')', function (r) {
        if (!r || r === 'undefined' || /^EvalScript error/.test(r)) return reject(new Error('Falha ao executar script no Premiere (' + fn + ').'));
        try { var o = JSON.parse(r); o.ok === false ? reject(new Error(o.error)) : resolve(o); } catch (e) { reject(new Error('Resposta invalida: ' + r)); }
      });
    });
  }
  function tmpDir() {
    var d = path.join(os.tmpdir(), 'captionflow');
    try { fs.mkdirSync(d, { recursive: true }); } catch (e) {}
    return d;
  }
  function layout() { return { maxChars: settings.maxChars, maxLines: +settings.maxLines, maxWps: settings.maxWps, pauseBreak: settings.pause }; }
  function clipRange() {
    var c = st.ctx && st.ctx.clip;
    return c ? { start: c.inPoint, duration: c.duration } : null;
  }

  // ---------- tabs ----------
  document.querySelectorAll('#tabs button').forEach(function (b) {
    b.onclick = function () {
      document.querySelectorAll('#tabs button').forEach(function (x) { x.classList.toggle('on', x === b); });
      document.querySelectorAll('.pane').forEach(function (p) { p.classList.toggle('on', p.id === 'tab-' + b.dataset.tab); });
    };
  });

  // ---------- binding de campos de configuracao ----------
  var binds = [['provider', 'v'], ['language', 'v'], ['apiKey', 'v'], ['whisperBin', 'v'], ['whisperModel', 'v'], ['ffmpegPath', 'v'], ['whisperPrompt', 'v'],
    ['chunkSec', 'n'], ['thr', 'n'], ['minSil', 'n'], ['pad', 'n'], ['jlMode', 'v'], ['lead', 'n'], ['tail', 'n'], ['protect', 'c'], ['followCut', 'c'],
    ['maxChars', 'n'], ['maxLines', 'n'], ['maxWps', 'n'], ['pause', 'n'], ['applyMode', 'v'], ['mogrtPath', 'v'], ['mogrtParam', 'v'], ['vTrack', 'n'], ['baseMode', 'v']];
  var labels = { thr: ['vThr', ' dB'], minSil: ['vMin', ' s'], pad: ['vPad', ' s'], lead: ['vLead', ' s'], tail: ['vTail', ' s'], maxChars: ['vChars', ''], maxWps: ['vWps', ''], pause: ['vPause', ' s'] };
  binds.forEach(function (b) {
    var el = $(b[0]); if (!el) return;
    if (b[1] === 'c') el.checked = !!settings[b[0]]; else el.value = settings[b[0]];
    function show() { var l = labels[b[0]]; if (l) $(l[0]).textContent = el.value + l[1]; }
    show();
    el.addEventListener('input', function () {
      settings[b[0]] = b[1] === 'c' ? el.checked : b[1] === 'n' ? +el.value : el.value;
      show(); saveSettings(); onSetting(b[0]);
    });
  });
  function onSetting(k) {
    if (k === 'provider') toggleProvider();
    if (k === 'applyMode') $('mogrtBox').classList.toggle('hidden', settings.applyMode !== 'mogrt');
    if (['thr', 'minSil', 'pad', 'protect'].indexOf(k) >= 0) recomputeCut();
    if (['maxChars', 'maxLines', 'maxWps', 'pause'].indexOf(k) >= 0) { C.finalizeTiming(st.blocks, layout()); renderBlocks(); draw(); }
  }
  function toggleProvider() {
    $('openaiBox').classList.toggle('hidden', settings.provider !== 'openai');
    $('localBox').classList.toggle('hidden', settings.provider !== 'local');
  }
  toggleProvider(); $('mogrtBox').classList.toggle('hidden', settings.applyMode !== 'mogrt');

  // ---------- contexto do Premiere ----------
  function refreshCtx() {
    $('hostDot').classList.toggle('on', cs.isHost());
    return host('cf_getContext').then(function (c) {
      st.ctx = c;
      $('ctxInfo').textContent = c.name + ' | ' + c.width + 'x' + c.height + (c.clip ? ' | ' + c.clip.name : ' | selecione um clip');
      var cv = $('cv'); cv.height = Math.round(cv.width * c.height / c.width);
      updateSeek(); draw(); return c;
    }).catch(function (e) { $('ctxInfo').textContent = cs.isHost() ? e.message : 'Modo navegador (sem Premiere)'; });
  }
  $('btnCtx').onclick = refreshCtx;

  // ---------- transcricao ----------
  $('btnTranscribe').onclick = function () {
    if (!HAS_NODE) return log('Transcricao requer o painel dentro do Premiere (Node.js).', 'err');
    refreshCtx().then(function () {
      var c = st.ctx && st.ctx.clip;
      if (!c) throw new Error('Selecione um clip na timeline.');
      if (settings.provider === 'openai' && !(settings.apiKey || process.env.OPENAI_API_KEY)) throw new Error('Informe a chave da API OpenAI.');
      if (settings.provider === 'local' && (!settings.whisperBin || !settings.whisperModel)) throw new Error('Informe o executavel e o modelo do whisper.cpp.');
      $('btnTranscribe').disabled = true;
      return CF.transcribe.transcribe({
        provider: settings.provider, ffmpeg: CF.audio.findFfmpeg(settings.ffmpegPath), input: c.mediaPath, start: c.inPoint, duration: c.duration,
        language: settings.language, apiKey: settings.apiKey || process.env.OPENAI_API_KEY, prompt: settings.whisperPrompt, chunkSec: settings.chunkSec,
        whisperBin: settings.whisperBin, whisperModel: settings.whisperModel, tmpDir: tmpDir(), onProgress: function (m) { log(m); }
      });
    }).then(function (r) {
      setWords(r.words);
      $('transStat').textContent = st.words.length + ' palavras | idioma: ' + (r.language || settings.language);
      log('Transcricao concluida.', 'ok');
    }).catch(function (e) { log(e.message, 'err'); }).then(function () { $('btnTranscribe').disabled = false; });
  };
  function setWords(words) {
    st.words = C.normalizeWords(words);
    st.blocks = C.buildBlocks(st.words, layout());
    st.sel = 0; st.t = st.blocks.length ? st.blocks[0].start : 0;
    renderBlocks(); updateSeek(); draw();
  }
  $('btnDemo').onclick = function () {
    var txt = 'Hoje eu vou mostrar como editar videos muito mais rapido. Primeiro, a gente remove os silencios automaticamente. Depois, ajusta as legendas com atalhos e escolhe um estilo moderno para o seu conteudo.';
    var t = 0.2, words = txt.split(' ').map(function (w) { var d = 0.18 + w.length * 0.035, o = { text: w, start: t, end: t + d }; t += d + (/[.,]$/.test(w) ? 0.45 : 0.04); return o; });
    setWords(words); $('transStat').textContent = 'Demo carregada.';
  };
  $('btnExportJson').onclick = function () { download('transcricao.json', JSON.stringify({ words: st.words }, null, 1)); };
  $('btnImportJson').onclick = function () { $('fileJson').click(); };
  $('fileJson').onchange = function (e) {
    var f = e.target.files[0]; if (!f) return;
    var r = new FileReader();
    r.onload = function () { try { var j = JSON.parse(r.result); setWords(j.words || j); log('Transcricao importada.', 'ok'); } catch (x) { log('JSON invalido.', 'err'); } };
    r.readAsText(f);
  };
  function download(name, text) {
    if (HAS_NODE && cs.isHost()) {
      var dir = path.join(cs.getSystemPath(CSInterface.SystemPath.MY_DOCUMENTS) || os.homedir(), 'CaptionFlow');
      try { fs.mkdirSync(dir, { recursive: true }); fs.writeFileSync(path.join(dir, name), text, 'utf8'); log('Salvo em ' + path.join(dir, name), 'ok'); return; } catch (e) {}
    }
    var a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([text], { type: 'text/plain' })); a.download = name; a.click();
  }

  // ---------- rough cut ----------
  function wave() {
    var cv = $('wave'), g = cv.getContext('2d'), W = cv.width, H = cv.height;
    g.clearRect(0, 0, W, H); g.fillStyle = '#161616'; g.fillRect(0, 0, W, H);
    if (!st.db) return;
    var n = st.db.length, per = n / W;
    for (var x = 0; x < W; x++) {
      var mx = -100;
      for (var i = Math.floor(x * per); i < Math.floor((x + 1) * per) && i < n; i++) if (st.db[i] > mx) mx = st.db[i];
      var h = Math.max(1, (mx + 70) / 70 * H);
      g.fillStyle = mx < settings.thr ? '#555' : '#4f8cff'; g.fillRect(x, H - h, 1, h);
    }
    var total = st.db.length * st.frameSec;
    if (st.cut) {
      g.fillStyle = 'rgba(239,83,80,.35)';
      st.cut.silences.forEach(function (s) { g.fillRect(s.start / total * W, 0, Math.max(1, (s.end - s.start) / total * W), H); });
    }
    var ty = H - (settings.thr + 70) / 70 * H; g.strokeStyle = '#f0a030'; g.beginPath(); g.moveTo(0, ty); g.lineTo(W, ty); g.stroke();
  }
  $('btnAnalyze').onclick = function () {
    if (!HAS_NODE) return log('Analise de audio requer o painel dentro do Premiere (Node.js).', 'err');
    refreshCtx().then(function () {
      var c = st.ctx && st.ctx.clip; if (!c) throw new Error('Selecione um clip na timeline.');
      log('Decodificando audio...'); $('btnAnalyze').disabled = true;
      return CF.audio.decodePCM({ ffmpeg: CF.audio.findFfmpeg(settings.ffmpegPath), input: c.mediaPath, start: c.inPoint, duration: c.duration, sampleRate: 16000 });
    }).then(function (r) {
      var o = S.rmsDb(r.samples, r.sampleRate, 10); st.db = o.db; st.frameSec = o.frameSec;
      recomputeCut(); log('Analise concluida.', 'ok');
    }).catch(function (e) { log(e.message, 'err'); }).then(function () { $('btnAnalyze').disabled = false; });
  };
  function recomputeCut() {
    if (!st.db) return;
    var r = S.detect(st.db, st.frameSec, { thresholdDb: settings.thr, minSilence: settings.minSil, padding: settings.pad });
    if (settings.protect && st.words.length) {
      r.keep = S.protectWords(r.keep, st.words, 0.03, r.total);
      r.silences = invert(r.keep, r.total);
    }
    st.cut = r; wave();
    var removed = r.silences.reduce(function (a, s) { return a + s.end - s.start; }, 0);
    $('cutStat').textContent = r.silences.length + ' cortes | remove ' + removed.toFixed(1) + ' s de ' + r.total.toFixed(1) + ' s (' + Math.round(removed / r.total * 100) + '%) | resultado ' + (r.total - removed).toFixed(1) + ' s';
    $('btnApplyCut').disabled = !cs.isHost() || !r.keep.length;
  }
  function invert(keep, total) {
    var out = [], t = 0;
    keep.forEach(function (k) { if (k.start - t > 0.01) out.push({ start: t, end: k.start }); t = k.end; });
    if (total - t > 0.01) out.push({ start: t, end: total });
    return out;
  }
  $('btnApplyCut').onclick = function () {
    var c = st.ctx.clip, r = st.cut;
    var segs = S.jlSegments(r.keep, { mode: settings.jlMode, lead: settings.lead, tail: settings.tail, total: r.total });
    log('Montando sequencia com ' + segs.length + ' cortes...'); $('btnApplyCut').disabled = true;
    host('cf_roughCut', [JSON.stringify({ mediaPath: c.mediaPath, base: c.inPoint, name: c.name + ' - Rough Cut', segs: segs, jl: settings.jlMode !== 'none' })]).then(function (res) {
      if (settings.followCut && st.blocks.length) {
        st.blocks = C.remapToCuts(st.blocks, r.keep);
        st.words = [].concat.apply([], st.blocks.map(function (b) { return b.words; }));
        C.finalizeTiming(st.blocks, layout()); renderBlocks();
      }
      st.cutDone = true; settings.baseMode = 'zero'; $('baseMode').value = 'zero'; saveSettings();
      log('Rough cut criado: ' + res.sequence + (res.warnings && res.warnings.length ? '\n' + res.warnings.join('\n') : ''), res.warnings && res.warnings.length ? '' : 'ok');
      return refreshCtx();
    }).catch(function (e) { log(e.message, 'err'); }).then(function () { $('btnApplyCut').disabled = false; });
  };

  // ---------- editor de blocos ----------
  function blocksText(b) { return C.linesText(b, layout()).join('\n'); }
  function renderBlocks() {
    var box = $('blocks'); box.innerHTML = '';
    st.blocks.forEach(function (b, i) {
      var d = document.createElement('div'); d.className = 'block' + (i === st.sel ? ' sel' : '') + (b.warn ? ' warn' : ''); d.dataset.i = i;
      var wps = b.words.length / Math.max(b.showEnd - b.start, 0.01);
      d.innerHTML = '<div class="meta">#' + (i + 1) + '<br>' + U.fmt(b.start) + '<br><span class="wps">' + wps.toFixed(1) + ' p/s</span></div>';
      var ta = document.createElement('textarea'); ta.value = blocksText(b); ta.rows = Math.max(2, ta.value.split('\n').length); ta.spellcheck = false;
      ta.onfocus = function () { selectBlock(i, false); };
      ta.oninput = function () {
        C.setBlockText(b, ta.value); C.finalizeTiming(st.blocks, layout()); draw();
        var m = d.querySelector('.meta'); m.innerHTML = '#' + (i + 1) + '<br>' + U.fmt(b.start) + '<br><span class="wps">' + (b.words.length / Math.max(b.showEnd - b.start, 0.01)).toFixed(1) + ' p/s</span>';
        d.classList.toggle('warn', !!b.warn);
      };
      ta.onkeydown = function (e) { keyHandler(e, i, ta); };
      d.appendChild(ta); box.appendChild(d);
    });
    $('capStat').textContent = st.blocks.length + ' blocos, ' + st.words.length + ' palavras';
  }
  function selectBlock(i, scroll) {
    st.sel = i; var b = st.blocks[i]; if (!b) return;
    document.querySelectorAll('.block').forEach(function (d, k) { d.classList.toggle('sel', k === i); });
    st.t = b.start + 0.02; updateSeek(); draw();
  }
  function focusBlock(i, end) {
    var tas = document.querySelectorAll('.block textarea'), ta = tas[Math.max(0, Math.min(i, tas.length - 1))];
    if (ta) { ta.focus(); if (end != null) ta.setSelectionRange(end, end); }
  }
  function mutate(fn, focusIdx) {
    fn(st.blocks); C.finalizeTiming(st.blocks, layout());
    st.words = [].concat.apply([], st.blocks.map(function (b) { return b.words; }));
    st.sel = Math.max(0, Math.min(focusIdx, st.blocks.length - 1));
    renderBlocks(); focusBlock(st.sel); selectBlock(st.sel);
  }
  function keyHandler(e, i, ta) {
    var alt = e.altKey, sh = e.shiftKey;
    if (alt && (e.key === 'ArrowRight' || e.key === 'ArrowLeft')) {
      e.preventDefault();
      if (e.key === 'ArrowRight') mutate(function (b) { sh ? C.pullFirstWordFromNext(b, i) : C.sendLastWordForward(b, i); }, i);
      else mutate(function (b) { sh ? C.pullLastWordFromPrev(b, i) : C.sendFirstWordBack(b, i); }, sh ? i : Math.max(0, i - (st.blocks[i] && st.blocks[i].words.length === 1 ? 1 : 0)));
    } else if (alt && e.key === 'ArrowDown') { e.preventDefault(); focusBlock(i + 1); }
    else if (alt && e.key === 'ArrowUp') { e.preventDefault(); focusBlock(i - 1); }
    else if (e.key === 'Enter') {
      e.preventDefault();
      var pos = ta.selectionStart, before = ta.value.slice(0, pos).split(/\s+/).filter(Boolean).length;
      if (sh) {
        var b = st.blocks[i]; if (before > 0 && before < b.words.length) { b.words[before - 1].br = !b.words[before - 1].br; renderBlocks(); focusBlock(i, pos); draw(); }
      } else mutate(function (bl) { C.splitBlock(bl, i, before); }, i + 1);
    } else if (e.key === 'Backspace' && ta.selectionStart === 0 && ta.selectionEnd === 0 && i > 0) {
      e.preventDefault(); mutate(function (bl) { C.mergeWithNext(bl, i - 1); }, i - 1);
    }
  }
  $('btnRebuild').onclick = function () { setWords(st.words); };
  $('btnExportSrt').onclick = function () { download('legendas.srt', C.toSRT(st.blocks, layout())); };

  // ---------- estilo ----------
  var FIELDS = [
    ['Tipografia'],
    ['family', 'Fonte (CSS font-family)', 'text'], ['weight', 'Peso', 'select', [300, 400, 500, 600, 700, 800, 900]], ['size', 'Tamanho (px a 1080p)', 'range', 20, 160, 1],
    ['uppercase', 'Caixa alta', 'check'], ['italic', 'Italico', 'check'], ['align', 'Alinhamento', 'select', ['left', 'center', 'right']], ['lineHeight', 'Entrelinha', 'range', 0.9, 1.6, 0.01],
    ['Posicao e cor'],
    ['x', 'Posicao X', 'range', 0, 1, 0.005], ['y', 'Posicao Y', 'range', 0, 1, 0.005], ['fill', 'Cor de preenchimento', 'color'],
    ['Traco e sombra'],
    ['stroke.width', 'Espessura do traco', 'range', 0, 20, 0.5], ['stroke.color', 'Cor do traco', 'color'],
    ['shadow.enabled', 'Sombra', 'check'], ['shadow.color', 'Cor da sombra', 'color'], ['shadow.alpha', 'Opacidade da sombra', 'range', 0, 1, 0.01],
    ['shadow.blur', 'Desfoque', 'range', 0, 40, 1], ['shadow.y', 'Deslocamento Y', 'range', -20, 20, 1],
    ['Caixa de fundo'],
    ['bg.enabled', 'Caixa atras do texto', 'check'], ['bg.color', 'Cor da caixa', 'color'], ['bg.alpha', 'Opacidade', 'range', 0, 1, 0.01], ['bg.padding', 'Margem interna', 'range', 0, 60, 1], ['bg.radius', 'Raio', 'range', 0, 40, 1],
    ['Animacao'],
    ['anim.entry', 'Entrada', 'select', CF.anim.ENTRIES], ['anim.exit', 'Saida', 'select', CF.anim.EXITS],
    ['anim.inDur', 'Duracao da entrada (s)', 'range', 0.05, 1, 0.01], ['anim.outDur', 'Duracao da saida (s)', 'range', 0.05, 1, 0.01],
    ['Destaque da palavra ativa'],
    ['highlight.mode', 'Modo', 'select', CF.anim.HIGHLIGHTS], ['highlight.color', 'Cor do destaque', 'color'], ['highlight.boxColor', 'Cor da caixa (modo caixa)', 'color'], ['highlight.scale', 'Escala (modo escala)', 'range', 1, 1.6, 0.01]
  ];
  function buildForm() {
    var f = $('styleForm'); f.innerHTML = '';
    var grid = null;
    FIELDS.forEach(function (fd) {
      if (fd.length === 1) { var h = document.createElement('div'); h.className = 'sect'; h.textContent = fd[0]; f.appendChild(h); grid = document.createElement('div'); grid.className = 'grid2'; f.appendChild(grid); return; }
      var key = fd[0], val = U.getPath(st.preset, key), lab = document.createElement('label'), inp;
      if (fd[2] === 'check') { lab.className = 'chk'; inp = document.createElement('input'); inp.type = 'checkbox'; inp.checked = !!val; lab.appendChild(inp); lab.appendChild(document.createTextNode(fd[1])); }
      else {
        lab.appendChild(document.createTextNode(fd[1]));
        if (fd[2] === 'select') { inp = document.createElement('select'); fd[3].forEach(function (o) { var op = document.createElement('option'); op.value = o; op.textContent = o; inp.appendChild(op); }); inp.value = val; }
        else { inp = document.createElement('input'); inp.type = fd[2]; if (fd[2] === 'range') { inp.min = fd[3]; inp.max = fd[4]; inp.step = fd[5]; } inp.value = val; }
        lab.appendChild(inp);
      }
      inp.addEventListener('input', function () {
        var v = fd[2] === 'check' ? inp.checked : fd[2] === 'range' ? +inp.value : (fd[2] === 'select' && /^\d+$/.test(inp.value) ? +inp.value : inp.value);
        U.setPath(st.preset, key, v); draw();
      });
      (grid || f).appendChild(lab);
    });
  }
  function fillPresetSel() {
    var sel = $('presetSel'); sel.innerHTML = '';
    P.all().forEach(function (p) { var o = document.createElement('option'); o.value = p.id; o.textContent = (p.builtin ? '' : '* ') + p.name; sel.appendChild(o); });
    sel.value = st.preset.id;
  }
  $('presetSel').onchange = function () { st.preset = P.get(this.value); settings.presetId = st.preset.id; saveSettings(); buildForm(); draw(); };
  $('btnPresetSave').onclick = function () {
    var name = window.prompt ? window.prompt('Nome do preset:', st.preset.builtin ? st.preset.name + ' (copia)' : st.preset.name) : null;
    if (!name) return;
    var p = U.clone(st.preset); p.name = name; p.id = 'user-' + name.toLowerCase().replace(/[^a-z0-9]+/g, '-') + '-' + Date.now().toString(36); delete p.builtin;
    P.saveCustom(p); st.preset = P.get(p.id); settings.presetId = p.id; saveSettings(); fillPresetSel(); log('Preset salvo.', 'ok');
  };
  $('btnPresetDel').onclick = function () {
    if (st.preset.builtin) return log('Presets padrao nao podem ser excluidos.', 'err');
    P.remove(st.preset.id); st.preset = P.get('clean'); fillPresetSel(); buildForm(); draw();
  };
  $('btnPresetExport').onclick = function () { download('presets-captionflow.json', P.exportJSON()); };
  $('btnPresetImport').onclick = function () { $('filePresets').click(); };
  $('filePresets').onchange = function (e) {
    var f = e.target.files[0]; if (!f) return; var r = new FileReader();
    r.onload = function () { try { log(P.importJSON(r.result) + ' preset(s) importado(s).', 'ok'); fillPresetSel(); } catch (x) { log('Arquivo de presets invalido.', 'err'); } };
    r.readAsText(f);
  };

  // ---------- preview em tempo real ----------
  var cv = $('cv'), g = cv.getContext('2d');
  function maxT() { var b = st.blocks[st.blocks.length - 1]; return b ? b.showEnd + 0.5 : 10; }
  function updateSeek() { var s = $('seek'); s.max = maxT(); s.value = st.t; $('tcode').textContent = U.fmt(st.t); }
  function blockAt(t) {
    for (var i = 0; i < st.blocks.length; i++) if (t >= st.blocks[i].start && t < st.blocks[i].showEnd) return i;
    return -1;
  }
  function draw() {
    if (!st.preset) return;
    var i = blockAt(st.t), b = st.blocks[i >= 0 ? i : (st.sel < st.blocks.length ? st.sel : 0)];
    var t = i >= 0 ? st.t : (b ? b.start + 0.5 : 0);
    if (!b) { g.clearRect(0, 0, cv.width, cv.height); return; }
    R.draw(g, cv.width, cv.height, b, i >= 0 ? t : Math.min(t, b.showEnd - 0.01), st.preset, { bg: st.bg, layout: layout() });
  }
  var raf = 0, last = 0;
  function tick(now) {
    if (!st.playing) return;
    st.t += (now - last) / 1000; last = now;
    if (st.t > maxT()) { st.t = 0; }
    var i = blockAt(st.t); if (i >= 0 && i !== st.sel) { st.sel = i; document.querySelectorAll('.block').forEach(function (d, k) { d.classList.toggle('sel', k === i); }); }
    updateSeek(); draw(); raf = requestAnimationFrame(tick);
  }
  function play(on) { st.playing = on; $('btnPlay').innerHTML = on ? '&#10074;&#10074;' : '&#9654;'; if (on) { last = performance.now(); raf = requestAnimationFrame(tick); } }
  $('btnPlay').onclick = function () { play(!st.playing); };
  $('seek').oninput = function () { st.t = +this.value; $('tcode').textContent = U.fmt(st.t); draw(); };
  document.addEventListener('keydown', function (e) { if (e.code === 'Space' && !/TEXTAREA|INPUT|SELECT/.test(document.activeElement.tagName)) { e.preventDefault(); play(!st.playing); } });

  // sincronismo com o playhead do Premiere
  var syncTimer = 0;
  $('syncHead').onchange = function () {
    clearInterval(syncTimer);
    if (!this.checked) return;
    syncTimer = setInterval(function () {
      if (st.playing || !st.ctx || !st.ctx.clip) return;
      host('cf_getPlayhead').then(function (r) {
        var rel = r.t - st.ctx.clip.start;
        if (Math.abs(rel - st.t) > 0.03) { st.t = Math.max(0, rel); var i = blockAt(st.t); if (i >= 0) st.sel = i; updateSeek(); draw(); }
      }).catch(function () {});
    }, 250);
  };
  $('btnFrame').onclick = function () {
    if (!HAS_NODE) return log('Requer Premiere.', 'err');
    var f = path.join(tmpDir(), 'bg_' + Date.now() + '.png');
    var rel = st.ctx && st.ctx.clip ? st.ctx.clip.start + st.t : 0;
    host('cf_exportFrame', [f, rel]).then(function () {
      var img = new Image(); img.onload = function () { st.bg = img; draw(); }; img.src = 'file:///' + f.replace(/\\/g, '/').replace(/^\//, '');
    }).catch(function (e) { log(e.message, 'err'); });
  };
  $('btnNoBg').onclick = function () { st.bg = null; draw(); };

  // ---------- aplicar na timeline ----------
  function baseTime() {
    var c = st.ctx && st.ctx.clip;
    if (settings.baseMode === 'zero') return 0;
    if (settings.baseMode === 'playhead') return st.ctx.playhead;
    return c ? c.start : 0;
  }
  $('btnApply').onclick = function () {
    if (!st.blocks.length) return log('Nao ha legendas para inserir.', 'err');
    $('btnApply').disabled = true;
    refreshCtx().then(function () {
      var base = baseTime(), mode = settings.applyMode, track = Math.max(0, settings.vTrack - 1);
      if (mode === 'srt') {
        var f = path.join(tmpDir(), 'captionflow_' + Date.now() + '.srt');
        fs.writeFileSync(f, C.toSRT(st.blocks, layout()), 'utf8');
        return host('cf_insertSrt', [f, base]).then(function () { return 'Faixa de legendas criada.'; });
      }
      if (mode === 'mogrt') {
        if (!settings.mogrtPath) throw new Error('Informe o caminho do arquivo .mogrt.');
        var items = st.blocks.map(function (b) { return { text: C.linesText(b, layout()).join('\n'), start: b.start, end: b.showEnd }; });
        return host('cf_insertMogrt', [JSON.stringify({ mogrt: settings.mogrtPath, track: track, base: base, textParam: settings.mogrtParam, items: items })])
          .then(function (r) { return r.placed + ' blocos MOGRT inseridos.' + (r.warnings.length ? '\n' + r.warnings.join('\n') : ''); });
      }
      return applyRaster(base, track);
    }).then(function (m) { $('applyStat').textContent = m; log(m, 'ok'); })
      .catch(function (e) { log(e.message, 'err'); }).then(function () { $('btnApply').disabled = false; });
  };
  function applyRaster(base, track) {
    var W = st.ctx.width, H = st.ctx.height, canvas = document.createElement('canvas'); canvas.width = W; canvas.height = H;
    var gc = canvas.getContext('2d'), dir = path.join(tmpDir(), 'sub_' + Date.now()), items = [], n = 0;
    fs.mkdirSync(dir, { recursive: true });
    st.blocks.forEach(function (b, bi) {
      var states = R.states(b, st.preset);
      states.forEach(function (s, si) {
        R.draw(gc, W, H, b, s.start + 0.001, st.preset, { steady: true, active: s.active, layout: layout() });
        var file = path.join(dir, 'cap_' + String(bi + 1).padStart(4, '0') + '_' + String(si + 1).padStart(2, '0') + '.png');
        fs.writeFileSync(file, Buffer.from(canvas.toDataURL('image/png').split(',')[1], 'base64'));
        var dur = s.end - s.start, first = si === 0, lastS = si === states.length - 1;
        var keys = R.keyframes(st.preset, W, H, dur, first, lastS);
        if (first && !lastS) keys = keys.filter(function (k) { return k.t <= (st.preset.anim.inDur || 0.2) + 1e-6; });
        if (lastS && !first) keys = keys.filter(function (k) { return k.t >= dur - (st.preset.anim.outDur || 0.2) - 1e-6; });
        items.push({ png: file, start: s.start, end: s.end, keys: keys }); n++;
      });
    });
    log('Inserindo ' + n + ' camadas...');
    return host('cf_insertRaster', [JSON.stringify({ track: track, base: base, items: items })]).then(function (r) {
      return r.placed + ' camadas inseridas na trilha V' + (track + 1) + (r.skipped ? ' (' + r.skipped + ' ignoradas)' : '') + '.';
    });
  }

  // ---------- inicializacao ----------
  var V = window.CF_VERSION || { version: '?', build: '' };
  $('ver').textContent = 'v' + V.version; $('ver').title = 'Build ' + V.build;
  document.title = 'CaptionFlow v' + V.version;
  st.preset = P.get(settings.presetId);
  fillPresetSel(); buildForm(); wave();
  if (!cs.isHost()) { $('ctxInfo').textContent = 'Modo navegador (sem Premiere)'; }
  refreshCtx();
  if (/[?&]demo=1/.test(location.search)) { $('btnDemo').click(); document.querySelector('#tabs [data-tab=cap]').click(); }
  window.CFApp = { state: st, setWords: setWords, draw: draw };
})();
