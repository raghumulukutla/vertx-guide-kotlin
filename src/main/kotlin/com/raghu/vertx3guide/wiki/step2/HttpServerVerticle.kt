package com.raghu.vertx3guide.wiki.step2

import com.github.rjeschke.txtmark.Processor
import com.raghu.vertx3guide.wiki.step1.MainVerticle
import io.netty.util.internal.logging.InternalLogger
import io.netty.util.internal.logging.InternalLoggerFactory
import io.netty.util.internal.logging.Slf4JLoggerFactory
import io.vertx.core.AbstractVerticle
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.eventbus.DeliveryOptions
import io.vertx.core.eventbus.Message
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

  private lateinit var templateEngine: FreeMarkerTemplateEngine

  override fun start(startFuture: Future<Void>) {
    InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE)
    log = InternalLoggerFactory.getInstance(MainVerticle::class.qualifiedName)
    wikiDbQueue = config().getString(CONFIG_WIKIDB_QUEUE, "wikidb.queue")
    val server = vertx.createHttpServer()


    val router = Router.router(vertx)
    router.get("/").handler{index_rc -> indexHandler(index_rc)}
    router.get("/wiki/:page").handler{page -> pageRenderingHandler(page)}
    router.post().handler(BodyHandler.create())
    router.post("/save").handler{save_rc -> pageUpdateHandler(save_rc)}
    router.post("/create").handler{create_rc -> pageCreateHandler(create_rc)}
    router.post("/delete").handler{delete_rc -> pageDeleteHandler(delete_rc)}
    val portNumber = config().getInteger(CONFIG_HTTP_SERVER_PORT, 8080)
    server.requestHandler(router::accept)
      .listen(portNumber) {
        if(it.succeeded()) {
          log.info("HTTP server is running on port: $portNumber")
          startFuture.complete()
        }
        else {
          log.info("Could not start HTTP server: ${it.cause()}")
          startFuture.fail(it.cause())
        }
      }

    templateEngine = FreeMarkerTemplateEngine.create()
  }

  private fun indexHandler(routingContext: RoutingContext) {
    val options = DeliveryOptions().addHeader("action", "all-pages")
    vertx.eventBus().send(wikiDbQueue, JsonObject(), options) {reply:AsyncResult<Message<Any>>->
      if(reply.succeeded()) {
        val body:JsonObject = reply.result().body() as JsonObject
        routingContext.put("title", "Wiki Home")
        routingContext.put("pages", body.getJsonArray("pages").list)
        templateEngine.render(routingContext, "templates/index.ftl") {
          index_ar->
          if(index_ar.succeeded()) {
            routingContext.response().putHeader("Content-Type", "text/html")
            routingContext.response().end(index_ar.result())
          }
          else {
            routingContext.fail(index_ar.cause())
          }
        }
      }
      else {
        routingContext.fail(reply.cause())
      }
    }
  }

  private val EMPTY_PAGE_MARKDOWN =
    "# A new page\n" +
      "\n" +
      "Feel-free to write in Markdown!\n"

  private fun pageRenderingHandler(routingContext: RoutingContext) {
    val requestedPage = routingContext.request().getParam("page")
    val request = JsonObject().put("page", requestedPage)

    val options = DeliveryOptions().addHeader("action", "get-page")
    vertx.eventBus().send(wikiDbQueue, request, options) {reply:AsyncResult<Message<Any>>->
      if(reply.succeeded()) {
        val body = reply.result().body() as JsonObject
        val found = body.getBoolean("found")
        val rawContent = body.getString("rawContent", EMPTY_PAGE_MARKDOWN)
        routingContext.put("title", requestedPage)
        routingContext.put("id", body.getInteger("id", -1))
        routingContext.put("newPage", if(found) "no" else "yes")
        routingContext.put("rawContent", rawContent)
        routingContext.put("content", Processor.process(rawContent))
        routingContext.put("timestamp", Date().toString())
        log.info("pageRenderingHandler: ${body.encodePrettily()}")
        templateEngine.render(routingContext, "templates/page.ftl") {
          page_ar->
          if(page_ar.succeeded()) {
            routingContext.response().putHeader("Content-Type", "text/html")
            routingContext.response().end(page_ar.result())
          }
          else {
            routingContext.fail(page_ar.cause())
          }
        }
      }
      else {
        routingContext.fail(reply.cause())
      }
    }
  }

  private fun pageUpdateHandler(routingContext: RoutingContext) {
    val title = routingContext.request().getParam("title")
    val request = JsonObject()
      .put("id", routingContext.request().getParam("id"))
      .put("title", title)
      .put("markdown", routingContext.request().getParam("markdown"))




    val options = DeliveryOptions()
    if("yes" == routingContext.request().getParam("newPage")) {
      options.addHeader("action", "create-page")
      log.info("pageUpdateHandler::creating page::${request.encodePrettily()}")
    }
    else {
      options.addHeader("action", "save-page")
      log.info("pageUpdateHandler::saving page::${request.encodePrettily()}")
    }

    vertx.eventBus().send(wikiDbQueue, request, options) {reply:AsyncResult<Message<Any>> ->
      if(reply.succeeded()) {
        routingContext.response().setStatusCode(303)
        routingContext.response().putHeader("Location", "/wiki/$title")
        routingContext.response().end()
      }
      else {
        routingContext.fail(reply.cause())
      }
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

  private fun pageDeleteHandler(routingContext: RoutingContext) {
    val id = routingContext.request().getParam("id")
    val request = JsonObject().put("id", id)
    val options = DeliveryOptions().addHeader("action", "delete-page")
    vertx.eventBus().send(wikiDbQueue, request, options) {reply:AsyncResult<Message<Any>> ->
      if(reply.succeeded()) {
        routingContext.response().setStatusCode(303)
        routingContext.response().putHeader("Location", "/")
        routingContext.response().end()
      }
      else {
        routingContext.fail(reply.cause())
      }
    }
  }
}
