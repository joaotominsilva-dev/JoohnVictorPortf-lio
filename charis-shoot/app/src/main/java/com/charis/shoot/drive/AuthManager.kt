package com.charis.shoot.drive

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class AuthException(message: String) : Exception(message)

/**
 * Login no Google com permissão para o Drive (Google Identity Services, AuthorizationClient).
 * Não precisa de ID de cliente no código: o Google reconhece o app pelo nome do pacote
 * e pelo SHA-1 da assinatura, cadastrados como "cliente Android" no Google Cloud Console.
 */
class AuthManager(private val activity: ComponentActivity) {

    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
        .build()

    private var pending: CompletableDeferred<String?>? = null
    private val mutex = Mutex()

    @Volatile
    var token: String? = null
        private set

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        val p = pending
        pending = null
        try {
            val r = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(res.data)
            p?.complete(r.accessToken)
        } catch (e: Exception) {
            p?.complete(null)
        }
    }

    /**
     * Devolve um token de acesso. [interactive] permite abrir a tela de escolha de conta /
     * permissão. [forceRefresh] descarta o token atual (ex.: depois de um 401).
     */
    suspend fun getToken(interactive: Boolean, forceRefresh: Boolean = false): String? = mutex.withLock {
        if (!forceRefresh) token?.let { return@withLock it }
        val old = token
        if (forceRefresh && old != null) {
            withContext(Dispatchers.IO) { runCatching { GoogleAuthUtil.clearToken(activity, old) } }
            token = null
        }
        val result = try {
            withContext(Dispatchers.Main) {
                Identity.getAuthorizationClient(activity).authorize(request).await()
            }
        } catch (e: ApiException) {
            throw AuthException(explain(e.statusCode))
        }
        token = if (result.hasResolution()) {
            if (!interactive) return@withLock null
            val d = CompletableDeferred<String?>()
            pending = d
            withContext(Dispatchers.Main) {
                launcher.launch(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build())
            }
            d.await()
        } else {
            result.accessToken
        }
        token
    }

    suspend fun signOut() {
        val t = token
        token = null
        if (t != null) withContext(Dispatchers.IO) { runCatching { GoogleAuthUtil.clearToken(activity, t) } }
    }

    private fun explain(code: Int): String = when (code) {
        10 -> "O Google recusou o login (código 10). Falta cadastrar o cliente Android no Google Cloud " +
            "com o pacote com.charis.shoot e o SHA-1 mostrado abaixo."
        7 -> "Sem internet. Confira a conexão e tente de novo."
        12501, 16 -> "Login cancelado."
        else -> "Não foi possível entrar (código $code)."
    }

    companion object {
        const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"

        /** SHA-1 do certificado que assinou este APK (para cadastrar no Google Cloud). */
        fun signingSha1(ctx: Context): String = try {
            val info = ctx.packageManager.getPackageInfo(
                ctx.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            )
            val cert = info.signingInfo!!.apkContentsSigners[0].toByteArray()
            MessageDigest.getInstance("SHA-1").digest(cert).joinToString(":") { "%02X".format(it) }
        } catch (e: Exception) {
            "indisponível"
        }
    }
}
