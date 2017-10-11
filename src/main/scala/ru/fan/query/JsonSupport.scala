package ru.fan.query

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import ru.fan.query.model.{Item, SOReply}
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val itemFormat: RootJsonFormat[Item] = jsonFormat1(Item)
  implicit val apiStatusFormat: RootJsonFormat[SOReply] = jsonFormat2(SOReply)
}