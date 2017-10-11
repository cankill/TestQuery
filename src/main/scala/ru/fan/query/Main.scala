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
import ru.fan.query.model.SOReply
import spray.json._

import scala.collection.immutable
import scala.concurrent.{ExecutionContextExecutor, Future, Promise}
import scala.io.StdIn
import scala.util.{Failure, Success}

object Main extends App with JsonSupport {
  implicit val system: ActorSystem = ActorSystem("query-test-system")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  ///2.2/search?pagesize=100&order=desc&sort=creation&tagged=clojure&site=stackoverflow
  val poolClientFlow = Http().cachedHostConnectionPoolHttps[(String, Promise[HttpResponse])](Config.Http.interface)

  val queue: SourceQueueWithComplete[(HttpRequest, (String, Promise[HttpResponse]))] =
    Source.queue[(HttpRequest, (String, Promise[HttpResponse]))](Config.Queue.size, OverflowStrategy.dropNew)
      .throttle(
        elements = Config.Throtling.elements,
        per = Config.Throtling.perFinite,
        maximumBurst = Config.Queue.size,
        mode = ThrottleMode.shaping
      )
      .via(poolClientFlow)
      .toMat(Sink.foreach({
        case ((Success(resp), p)) => (p._1, p._2.success(resp))
        case ((Failure(e), p))    => (p._1, p._2.failure(e))
      }))(Keep.left)
      .run()

  def queueRequest(tag: String): Future[JsObject] = {
    val uri = Uri("/2.2/search").withQuery(Config.Client.query(tag))
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
      Unmarshal(unzippedResponse.entity).to[SOReply].map(aps => doCalculations(aps.copy(tag = Some(tuple._1))))
    } else {
      Unmarshal(unzippedResponse.entity).to[JsObject]
    }
  }

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

  def doCalculations(apiStatus: SOReply): JsObject = {
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
              .mapAsyncUnordered(Config.Client.parallelism)(queueRequest)
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
