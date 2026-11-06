package com.blissplayer.core

/**
 * One shared schema for both drivers (D-3): Android's SQLiteOpenHelper and
 * the desktop sqlite-jdbc driver execute these exact statements. Same
 * tables, same meaning, same version — only the connection is platform
 * specific. Existing Android databases are never migrated by this move:
 * the statements and the version are byte-identical to what the app
 * already created, so user data (library, stats, gains, caches) survives.
 */
object DbSchema {
    const val VERSION = 14

    val statements: List<String> = listOf(
        "CREATE TABLE gains(media_id INTEGER PRIMARY KEY, gain_mult REAL NOT NULL)",
        "CREATE TABLE playlists(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, position INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE playlist_songs(playlist_id INTEGER NOT NULL, media_id INTEGER NOT NULL, position INTEGER NOT NULL)",
        "CREATE INDEX idx_ps ON playlist_songs(playlist_id, position)",
        "CREATE TABLE boost(device_name TEXT PRIMARY KEY, gain_mult REAL NOT NULL)",
        "CREATE TABLE plays(media_id INTEGER PRIMARY KEY, count INTEGER NOT NULL)",
        "CREATE TABLE measure(media_id INTEGER PRIMARY KEY, rms REAL NOT NULL, modified INTEGER NOT NULL)",
        "CREATE TABLE preset(name TEXT PRIMARY KEY, gain_mult REAL NOT NULL)",
        "CREATE TABLE plays_total(media_id INTEGER PRIMARY KEY, count INTEGER NOT NULL)",
        "CREATE TABLE segments(id INTEGER PRIMARY KEY AUTOINCREMENT, media_id INTEGER NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, label TEXT NOT NULL DEFAULT '')",
        "CREATE INDEX idx_seg ON segments(media_id)",
        "CREATE TABLE song_genre(media_id INTEGER PRIMARY KEY, genre TEXT NOT NULL)",
        "CREATE TABLE song_album(media_id INTEGER PRIMARY KEY, album TEXT NOT NULL)",
        "CREATE TABLE lookup_cache(key TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL, genre TEXT NOT NULL DEFAULT '', album TEXT NOT NULL DEFAULT '', pageid INTEGER NOT NULL DEFAULT 0, hittitle TEXT NOT NULL DEFAULT '', high INTEGER NOT NULL DEFAULT 0, confirmed INTEGER NOT NULL DEFAULT 0, found INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE song_state(media_id INTEGER PRIMARY KEY, fingerprint TEXT NOT NULL DEFAULT '', analyzed INTEGER NOT NULL DEFAULT 0, analyzed_at INTEGER NOT NULL DEFAULT 0, eq INTEGER NOT NULL DEFAULT 0, eq_at INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE favorites(media_id INTEGER PRIMARY KEY)",
        "CREATE TABLE song_stats(media_id INTEGER PRIMARY KEY, skips INTEGER NOT NULL DEFAULT 0, completions INTEGER NOT NULL DEFAULT 0, listen_ms INTEGER NOT NULL DEFAULT 0, last_played INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE stat_events(media_id INTEGER NOT NULL, kind TEXT NOT NULL, at INTEGER NOT NULL, ms INTEGER NOT NULL DEFAULT 0)",
        "CREATE INDEX idx_stat_events ON stat_events(kind, at)",
        "CREATE TABLE learned_artists(alias_key TEXT PRIMARY KEY, name TEXT NOT NULL)"
    )

    /** Table names in creation order (for tests and debugging). */
    val tables: List<String> = statements
        .filter { it.startsWith("CREATE TABLE") }
        .map { it.substringAfter("CREATE TABLE ").substringBefore("(").trim() }
}
