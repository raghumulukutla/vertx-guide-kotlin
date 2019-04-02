package com.raghu.vertx3guide.wiki.step2

import com.raghu.vertx3guide.wiki.step1.MainVerticle
import io.netty.util.internal.logging.InternalLogger
import io.netty.util.internal.logging.InternalLoggerFactory
import io.netty.util.internal.logging.Slf4JLoggerFactory
import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.asyncsql.AsyncSQLClient
import io.vertx.ext.asyncsql.MySQLClient
import io.vertx.ext.sql.SQLConnection
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import java.io.FileInputStream
import java.io.InputStream
import java.util.*
import java.util.stream.Collectors


class WikiDatabaseVerticle : AbstractVerticle() {

  private lateinit var mySQLClient: AsyncSQLClient

  private enum class SqlQuery {
    CREATE_PAGES_TABLE,
    ALL_PAGES,
    GET_PAGE,
    CREATE_PAGE,
    SAVE_PAGE,
    DELETE_PAGE
  }

  val CONFIG_WIKIDB_DB_NAME = "wikidb.dbname"
  val CONFIG_WIKIDB_DB_HOST = "wikidb.dbhost"
  val CONFIG_WIKIDB_DB_PORT = "wikidb.dbport"
  val CONFIG_WIKIDB_DB_USER = "wikidb.dbuser"
  val CONFIG_WIKIDB_DB_PWD = "wikidb.dbpwd"

  val CONFIG_WIKIDB_MYSQL_MAX_POOL_SIZE = "wikidb.jdbc.max_pool_size"
  val CONFIG_WIKIDB_SQL_QUERIES_RESOURCE_FILE = "wikidb.sqlqueries.resource.file"

  val CONFIG_WIKIDB_QUEUE = "wikidb.queue"

  private lateinit var log: InternalLogger

  private val sqlQueries = hashMapOf<SqlQuery, String>()

  private fun loadSqlQueries() {
    val queriesFile = config().getString(CONFIG_WIKIDB_SQL_QUERIES_RESOURCE_FILE)
    var queriesInputStream:InputStream
    if(!queriesFile.isNullOrEmpty()) {
      queriesInputStream = FileInputStream(queriesFile)
    }
    else {
      queriesInputStream = WikiDatabaseVerticle::class.java.getResourceAsStream("/db-queries.properties")
    }

    val queriesProps = Properties()
    queriesProps.load(queriesInputStream)
    queriesInputStream.close()

    sqlQueries[SqlQuery.CREATE_PAGES_TABLE] = queriesProps.getProperty("create-pages-table")
    sqlQueries[SqlQuery.ALL_PAGES] = queriesProps.getProperty("all-pages")
    sqlQueries[SqlQuery.GET_PAGE] = queriesProps.getProperty("get-page")
    sqlQueries[SqlQuery.CREATE_PAGE] = queriesProps.getProperty("create-page")
    sqlQueries[SqlQuery.SAVE_PAGE] = queriesProps.getProperty("save-page")
    sqlQueries[SqlQuery.DELETE_PAGE] = queriesProps.getProperty("delete-page")
  }


  override fun start(startFuture:Future<Void> ) {
    InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE)
    log = InternalLoggerFactory.getInstance(WikiDatabaseVerticle::class.qualifiedName)
    log.info("here!")
    loadSqlQueries()

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

    mySQLClient = MySQLClient.createShared(vertx, mySQLClientConfig)

    mySQLClient.getConnection{mysql_ar->
      if(mysql_ar.failed()) {
        log.error("Could not open a database connection", mysql_ar.cause())
        startFuture.fail(mysql_ar.cause())
      }
      else {
        val connection = mysql_ar.result()
        connection.execute(sqlQueries.get(SqlQuery.CREATE_PAGES_TABLE)) {create->
          if(create.succeeded()) {
            vertx.eventBus().consumer(config().getString(CONFIG_WIKIDB_QUEUE, "wikidb.queue")){receivedMessage:Message<Any> ->
              onMessage(receivedMessage as Message<JsonObject>)
            }
            startFuture.complete()

          }
          else {
            log.error("Database preparation error", create.cause())
            startFuture.fail(create.cause())
          }

        }
      }

    }

  }

  enum class ErrorCodes {
    NO_ACTION_SPECIFIED,
    BAD_ACTION,
    DB_ERROR
  }

  fun onMessage(message: Message<JsonObject>) {
    if(!message.headers().contains("action")) {
      log.error("No action header specified for message with headers {${message.headers()}}, {${message.body().encodePrettily()}}")
      message.fail(ErrorCodes.NO_ACTION_SPECIFIED.ordinal, "No action header specified")
      return
    }

    val action = message.headers().get("action")
    when(action) {
      "all-pages" -> fetchAllPages(message)
      "get-page" -> fetchPage(message)
      "create-page" -> createPage(message)
      "save-page" -> savePage(message)
      "delete-page" -> deletePage(message)
      else -> message.fail(ErrorCodes.BAD_ACTION.ordinal, "Bac action: $action")
    }
  }



