package com.alhorrtv.app

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(
    tableName = "content", primaryKeys = ["kind", "contentId"],
    // Indexes keep opening Movies/Series instant even with 100k+ titles (no full-table scan + sort on every open).
    indices = [Index(value = ["kind", "category"]), Index(value = ["kind", "idNum"])]
)
data class ContentEntity(
    val kind: String,
    val contentId: String,
    val name: String,
    val normalizedName: String,
    val category: String,
    val imageUrl: String,
    val extension: String,
    val syncToken: String,
    /** Server rating on a 0-10 scale as text ("7.8"), empty when the provider sends none. */
    @ColumnInfo(defaultValue = "''") val rating: String = "",
    /** [contentId] as a number (0 when it is not numeric): lets "newest first" use an index instead of sorting every row. */
    @ColumnInfo(defaultValue = "0") val idNum: Long = contentId.toLongOrNull() ?: 0L
)

@Entity(tableName = "episodes", primaryKeys = ["seriesId", "episodeId"])
data class EpisodeEntity(
    val seriesId: String,
    val seasonKey: String,
    val episodeId: String,
    val title: String,
    val imageUrl: String,
    val extension: String,
    val episodeNumber: String
)

@Entity(tableName = "categories", primaryKeys = ["kind", "categoryId"])
data class CategoryEntity(
    val kind: String,
    val categoryId: String,
    val categoryName: String,
    val parentId: String
)

@Entity(tableName = "user_marks", primaryKeys = ["contentKind", "contentId"])
data class UserMarkEntity(
    val contentKind: String,
    val contentId: String,
    val favorite: Boolean = false,
    val followed: Boolean = false,
    val positionMs: Long = 0L,
    val updatedAt: Long = 0L
)

data class CategoryCount(val categoryId: String, val cnt: Int)

@Dao
interface ContentDao {
    @Query("SELECT * FROM content WHERE (:kind = '' OR kind = :kind) AND kind NOT LIKE 'hidden:%' AND (:allCats = 1 OR category IN (:ids)) AND normalizedName LIKE :q ORDER BY (CASE WHEN :byName = 1 THEN 0 ELSE -CAST(contentId AS INTEGER) END), name COLLATE NOCASE LIMIT :limit OFFSET :offset")
    fun browse(kind: String, allCats: Int, ids: List<String>, q: String, byName: Int, limit: Int, offset: Int): List<ContentEntity>

    /** Fast path for ONE section (index on kind + idNum): newest first. */
    @Query("SELECT * FROM content WHERE kind = :kind AND (:allCats = 1 OR category IN (:ids)) AND normalizedName LIKE :q ORDER BY idNum DESC LIMIT :limit OFFSET :offset")
    fun browseNewest(kind: String, allCats: Int, ids: List<String>, q: String, limit: Int, offset: Int): List<ContentEntity>

    /** Fast path for ONE section, alphabetical (live channels). */
    @Query("SELECT * FROM content WHERE kind = :kind AND (:allCats = 1 OR category IN (:ids)) AND normalizedName LIKE :q ORDER BY name COLLATE NOCASE LIMIT :limit OFFSET :offset")
    fun browseByName(kind: String, allCats: Int, ids: List<String>, q: String, limit: Int, offset: Int): List<ContentEntity>

    @Query("SELECT COUNT(*) FROM content WHERE kind = :kind AND category IN (:ids)")
    fun countInCategories(kind: String, ids: List<String>): Int

    @Query("SELECT COUNT(*) FROM content WHERE (:kind = '' OR kind = :kind) AND kind NOT LIKE 'hidden:%' AND (:allCats = 1 OR category IN (:ids)) AND normalizedName LIKE :q")
    fun countBrowse(kind: String, allCats: Int, ids: List<String>, q: String): Int

    @Query("SELECT category AS categoryId, COUNT(*) AS cnt FROM content WHERE kind = :kind GROUP BY category")
    fun categoryCounts(kind: String): List<CategoryCount>

    @Query("SELECT * FROM (SELECT * FROM content WHERE kind = :kind AND imageUrl != '' ORDER BY idNum DESC LIMIT 2000) ORDER BY RANDOM() LIMIT :limit")
    fun randomWithImage(kind: String, limit: Int): List<ContentEntity>

    @Query("SELECT c.* FROM content c INNER JOIN user_marks m ON m.contentKind = c.kind AND m.contentId = c.contentId WHERE m.positionMs > 0 ORDER BY m.updatedAt DESC LIMIT :limit OFFSET :offset")
    fun continueWatching(limit: Int, offset: Int): List<ContentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertBatch(items: List<ContentEntity>)

