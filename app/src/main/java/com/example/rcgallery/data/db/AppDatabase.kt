package com.example.rcgallery.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TagEntity::class, TagTargetEntity::class, ViewHistoryEntity::class, ParentAlbumEntity::class, ParentChildEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun tagDao(): TagDao
    abstract fun viewHistoryDao(): ViewHistoryDao
    abstract fun parentAlbumDao(): ParentAlbumDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** 版本 1→2：新增 view_history 表 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `view_history` (
                        `targetKey` TEXT NOT NULL,
                        `id` INTEGER NOT NULL,
                        `targetType` INTEGER NOT NULL,
                        `viewedAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`targetKey`)
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_view_history_viewedAt` ON `view_history` (`viewedAt`)")
            }
        }

        /** 版本 2→3：新增层级相册表 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `parent_albums` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL DEFAULT 0
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_parent_albums_name` ON `parent_albums` (`name`)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `parent_children` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `parentId` INTEGER NOT NULL,
                        `childBucketId` TEXT NOT NULL,
                        `orderIndex` INTEGER NOT NULL DEFAULT 0
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_parent_children_parentId` ON `parent_children` (`parentId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_parent_children_childBucketId` ON `parent_children` (`childBucketId`)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "rcgallery_tags.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
