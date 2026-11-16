package fr.bonobo.filemanager

import fr.bonobo.filemanager.service.SessionUserManager
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.usermanager.AnonymousAuthentication
import org.junit.Assert.*
import org.junit.Test

class SessionUserManagerTest {
    @Test fun onlyCurrentSessionPasswordIsAccepted() {
        val user = BaseUser().apply { name = "bonobo" }
        val manager = SessionUserManager(user, "password123")
        assertEquals(user, manager.authenticate(UsernamePasswordAuthentication("bonobo", "password123")))
        assertTrue(runCatching { manager.authenticate(UsernamePasswordAuthentication("bonobo", "wrong")) }.isFailure)
        assertTrue(runCatching { manager.authenticate(AnonymousAuthentication()) }.isFailure)
        assertTrue(runCatching { manager.save(user) }.isFailure)
        manager.clear()
        assertTrue(runCatching { manager.authenticate(UsernamePasswordAuthentication("bonobo", "password123")) }.isFailure)
    }
}
