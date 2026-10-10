# CaptionFlow para Adobe Premiere Pro

Painel CEP (Premiere 2022 ou superior) para transcricao, rough cut e legendas animadas.

## Instalacao
1. Instale o ffmpeg (https://ffmpeg.org) ou informe o caminho nas opcoes avancadas.
2. Windows: execute `installer/install-win.bat`. macOS: `bash installer/install-mac.sh`.
3. Reinicie o Premiere e abra Janela > Extensoes > CaptionFlow.

## Preview sem Premiere
Abra `preview.html` no navegador (modo demo com editor, atalhos, presets e animacoes).

## Fluxo
1. **Transcrever**: selecione um clip, escolha Whisper API (chave OpenAI) ou whisper.cpp local (pt, en, es). Gera timestamps por palavra.
2. **Cortes**: Analisar audio, ajuste limiar, silencio minimo e padding. Escolha corte seco, J-cut, L-cut ou alternados. Aplicar cria uma nova sequencia "Rough Cut".
3. **Legendas**: limite de caracteres por linha, linhas, palavras por segundo e pausa. Atalhos nos blocos:
   Alt+Seta direita envia a ultima palavra ao proximo bloco, Alt+Seta esquerda envia a primeira ao anterior,
   com Shift traz a palavra do vizinho, Enter divide, Backspace no inicio une, Shift+Enter quebra linha.
4. **Estilo**: presets embutidos (clean, minimalista, corporativo, social pop, karaoke, caixa, escala) e presets proprios (salvar, exportar, importar).
   Animacoes de entrada/saida: fade, slide, pop, zoom. Destaque da palavra ativa: cor, escala, caixa, preenchimento.
5. **Aplicar**: PNG animado com keyframes de Opacidade/Posicao/Escala, MOGRT (Essential Graphics) ou faixa SRT nativa.

O preview do painel usa o mesmo renderizador que gera as camadas, entao o que voce ve e o que entra na timeline.

## Observacoes
- A API do ExtendScript do Premiere varia por versao. J/L cut, keyframes e MOGRT usam chamadas que podem exigir ajuste na sua versao; o painel avisa e cai para corte seco quando algo falha.
- A chave da API fica no armazenamento local do painel.
- Testes da logica: `node --test tests/core.test.js`.
