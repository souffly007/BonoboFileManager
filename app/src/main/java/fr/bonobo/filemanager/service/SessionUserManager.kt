package fr.bonobo.filemanager.service

import org.apache.ftpserver.ftplet.Authentication
import org.apache.ftpserver.ftplet.AuthenticationFailedException
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import java.security.MessageDigest

/** Ephemeral FTP user: never calls a file-backed PropertiesUserManager. */
class SessionUserManager(private val user: User, password: String) : UserManager {
    private val expected = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
    override fun getUserByName(name: String): User? = if (name == user.name) user else null
    override fun getAllUserNames(): Array<String> = arrayOf(user.name)
    override fun delete(name: String) { throw UnsupportedOperationException("Session en mémoire") }
    override fun save(user: User) { throw UnsupportedOperationException("Session en mémoire") }
    override fun doesExist(name: String): Boolean = name == user.name
    override fun getAdminName(): String = user.name
    override fun isAdmin(name: String): Boolean = false
    override fun authenticate(authentication: Authentication): User {
        if (authentication is UsernamePasswordAuthentication && authentication.username == user.name) {
            val actual = MessageDigest.getInstance("SHA-256").digest(authentication.password.toByteArray(Charsets.UTF_8))
            if (MessageDigest.isEqual(actual, expected)) return user
        }
        throw AuthenticationFailedException("Identifiants invalides")
    }
    fun clear() { expected.fill(0) }
}
