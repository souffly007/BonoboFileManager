package fr.bonobo.filemanager.data.repository

import com.hierynomus.mssmb2.SMBApiException
import fr.bonobo.filemanager.diagnostic.TechnicalJournal
import fr.bonobo.filemanager.domain.transfer.FailureKind
import fr.bonobo.filemanager.domain.transfer.TransferFailure
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/**
 * Turns an SMB failure into a short French message for the screen, a technical detail
 * (NTSTATUS name + code, or exception class) for « Détails techniques », and a journal result.
 */
object SmbErrors {

    enum class Kind { ACCESS_DENIED, NOT_FOUND, SHARE_NOT_FOUND, AUTH_REFUSED, UNREACHABLE, REFUSED, OTHER }

    data class Classified(val kind: Kind, val message: String, val technical: String?, val ntStatus: Long?) {
        val journalResult: TechnicalJournal.Result get() = when (kind) {
            Kind.ACCESS_DENIED -> TechnicalJournal.Result.PERMISSION_DENIED
            Kind.NOT_FOUND -> TechnicalJournal.Result.NOT_FOUND
            Kind.SHARE_NOT_FOUND -> TechnicalJournal.Result.SHARE_NOT_FOUND
            Kind.AUTH_REFUSED -> TechnicalJournal.Result.AUTH_REFUSED
            Kind.UNREACHABLE -> TechnicalJournal.Result.UNREACHABLE
            Kind.REFUSED -> TechnicalJournal.Result.REFUSED
            Kind.OTHER -> TechnicalJournal.Result.ERROR
        }
        /** Same short message; the raw code only travels in [TransferFailure.technical]. */
        fun toThrowable(cause: Throwable): TransferFailure =
            TransferFailure(FailureKind.OTHER, message, technical, cause = cause)
    }

    // NTSTATUS values (MS-ERREF 2.3.1).
    private const val ACCESS_DENIED = 0xC0000022L
    private const val NETWORK_ACCESS_DENIED = 0xC00000CAL
    private const val NO_SUCH_FILE = 0xC000000FL
    private const val OBJECT_NAME_NOT_FOUND = 0xC0000034L
    private const val OBJECT_PATH_NOT_FOUND = 0xC000003AL
    private const val NOT_A_DIRECTORY = 0xC0000103L
    private const val BAD_NETWORK_NAME = 0xC00000CCL
    private const val BAD_NETWORK_PATH = 0xC00000BEL
    private const val HOST_UNREACHABLE = 0xC000023DL
    private const val NETWORK_UNREACHABLE = 0xC000023CL
    private const val IO_TIMEOUT = 0xC00000B5L
    private const val LOGON_FAILURE = 0xC000006DL
    private const val WRONG_PASSWORD = 0xC000006AL
    private const val NO_SUCH_USER = 0xC0000064L
    private const val ACCOUNT_RESTRICTION = 0xC000006EL
    private const val INVALID_LOGON_HOURS = 0xC000006FL
    private const val INVALID_WORKSTATION = 0xC0000070L
    private const val PASSWORD_EXPIRED = 0xC0000071L
    private const val ACCOUNT_DISABLED = 0xC0000072L
    private const val ACCOUNT_LOCKED_OUT = 0xC0000234L
    private const val PASSWORD_MUST_CHANGE = 0xC0000224L
    private const val LOGON_TYPE_NOT_GRANTED = 0xC000015BL

    fun message(kind: Kind): String = when (kind) {
        Kind.ACCESS_DENIED -> "Accès refusé par le serveur SMB."
        Kind.NOT_FOUND -> "Fichier ou dossier introuvable sur le serveur."
        Kind.SHARE_NOT_FOUND -> "Partage SMB introuvable : vérifiez le nom du partage."
        Kind.AUTH_REFUSED -> "Identifiants refusés : vérifiez l’utilisateur et le mot de passe."
        Kind.UNREACHABLE -> "Serveur SMB injoignable : vérifiez l’adresse, le port et le réseau."
        Kind.REFUSED -> "Opération refusée."
        Kind.OTHER -> "Le serveur SMB a refusé l’opération."
    }

