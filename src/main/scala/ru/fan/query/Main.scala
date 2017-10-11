package ru.fan.query
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.coding.{Deflate, Gzip, NoCoding}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.HttpEncodings
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.ExceptionHandler
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream._
import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import com.typesafe.config.ConfigFactory
import spray.json._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport

import scala.collection.immutable
import scala.concurrent.{ExecutionContextExecutor, Future, Promise}
import scala.io.StdIn
import scala.util.{Failure, Success}

final case class Item(is_answered: Boolean)
final case class SOReply(tag: Option[String], items: List[Item])

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val itemFormat: RootJsonFormat[Item] = jsonFormat1(Item)
  implicit val apiStatusFormat: RootJsonFormat[SOReply] = jsonFormat2(SOReply)
}

object Main extends App with JsonSupport {
  implicit val system: ActorSystem = ActorSystem("my-system")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  private val config = ConfigFactory.load()

  private val httpConfig = config.getConfig("http")
  private val queueConfig = config.getConfig("queue")
  lazy val interface = httpConfig.getString("interface")
  lazy val port = httpConfig.getInt("port")
  lazy val queueSize = queueConfig.getInt("size")

  def decodeResponse(response: HttpResponse): HttpResponse = {
    val decoder = response.encoding match {
      case HttpEncodings.gzip ⇒
        Gzip
      case HttpEncodings.deflate ⇒
        Deflate
      case HttpEncodings.identity ⇒
        NoCoding
    }

    decoder.decodeMessage(response)
  }

  ///2.2/search?pagesize=100&order=desc&sort=creation&tagged=clojure&site=stackoverflow
  val poolClientFlow = Http().cachedHostConnectionPoolHttps[(String, Promise[HttpResponse])](interface)

  val queue: SourceQueueWithComplete[(HttpRequest, (String, Promise[HttpResponse]))] =
    Source.queue[(HttpRequest, (String, Promise[HttpResponse]))](queueSize, OverflowStrategy.dropNew)
      .via(poolClientFlow)
      .toMat(Sink.foreach({
        case ((Success(resp), p)) => (p._1, p._2.success(resp))
        case ((Failure(e), p))    => (p._1, p._2.failure(e))
      }))(Keep.left)
      .run()

  def queueRequest(tag: String): Future[(String, HttpResponse)] = {
    val queryParams: Map[String, String] = Map(
      "pagesize" -> "100",
      "order" -> "desc",
      "sort" -> "creation",
      "tagged" -> tag,
      "site" -> "stackoverflow"
    )
    val uri = Uri("/2.2/search").withQuery(Uri.Query(queryParams))
//    val uri = Uri(s"/2.2/search?pagesize=100&order=desc&sort=creation&tagged=$tag&site=stackoverflow", Uri.ParsingMode.Relaxed)



//    val uri = Uri(s"/2.2/search?pagesize=100&order=desc&sort=creation&tagged=$tag&site=stackoverflow", Uri.ParsingMode.Relaxed)
    val request = HttpRequest(uri = uri)

    val responsePromise = Promise[HttpResponse]()
    queue.offer(request -> (tag, responsePromise)).flatMap {
      case QueueOfferResult.Enqueued    => responsePromise.future.map(r => (tag, r))
      case QueueOfferResult.Dropped     => Future.failed(new RuntimeException("Queue overflowed. Try again later."))
      case QueueOfferResult.Failure(ex) => Future.failed(ex)
      case QueueOfferResult.QueueClosed => Future.failed(new RuntimeException("Queue was closed (pool shut down) while running the request. Try again later."))
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

  def extractResult(tuple: (String, HttpResponse)): Future[JsObject] = {
    val unzippedResponse = decodeResponse(tuple._2)

    Unmarshal(unzippedResponse.entity).to[SOReply].map(aps => doCalculate(aps.copy(tag = Some(tuple._1))))
//
//    unzippedResponse.entity.dataBytes
//      .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, allowTruncation = true))
//      .map(_.utf8String)
//      .runWith(Sink.head[String])
  }

  implicit def myExceptionHandler: ExceptionHandler =
    ExceptionHandler {
      case ex =>
        extractUri { uri =>
          println(s"Request to $uri could not be handled normally")
          complete(HttpResponse(StatusCodes.InternalServerError, entity = s"Something wrong happens! ${ex.getMessage}"))
        }
    }

  val route =
    path("search") {
      parameters('tag.*, 'format ? false) { (tags, doPrettyPrint) =>
        get {
          val responseFut1: Future[immutable.Seq[JsObject]] =
            Source(tags.toList)
            .mapAsyncUnordered(4)(queueRequest)
            .mapAsyncUnordered(4)(extractResult)
            .runWith(Sink.seq[JsObject])

          onComplete(responseFut1) {
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
  StdIn.readLine() // let it run until user presses return
  bindingFuture
    .flatMap(_.unbind()) // trigger unbinding from the port
    .onComplete(_ => system.terminate()) // and shutdown when done
}
