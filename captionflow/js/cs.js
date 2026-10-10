// Ponte minima com o host CEP (substitui o CSInterface.js oficial) + modo navegador para testes.
(function (root) {
  var cep = root.__adobe_cep__;
  function CSInterface() {}
  CSInterface.prototype.isHost = function () { return !!cep; };
  CSInterface.prototype.evalScript = function (script, cb) {
    if (!cep) { if (cb) cb('EvalScript error.'); return; }
    cep.evalScript(script, cb || function () {});
  };
  CSInterface.prototype.getSystemPath = function (type) {
    if (!cep) return '';
    var p = decodeURI(cep.getSystemPath(type));
    return navigator.platform.indexOf('Win') === 0 ? p.replace('file:///', '') : p.replace('file://', '');
  };
  CSInterface.prototype.openURL = function (url) { if (cep) cep.openURLInDefaultBrowser(url); else root.open(url); };
  CSInterface.SystemPath = { EXTENSION: 'extension', USER_DATA: 'userData', MY_DOCUMENTS: 'myDocuments' };
  root.CSInterface = CSInterface;
})(window);
