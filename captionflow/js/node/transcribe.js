// Speech-to-text: OpenAI Whisper (API) ou whisper.cpp local. Timestamps por palavra.
(function (root) {
  if (typeof require !== 'function') return; // modo navegador: sem Node
  var CF = (root.CF = root.CF || {});
  var https = require('https');
  var fs = require('fs');
  var os = require('os');
  var path = require('path');
  var cp = require('child_process');

  var LANGS = { pt: 'Portugues', en: 'Ingles', es: 'Espanhol', auto: 'Detectar automaticamente' };

  function multipart(fields, file) {
    var b = 'cfboundary' + Date.now().toString(16), parts = [];
    fields.forEach(function (f) {
      parts.push(Buffer.from('--' + b + '\r\nContent-Disposition: form-data; name="' + f[0] + '"\r\n\r\n' + f[1] + '\r\n'));
    });
    parts.push(Buffer.from('--' + b + '\r\nContent-Disposition: form-data; name="file"; filename="' + path.basename(file) +
      '"\r\nContent-Type: application/octet-stream\r\n\r\n'));
    parts.push(fs.readFileSync(file));
    parts.push(Buffer.from('\r\n--' + b + '--\r\n'));
    return { body: Buffer.concat(parts), type: 'multipart/form-data; boundary=' + b };
  }

  function openaiRequest(o) {
    var fields = [['model', o.model || 'whisper-1'], ['response_format', 'verbose_json'],
      ['timestamp_granularities[]', 'word'], ['timestamp_granularities[]', 'segment']];
    if (o.language && o.language !== 'auto') fields.push(['language', o.language]);
    if (o.prompt) fields.push(['prompt', o.prompt]);
    var mp = multipart(fields, o.file);
    var host = (o.baseUrl || 'https://api.openai.com').replace(/^https?:\/\//, '').replace(/\/$/, '');
    return new Promise(function (resolve, reject) {
      var req = https.request({
        method: 'POST', host: host.split('/')[0], path: '/' + (host.split('/').slice(1).join('/') ? host.split('/').slice(1).join('/') + '/' : '') + 'v1/audio/transcriptions',
        headers: { Authorization: 'Bearer ' + o.apiKey, 'Content-Type': mp.type, 'Content-Length': mp.body.length }
      }, function (res) {
        var data = '';
        res.on('data', function (d) { data += d; });
        res.on('end', function () {
          if (res.statusCode >= 300) {
            var msg = data; try { msg = JSON.parse(data).error.message; } catch (e) {}
            return reject(new Error('OpenAI ' + res.statusCode + ': ' + msg));
          }
          try { resolve(JSON.parse(data)); } catch (e) { reject(e); }
        });
      });
      req.on('error', reject);
      req.write(mp.body); req.end();
    });
  }

  function fromOpenAI(json, offset) {
    var words = (json.words || []).map(function (w) { return { text: w.word, start: w.start + offset, end: w.end + offset }; });
    return { words: words, language: json.language, text: json.text };
  }

  // whisper.cpp: --max-len 1 --split-on-word gera uma entrada por palavra (tokens sem espaco inicial colam na anterior).
  function localRun(o) {
    return new Promise(function (resolve, reject) {
      var base = path.join(os.tmpdir(), 'captionflow_wcpp_' + Date.now());
      var args = ['-m', o.model, '-f', o.file, '-oj', '-of', base, '-ml', '1', '-sow', '-l', o.language || 'auto'];
      if (o.extraArgs) args = args.concat(o.extraArgs);
      var p = cp.spawn(o.bin, args, { windowsHide: true }), err = '';
      p.stderr.on('data', function (d) { err += d; });
      p.on('error', function (e) { reject(new Error('whisper.cpp nao executou: ' + e.message)); });
      p.on('close', function (code) {
        if (code !== 0) return reject(new Error('whisper.cpp falhou (' + code + '): ' + err.slice(-300)));
        try {
          var j = JSON.parse(fs.readFileSync(base + '.json', 'utf8'));
          fs.unlinkSync(base + '.json');
          resolve(j);
        } catch (e) { reject(e); }
      });
    });
  }

  function fromWhisperCpp(json, offset) {
    var words = [];
    (json.transcription || []).forEach(function (t) {
      var raw = t.text || '';
      if (!raw.trim() || /^\s*\[.*\]\s*$/.test(raw)) return;
      var from = t.offsets.from / 1000 + offset, to = t.offsets.to / 1000 + offset;
      if (!/^\s/.test(raw) && words.length) { var l = words[words.length - 1]; l.text += raw.trim(); l.end = to; }
      else words.push({ text: raw.trim(), start: from, end: to });
    });
    return { words: words, language: json.result && json.result.language };
  }

  /*
   opts: {provider:'openai'|'local', ffmpeg, input, start, duration, language, apiKey, model, prompt, baseUrl,
          whisperBin, whisperModel, chunkSec, tmpDir, onProgress}
  */
  function transcribe(o) {
    var A = CF.audio, tmp = o.tmpDir || path.join(os.tmpdir(), 'captionflow');
    try { fs.mkdirSync(tmp, { recursive: true }); } catch (e) {}
    var chunk = o.provider === 'local' ? 0 : (o.chunkSec || 600);
    var total = o.duration, jobs = [], t = 0;
    if (!chunk || total <= chunk * 1.2) jobs.push({ off: 0, dur: total });
    else while (t < total - 0.01) { jobs.push({ off: t, dur: Math.min(chunk, total - t) }); t += chunk; }
    var all = [], lang = null, idx = 0;

    function next() {
      if (idx >= jobs.length) return Promise.resolve({ words: all, language: lang });
      var j = jobs[idx++], tag = Date.now() + '_' + idx;
      var ext = o.provider === 'local' ? 'wav' : 'mp3', out = path.join(tmp, 'cf_' + tag + '.' + ext);
      if (o.onProgress) o.onProgress('Extraindo audio (' + idx + '/' + jobs.length + ')...');
      return A.extract({ ffmpeg: o.ffmpeg, input: o.input, start: o.start + j.off, duration: j.dur, out: out, format: ext })
        .then(function () {
          if (o.provider !== 'local' && fs.statSync(out).size > 24 * 1024 * 1024) throw new Error('Trecho acima de 25 MB. Reduza "tamanho do trecho" nas opcoes.');
          if (o.onProgress) o.onProgress('Transcrevendo (' + idx + '/' + jobs.length + ')...');
          return o.provider === 'local'
            ? localRun({ bin: o.whisperBin, model: o.whisperModel, file: out, language: o.language }).then(function (r) { return fromWhisperCpp(r, j.off); })
            : openaiRequest({ apiKey: o.apiKey, model: o.model, file: out, language: o.language, prompt: o.prompt, baseUrl: o.baseUrl }).then(function (r) { return fromOpenAI(r, j.off); });
        })
        .then(function (r) {
          try { fs.unlinkSync(out); } catch (e) {}
          all = all.concat(r.words); lang = lang || r.language;
          return next();
        });
    }
    return next();
  }

  CF.transcribe = { LANGS: LANGS, transcribe: transcribe, fromOpenAI: fromOpenAI, fromWhisperCpp: fromWhisperCpp };
  if (typeof module !== 'undefined' && module.exports) module.exports = CF.transcribe;
})(typeof window !== 'undefined' ? window : globalThis);
