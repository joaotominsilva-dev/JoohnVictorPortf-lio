# Charis Shoot (Android)

App de culling de fotos com a identidade visual Charis (creme `#F3F4EA`, sálvia `#9A9B89`,
sálvia escuro `#5E604F`, grafite `#20211C`, fonte Inter, cantos arredondados, modo escuro e claro).
É um app novo, feito do zero: não usa código nem a IA do AfterShoot.

Instalador: `apk/CharisShoot.apk` (gerado e testado pelo GitHub Actions a cada mudança).

## Onde as fotos ficam

**Google Drive.** Entre com a conta Google, navegue até a pasta e toque em **Culling**.
Estrelas, rótulos de cor, escolhida/rejeitada e o resultado da análise ficam gravados no próprio
arquivo do Drive (`appProperties`). Então abrir em outro celular mostra tudo igual.

**Pasta do aparelho.** Botão **Pasta do celular, HD ou cartão** (na tela inicial, funciona sem login)
ou o filtro **Celular / HD** na tela de pastas. O seletor do Android mostra a memória interna,
o cartão SD, HD ou pendrive no USB-OTG e o leitor de cartão da câmera. As subpastas entram juntas
(até 3 níveis, por exemplo `DCIM/100CANON`). As marcações ficam em `charis-shoot.json` dentro da pasta;
se a pasta for só leitura, ficam guardadas no app. As pastas usadas aparecem em **Pastas do aparelho**.

## O que faz

- **Analisar**: nitidez (no rosto principal ou no trecho mais nítido), olhos fechados (ML Kit,
  roda no aparelho) e fotos repetidas da mesma série (hash perceptual + horário).
  Sensibilidade ao desfoque: Baixa, Média ou Alta (menu ⋮).
- **Sugestão automática**: nas fotos sem marcação, rejeita desfocadas, olhos fechados e as repetidas
  que não são a melhor da série; o resto vira escolhida. Marcações manuais não mudam.
- **Visualizador**: arraste para o lado, toque duplo para zoom, estrelas, rótulos e os botões
  Rejeitar / Escolher (pula para a próxima).
- **Separar**: copia ou move as escolhidas, rejeitadas ou com estrelas para uma subpasta
  (`Charis Selecionadas` por padrão).
- **Editar**: exposição, contraste, saturação, temperatura e tonalidade. Salva um JPEG em
  `Charis Editadas`, com os dados da câmera (EXIF). RAW é editado pela prévia (do Drive ou a embutida no RAW).

## Liberar o login do Google (uma vez só)

O Google só deixa o app acessar o Drive depois que ele é cadastrado no Google Cloud da sua conta.
Não precisa colar nada no app: o Google reconhece o APK pelo nome do pacote e pela assinatura.

1. Abra <https://console.cloud.google.com/> e crie um projeto (ex.: `Charis Shoot`).
2. **APIs e serviços › Biblioteca**: procure **Google Drive API** e clique em **Ativar**.
3. **Google Auth Platform › Branding** (ou "Tela de consentimento OAuth"): preencha nome do app
   e seu e-mail. Tipo de público: **Externo**.
4. **Público-alvo › Usuários de teste**: adicione o seu e-mail do Google.
5. **Clientes › Criar cliente › Android**:
   - Nome do pacote: `com.charis.shoot`
   - Impressão digital SHA-1: `88:5E:4B:F7:78:25:98:77:E0:1A:FB:0E:BE:7D:6C:0F:2C:C7:A1:A8`
6. Pronto. No app, toque em **Entrar com Google**. Vai aparecer o aviso "o Google não verificou este app"
   (normal para app pessoal): toque em **Continuar**.

Se o login falhar com o código 10, o cadastro do passo 5 está diferente do APK. O app mostra
o pacote e o SHA-1 certos na própria tela de login.

## Compilar

Pelo GitHub Actions (`.github/workflows/charis-shoot-apk.yml`): compila, abre o app num emulador,
tira uma captura de tela e grava o APK em `apk/` nesta branch.

Localmente: `gradle assembleRelease` dentro de `charis-shoot/` (JDK 17 e Android SDK 35).

A chave de assinatura (`keystore/charis-shoot.jks`) está no repositório para o SHA-1 nunca mudar.
Ela serve para uso pessoal; para publicar na Play Store, gere uma chave própria e guarde fora do repositório.

## Limites

- RAW sem miniatura no Drive não pode ser analisado pela nuvem; na pasta local usa a prévia embutida.
- Com a permissão em modo teste, o Google pode pedir para entrar de novo depois de alguns dias.
- Câmera ligada direto no USB (modo PTP/MTP) costuma ser só leitura: dá para fazer o culling,
  mas separar e editar exigem uma pasta com gravação (cartão no leitor, memória ou HD).
