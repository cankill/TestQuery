package ru.fan.query
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.coding.{Deflate, Gzip, NoCoding}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.HttpEncodings
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.ExceptionHandler
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream._
import akka.stream.scaladsl.{Framing, Keep, Sink, Source, SourceQueueWithComplete}
import com.typesafe.config.ConfigFactory
import spray.json._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes.{CustomStatusCode, HttpFailure, HttpSuccess}
import akka.util.ByteString

import scala.collection.immutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContextExecutor, Future, Promise}
import scala.io.StdIn
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._

final case class Item(is_answered: Boolean)
final case class SOReply(tag: Option[String], items: List[Item])
final case class SOError(error_id: String, error_message: String,error_name: String)

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val itemFormat: RootJsonFormat[Item] = jsonFormat1(Item)
  implicit val apiStatusFormat: RootJsonFormat[SOReply] = jsonFormat2(SOReply)
  implicit val errorFormat: RootJsonFormat[SOError] = jsonFormat3(SOError)
}

object Main extends App with JsonSupport {
  implicit val system: ActorSystem = ActorSystem("my-system")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  private val config = ConfigFactory.load()

  private val httpConfig = config.getConfig("http")
  private val queueConfig = config.getConfig("queue")
  private val throtlingConfig = config.getConfig("throtling")
  lazy val interface = httpConfig.getString("interface")
  lazy val port = httpConfig.getInt("port")
  lazy val queueSize = queueConfig.getInt("size")
  lazy val elements = throtlingConfig.getInt("elements")
  lazy val per = throtlingConfig.getDuration("per")
  lazy val perFinite = FiniteDuration(per.getSeconds, TimeUnit.SECONDS)

  println(queueSize)

  def decodeResponse(response: HttpResponse): HttpResponse = {
    val decoder = response.encoding match {
      case HttpEncodings.gzip ⇒
        Gzip
      case HttpEncodings.deflate ⇒
        Deflate
      case _ ⇒
        NoCoding
    }

    decoder.decodeMessage(response)
  }

  ///2.2/search?pagesize=100&order=desc&sort=creation&tagged=clojure&site=stackoverflow
  val poolClientFlow = Http().cachedHostConnectionPoolHttps[(String, Promise[HttpResponse])](interface)

  val queue: SourceQueueWithComplete[(HttpRequest, (String, Promise[HttpResponse]))] =
    Source.queue[(HttpRequest, (String, Promise[HttpResponse]))](queueSize, OverflowStrategy.dropNew)
      .throttle(
        elements = elements, //number of elements to be taken from bucket
        per = perFinite,
        maximumBurst = queueSize,  //capacity of bucket
        mode = ThrottleMode.shaping
      )
      .via(poolClientFlow)
      .toMat(Sink.foreach({
        case ((Success(resp), p)) => (p._1, p._2.success(resp))
        case ((Failure(e), p))    => (p._1, p._2.failure(e))
      }))(Keep.left)
      .run()

  def queueRequest(tag: String): Future[JsObject] = {
    val queryParams: Map[String, String] = Map(
      "pagesize" -> "100",
      "order" -> "desc",
      "sort" -> "creation",
      "tagged" -> tag,
      "site" -> "stackoverflow",
      "key" -> "1Hh4m1ExsXrtnD2PJQO1JA(("
    )
    val uri = Uri("/2.2/search").withQuery(Uri.Query(queryParams))
    val request = HttpRequest(uri = uri)

    val responsePromise = Promise[HttpResponse]()
    queue.offer(request -> (tag, responsePromise)).flatMap {
      case QueueOfferResult.Enqueued    => responsePromise.future.flatMap(r => extractResult((tag, r)))
      case QueueOfferResult.Dropped     => Future.failed(new RuntimeException("Queue overflowed. Try again later."))
      case QueueOfferResult.Failure(ex) => Future.failed(ex)
      case QueueOfferResult.QueueClosed => Future.failed(new RuntimeException("Queue was closed (pool shut down) while running the request. Try again later."))
    }
  }

  def extractResult(tuple: (String, HttpResponse)): Future[JsObject] = {
    val unzippedResponse = decodeResponse(tuple._2)

    if(unzippedResponse.status.isSuccess()) {
      Unmarshal(unzippedResponse.entity).to[SOReply].map(aps => doCalculate(aps.copy(tag = Some(tuple._1))))
    } else {
      Unmarshal(unzippedResponse.entity).to[JsObject]
    }
  }

  def doCalculate(apiStatus: SOReply): JsObject = {
    val (countRes, answeredRes) = apiStatus.items.foldLeft((0,0)) {
      case ((count, answered), item) if item.is_answered => (count + 1, answered + 1)
      case ((count, answered), _) => (count + 1, answered)
    }

    JsObject(
      apiStatus.tag.get -> JsObject(
        "total" -> JsNumber(countRes),
        "answered" -> JsNumber(answeredRes)
      )
    )
  }

  implicit def myExceptionHandler: ExceptionHandler =
    ExceptionHandler {
      case ex =>
        extractUri { uri =>
          println(s"Request to $uri could not be handled normally")
          ex.printStackTrace()
          complete(HttpResponse(StatusCodes.InternalServerError, entity = s"Something wrong happens! ${ex.getMessage}"))
        }
    }

  val route =
    path("search") {
      parameters('tag.*, 'format ? false) { (tags, doPrettyPrint) =>
        get {
          val responseFut: Future[immutable.Seq[JsObject]] =
            Source(tags.toList)
              .mapAsyncUnordered(8)(queueRequest)
              .runWith(Sink.seq[JsObject])

          onComplete(responseFut) {
            case Failure(exception) =>
              complete(exception)

            case Success(jsonAstList) =>
              complete(jsonAstList.map(jsAst => if(doPrettyPrint) jsAst.prettyPrint else jsAst.compactPrint).mkString(",\n"))
          }
        }
      }
    }

  val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)

  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  StdIn.readLine()
  bindingFuture
    .flatMap(_.unbind())
    .onComplete(_ => system.terminate())
}
