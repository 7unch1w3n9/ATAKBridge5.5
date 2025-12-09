package com.atakmap.android.LoRaBridge.Database;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/**
 * ChatDatabase
 *
 * Central Room database for the LoRaBridge plugin.
 *
 * Contains two entity tables:
 *   - ChatMessageEntity   (chat messages)
 *   - GenericCotEntity    (generic CoT events and EXI payloads)
 *
 * The database is implemented as a thread safe singleton so that the
 * entire plugin shares one connection pool per process.
 */
@Database(
        entities = {ChatMessageEntity.class, GenericCotEntity.class},
        version = 9,
        exportSchema = false
)
public abstract class ChatDatabase extends RoomDatabase {

    /** Singleton instance of the database */
    private static volatile ChatDatabase INSTANCE;

    /** DAO for chat message access */
    public abstract ChatMessageDao chatMessageDao();

    /** DAO for generic CoT storage and retrieval */
    public abstract GenericCotDao genericCotDao();


    /**
     * Migration from schema version 8 to 9.
     *
     * Adds the generic_cot table which stores generic CoT events, together
     * with optional EXI encoded payloads.
     *
     * Columns:
     *   id        primary key (text)
     *   uid       CoT UID of the event
     *   type      CoT type string
     *   timeIso   ISO formatted timestamp
     *   origin    logical source of the event
     *   cotRawXml raw XML representation of the CoT event
     *   exiBytes  optional EXI encoded representation (BLOB)
     */
    private static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL(
                    "CREATE TABLE IF NOT EXISTS generic_cot (" +
                            "id TEXT NOT NULL PRIMARY KEY, " +
                            "uid TEXT, " +
                            "type TEXT, " +
                            "timeIso TEXT, " +
                            "origin TEXT, " +
                            "cotRawXml TEXT, " +
                            "exiBytes BLOB)"
            );
        }
    };

    /**
     * Get the singleton ChatDatabase instance.
     *
     * The builder:
     *   - Uses application context to avoid leaking activities
     *   - Registers MIGRATION_8_9
     *   - Enables fallbackToDestructiveMigration which will drop
     *     and recreate the database if a future version change is
     *     not covered by an explicit migration
     *
     * @param context any context, typically plugin or activity context
     * @return singleton ChatDatabase instance
     */
    public static ChatDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (ChatDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    ChatDatabase.class,
                                    "chat_database"
                            )
                            .addMigrations(MIGRATION_8_9)
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
