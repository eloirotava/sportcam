package dev.cascam.remote

import java.security.MessageDigest
import java.util.Base64

/**
 * Autenticação básica, a do próprio navegador — sem tela de login, sem cookie, sem sessão.
 *
 * O site expõe a chave de transmissão e o botão de entrar ao vivo, e o túnel o publica num
 * hostname que qualquer varredura de certificado encontra. Então não existe modo aberto: sem
 * credencial, 401 em toda rota, inclusive nos arquivos estáticos.
 */
class BasicAuth(private val user: String, private val password: String) {
    /** Sem senha configurada nada é liberado; quem decide ligar o remoto tem que escolher uma. */
    val configured: Boolean get() = password.isNotBlank()

    fun accepts(header: String?): Boolean {
        if (!configured) return false
        val encoded = header?.trim()?.takeIf { it.startsWith(PREFIX, ignoreCase = true) }
            ?.substring(PREFIX.length)?.trim() ?: return false
        // java.util.Base64 e não android.util.Base64: o primeiro existe no JUnit do módulo, e é o
        // que deixa esta classe ser testada sem Robolectric. Está disponível desde a API 26.
        val decoded = runCatching {
            String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrNull() ?: return false
        val separator = decoded.indexOf(':')
        if (separator < 0) return false
        // MessageDigest.isEqual não sai mais cedo no primeiro byte diferente. Aqui o ganho é
        // pequeno, mas comparar segredo com == é o tipo de detalhe que se copia adiante.
        return constantTimeEquals(decoded.substring(0, separator), user) &&
            constantTimeEquals(decoded.substring(separator + 1), password)
    }

    private fun constantTimeEquals(candidate: String, expected: String): Boolean =
        MessageDigest.isEqual(candidate.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

    private companion object {
        const val PREFIX = "Basic "
    }
}
