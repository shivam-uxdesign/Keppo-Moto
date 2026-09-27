package com.ridetrack.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BikeEntity::class, RideEntity::class, SampleEntity::class, EventEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class RideTrackDatabase : RoomDatabase() {
    abstract fun bikeDao(): BikeDao
    abstract fun rideDao(): RideDao

    companion object {
        fun create(context: Context): RideTrackDatabase =
            Room.databaseBuilder(context, RideTrackDatabase::class.java, "ridetrack.db")
                // WAL keeps frequent small ride writes cheap and durable.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
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
    }
}
