package fr.bonobo.filemanager.util

import android.util.Xml
import fr.bonobo.filemanager.domain.model.ConnectionType
import fr.bonobo.filemanager.domain.model.RemoteConnection
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

object ConfigParser {

    fun parseConnections(inputStream: InputStream): List<RemoteConnection> {
        val connections = mutableListOf<RemoteConnection>()
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, null)

        var eventType = parser.eventType
        var currentConnection: MutableConnection? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val name = parser.name?.lowercase()
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (name == "connection" || name == "item" || name == "server") {
                        currentConnection = MutableConnection()
                    } else if (currentConnection != null) {
                        when (name) {
                            "name" -> currentConnection.name = parser.nextText()
                            "host" -> currentConnection.host = parser.nextText()
                            "port" -> currentConnection.port = parser.nextText().toIntOrNull() ?: 21
                            "user" -> currentConnection.user = parser.nextText()
                            "pass" -> currentConnection.pass = parser.nextText()
                            "protocol" -> {
                                val protocol = parser.nextText()
                                // Dans FileZilla, Protocol 4 = FTPS/TLS
                                if (protocol == "4") {
                                    currentConnection.type = ConnectionType.FTPS
                                }
                            }
                            "type" -> {
                                val typeStr = parser.nextText().uppercase()
                                currentConnection.type = when {
                                    typeStr.contains("FTPS") || typeStr.contains("SSL") -> ConnectionType.FTPS
                                    typeStr.contains("SFTP") -> ConnectionType.SFTP
                                    typeStr.contains("SMB") -> ConnectionType.SMB
                                    typeStr.contains("DRIVE") -> ConnectionType.GOOGLE_DRIVE
                                    else -> ConnectionType.FTP
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if ((name == "connection" || name == "item" || name == "server") && currentConnection != null) {
                        // S'assurer qu'on a au moins un hôte et un nom
                        if (currentConnection.host.isNotBlank()) {
                            if (currentConnection.name.isBlank()) {
                                currentConnection.name = currentConnection.host
                            }
                            connections.add(currentConnection.toRemoteConnection())
                        }
                        currentConnection = null
                    }
                }
            }
            eventType = parser.next()
        }
        return connections
    }

    private class MutableConnection {
        var name: String = ""
        var host: String = ""
        var port: Int = 21
        var user: String = "anonymous"
        var pass: String = ""
        var type: ConnectionType = ConnectionType.FTP

        fun toRemoteConnection() = RemoteConnection(
            name = name, 
            host = host, 
            port = port, 
            user = user, 
            pass = pass, 
            type = type
        )
    }
}
