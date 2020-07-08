package org.gotson.komga.infrastructure.datasource

import mu.KotlinLogging
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.support.JdbcUtils
import org.springframework.jms.config.JmsListenerEndpointRegistry
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Types
import javax.annotation.PostConstruct
import javax.sql.DataSource
import kotlin.time.measureTime

private val logger = KotlinLogging.logger {}

@Component
@Profile("!test")
class DatabaseMigration(
  @Qualifier("h2DataSource") private val h2DataSource: DataSource,
  @Qualifier("sqliteDataSource") private val sqliteDataSource: DataSource,
  private val jmsListenerEndpointRegistry: JmsListenerEndpointRegistry,
  @Value("\${spring.datasource.url}") private val h2Url: String,
  private val userRepository: KomgaUserRepository,
  @Suppress("unused") private val flywayInitializer: FlywayMigrationInitializer // ensures the SQLite database is properly initialized
) {

  // tables in order of creation, to ensure there is no missing foreign key
  private val tables = listOf(
    "LIBRARY",
    "USER",
    "USER_LIBRARY_SHARING",
    "SERIES",
    "SERIES_METADATA",
    "BOOK",
    "MEDIA",
    "MEDIA_PAGE",
    "MEDIA_FILE",
    "BOOK_METADATA",
    "BOOK_METADATA_AUTHOR",
    "READ_PROGRESS",
    "COLLECTION",
    "COLLECTION_SERIES"
  )

  @PostConstruct
  fun h2ToSqliteMigration() {
    logger.info { "Initiating database migration from H2 to SQLite" }

    logger.info { "H2 url: $h2Url" }
    var h2Filename = extractH2Path(h2Url)
    if (h2Filename == null) {
      logger.warn { "The H2 URL ($h2Url) does not refer to a file database, aborting migration" }
      return
    }
    h2Filename += ".mv.db"

    logger.info { "H2 database file: $h2Filename" }

    val h2Path = Paths.get(h2Filename)
    val h2MigratedFile = Paths.get("$h2Filename.migrated")

    if (Files.exists(h2MigratedFile)) {
      logger.info { "The H2 database has already been migrated, aborting migration" }
      return
    }

    if (!Files.exists(h2Path)) {
      logger.warn { "The H2 database file does not exists: $h2Path, aborting migration" }
      return
    }

    if (userRepository.count() != 0L) {
      logger.warn { "The SQLite database already contains data, aborting migration" }
      return
    }


    logger.info { "Stopping all JMS listeners" }
    jmsListenerEndpointRegistry.stop()

    try {
      measureTime {
        performMigration()
      }.also { logger.info { "Migration performed in $it" } }
    } catch (e: Exception) {
      logger.error(e) { "Error while trying to migrate from H2 to Sqlite" }
    }

    logger.info { "Creating H2 migrated file: $h2MigratedFile" }
    Files.createFile(h2MigratedFile)

    logger.info { "Starting all JMS listeners" }
    jmsListenerEndpointRegistry.start()

    logger.info { "Migration finished" }
  }

  private fun performMigration() {
    logger.info { "Migrating H2 database to the latest migration" }
    Flyway(FluentConfiguration()
      .dataSource(h2DataSource)
      .locations("classpath:db/migration/h2")
    ).migrate()

    val maxBatchSize = 500
    tables.forEach { table ->
      val sourceConnection = h2DataSource.connection
      val destinationConnection = sqliteDataSource.connection
      lateinit var resultSet: ResultSet
      lateinit var selectStatement: PreparedStatement
      lateinit var insertStatement: PreparedStatement

      try {
        logger.info { "Migrate table: $table" }
        selectStatement = sourceConnection.prepareStatement("select * from $table")
        resultSet = selectStatement.executeQuery()
        insertStatement = destinationConnection.prepareStatement(createInsert(resultSet.metaData, table))

        var batchSize = 0
        while (resultSet.next()) {
          (1..resultSet.metaData.columnCount).forEach { i ->
            if (resultSet.metaData.getColumnType(i) == Types.BLOB) {
              val blob = resultSet.getBlob(i)
              val byteArray = blob.binaryStream.readBytes()
              insertStatement.setObject(i, byteArray)
            } else
              insertStatement.setObject(i, resultSet.getObject(i))
          }
          insertStatement.addBatch()
          batchSize++

          if (batchSize >= maxBatchSize) {
            insertStatement.executeBatch()
            batchSize = 0
          }
        }
        insertStatement.executeBatch()
      } finally {
        JdbcUtils.closeResultSet(resultSet)
        JdbcUtils.closeStatement(selectStatement)
        JdbcUtils.closeStatement(insertStatement)
        JdbcUtils.closeConnection(sourceConnection)
        JdbcUtils.closeConnection(destinationConnection)
      }
    }
  }

  private fun createInsert(metadata: ResultSetMetaData, table: String): String {
    val columns = (1..metadata.columnCount).map { metadata.getColumnName(it) }
    val quids = MutableList(columns.size) { "?" }

    return "insert into $table (${columns.joinToString()}) values (${quids.joinToString()})"
  }

}

val excludeH2Url = listOf(":mem:", ":ssl:", ":tcp:", ":zip:")

fun extractH2Path(url: String): String? {
  if (excludeH2Url.any { url.contains(it, ignoreCase = true) }) return null
  return url.split(":").last().split(";").first()
}
