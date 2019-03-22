package com.raghu.vertx3guide.wiki.step1

import com.github.mauricio.async.db.util.Log
import com.github.rjeschke.txtmark.Processor
import io.netty.util.internal.logging.InternalLogger
import io.netty.util.internal.logging.InternalLoggerFactory
import io.netty.util.internal.logging.Slf4JLoggerFactory
import io.vertx.core.AbstractVerticle
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Future.future
import io.vertx.core.http.HttpServer
import io.vertx.core.json.JsonArray
import io.vertx.ext.asyncsql.AsyncSQLClient
import io.vertx.ext.asyncsql.MySQLClient
import io.vertx.ext.sql.SQLConnection
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.templ.freemarker.FreeMarkerTemplateEngine
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import java.util.*
import java.util.stream.Collectors


class MainVerticle : AbstractVerticle() {

  private lateinit var log: InternalLogger ;
  val SQL_CREATE_PAGES_TABLE :String= "create table if not exists Pages (Id integer primary key, Name varchar(255) unique, Content mediumtext)"
  val SQL_GET_PAGE : String = "select Id, Content from Pages where Name = ?"
  val SQL_CREATE_PAGE : String= "insert into Pages values (NULL, ?, ?)"
  val SQL_SAVE_PAGE: String = "update Pages set Content = ? where Id = ?"
  val SQL_ALL_PAGES: String = "select Name from Pages"
  val SQL_DELETE_PAGE: String = "delete from Pages where Id = ?"

  private lateinit var mySQLClient: AsyncSQLClient
  private lateinit var templateEngine: FreeMarkerTemplateEngine
  /**
   * start maybe declared
   * 1) With a future - more fine-grained checks on operations within start. We need to call startFuture.fail or
   *                    startFuture.complete. Asynchronous programming demands for these sorts of checks
   * 2) Without a future - no checks on operations. Success is always assumed if an exception is not thrown
   */
  override fun start(startFuture: Future<Void>) {
    InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE)
    log = InternalLoggerFactory.getInstance(MainVerticle::class.qualifiedName)
//    vertx
//      .createHttpServer()
//      .requestHandler { req ->
//        req.response()
//          .putHeader("content-type", "text/plain")
//          .end("Hello from Vert.x!")
//      }
//      .listen(8888) { http ->
//        if (http.succeeded()) {
//          startFuture.complete()
//          println("HTTP server started on port 8888")
//        } else {
//          startFuture.fail(http.cause())
//        }
//      }




    // in vertx3, operations are non-blocking. So, we'll need to use futures as a signal
    // of completion together with compose to execute tasks sequentially.

    templateEngine = FreeMarkerTemplateEngine.create(vertx)