    fun kindForStatus(code: Long): Kind = when (code and 0xFFFFFFFFL) {
        ACCESS_DENIED, NETWORK_ACCESS_DENIED -> Kind.ACCESS_DENIED
        NO_SUCH_FILE, OBJECT_NAME_NOT_FOUND, OBJECT_PATH_NOT_FOUND, NOT_A_DIRECTORY -> Kind.NOT_FOUND
        BAD_NETWORK_NAME -> Kind.SHARE_NOT_FOUND
        LOGON_FAILURE, WRONG_PASSWORD, NO_SUCH_USER, ACCOUNT_RESTRICTION, INVALID_LOGON_HOURS, INVALID_WORKSTATION,
        PASSWORD_EXPIRED, ACCOUNT_DISABLED, ACCOUNT_LOCKED_OUT, PASSWORD_MUST_CHANGE, LOGON_TYPE_NOT_GRANTED -> Kind.AUTH_REFUSED
        BAD_NETWORK_PATH, HOST_UNREACHABLE, NETWORK_UNREACHABLE, IO_TIMEOUT -> Kind.UNREACHABLE
        else -> Kind.OTHER
    }

    /** Classification from a raw NTSTATUS (name optional, used only in the technical detail). */
    fun fromStatus(code: Long, name: String? = null): Classified {
        val kind = kindForStatus(code)
        val hex = TechnicalJournal.formatNtStatus(code)
        return Classified(kind, message(kind), if (name.isNullOrBlank()) "NTSTATUS $hex" else "$name ($hex)", code and 0xFFFFFFFFL)
    }

    /** Shown after the cause when a verified write failed and its partial could not be removed. */
    const val LEFTOVER_NOTE = "Un fichier partiel n’a pas pu être supprimé sur le serveur : il sera retiré à la prochaine connexion."

    /** Message of a share that is not a disk (printer, IPC$…). */
    const val NOT_A_DISK = "Ce partage n’est pas un dossier de fichiers."

    // Session lost: the next operation must reconnect.
    private const val NETWORK_NAME_DELETED = 0xC00000C9L
    private const val USER_SESSION_DELETED = 0xC0000203L
    private const val NETWORK_SESSION_EXPIRED = 0xC000035CL
    private const val CONNECTION_DISCONNECTED = 0xC000020CL
    private const val CONNECTION_RESET = 0xC000020DL

    /** Does [error] mean the SMB connection / session itself is gone (reconnect, then possibly retry)? */
    fun isDisconnect(error: Throwable): Boolean {
        val chain = generateSequence(error) { it.cause }.take(12).toList()
        chain.firstNotNullOfOrNull { it as? SMBApiException }?.let { api ->
            return (api.statusCode and 0xFFFFFFFFL) in setOf(NETWORK_NAME_DELETED, USER_SESSION_DELETED,
                NETWORK_SESSION_EXPIRED, CONNECTION_DISCONNECTED, CONNECTION_RESET)
        }
        return chain.any { it is java.net.SocketException || it is java.io.EOFException ||
            it.javaClass.name == "com.hierynomus.protocol.transport.TransportException" }
    }

    fun classify(error: Throwable): Classified {
        // Verified write that failed: classify the cause, then say whether a partial remains.
        if (error is SmbTransfers.Failed) {
            val cause = error.cause ?: return Classified(Kind.OTHER, message(Kind.OTHER), null, null)
            val inner = classify(cause)
            return if (error.leftover) inner.copy(message = "${inner.message} $LEFTOVER_NOTE") else inner
        }
        // Already classified lower down (e.g. a listing inside a transfer): keep it as is.
        if (error is TransferFailure) return Classified(Kind.OTHER, error.message ?: message(Kind.OTHER), error.technical, null)
        val chain = generateSequence(error) { it.cause }.take(12).toList()
        chain.firstNotNullOfOrNull { it as? SMBApiException }?.let { api ->
            val name = runCatching { api.status?.name }.getOrNull()?.takeUnless { it == "UNKNOWN" }
            return fromStatus(api.statusCode, name)
        }
        chain.firstOrNull { it is ConnectException || it is UnknownHostException || it is NoRouteToHostException ||
            it is SocketTimeoutException || it is PortUnreachableException || it is TimeoutException }?.let { network ->
            return Classified(Kind.UNREACHABLE, message(Kind.UNREACHABLE), technical(network), null)
        }
        // Our own require()/check() messages are already short French sentences.
        if (error is IllegalArgumentException || error is IllegalStateException) {
            return Classified(Kind.REFUSED, error.message?.takeIf { it.isNotBlank() } ?: message(Kind.REFUSED), null, null)
        }
        return Classified(Kind.OTHER, message(Kind.OTHER), technical(error), null)
    }

    private fun technical(error: Throwable): String =
        error.javaClass.simpleName + (error.message?.takeIf { it.isNotBlank() }?.let { " : $it" } ?: "")

    /** Short message for a failure returned by the SMB repository. */
    fun userMessage(error: Throwable): String = error.message?.takeIf { it.isNotBlank() } ?: message(Kind.OTHER)

    /** Technical detail of a failure returned by the SMB repository, or null. */
    fun technicalDetail(error: Throwable): String? = (error as? TransferFailure)?.technical
}
