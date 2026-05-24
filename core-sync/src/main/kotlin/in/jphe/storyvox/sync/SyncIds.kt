package `in`.jphe.storyvox.sync

import java.util.UUID

object SyncIds {
    fun rowUuid(domain: String, userId: String): String =
        UUID.nameUUIDFromBytes("$domain:$userId".toByteArray()).toString()
}
