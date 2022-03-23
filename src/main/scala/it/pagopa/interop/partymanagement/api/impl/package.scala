package it.pagopa.interop.partymanagement.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCode
import it.pagopa.interop.partymanagement.model._
import it.pagopa.interop.commons.utils.SprayCommonFormats.{offsetDateTimeFormat, uuidFormat}
import it.pagopa.interop.commons.utils.errors.ComponentError
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

package object impl extends SprayJsonSupport with DefaultJsonProtocol {

  implicit val attributeFormat: RootJsonFormat[Attribute]                             = jsonFormat3(Attribute)
  implicit val personSeedFormat: RootJsonFormat[PersonSeed]                           = jsonFormat1(PersonSeed)
  implicit val personFormat: RootJsonFormat[Person]                                   = jsonFormat1(Person)
  implicit val organizationSeedFormat: RootJsonFormat[OrganizationSeed]               = jsonFormat8(OrganizationSeed)
  implicit val organizationFormat: RootJsonFormat[Organization]                       = jsonFormat8(Organization)
  implicit val relationshipProductFormat: RootJsonFormat[RelationshipProduct]         = jsonFormat3(RelationshipProduct)
  implicit val relationshipFormat: RootJsonFormat[Relationship]                       = jsonFormat12(Relationship)
  implicit val relationshipProductSeedFormat: RootJsonFormat[RelationshipProductSeed] = jsonFormat2(
    RelationshipProductSeed
  )
  implicit val relationshipSeedFormat: RootJsonFormat[RelationshipSeed]               = jsonFormat4(RelationshipSeed)
  implicit val relationshipsFormat: RootJsonFormat[Relationships]                     = jsonFormat1(Relationships)
  implicit val relationshipsSeedFormat: RootJsonFormat[RelationshipsSeed]             = jsonFormat1(RelationshipsSeed)
  implicit val problemErrorFormat: RootJsonFormat[ProblemError]                       = jsonFormat2(ProblemError)
  implicit val problemFormat: RootJsonFormat[Problem]                                 = jsonFormat5(Problem)
  implicit val onboardingContractInfoFormat: RootJsonFormat[OnboardingContractInfo]   = jsonFormat2(
    OnboardingContractInfo
  )
  implicit val tokenSeedFormat: RootJsonFormat[TokenSeed]                             = jsonFormat4(TokenSeed)
  implicit val tokenTextFormat: RootJsonFormat[TokenText]                             = jsonFormat1(TokenText)
  implicit val relationshipBindingFormat: RootJsonFormat[RelationshipBinding]         = jsonFormat2(RelationshipBinding)
  implicit val tokenInfoFormat: RootJsonFormat[TokenInfo]                             = jsonFormat3(TokenInfo)
  implicit val bulkPartiesSeedFormat: RootJsonFormat[BulkPartiesSeed]                 = jsonFormat1(BulkPartiesSeed)
  implicit val bulkOrganizationsFormat: RootJsonFormat[BulkOrganizations]             = jsonFormat2(BulkOrganizations)

  final val serviceErrorCodePrefix: String = "001"
  final val defaultProblemType: String     = "about:blank"

  def problemOf(httpError: StatusCode, error: ComponentError, defaultMessage: String = "Unknown error"): Problem =
    Problem(
      `type` = defaultProblemType,
      status = httpError.intValue,
      title = httpError.defaultMessage,
      errors = Seq(
        ProblemError(
          code = s"$serviceErrorCodePrefix-${error.code}",
          detail = Option(error.getMessage).getOrElse(defaultMessage)
        )
      )
    )
}