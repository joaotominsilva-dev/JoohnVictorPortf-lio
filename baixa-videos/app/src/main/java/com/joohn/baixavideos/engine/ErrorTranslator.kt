package com.joohn.baixavideos.engine

import com.joohn.baixavideos.util.Platform

/** Converte as mensagens do yt-dlp em algo que dá para entender e agir. */
object ErrorTranslator {

    fun friendly(raw: String, platform: Platform, instagramLoggedIn: Boolean): String {
        val r = raw.lowercase()
        fun has(vararg parts: String) = parts.any { it in r }

        return when {
            has("unsupported url") ->
                "Esse link não é de um vídeo suportado. Copie o link do post, reel, short ou vídeo."
            has("is not a valid url", "invalid url") ->
                "Link inválido. Confira se copiou o endereço completo."
            platform == Platform.INSTAGRAM && has(
                "login", "rate-limit", "rate limit", "empty media response", "not available",
                "unable to extract", "401", "checkpoint", "requested content",
            ) ->
                if (instagramLoggedIn) {
                    "O Instagram recusou o acesso. Entre de novo na sua conta em Ajustes e tente outra vez."
                } else {
                    "O Instagram pediu login para esse vídeo. Entre na sua conta em Ajustes e tente de novo."
                }
            has("private video", "this video is private", "account is private", "private account") ->
                "Esse vídeo é privado."
            has("confirm your age", "age-restricted", "age restricted", "inappropriate for some users") ->
                "Vídeo com restrição de idade. O site exige login para liberar."
            has("not a bot", "not a robot") ->
                "O YouTube pediu uma verificação anti-robô. Tente mais tarde ou troque de rede (Wi-Fi/dados)."
            has("live event will begin", "premieres in", "is upcoming") ->
                "Essa live ou estreia ainda não começou."
            has("http error 429", "too many requests") ->
                "Muitas tentativas seguidas. Espere alguns minutos e tente de novo."
            has("http error 403", "403: forbidden") ->
                "O site bloqueou o download (erro 403). Atualize o motor em Ajustes e tente de novo."
            has("video unavailable", "has been removed", "no longer available", "http error 404", "does not exist") ->
                "Vídeo indisponível ou removido."
            has("no video formats found", "requested format is not available", "there's no video", "no video in this") ->
                "Não encontrei vídeo nesse link (pode ser um post só com fotos)."
            has("no space left") ->
                "Sem espaço no aparelho. Libere espaço e tente de novo."
            has(
                "unable to download", "failed to resolve", "network is unreachable", "timed out",
                "connection reset", "connection refused", "temporary failure", "name or service not known",
                "unable to connect", "ssl",
            ) ->
                "Falha de conexão. Verifique sua internet e tente de novo."
            has("ffmpeg", "postprocessing", "conversion failed") ->
                "Baixei o vídeo, mas falhou ao processar o arquivo."
            else -> "Não foi possível baixar esse link."
        }
    }

    /** Remove o prefixo "[extrator] id:" que o yt-dlp coloca antes da mensagem. */
    fun clean(raw: String): String =
        raw.replace(Regex("""^\[[^\]]+\]\s*([\w-]+:\s*)?"""), "").trim()
}
