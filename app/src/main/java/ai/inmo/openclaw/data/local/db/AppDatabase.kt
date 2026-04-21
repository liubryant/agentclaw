package ai.inmo.openclaw.data.local.db

import ai.inmo.core_common.utils.Logger
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        SyncedSessionEntity::class,
        SyncedMessageEntity::class,
        SyncedSegmentEntity::class,
        SyncedToolCallEntity::class
    ],
    version = 7,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun syncedSessionDao(): SyncedSessionDao
    abstract fun syncedMessageDao(): SyncedMessageDao
    abstract fun syncedSegmentDao(): SyncedSegmentDao
    abstract fun syncedToolCallDao(): SyncedToolCallDao
    abstract fun searchDao(): SearchDao

    fun rebuildSearchIndexes() {
        val db = openHelper.writableDatabase
        db.beginTransaction()
        try {
            Logger.d(TAG, "rebuildSearchIndexes start")
            rebuildSearchFtsIndexes(db)
            db.setTransactionSuccessful()
            Logger.d(TAG, "rebuildSearchIndexes done")
        } catch (t: Throwable) {
            Logger.e(TAG, "rebuildSearchIndexes failed: ${t.message}")
            throw t
        } finally {
            db.endTransaction()
        }
    }

    fun insertMessageFtsEntry(messageRowId: Long, content: String) {
        val db = openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO fts_synced_messages(rowid, content) VALUES(?, ?)",
            arrayOf<Any?>(messageRowId, content)
        )
    }

    fun insertToolCallFtsEntries(toolCallIds: List<Long>, toolCalls: List<SyncedToolCallEntity>) {
        if (toolCalls.isEmpty()) return
        val db = openHelper.writableDatabase
        toolCallIds.zip(toolCalls).forEach { (id, toolCall) ->
            db.execSQL(
                "INSERT INTO fts_synced_tool_calls(rowid, name, description, resultPreview) VALUES(?, ?, ?, ?)",
                arrayOf<Any?>(id, toolCall.name, toolCall.description ?: "", toolCall.resultPreview ?: "")
            )
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `synced_sessions` (" +
                        "`sessionKey` TEXT NOT NULL, `title` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "`kind` TEXT, PRIMARY KEY(`sessionKey`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `synced_messages` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionKey` TEXT NOT NULL, " +
                        "`messageIndex` INTEGER NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `isStreaming` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_synced_messages_sessionKey` " +
                        "ON `synced_messages` (`sessionKey`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_synced_messages_sessionKey_messageIndex` " +
                        "ON `synced_messages` (`sessionKey`, `messageIndex`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `synced_segments` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `messageId` INTEGER NOT NULL, " +
                        "`segmentIndex` INTEGER NOT NULL, `type` TEXT NOT NULL, `textContent` TEXT, " +
                        "FOREIGN KEY(`messageId`) REFERENCES `synced_messages`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_synced_segments_messageId` " +
                        "ON `synced_segments` (`messageId`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `synced_tool_calls` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `segmentId` INTEGER NOT NULL, " +
                        "`toolIndex` INTEGER NOT NULL, `toolCallId` TEXT, `name` TEXT NOT NULL, " +
                        "`description` TEXT, `resultPreview` TEXT, " +
                        "FOREIGN KEY(`segmentId`) REFERENCES `synced_segments`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_synced_tool_calls_segmentId` " +
                        "ON `synced_tool_calls` (`segmentId`)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createSearchFtsObjects(db)
                rebuildSearchFtsIndexes(db)
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                dropSearchFtsObjects(db)
                createSearchFtsObjects(db)
                rebuildSearchFtsIndexes(db)
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Logger.d(TAG, "MIGRATION_4_5 start")
                resetSearchFtsObjects(db)
                Logger.d(TAG, "MIGRATION_4_5 done")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Logger.d(TAG, "MIGRATION_5_6 start")
                db.execSQL(
                    "ALTER TABLE `synced_messages` ADD COLUMN `clientMessageId` TEXT"
                )
                Logger.d(TAG, "MIGRATION_5_6 done")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Logger.d(TAG, "MIGRATION_6_7 start")
                db.execSQL(
                    "ALTER TABLE `synced_messages` ADD COLUMN `sendStatus` TEXT NOT NULL DEFAULT 'SENT'"
                )
                Logger.d(TAG, "MIGRATION_6_7 done")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "openclaw.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7
                    )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            resetSearchFtsObjects(db)
                        }

                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            // Search indexes are derived from synced_* cache tables, so rebuilding on open
                            // recovers from any prior partial writes or stale FTS contents.
                            resetSearchFtsObjects(db)
                        }
                    })
                    .build()
                    .also { INSTANCE = it }
            }
        }

        private fun resetSearchFtsObjects(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try {
                Logger.d(TAG, "resetSearchFtsObjects start")
                dropSearchFtsObjects(db)
                createSearchFtsObjects(db)
                rebuildSearchFtsIndexes(db)
                db.setTransactionSuccessful()
                Logger.d(TAG, "resetSearchFtsObjects done")
            } catch (t: Throwable) {
                Logger.e(TAG, "resetSearchFtsObjects failed: ${t.message}")
                throw t
            } finally {
                db.endTransaction()
            }
        }

        private fun createSearchFtsObjects(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS `fts_synced_messages` " +
                    "USING fts4(`content`, tokenize=unicode61)"
            )
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS `fts_synced_tool_calls` " +
                    "USING fts4(`name`, `description`, `resultPreview`, tokenize=unicode61)"
            )
        }

        private fun rebuildSearchFtsIndexes(db: SupportSQLiteDatabase) {
            db.execSQL("DELETE FROM `fts_synced_messages`")
            db.execSQL(
                "INSERT INTO `fts_synced_messages`(`rowid`, `content`) " +
                    "SELECT `id`, `content` FROM `synced_messages`"
            )
            db.execSQL("DELETE FROM `fts_synced_tool_calls`")
            db.execSQL(
                "INSERT INTO `fts_synced_tool_calls`(`rowid`, `name`, `description`, `resultPreview`) " +
                    "SELECT `id`, `name`, `description`, `resultPreview` FROM `synced_tool_calls`"
            )
        }

        private fun dropSearchFtsObjects(db: SupportSQLiteDatabase) {
            dropLegacySearchTriggers(db)
            db.execSQL("DROP TABLE IF EXISTS `fts_synced_messages`")
            db.execSQL("DROP TABLE IF EXISTS `fts_synced_tool_calls`")
        }

        private fun dropLegacySearchTriggers(db: SupportSQLiteDatabase) {
            Logger.d(TAG, "dropLegacySearchTriggers")
            db.execSQL("DROP TRIGGER IF EXISTS `synced_messages_ai`")
            db.execSQL("DROP TRIGGER IF EXISTS `synced_messages_ad`")
            db.execSQL("DROP TRIGGER IF EXISTS `synced_messages_bu`")
            db.execSQL("DROP TRIGGER IF EXISTS `synced_messages_au`")
            db.execSQL("DROP TRIGGER IF EXISTS `synced_tool_calls_ai`")
            db.execSQL("DROP TRIGGER IF EXISTS `synced_tool_calls_ad`")
            db.execSQL("DROP TRIGGER IF EXISTS `synced_tool_calls_bu`")
            db.execSQL("DROP TRIGGER IF EXISTS `synced_tool_calls_au`")
        }

        private const val TAG = "AppDatabase"
    }
}
