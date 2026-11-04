package ai.nidhoggfgg.phoneuse.server

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.EOFException
import java.net.Socket
import java.net.SocketTimeoutException

internal object ConnectionProtocol {
    fun isTls(socket: Socket): Boolean {
        // Peek without consuming the ClientHello: Android's public SSLSocketFactory has no
        // consumed-input overload. Layering TLS then keeps Conscrypt/Keystore behavior intact.
        ParcelFileDescriptor.fromSocket(socket).use { descriptor ->
            val poll = StructPollfd().apply { fd = descriptor.fileDescriptor; events = OsConstants.POLLIN.toShort() }
            if (Os.poll(arrayOf(poll), socket.soTimeout) == 0) throw SocketTimeoutException("Protocol detection timed out")
            val first = ByteArray(1)
            if (Os.recvfrom(descriptor.fileDescriptor, first, 0, 1, OsConstants.MSG_PEEK, null) != 1)
                throw EOFException("Connection closed before protocol detection")
            return first[0].toInt() == 22 // TLS handshake record; SSLv2 is intentionally unsupported.
        }
    }
}
