package com.raghu.vertx3guide.wiki.step3.database

import io.vertx.codegen.annotations.Fluent
import io.vertx.codegen.annotations.GenIgnore
import io.vertx.codegen.annotations.ProxyGen
import io.vertx.codegen.annotations.VertxGen
import io.vertx.core.AsyncResult
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.asyncsql.AsyncSQLClient


@ProxyGen
@VertxGen
interface WikiDatabaseService {
  @Fluent
  fun fetchAllPages(resultHandler: Handler<AsyncResult<JsonArray>>):WikiDatabaseService

  @Fluent
  fun fetchPage(name:String,resultHandler: Handler<AsyncResult<JsonObject>>):WikiDatabaseService

  @Fluent
  fun createPage(title:String, markdown:String, resultHandler: Handler<AsyncResult<Void>>):WikiDatabaseService

  @Fluent
  fun savePage(id:Int, markdown:String,resultHandler: Handler<AsyncResult<Void>>):WikiDatabaseService

  @Fluent
  fun deletePage(id:Int, resultHandler: Handler<AsyncResult<Void>>):WikiDatabaseService

  @GenIgnore
  companion object {
    @GenIgnore
    @JvmStatic
    fun create(dbClient:AsyncSQLClient, sqlQueries: HashMap<SqlQuery, String>, readyHandler: Handler<AsyncResult<WikiDatabaseService>>):WikiDatabaseService {
      return WikiDatabaseServiceImpl(dbClient, sqlQueries, readyHandler)

    }
    @GenIgnore
    @JvmStatic
    fun createProxy(vertx: Vertx, address:String ):WikiDatabaseService {
      return WikiDatabaseServiceVertxEBProxy(vertx, address)
    }
  }

}
