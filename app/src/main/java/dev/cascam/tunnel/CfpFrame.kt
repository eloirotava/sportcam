package dev.cascam.tunnel

/**
 * Wire v2 do cf-p: cabeçalho de seis bytes e nove opcodes, uma mensagem WebSocket binária por
 * quadro. Não há prefixo de tamanho porque o WebSocket já delimita.
 *
 * ```
 * offset 0  1 byte   versão = 2
 * offset 1  1 byte   kind
 * offset 2  4 bytes  stream_id, big-endian
 * offset 6  ...      payload
 * ```
 *
 * O byte de versão é o que separa v1 de v2: o servidor recusa o quadro inteiro na decodificação,
 * ainda no AUTH, antes de olhar o token — e sem devolver ERROR, então o sintoma de errar aqui é
 * a conexão fechar calada.
 */
data class CfpFrame(val kind: Int, val streamId: Long, val payload: ByteArray) {
    fun encode(): ByteArray {
        val bytes = ByteArray(HEADER_BYTES + payload.size)
        bytes[0] = VERSION.toByte()
        bytes[1] = kind.toByte()
        bytes[2] = (streamId ushr 24 and 0xFF).toByte()
        bytes[3] = (streamId ushr 16 and 0xFF).toByte()
        bytes[4] = (streamId ushr 8 and 0xFF).toByte()
        bytes[5] = (streamId and 0xFF).toByte()
        payload.copyInto(bytes, HEADER_BYTES)
        return bytes
    }

    val text: String get() = String(payload, Charsets.UTF_8)

    /**
     * Créditos de um WINDOW_UPDATE. Payload curto devolve `null` e o quadro é ignorado sem erro,
     * como o cliente de referência faz — um update perdido atrasa, um `bail!` derruba a sessão.
     */
    val credit: Long?
        get() = if (payload.size < 4) null else
            (payload[0].toLong() and 0xFF shl 24) or
                (payload[1].toLong() and 0xFF shl 16) or
                (payload[2].toLong() and 0xFF shl 8) or
                (payload[3].toLong() and 0xFF)

    override fun equals(other: Any?): Boolean = other is CfpFrame &&
        kind == other.kind && streamId == other.streamId && payload.contentEquals(other.payload)

    override fun hashCode(): Int = (kind * 31 + streamId.hashCode()) * 31 + payload.contentHashCode()

    companion object {
        const val VERSION = 2
        const val HEADER_BYTES = 6

        const val AUTH = 1
        const val AUTH_OK = 2
        const val OPEN = 3
        const val OPEN_OK = 4
        const val OPEN_ERROR = 5
        const val DATA = 6
        const val CLOSE = 7
        const val ERROR = 8
        const val WINDOW_UPDATE = 9

        /**
         * Crédito inicial de cada stream, em cada direção. Dimensionado pelo produto banda×atraso
         * do caminho até a VPS; passar disso faz o servidor derrubar a sessão WebSocket inteira,
         * e não só o stream.
         */
        const val WINDOW = 1024 * 1024

        /** Quanto o receptor consome antes de devolver crédito. Metade da janela. */
        const val WINDOW_UPDATE_THRESHOLD = WINDOW / 2

        /** Leitura máxima do socket local, e portanto payload máximo de um DATA que geramos. */
        const val CHUNK = 16 * 1024

        fun of(kind: Int, streamId: Long = 0, payload: ByteArray = ByteArray(0)) =
            CfpFrame(kind, streamId, payload)

        fun text(kind: Int, streamId: Long, message: String) =
            CfpFrame(kind, streamId, message.toByteArray(Charsets.UTF_8))

        fun windowUpdate(streamId: Long, credit: Long) = CfpFrame(
            WINDOW_UPDATE, streamId,
            byteArrayOf(
                (credit ushr 24 and 0xFF).toByte(),
                (credit ushr 16 and 0xFF).toByte(),
                (credit ushr 8 and 0xFF).toByte(),
                (credit and 0xFF).toByte(),
            ),
        )

        fun decode(bytes: ByteArray): CfpFrame {
            require(bytes.size >= HEADER_BYTES) { "Quadro curto: ${bytes.size} bytes" }
            val version = bytes[0].toInt() and 0xFF
            require(version == VERSION) {
                "Versão de protocolo $version incompatível: este cliente fala $VERSION"
            }
            val streamId = (bytes[2].toLong() and 0xFF shl 24) or
                (bytes[3].toLong() and 0xFF shl 16) or
                (bytes[4].toLong() and 0xFF shl 8) or
                (bytes[5].toLong() and 0xFF)
            return CfpFrame(bytes[1].toInt() and 0xFF, streamId, bytes.copyOfRange(HEADER_BYTES, bytes.size))
        }
    }
}
