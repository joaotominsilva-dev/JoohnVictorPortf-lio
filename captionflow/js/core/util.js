(function (root) {
  var CF = (root.CF = root.CF || {});
  var seq = 0;
  CF.util = {
    uid: function () { seq += 1; return 'b' + Date.now().toString(36) + seq; },
    clamp: function (v, a, b) { return Math.max(a, Math.min(b, v)); },
    round: function (v, n) { var m = Math.pow(10, n == null ? 3 : n); return Math.round(v * m) / m; },
    fmt: function (s) {
      s = Math.max(0, s || 0);
      var m = Math.floor(s / 60), r = s - m * 60;
      return (m < 10 ? '0' : '') + m + ':' + (r < 10 ? '0' : '') + r.toFixed(2);
    },
    hexToRgb: function (hex) {
      var h = String(hex || '#000000').replace('#', '');
      if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
      var n = parseInt(h, 16) || 0;
      return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255 };
    },
    rgba: function (hex, a) {
      var c = CF.util.hexToRgb(hex);
      return 'rgba(' + c.r + ',' + c.g + ',' + c.b + ',' + (a == null ? 1 : a) + ')';
    },
    getPath: function (o, p) {
      var parts = p.split('.');
      for (var i = 0; i < parts.length && o != null; i++) o = o[parts[i]];
      return o;
    },
    setPath: function (o, p, v) {
      var parts = p.split('.');
      for (var i = 0; i < parts.length - 1; i++) {
        if (o[parts[i]] == null) o[parts[i]] = {};
        o = o[parts[i]];
      }
      o[parts[parts.length - 1]] = v;
    },
    clone: function (o) { return JSON.parse(JSON.stringify(o)); }
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = CF.util;
})(typeof window !== 'undefined' ? window : globalThis);
