# Baixa Vídeos

App Android para baixar vídeos e áudios do Instagram, YouTube e TikTok (e de centenas de outros sites
suportados pelo yt-dlp), com a identidade visual do portfólio JOOHN.

## Instalar

1. Pegue o APK na pasta `apk/` desta pasta (ou em Releases / Actions do GitHub):
   - `BaixaVideos-1.0.0-arm64-v8a.apk`: serve em quase todos os celulares.
   - `BaixaVideos-1.0.0-armeabi-v7a.apk`: só se o primeiro não instalar (celulares antigos ou Android Go).
2. Abra o arquivo no celular e permita "instalar apps desconhecidos" quando o Android pedir.
3. Na primeira abertura o app prepara o motor (alguns segundos) e atualiza o yt-dlp sozinho.

Requer Android 7.0 ou mais novo.

## Usar

- Cole o link e toque em **BAIXAR VÍDEO**; ou, no Instagram, YouTube ou TikTok, toque em
  **Compartilhar** e escolha **Baixa Vídeos**.
- Escolha **Vídeo MP4** (Máxima, 1080p, 720p ou 480p) ou **Áudio MP3**.
- Os arquivos ficam em `Download/BaixaVideos` e aparecem na galeria e no app de música.
- O Instagram pediu login? **Ajustes → Entrar no Instagram** (login no site oficial, salvo só no aparelho).
- Algum site parou de baixar? **Ajustes → Atualizar agora**, ou ligue a versão nightly.
- Os downloads continuam com o app em segundo plano (com notificação de progresso).

## Como funciona

- O motor é o [yt-dlp](https://github.com/yt-dlp/yt-dlp) rodando no próprio celular, com Python,
  FFmpeg e QuickJS empacotados pela biblioteca
  [youtubedl-android](https://github.com/yausername/youtubedl-android).
- O app atualiza o yt-dlp direto das releases do GitHub (a cada 12 h, ou pelo botão em Ajustes).
  Isso é o que mantém YouTube, Instagram e TikTok funcionando quando os sites mudam.
- Cada download roda numa pasta privada do app e o resultado é copiado para `Download/BaixaVideos`.
- O progresso vem de linhas próprias pedidas ao yt-dlp (`--print` e `--progress-template` em JSON),
  então o app não depende do texto de log do yt-dlp.

Código principal em `app/src/main/java/com/joohn/baixavideos`:

| Pasta | O que tem |
| --- | --- |
| `engine/` | motor, fila de downloads, leitura da saída do yt-dlp, atualizador, Instagram, salvamento |
| `service/` | serviço em primeiro plano e notificações |
| `ui/` | telas em Jetpack Compose (início, ajustes, login do Instagram) |
| `data/` | preferências e histórico |

## Compilar

```bash
./gradlew assembleRelease        # JDK 17 e Android SDK 36
```

Gera um APK por arquitetura em `app/build/outputs/apk/release/`. Um APK universal passaria de
200 MB, porque Python e FFmpeg são binários nativos de cada arquitetura.

O workflow `.github/workflows/baixa-videos.yml`:

- compila os APKs e anexa como artefatos a cada push;
- roda os testes instrumentados num emulador Android 14 (motor, download e conversão para MP3 de um
  vídeo local, e uma tentativa com links reais), guardando log e screenshots;
- com **Run workflow** e a opção "Salvar os APKs" marcada, grava os APKs em `apk/` na branch;
- em `main`, publica os APKs numa Release.

**Assinatura:** a chave em `app/keystore/` é do projeto e fica no repositório de propósito, para toda
build ter a mesma assinatura e o app atualizar sem desinstalar. Para usar uma chave privada, defina
`BV_KEYSTORE_FILE`, `BV_KEYSTORE_PASSWORD`, `BV_KEY_ALIAS` e `BV_KEY_PASSWORD` no ambiente de build
(a troca de chave exige desinstalar a versão antiga uma vez).

## Licença

GPL-3.0 (veja `LICENSE`), porque o app usa a biblioteca youtubedl-android, que é GPL-3.0.
As fontes Archivo e Courier Prime são da SIL Open Font License 1.1 (textos em
`app/src/main/assets/licenses/`).

Use com responsabilidade: baixe só o que você tem direito de usar e respeite quem criou o conteúdo.
