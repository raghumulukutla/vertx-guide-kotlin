package com.raghu.vertx3guide.wiki.step3

import com.raghu.vertx3guide.wiki.step3.database.WikiDatabaseVerticle
import io.vertx.core.AbstractVerticle
import io.vertx.core.DeploymentOptions
import io.vertx.core.Future

class MainVerticle:AbstractVerticle() {
  override fun start(startFuture: Future<Void>) {

    val dbVerticleDeployment:Future<String> = Future.future()
    vertx.deployVerticle(WikiDatabaseVerticle(), dbVerticleDeployment.completer())

    dbVerticleDeployment.compose{
      val httpVerticleDeployment:Future<String> = Future.future()
      vertx.deployVerticle(
        "com.raghu.vertx3guide.wiki.step3.HttpServerVerticle",
        DeploymentOptions().setInstances(2),
        httpVerticleDeployment.completer()
      )
      httpVerticleDeployment
    }
      .setHandler{
        if(it.succeeded()) {
          startFuture.complete()
        }
        else {
          startFuture.fail(it.cause())
        }
      }
  }
}
