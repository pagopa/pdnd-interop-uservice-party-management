package it.pagopa.pdnd.interop.uservice.partymanagement.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import it.pagopa.pdnd.interop.uservice.partymanagement.model._
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

package object impl extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val personSeedFormat: RootJsonFormat[PersonSeed]             = jsonFormat3(PersonSeed)
  implicit val personFormat: RootJsonFormat[Person]                     = jsonFormat4(Person)
  implicit val organizationSeedFormat: RootJsonFormat[OrganizationSeed] = jsonFormat6(OrganizationSeed)
  implicit val organizationFormat: RootJsonFormat[Organization]         = jsonFormat7(Organization)
  implicit val relationShipFormat: RootJsonFormat[RelationShip]         = jsonFormat4(RelationShip)
  implicit val relationShipsFormat: RootJsonFormat[RelationShips]       = jsonFormat1(RelationShips)
  implicit val problemFormat: RootJsonFormat[Problem]                   = jsonFormat3(Problem)
  implicit val tokenFeedFormat: RootJsonFormat[TokenSeed]               = jsonFormat3(TokenSeed)
  implicit val tokenTextFormat: RootJsonFormat[TokenText]               = jsonFormat1(TokenText)

}
