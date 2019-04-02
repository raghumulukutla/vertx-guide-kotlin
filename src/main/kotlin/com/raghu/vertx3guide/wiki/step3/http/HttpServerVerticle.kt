package com.raghu.vertx3guide.wiki.step3.http

import com.github.rjeschke.txtmark.Processor
import com.raghu.vertx3guide.wiki.step1.MainVerticle
import com.raghu.vertx3guide.wiki.step3.database.WikiDatabaseService
import examples.WebServerOptions
import io.netty.util.internal.logging.InternalLogger
import io.netty.util.internal.logging.InternalLoggerFactory
import io.netty.util.internal.logging.Slf4JLoggerFactory
import io.vertx.core.AbstractVerticle
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.templ.FreeMarkerTemplateEngine
import java.util.*

class HttpServerVerticle : AbstractVerticle() {
  lateinit var log: InternalLogger
  val CONFIG_HTTP_SERVER_PORT = "http.server.port"
  val CONFIG_WIKIDB_QUEUE = "wikidb.queue"
  var wikiDbQueue = "wikidb.queue"

  private var templateEngine: FreeMarkerTemplateEngine = FreeMarkerTemplateEngine.create()

  private val EMPTY_PAGE_MARKDOWN = "# A new page\n" +
  "\n" +
  "Feel-free to write in Markdown!\n"

  private lateinit var dbService:WikiDatabaseService


  override fun start(startFuture: Future<Void>?) {

    InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE)
    log = InternalLoggerFactory.getInstance(HttpServerVerticle::class.qualifiedName)

    val wikiDbQueue = config().getString(CONFIG_WIKIDB_QUEUE, "wikidb.queue")
    dbService = WikiDatabaseService.createProxy(vertx, wikiDbQueue)

    val server = vertx.createHttpServer()

    val router = Router.router(vertx)
    router.get("/").handler(this::indexHandler)
    router.get("/wiki/:page").handler(this::pageRenderingHandler)
    router.post().handler(BodyHandler.create())
    router.post("/save").handler(this::pageUpdateHandler)
    router.post("/create").handler(this::pageCreateHandler)
    router.post("/delete").handler(this::pageDeleteionHandler)

    val portNumber = config().getInteger(CONFIG_HTTP_SERVER_PORT, 8080)
    server
      .requestHandler(router::accept)
      .listen(portNumber) {
        if(it.succeeded()) {
          log.info("HTTP server running on port $portNumber")
          startFuture!!.complete()
        }
        else {
          log.error("Could not start a HTTP server", it.cause())
          startFuture!!.fail(it.cause())
        }
      }


  }

  private fun indexHandler(routingContext: RoutingContext) {
    val handler:Handler<AsyncResult<JsonObject>> = Handler { reply->
      if(reply.succeeded()) {
        routingContext.put("title", "Wiki home")
        routingContext.put("pages", reply.result().toList())
        templateEngine.render(routingContext, "templates", "/index.ftl") {index->
          if(index.succeeded()) {
            routingContext.response().putHeader("Content-Type", "text/html")
            routingContext.response().end(index.result())
          }
          else {
            routingContext.fail(index.cause())
          }
        }
      }
      else {
        routingContext.fail(reply.cause())
      }
    }
  }

  private fun pageRenderingHandler(routingContext: RoutingContext) {
    val requestedPage = routingContext.request().getParam("page")
    val handler:Handler<AsyncResult<JsonObject>> = Handler {reply->
      if(reply.succeeded()) {
        val payLoad = reply.result()
        val found:Boolean = payLoad.getBoolean("found")
        val rawContent = payLoad.getString("rawContent", EMPTY_PAGE_MARKDOWN)
        routingContext.put("title", requestedPage)
        routingContext.put("id", payLoad.getInteger("id", -1))
        routingContext.put("newPage", if(found) "no" else "yes")
        routingContext.put("rawContent", rawContent)
        routingContext.put("content", Processor.process(rawContent))
        routingContext.put("timestamp", Date().toString())
        templateEngine.render(routingContext, "templates", "/page.flt"){
          if(it.succeeded()) {
            routingContext.response().putHeader("Content-Type", "text/html")
            routingContext.response().end(it.result())
          }

          else {
            routingContext.fail(it.cause())
          }
        }
      }
      else {
        routingContext.fail(reply.cause())
      }
    }
    dbService.fetchPage(requestedPage, handler)
  }

  private fun pageUpdateHandler(routingContext: RoutingContext) {
    val title = routingContext.request().getParam("title")

    val handler:Handler<AsyncResult<Void>> = Handler { reply->
      if(reply.succeeded()) {
        routingContext.response().setStatusCode(303)
        routingContext.response().putHeader("Location", "/wiki/" + title)
        routingContext.response().end()
      }
      else {
        routingContext.fail(reply.cause())
      }
    }

    val markdown = routingContext.request().getParam("markdown")
    if("yes".equals(routingContext.request().getParam("newPage"))) {
      dbService.createPage(title, markdown, handler)
    }
    else {
      dbService.savePage(Integer.valueOf(routingContext.request().getParam("id")), markdown, handler)
    }
  }

  private fun pageCreateHandler(routingContext: RoutingContext) {
    val pageName = routingContext.request().getParam("name")
    var location = "/wiki/$pageName"
    if(pageName.isNullOrEmpty()) {
      location = "/"
    }
    routingContext.response().setStatusCode(303)
    routingContext.response().putHeader("Location", location)
    routingContext.response().end()
  }

  private fun pageDeleteionHandler(routingContext: RoutingContext) {
    val handler:Handler<AsyncResult<Void>> = Handler { reply->
      if(reply.succeeded()) {
        routingContext.response().setStatusCode(303)
        routingContext.response().putHeader("Location", "/")
        routingContext.response().end()
      }
      else {
        routingContext.fail(reply.cause())
      }
    }
    dbService.deletePage(Integer.valueOf(routingContext.request().getParam("id")), handler)
  }

}
