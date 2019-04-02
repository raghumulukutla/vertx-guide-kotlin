package com.raghu.vertx3guide.wiki.step3.database

import com.raghu.vertx3guide.wiki.step1.MainVerticle
import io.netty.util.internal.logging.InternalLogger
import io.netty.util.internal.logging.InternalLoggerFactory
import io.netty.util.internal.logging.Slf4JLoggerFactory
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.asyncsql.AsyncSQLClient
import io.vertx.ext.asyncsql.MySQLClient
import java.util.stream.Collectors

class WikiDatabaseServiceImpl(
  dClient: AsyncSQLClient,
  sQueries: HashMap<SqlQuery, String>,
  rHandler: Handler<AsyncResult<WikiDatabaseService>>
) :WikiDatabaseService {

  private var log: InternalLogger

  private var dbClient:AsyncSQLClient
  private var readyHandler:Handler<AsyncResult<WikiDatabaseService>>
  private var sqlQueries: HashMap<SqlQuery, String>


  init {
    InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE)
    log = InternalLoggerFactory.getInstance(MainVerticle::class.qualifiedName)
    this.dbClient = dClient
    this.readyHandler = rHandler
    this.sqlQueries = sQueries
    dbClient.getConnection {
      if(it.failed()) {
        log.error("Could not open a database connection ${it.cause()}")
        this.readyHandler.handle(Future.failedFuture(it.cause()))
      }
      else {
        val connection = it.result()
        connection.execute(sqlQueries.get(SqlQuery.CREATE_PAGES_TABLE)) {create->
          connection.close()
          if(create.failed()) {
            log.error("Database preparation error", create.cause())
            this.readyHandler.handle(Future.failedFuture(create.cause()))
          }
          else {
            this.readyHandler.handle(Future.succeededFuture(this))
          }
        }
      }
    }
  }



  override fun fetchAllPages(resultHandler: Handler<AsyncResult<JsonArray>>): WikiDatabaseService {
    log.info("fetchAllPages")
    dbClient.query(sqlQueries.get(SqlQuery.ALL_PAGES)) {res->
      if(res.succeeded()) {
        val pages = res.result()
          .results
          .stream()
          .map { json -> json.getString(0) }
          .sorted()
          .collect(Collectors.toList())

        resultHandler.handle(Future.succeededFuture(JsonArray(pages)))

      }
      else {
        log.error("Database query error", res.cause())
        resultHandler.handle(Future.failedFuture(res.cause()))
      }
    }
    return this
  }

  override fun fetchPage(name: String, resultHandler: Handler<AsyncResult<JsonObject>>): WikiDatabaseService {

    dbClient.queryWithParams(sqlQueries.get(SqlQuery.GET_PAGE), JsonArray().add(name)) {fetch->
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
        resultHandler.handle(Future.succeededFuture(response))
      }
      else {
        log.error("Database query error", fetch.cause())
        resultHandler.handle(Future.failedFuture(fetch.cause()))
      }
    }
    return this
  }

  override fun createPage(
    title: String,
    markdown: String,
    resultHandler: Handler<AsyncResult<Void>>
  ): WikiDatabaseService {
    val data = JsonArray()
      .add("100")
      .add(title)
      .add(markdown)

    dbClient.updateWithParams(sqlQueries.get(SqlQuery.CREATE_PAGE), data) { create ->
      if(create.succeeded()) {
        resultHandler.handle(Future.succeededFuture())
      }
      else {
        resultHandler.handle(Future.failedFuture(create.cause()))
      }
    }
    return this
  }

  override fun savePage(
    id: Int,
    markdown: String,
    resultHandler: Handler<AsyncResult<Void>>
  ): WikiDatabaseService {
    val data = JsonArray().add(markdown).add(id)

    dbClient.updateWithParams(sqlQueries.get(SqlQuery.SAVE_PAGE), data) {save->
      if(save.succeeded()) {
        resultHandler.handle(Future.succeededFuture())
      }
      else {
        log.error("Database query error", save.cause())
        resultHandler.handle(Future.failedFuture(save.cause()))
      }
    }


    return this
  }

  override fun deletePage(id: Int, resultHandler: Handler<AsyncResult<Void>>): WikiDatabaseService {
    val data = JsonArray().add(id)

    dbClient.updateWithParams(sqlQueries.get(SqlQuery.DELETE_PAGE), data) {delete->
      if(delete.succeeded()) {
        resultHandler.handle(Future.succeededFuture())
      }
      else {
        resultHandler.handle(Future.failedFuture(delete.cause()))
      }
    }
    return this
  }
}
