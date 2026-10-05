package com.ridetrack.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BikeEntity::class, RideEntity::class, SampleEntity::class, EventEntity::class, MomentEntity::class],
    version = 9,
    exportSchema = true,
)
abstract class RideTrackDatabase : RoomDatabase() {
    abstract fun bikeDao(): BikeDao
    abstract fun rideDao(): RideDao
    abstract fun momentDao(): MomentDao

    companion object {
        fun create(context: Context): RideTrackDatabase =
            Room.databaseBuilder(context, RideTrackDatabase::class.java, "ridetrack.db")
                // WAL keeps frequent small ride writes cheap and durable.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .build()

        /** Bike photo + redline, and OBD engine data per sample. Existing rows get NULL (unknown). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bikes ADD COLUMN redlineRpm INTEGER")
                db.execSQL("ALTER TABLE bikes ADD COLUMN photoFile TEXT")
                db.execSQL("ALTER TABLE samples ADD COLUMN rpm REAL")
                db.execSQL("ALTER TABLE samples ADD COLUMN gear INTEGER")
            }
        }

        /** Moments (clips/photos). SQL matches schemas/3.json exactly. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_MOMENTS)
                db.execSQL(CREATE_MOMENTS_INDEX)
            }
        }

        /** Clip start times for telemetry-synced share videos; older clips get NULL (estimated). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE moments ADD COLUMN clipStartMillis INTEGER")
            }
        }

        /** Bike odometer: the rider's reading and when it was set. Existing bikes get NULL (not set). */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bikes ADD COLUMN odometerKm REAL")
                db.execSQL("ALTER TABLE bikes ADD COLUMN odometerSetAtMillis INTEGER")
            }
        }

        /** Where a clip came from (your video, GPS lost); existing clips get NULL (an event). */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE moments ADD COLUMN source TEXT")
            }
        }

        /** Recently deleted: rides and moments get a "deleted at" time instead of vanishing at once. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rides ADD COLUMN deletedAtMillis INTEGER")
                db.execSQL("ALTER TABLE moments ADD COLUMN deletedAtMillis INTEGER")
            }
        }

        /** Break time per ride (long stops off the bike); older rides had none. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rides ADD COLUMN breakMillis INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** The part of a clip to share; the clip file itself is never cut. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE moments ADD COLUMN trimStartMillis INTEGER")
                db.execSQL("ALTER TABLE moments ADD COLUMN trimEndMillis INTEGER")
            }
        }

        private const val CREATE_MOMENTS = "CREATE TABLE IF NOT EXISTS `moments` (`id` TEXT NOT NULL, `rideId` TEXT NOT NULL, " +
            "`kind` TEXT NOT NULL, `types` TEXT NOT NULL, `timeMillis` INTEGER NOT NULL, `latitude` REAL, `longitude` REAL, " +
            "`speedMps` REAL, `peakValue` REAL, `file` TEXT NOT NULL, `thumbFile` TEXT, `durationMillis` INTEGER, " +
            "`starred` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`rideId`) REFERENCES `rides`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )"
        private const val CREATE_MOMENTS_INDEX =
            "CREATE INDEX IF NOT EXISTS `index_moments_rideId_timeMillis` ON `moments` (`rideId`, `timeMillis`)"
    }
}