    @Query("SELECT * FROM content WHERE kind = :kind ORDER BY name COLLATE NOCASE, contentId LIMIT :limit OFFSET :offset")
    fun page(kind: String, limit: Int, offset: Int): List<ContentEntity>

    @Query("SELECT * FROM content WHERE kind = :kind ORDER BY idNum DESC LIMIT :limit OFFSET :offset")
    fun latest(kind: String, limit: Int, offset: Int): List<ContentEntity>

    /** Trending = the best-rated titles among the most recently added ones that have artwork. */
    @Query("SELECT * FROM (SELECT * FROM content WHERE kind = :kind AND imageUrl LIKE 'http%' ORDER BY idNum DESC LIMIT 400) WHERE CAST(rating AS REAL) > 0 ORDER BY CAST(rating AS REAL) DESC, idNum DESC LIMIT :limit")
    fun trending(kind: String, limit: Int): List<ContentEntity>

    @Query("SELECT * FROM content WHERE kind = :kind AND (:categoryId = '' OR category = :categoryId) ORDER BY name COLLATE NOCASE, contentId LIMIT :limit OFFSET :offset")
    fun pageByCategory(kind: String, categoryId: String, limit: Int, offset: Int): List<ContentEntity>

    @Query("SELECT * FROM content WHERE kind = 'get_live_streams' AND (:categoryId = '' OR category = :categoryId) AND normalizedName LIKE :query ORDER BY name COLLATE NOCASE, contentId LIMIT :limit OFFSET :offset")
    fun searchChannels(categoryId: String, query: String, limit: Int, offset: Int): List<ContentEntity>

    @Query("SELECT * FROM content WHERE kind NOT LIKE 'hidden:%' AND normalizedName LIKE :query ORDER BY kind, name COLLATE NOCASE LIMIT :limit OFFSET :offset")
    fun searchPage(query: String, limit: Int, offset: Int): List<ContentEntity>

    @Query("SELECT c.* FROM content c INNER JOIN user_marks m ON m.contentKind = c.kind AND m.contentId = c.contentId WHERE m.favorite = 1 ORDER BY m.updatedAt DESC LIMIT :limit OFFSET :offset")
    fun favorites(limit: Int, offset: Int): List<ContentEntity>

    @Query("SELECT c.* FROM content c INNER JOIN user_marks m ON m.contentKind = c.kind AND m.contentId = c.contentId WHERE m.followed = 1 ORDER BY m.updatedAt DESC LIMIT :limit OFFSET :offset")
    fun followed(limit: Int, offset: Int): List<ContentEntity>

    @Query("SELECT COUNT(*) FROM content WHERE kind = :kind")
    fun count(kind: String): Int

    @Query("SELECT COUNT(*) FROM content WHERE kind NOT LIKE 'hidden:%' AND normalizedName LIKE :query")
    fun searchCount(query: String): Int

    @Query("DELETE FROM content WHERE kind = :kind")
    fun clearContent(kind: String)

    // ---- privacy lock: hidden rows live under the kind "hidden:<kind>" (see HiddenStore)
    @Query("UPDATE OR REPLACE content SET kind = :hiddenKind WHERE kind = :kind AND category = :categoryId")
    fun hideCategoryItems(kind: String, hiddenKind: String, categoryId: String)

    @Query("UPDATE OR REPLACE categories SET kind = :hiddenKind WHERE kind = :kind AND categoryId = :categoryId")
    fun hideCategoryRow(kind: String, hiddenKind: String, categoryId: String)

    @Query("UPDATE OR REPLACE content SET kind = :kind WHERE kind = :hiddenKind AND category = :categoryId AND contentId NOT IN (:keepHidden)")
    fun showCategoryItems(kind: String, hiddenKind: String, categoryId: String, keepHidden: List<String>)

    @Query("UPDATE OR REPLACE categories SET kind = :kind WHERE kind = :hiddenKind AND categoryId = :categoryId")
    fun showCategoryRow(kind: String, hiddenKind: String, categoryId: String)

    @Query("UPDATE OR REPLACE content SET kind = :hiddenKind WHERE kind = :kind AND contentId = :id")
    fun hideItem(kind: String, hiddenKind: String, id: String)

    @Query("UPDATE OR REPLACE content SET kind = :kind WHERE kind = :hiddenKind AND contentId = :id")
    fun showItem(kind: String, hiddenKind: String, id: String)

    @Query("UPDATE OR REPLACE content SET kind = substr(kind, 8) WHERE kind LIKE 'hidden:%'")
    fun showAllContent()

