package ru.fan.query

import java.util.concurrent.TimeUnit

import akka.http.scaladsl.model.Uri
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.FiniteDuration

object Config {
  private val config = ConfigFactory.load()

  private val httpConfig = config.getConfig("http")
  private val queueConfig = config.getConfig("queue")
  private val throtlingConfig = config.getConfig("throtling")
  private val clientConfig = config.getConfig("client")

  object Http {
    lazy val interface: String = httpConfig.getString("interface")
  }

  object Queue {
    lazy val size: Int = queueConfig.getInt("size")
  }

  object Throtling {
    lazy val elements: Int = throtlingConfig.getInt("elements")
    private lazy val per = throtlingConfig.getDuration("per")
    lazy val perFinite: FiniteDuration = FiniteDuration(per.getSeconds, TimeUnit.SECONDS)
  }

  object Client {
    private val paramsMap = Map(
      "pagesize" -> "100",
      "order" -> "desc",
      "sort" -> "creation",
      "site" -> "stackoverflow"
    )

    def query(tag: String): Uri.Query = Uri.Query(paramsMap + ("tagged" -> tag))
    lazy val parallelism: Int = clientConfig.getInt("parallelism")
  }
}
