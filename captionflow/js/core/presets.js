(function (root) {
  var CF = (root.CF = root.CF || {});
  var U = CF.util || require('./util.js');

  var BASE = {
    family: 'Inter, Helvetica Neue, Arial', weight: 700, italic: false, size: 64, uppercase: false,
    fill: '#ffffff', align: 'center', x: 0.5, y: 0.82, lineHeight: 1.15,
    shadow: { enabled: true, color: '#000000', alpha: 0.5, blur: 10, x: 0, y: 3 },
    stroke: { width: 0, color: '#000000' },
    bg: { enabled: false, color: '#000000', alpha: 0.55, padding: 18, radius: 14 },
    anim: { entry: 'fade', exit: 'fade', inDur: 0.22, outDur: 0.18 },
    highlight: { mode: 'none', color: '#ffd400', scale: 1.12, boxColor: '#ffd400' }
  };

  function make(over) {
    var p = U.clone(BASE);
    (function merge(t, s) {
      Object.keys(s).forEach(function (k) {
        if (s[k] && typeof s[k] === 'object' && !Array.isArray(s[k])) merge(t[k] = t[k] || {}, s[k]);
        else t[k] = s[k];
      });
    })(p, over);
    return p;
  }

  var BUILTIN = [
    make({ id: 'clean', name: 'Clean', family: 'Inter, Helvetica Neue, Arial', weight: 600, size: 58,
      anim: { entry: 'fade', exit: 'fade' } }),
    make({ id: 'minimal', name: 'Minimalista', family: 'Helvetica Neue, Arial', weight: 400, size: 52, y: 0.88,
      shadow: { enabled: false }, anim: { entry: 'fade', exit: 'fade', inDur: 0.3, outDur: 0.3 } }),
    make({ id: 'corporate', name: 'Corporativo', family: 'Montserrat, Arial', weight: 600, size: 50, y: 0.86,
      shadow: { enabled: false }, bg: { enabled: true, color: '#0b2545', alpha: 0.82, padding: 20, radius: 8 },
      anim: { entry: 'slideUp', exit: 'fade', inDur: 0.28 } }),
    make({ id: 'social-pop', name: 'Social Pop', family: 'Poppins, Montserrat, Arial Black', weight: 800, size: 84, uppercase: true,
      y: 0.62, stroke: { width: 6, color: '#000000' }, shadow: { enabled: true, alpha: 0.6, blur: 0, x: 0, y: 6 },
      anim: { entry: 'pop', exit: 'zoom', inDur: 0.22, outDur: 0.12 },
      highlight: { mode: 'color', color: '#ffe600', scale: 1.1 } }),
    make({ id: 'karaoke', name: 'Karaoke (preenchimento)', family: 'Montserrat, Arial', weight: 800, size: 72, uppercase: true, y: 0.7,
      fill: '#d9d9d9', stroke: { width: 4, color: '#000000' }, shadow: { enabled: false },
      anim: { entry: 'slideUp', exit: 'fade' }, highlight: { mode: 'fill', color: '#00e0a4' } }),
    make({ id: 'box-highlight', name: 'Destaque em caixa', family: 'Inter, Arial', weight: 800, size: 70, y: 0.74,
      shadow: { enabled: false }, anim: { entry: 'zoom', exit: 'fade' },
      highlight: { mode: 'box', color: '#111111', boxColor: '#ffd400', scale: 1 } }),
    make({ id: 'scale-word', name: 'Palavra ativa maior', family: 'Inter, Arial', weight: 700, size: 66, y: 0.78,
      anim: { entry: 'slideDown', exit: 'fade' }, highlight: { mode: 'scale', color: '#7cf3ff', scale: 1.22 } })
  ];

  var KEY = 'captionflow.presets.v1';
  function store() { try { return window.localStorage; } catch (e) { return null; } }

  function loadCustom() {
    var s = store(); if (!s) return [];
    try { return JSON.parse(s.getItem(KEY) || '[]'); } catch (e) { return []; }
  }
  function saveCustom(list) { var s = store(); if (s) s.setItem(KEY, JSON.stringify(list)); }

  CF.presets = {
    BASE: BASE, make: make,
    all: function () {
      return BUILTIN.map(function (p) { p = U.clone(p); p.builtin = true; return p; }).concat(loadCustom());
    },
    get: function (id) {
      var all = CF.presets.all();
      for (var i = 0; i < all.length; i++) if (all[i].id === id) return U.clone(all[i]);
      return U.clone(all[0]);
    },
    saveCustom: function (preset) {
      var list = loadCustom().filter(function (p) { return p.id !== preset.id; });
      var copy = U.clone(preset); delete copy.builtin;
      list.push(copy); saveCustom(list); return copy;
    },
    remove: function (id) { saveCustom(loadCustom().filter(function (p) { return p.id !== id; })); },
    exportJSON: function () { return JSON.stringify({ captionflowPresets: loadCustom() }, null, 2); },
    importJSON: function (text) {
      var data = JSON.parse(text), arr = data.captionflowPresets || (Array.isArray(data) ? data : [data]);
      var list = loadCustom();
      arr.forEach(function (p) {
        var full = make(p); full.id = p.id || U.uid(); full.name = p.name || 'Importado';
        list = list.filter(function (x) { return x.id !== full.id; }); list.push(full);
      });
      saveCustom(list); return arr.length;
    }
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = CF.presets;
})(typeof window !== 'undefined' ? window : globalThis);