//  private void fetchPage(Message<JsonObject> message) {
//    String requestedPage = message.body().getString("page");
//    JsonArray params = new JsonArray().add(requestedPage);
//
//    dbClient.queryWithParams(sqlQueries.get(SqlQuery.GET_PAGE), params, fetch -> {
//      if (fetch.succeeded()) {
//        JsonObject response = new JsonObject();
//        ResultSet resultSet = fetch.result();
//        if (resultSet.getNumRows() == 0) {
//          response.put("found", false);
//        } else {
//          response.put("found", true);
//          JsonArray row = resultSet.getResults().get(0);
//          response.put("id", row.getInteger(0));
//          response.put("rawContent", row.getString(1));
//        }
//        message.reply(response);
//      } else {
//        reportQueryError(message, fetch.cause());
//      }
//    });
//  }

//  private fun fetchAllPages(message: Message<JsonObject>) {
//    val requestedPage = message.body().getString("page")
//    val params = JsonArray().add(requestedPage)
//
//    mySQLClient.queryWithParams(sqlQueries.get(SqlQuery.GET_PAGE), params){fetch_all->
//      if(fetch_all.succeeded()) {
//        val response = JsonObject()
//        val resultSet = fetch_all.result()
//        if(resultSet.numRows == 0) {
//          response.put("found", false)
//        }
//        else {
//          response.put("found", true)
//          val row = resultSet.results.get(0)
//          response.put("id",row.getString(0))
//          response.put("rawContent", row.getString(1))
//        }
//        message.reply(response)
//      }
//      else {
//        reportQueryError(message, fetch_all.cause())
//      }
//    }
//  }

  private fun fetchAllPages(message: Message<JsonObject>) {
    log.info("fetchAllPages")
    mySQLClient.query(sqlQueries.get(SqlQuery.ALL_PAGES)) {res->
      if(res.succeeded()) {
        val pages = res.result()
          .results
          .stream()
          .map { json -> json.getString(0) }
          .sorted()
          .collect(Collectors.toList())
        message.reply(JsonObject().put("pages", JsonArray(pages)))
      }
      else {
        reportQueryError(message, res.cause())
      }
    }
  }




  private fun fetchPage(message: Message<JsonObject>) {
    val requestedPage = message.body().getString("page")
    val params = JsonArray().add(requestedPage)

    mySQLClient.queryWithParams(sqlQueries.get(SqlQuery.GET_PAGE), params) {fetch->
      if(fetch.succeeded()) {
        val response = JsonObject()
        val resulSet = fetch.result()
        if(resulSet.numRows == 0) {
          response.put("found", false)
        }
        else {
          response.put("found", true)
          val row = resulSet.results.get(0)
          response.put("id", row.getInteger(0))
          response.put("rawContent", row.getString(1))
        }
        message.reply(response)
      }
      else {
        reportQueryError(message, fetch.cause())
      }
    }
  }

  private fun savePage(message: Message<JsonObject>) {
    val request = message.body()
    val data = JsonArray()
      .add(request.getString("markdown"))
      .add(request.getString("id"))

    mySQLClient.updateWithParams(sqlQueries.get(SqlQuery.SAVE_PAGE), data) {save->
      if(save.succeeded()) {
        message.reply("ok")
      }
      else {
        reportQueryError(message, save.cause())
      }
    }

  }


  private fun createPage(message: Message<JsonObject>) {
    val request = message.body()
    log.info("create-page request" + request.encodePrettily())
    val data = JsonArray()
      .add("100")
      .add(request.getString("title"))
      .add(request.getString("markdown"))

    mySQLClient.updateWithParams(sqlQueries.get(SqlQuery.CREATE_PAGE), data) {create ->
      if(create.succeeded()) {
        message.reply("ok")
      }
      else {
        reportQueryError(message, create.cause())
      }
    }
  }

  private fun deletePage(message: Message<JsonObject>) {
    val data = JsonArray().add(message.body().getString("id"))

    mySQLClient.updateWithParams(sqlQueries.get(SqlQuery.DELETE_PAGE), data) {delete->
      if(delete.succeeded()) {
        message.reply("ok")
      }
      else {
        reportQueryError(message, delete.cause())
      }
    }
  }

  private fun reportQueryError(message: Message<JsonObject>, cause: Throwable) {
    log.error("Database query error")
    message.fail(ErrorCodes.DB_ERROR.ordinal, cause.message)
  }



}
