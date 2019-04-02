package com.raghu.vertx3guide.wiki.step3.database

import io.netty.util.internal.logging.InternalLogger
import io.vertx.core.AbstractVerticle
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.ext.asyncsql.MySQLClient
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.serviceproxy.ServiceBinder
import java.io.FileInputStream
import java.io.InputStream
import java.util.*

class WikiDatabaseVerticle:AbstractVerticle() {

  val CONFIG_WIKIDB_DB_NAME = "wikidb.dbname"
  val CONFIG_WIKIDB_DB_HOST = "wikidb.dbhost"
  val CONFIG_WIKIDB_DB_PORT = "wikidb.dbport"
  val CONFIG_WIKIDB_DB_USER = "wikidb.dbuser"
  val CONFIG_WIKIDB_DB_PWD = "wikidb.dbpwd"

  val CONFIG_WIKIDB_MYSQL_MAX_POOL_SIZE = "wikidb.jdbc.max_pool_size"
  val CONFIG_WIKIDB_SQL_QUERIES_RESOURCE_FILE = "wikidb.sqlqueries.resource.file"

  val CONFIG_WIKIDB_QUEUE = "wikidb.queue"

  private lateinit var log: InternalLogger



  override fun start(startFuture: Future<Void>?) {

    val sqlQueries = loadSqlQueries()

    val mySQLClientConfig =   json {
      obj(
        "host" to config().getString(CONFIG_WIKIDB_DB_HOST, "localhost"),
        "port" to config().getInteger(CONFIG_WIKIDB_DB_PORT, 3306),
        "maxPoolSize" to config().getInteger(CONFIG_WIKIDB_MYSQL_MAX_POOL_SIZE, 30),
        "username" to config().getString(CONFIG_WIKIDB_DB_USER, "vertx"),
        "password" to config().getString(CONFIG_WIKIDB_DB_PWD, "vertx123"),
        "database" to config().getString(CONFIG_WIKIDB_DB_NAME, "wikistarter")
      )
    }

    val dbClient = MySQLClient.createShared(vertx, mySQLClientConfig)


    val handler:Handler<AsyncResult<WikiDatabaseService>> = Handler {
      if(it.succeeded()) {
        val binder = ServiceBinder(vertx)
        binder
          .setAddress(CONFIG_WIKIDB_QUEUE)
          .register(WikiDatabaseService::class.java, it.result())
        startFuture!!.complete()
      } else {
      startFuture!!.fail(it.cause());
    }
    }


    WikiDatabaseService.create(dbClient, sqlQueries, handler)



  }


  private fun loadSqlQueries(): HashMap<SqlQuery, String> {
    val queriesFile = config().getString(CONFIG_WIKIDB_SQL_QUERIES_RESOURCE_FILE)
    var queriesInputStream: InputStream
    if(!queriesFile.isNullOrEmpty()) {
      queriesInputStream = FileInputStream(queriesFile)
    }
    else {
      queriesInputStream = WikiDatabaseVerticle::class.java.getResourceAsStream("/db-queries.properties")
    }

    val queriesProps = Properties()
    queriesProps.load(queriesInputStream)
    queriesInputStream.close()
    val sqlQueries = hashMapOf<SqlQuery, String>()
    sqlQueries[SqlQuery.CREATE_PAGES_TABLE] = queriesProps.getProperty("create-pages-table")
    sqlQueries[SqlQuery.ALL_PAGES] = queriesProps.getProperty("all-pages")
    sqlQueries[SqlQuery.GET_PAGE] = queriesProps.getProperty("get-page")
    sqlQueries[SqlQuery.CREATE_PAGE] = queriesProps.getProperty("create-page")
    sqlQueries[SqlQuery.SAVE_PAGE] = queriesProps.getProperty("save-page")
    sqlQueries[SqlQuery.DELETE_PAGE] = queriesProps.getProperty("delete-page")
    return sqlQueries
  }

}
