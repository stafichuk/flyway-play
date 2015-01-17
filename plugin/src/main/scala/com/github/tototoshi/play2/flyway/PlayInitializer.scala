/*
 * Copyright 2013 Toshiyuki Takahashi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.tototoshi.play2.flyway

import play.api._
import play.api.mvc._
import play.api.mvc.Results._
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationInfo
import play.core._
import java.io.FileNotFoundException
import org.flywaydb.core.internal.util.jdbc.DriverDataSource
import scala.collection.JavaConverters._
import javax.inject._
import play.api.inject._

@Singleton
class PlayInitializer @Inject() (implicit app: Application)
    extends HandleWebCommandSupport
    with FileUtils {

  private val configReader = new ConfigReader(app)

  private val allDatabaseNames = configReader.getDatabaseConfigurations.keys

  private val flywayPrefixToMigrationScript = "db/migration"

  private def migrationFileDirectoryExists(path: String): Boolean = {
    app.resource(path) match {
      case Some(r) => {
        Logger.debug(s"Directory for migration files found. ${path}")
        true
      }
      case None => {
        Logger.warn(s"Directory for migration files not found. ${path}")
        false
      }
    }
  }

  private lazy val flyways: Map[String, Flyway] = {
    for {
      (dbName, configuration) <- configReader.getDatabaseConfigurations
      migrationFilesLocation = s"db/migration/${dbName}"
      if migrationFileDirectoryExists(migrationFilesLocation)
    } yield {
      val flyway = new Flyway
      val database = configuration.database
      flyway.setDataSource(new DriverDataSource(getClass.getClassLoader, database.driver, database.url, database.user, database.password))
      flyway.setLocations(migrationFilesLocation)
      flyway.setValidateOnMigrate(configuration.validateOnMigrate)
      flyway.setEncoding(configuration.encoding)
      if (configuration.initOnMigrate) {
        flyway.setBaselineOnMigrate(true)
      }
      for (prefix <- configuration.placeholderPrefix) {
        flyway.setPlaceholderPrefix(prefix)
      }
      for (suffix <- configuration.placeholderSuffix) {
        flyway.setPlaceholderSuffix(suffix)
      }
      flyway.setPlaceholders(configuration.placeholders.asJava)

      dbName -> flyway
    }
  }

  private def migrationDescriptionToShow(dbName: String, migration: MigrationInfo): String = {
    app.resourceAsStream(s"${flywayPrefixToMigrationScript}/${dbName}/${migration.getScript}").map { in =>
      s"""|--- ${migration.getScript} ---
          |${readInputStreamToString(in)}""".stripMargin
    }.orElse {
      import scala.util.control.Exception._
      allCatch opt { Class.forName(migration.getScript) } map { cls =>
        s"""|--- ${migration.getScript} ---
            | (Java-based migration)""".stripMargin
      }
    }.getOrElse(throw new FileNotFoundException(s"Migration file not found. ${migration.getScript}"))
  }

  private def checkState(dbName: String): Unit = {
    flyways.get(dbName).foreach { flyway =>
      val pendingMigrations = flyway.info().pending
      if (!pendingMigrations.isEmpty) {
        throw InvalidDatabaseRevision(
          dbName,
          pendingMigrations.map(migration => migrationDescriptionToShow(dbName, migration)).mkString("\n"))
      }
    }
  }

  def onStart(): Unit = {
    for (dbName <- allDatabaseNames) {
      if (Play.isTest || app.configuration.getBoolean(s"db.${dbName}.migration.auto").getOrElse(false)) {
        migrateAutomatically(dbName)
      } else {
        checkState(dbName)
      }
    }
  }

  private def migrateAutomatically(dbName: String): Unit = {
    flyways.get(dbName).foreach { flyway =>
      flyway.migrate()
    }
  }

  override def handleWebCommand(request: RequestHeader, sbtLink: BuildLink, path: java.io.File): Option[Result] = {
    val webCommand = new FlywayWebCommand(app, flywayPrefixToMigrationScript, flyways)
    webCommand.handleWebCommand(request: RequestHeader, sbtLink: BuildLink, path: java.io.File)
  }

  onStart()
}