    @Query("UPDATE OR REPLACE categories SET kind = substr(kind, 8) WHERE kind LIKE 'hidden:%'")
    fun showAllCategories()

    @Query("DELETE FROM categories WHERE kind = :kind")
    fun clearCategories(kind: String)

    @Query("DELETE FROM content WHERE kind = :kind AND syncToken != :token")
    fun deleteStale(kind: String, token: String)

    @Query("SELECT * FROM content WHERE kind = :kind AND contentId IN (:ids)")
    fun findByIds(kind: String, ids: List<String>): List<ContentEntity>

    @Query("SELECT * FROM content WHERE kind = :kind AND contentId = :id LIMIT 1")
    fun getContent(kind: String, id: String): ContentEntity?

    @Query("SELECT * FROM content WHERE kind = :kind AND (:categoryId = '' OR category = :categoryId) ORDER BY name COLLATE NOCASE, contentId LIMIT 1")
    fun firstInCategory(kind: String, categoryId: String): ContentEntity?

    @Query("SELECT * FROM content WHERE kind = :kind AND (:categoryId = '' OR category = :categoryId) AND ((name COLLATE NOCASE > :name COLLATE NOCASE) OR (name = :name AND contentId > :id)) ORDER BY name COLLATE NOCASE, contentId LIMIT 1")
    fun nextInCategory(kind: String, categoryId: String, name: String, id: String): ContentEntity?

    @Query("SELECT * FROM content WHERE kind = :kind AND (:categoryId = '' OR category = :categoryId) AND ((name COLLATE NOCASE < :name COLLATE NOCASE) OR (name = :name AND contentId < :id)) ORDER BY name COLLATE NOCASE DESC, contentId DESC LIMIT 1")
    fun previousInCategory(kind: String, categoryId: String, name: String, id: String): ContentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertCategories(items: List<CategoryEntity>)

    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY categoryName COLLATE NOCASE, categoryId")
    fun categories(kind: String): List<CategoryEntity>

    @Query("DELETE FROM categories WHERE kind = :kind AND categoryId NOT IN (:ids)")
    fun deleteMissingCategories(kind: String, ids: List<String>)

    @Query("SELECT * FROM user_marks WHERE contentKind = :kind AND contentId = :id LIMIT 1")
    fun getMark(kind: String, id: String): UserMarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveMark(mark: UserMarkEntity)

    @Query("DELETE FROM user_marks WHERE contentKind = :kind AND contentId = :id")
    fun deleteMark(kind: String, id: String)
}

@Dao
interface EpisodeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertBatch(items: List<EpisodeEntity>)

    @Query("DELETE FROM episodes WHERE seriesId = :seriesId")
    fun clearSeries(seriesId: String)

    @Query("UPDATE episodes SET seriesId = :target WHERE seriesId = :source")
    fun renameSeries(source: String, target: String)

    @Query("SELECT DISTINCT seasonKey FROM episodes WHERE seriesId = :seriesId ORDER BY seasonKey")
    fun seasons(seriesId: String): List<String>

    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId AND seasonKey = :season ORDER BY CAST(episodeNumber AS INTEGER), title LIMIT :limit OFFSET :offset")
    fun page(seriesId: String, season: String, limit: Int, offset: Int): List<EpisodeEntity>
}

@Database(entities = [ContentEntity::class, EpisodeEntity::class, CategoryEntity::class, UserMarkEntity::class], version = 5, exportSchema = false)
abstract class ContentDatabase : RoomDatabase() {
    abstract fun contentDao(): ContentDao
    abstract fun episodeDao(): EpisodeDao

    companion object {
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS categories (kind TEXT NOT NULL, categoryId TEXT NOT NULL, categoryName TEXT NOT NULL, parentId TEXT NOT NULL, PRIMARY KEY(kind, categoryId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS user_marks (contentKind TEXT NOT NULL, contentId TEXT NOT NULL, favorite INTEGER NOT NULL DEFAULT 0, followed INTEGER NOT NULL DEFAULT 0, positionMs INTEGER NOT NULL DEFAULT 0, updatedAt INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(contentKind, contentId))")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE content ADD COLUMN rating TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE content ADD COLUMN idNum INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE content SET idNum = CAST(contentId AS INTEGER)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_content_kind_category ON content (kind, category)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_content_kind_idNum ON content (kind, idNum)")
            }
        }

        @Volatile private var instance: ContentDatabase? = null
        fun get(context: Context): ContentDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ContentDatabase::class.java, "alhorr_content.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .fallbackToDestructiveMigration(false)
                .build().also { instance = it }
        }
    }
}
