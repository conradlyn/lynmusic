package top.iwesley.lyn.music.data.repository

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import top.iwesley.lyn.music.data.db.LynMusicDatabase

internal suspend fun <T> LynMusicDatabase.immediateWriteTransaction(block: suspend () -> T): T {
    return useWriterConnection { transactor ->
        transactor.immediateTransaction {
            block()
        }
    }
}