    val steps = prepareDatabase().compose{startHttpServer()}
    steps.setHandler(startFuture.completer())

  }


  /**
   * dividing phases into Kotlin functions
   */

  private fun prepareDatabase():Future<Void> {
    val future:Future<Void> = future()
    val mySQLClientConfig = json {
      obj("host" to "localhost",
        "port" to 3306,
        "maxPoolSize" to 30,
        "username" to "vertx",
        "password" to "vertx123",
        "database" to "wikistarter"
      )
    }
    mySQLClient = MySQLClient.createShared(vertx, mySQLClientConfig)

    mySQLClient.getConnection{ get_connection: AsyncResult<SQLConnection>? ->
      if(get_connection!!.succeeded()) {
        log.info("MySQL client connection succeeded")
        val connection:SQLConnection = get_connection.result()
        connection.execute(SQL_CREATE_PAGES_TABLE) {create ->
          connection.close()
          if(create.failed()) {
            log.error("Database preparation error", create.cause())
            future.fail(create.cause())
          }
          else {
            future.complete()
          }
        }
      }
      else{
        log.info("MySQL client connection failed - could not open a database connection")
        future.fail(get_connection.cause())
      }
    }

    return future

  }

  private fun startHttpServer():Future<Void> {
    val future:Future<Void> = future()
    val server:HttpServer = vertx.createHttpServer()

    val router = Router.router(vertx)
    router.get("/").handler{index_rc -> indexHandler(index_rc)}
    router.get("/wiki/:page").handler{index_rc -> pageRenderingHandler(index_rc)}
    router.post().handler(BodyHandler.create())
    router.post("/save").handler{save_rc -> pageUpdateHandler(save_rc)}
    router.post("/create").handler{create_rc -> pageCreateHandler(create_rc)}
    router.post("/delete").handler{delete_rc -> pageDeleteHandler(delete_rc)}

    server
      .requestHandler(router)
      .listen(8080) {
        if(it.succeeded()) {
          log.info("HTTP server running on port 8080")
          future.complete()
        }
        else {
          log.info("Could not start HTTP server", it.cause())
          future.fail(it.cause())
        }
      }

    return future
  }

  private fun indexHandler(routingContext: RoutingContext):Future<Void> {
    val future:Future<Void> = future()
    mySQLClient.getConnection{mysql_ar ->
      if(mysql_ar.succeeded()) {
        val connection:SQLConnection = mysql_ar.result()
        connection.query(SQL_ALL_PAGES){ sql_all ->
          if(sql_all.succeeded()) {
            val pages:List<String> = sql_all.result()
              .results.stream().map { json -> json.getString(0) }.sorted().collect(Collectors.toList())
            log.info(""+pages)
            routingContext.put("title", "WikiHome")
              .put("pages", pages)

            templateEngine.render(routingContext.data(), "templates/index.ftl") {index_template ->
              if(index_template.succeeded()) {
                routingContext.response().putHeader("Content-Type", "text/html")
                val buffer = index_template.result().getString(0, index_template.result().length())
                routingContext.response().end(buffer)
              }
              else {
                routingContext.fail(index_template.cause())
                log.info("failed - index_template")

              }
            }
          }
          else {
            routingContext.fail(sql_all.cause())
            log.info("failed - sql_all")
          }

        }
      }
      else {
        routingContext.fail(mysql_ar.cause())
        log.info("failed - mysql_ar")
      }
    }
    return future
  }

  private fun pageRenderingHandler(routingContext: RoutingContext):Future<Void> {
    val future:Future<Void> = future()

    val page = routingContext.request().getParam("page")

    val EMPTY_PAGE_MARKDOWN =
      "# A new page\n" +
        "\n" +
        "Feel-free to write in Markdown!\n"


    mySQLClient.getConnection {mysql_ar->
      if(mysql_ar.succeeded()) {
        val connection:SQLConnection = mysql_ar.result()
        connection.queryWithParams(SQL_GET_PAGE, JsonArray().add(page)) { fetch_ar ->
          if(fetch_ar.succeeded()) {
            val row = fetch_ar.result().results
              .stream()
              .findFirst()
              .orElseGet {JsonArray().add(-1).add(EMPTY_PAGE_MARKDOWN)}

            val id = row.getString(1)
            val rawContent = row.getString(1)

            routingContext.put("title", page)
            routingContext.put("id", id)
            routingContext.put("newPage",  if(fetch_ar.result().results.size == 0) "yes" else "no")
            routingContext.put("rawContent", rawContent)
            routingContext.put("content", Processor.process(rawContent))
            routingContext.put("timestamp", Date().toString())


            templateEngine.render(routingContext.data(), "templates/page.ftl") { page_ar->

              if(page_ar.succeeded()) {
                routingContext.response().putHeader("Content-Type", "text/html")
                routingContext.response().end(page_ar.result())
              }
              else {
                routingContext.fail(page_ar.cause())
                log.info("failed!")
              }

            }


          }
          else {
            routingContext.fail(fetch_ar.cause())
            log.info("failed!")
          }

        }
      }
      else {

      }
    }

    return future
  }

  private fun pageCreateHandler(routingContext: RoutingContext):Future<Void> {
    val future:Future<Void> = Future.future()
    val pageName = routingContext.request().getParam("name")
    var location = "/wiki/$pageName"
    if((pageName==null) || pageName.isEmpty()) {
      location = "/"
    }
    routingContext.response().statusCode = 303
    routingContext.response().putHeader("Location", location)
    routingContext.response().end()

    return future
  }

  private fun pageUpdateHandler(routingContext: RoutingContext):Future<Void> {
    val future:Future<Void> = Future.future()
    val id = routingContext.request().getParam("id")
    val title = routingContext.request().getParam("title")
    val markdown = routingContext.request().getParam("markdown")
    val newPage = "yes" == routingContext.request().getParam("newPage")


    mySQLClient.getConnection{ mysql_ar->
      if(mysql_ar.succeeded()) {
        val connection = mysql_ar.result()
        val sql = if(newPage) SQL_CREATE_PAGE else SQL_SAVE_PAGE
        val params = JsonArray()
        if(newPage) {

          params.add(title).add(markdown)
        }
        else {
          params.add(markdown).add(id)
        }

        connection.updateWithParams(sql, params) { update_ar ->
          connection.close()
          if(update_ar.succeeded()) {
            routingContext.response().statusCode = 303
            routingContext.response().putHeader("Location", "/wiki/$title")
            routingContext.response().end()
          }
          else {
            routingContext.fail(update_ar.cause())
          }

        }
      }
      else {
        routingContext.fail(mysql_ar.cause())
      }


    }


    return future
  }

  private fun pageDeleteHandler(routingContext: RoutingContext):Future<Void> {
    val future:Future<Void> = Future.future()
    val id = routingContext.request().getParam("id")
    mySQLClient.getConnection{page_ar->
      if(page_ar.succeeded()) {
        val connection = page_ar.result()
        connection.updateWithParams(SQL_DELETE_PAGE, JsonArray().add(id)) {updatep_ar->
          connection.close()
          if(updatep_ar.succeeded()) {
            routingContext.response().statusCode = 303
            routingContext.response().putHeader("Location", "/")
            routingContext.response().end()
          }
          else {
            routingContext.fail(updatep_ar.cause())
          }
        }
      }
      else {
        routingContext.fail(page_ar.cause())
      }
    }

    return future
  }

}
