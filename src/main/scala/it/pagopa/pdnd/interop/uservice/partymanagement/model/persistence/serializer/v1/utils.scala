package it.pagopa.pdnd.interop.uservice.partymanagement.model.persistence.serializer.v1

import cats.implicits._
import it.pagopa.pdnd.interop.uservice.partymanagement.common.utils.{ErrorOr, formatter, toOffsetDateTime}
import it.pagopa.pdnd.interop.uservice.partymanagement.model.party._
import it.pagopa.pdnd.interop.uservice.partymanagement.model.persistence.serializer.v1.party.PartyV1.Empty
import it.pagopa.pdnd.interop.uservice.partymanagement.model.persistence.serializer.v1.party.{
  InstitutionPartyV1,
  PartyV1,
  PersonPartyV1
}
import it.pagopa.pdnd.interop.uservice.partymanagement.model.persistence.serializer.v1.relationship.PartyRelationShipIdV1.PartyRoleV1
import it.pagopa.pdnd.interop.uservice.partymanagement.model.persistence.serializer.v1.relationship.PartyRelationShipV1.PartyRelationShipStatusV1
import it.pagopa.pdnd.interop.uservice.partymanagement.model.persistence.serializer.v1.relationship.{
  PartyRelationShipIdV1,
  PartyRelationShipV1
}
import it.pagopa.pdnd.interop.uservice.partymanagement.model.persistence.serializer.v1.token.TokenV1

import java.util.UUID

@SuppressWarnings(Array("org.wartremover.warts.Nothing"))
object utils {

  def getParty(partyV1: PartyV1): ErrorOr[Party] = partyV1 match {
    case p: PersonPartyV1 =>
      Right(
        PersonParty(
          id = UUID.fromString(p.id),
          externalId = p.externalId,
          name = p.name,
          surname = p.surname,
          start = toOffsetDateTime(p.start),
          end = p.end.map(toOffsetDateTime)
        )
      )
    case i: InstitutionPartyV1 =>
      Right(
        InstitutionParty(
          id = UUID.fromString(i.id),
          externalId = i.externalId,
          description = i.description,
          digitalAddress = i.digitalAddress,
          managerName = i.managerName,
          managerSurname = i.managerSurname,
          attributes = i.attributes.toSet,
          start = toOffsetDateTime(i.start),
          end = i.end.map(toOffsetDateTime)
        )
      )
    case Empty => Left(new RuntimeException("Deserialization from protobuf failed"))
  }

  def getPartyV1(party: Party): ErrorOr[PartyV1] = party match {
    case p: PersonParty =>
      Right(
        PersonPartyV1(
          id = p.id.toString,
          externalId = p.externalId,
          name = p.name,
          surname = p.surname,
          start = p.start.format(formatter),
          end = p.end.map(_.format(formatter))
        )
      )
    case i: InstitutionParty =>
      Right(
        InstitutionPartyV1(
          id = i.id.toString,
          externalId = i.externalId,
          description = i.description,
          digitalAddress = i.digitalAddress,
          managerName = i.managerName,
          managerSurname = i.managerSurname,
          attributes = i.attributes.toSeq,
          start = i.start.format(formatter),
          end = i.end.map(_.format(formatter))
        )
      )
  }

  def getPartyRelationShipId(partyRelationShipIdV1: PartyRelationShipIdV1): ErrorOr[PartyRelationShipId] =
    PartyRole
      .fromText(partyRelationShipIdV1.status.name)
      .map(role =>
        PartyRelationShipId(
          UUID.fromString(partyRelationShipIdV1.from),
          UUID.fromString(partyRelationShipIdV1.to),
          role
        )
      )

  def getPartyRelationShipIdV1(partyRelationShipId: PartyRelationShipId): ErrorOr[PartyRelationShipIdV1] = {
    PartyRoleV1
      .fromName(partyRelationShipId.role.stringify)
      .toRight(new RuntimeException("Deserialization from protobuf failed"))
      .map(role => PartyRelationShipIdV1(partyRelationShipId.from.toString, partyRelationShipId.to.toString, role))
  }

  def getPartyRelationShip(partyRelationShipV1: PartyRelationShipV1): ErrorOr[PartyRelationShip] = {
    for {
      id     <- getPartyRelationShipId(partyRelationShipV1.id)
      status <- PartyRelationShipStatus.fromText(partyRelationShipV1.status.name)
    } yield PartyRelationShip(
      id = id,
      start = toOffsetDateTime(partyRelationShipV1.start),
      end = partyRelationShipV1.end.map(toOffsetDateTime),
      status = status
    )
  }

  def getPartyRelationShipV1(partyRelationShip: PartyRelationShip): ErrorOr[PartyRelationShipV1] = {
    for {
      id <- getPartyRelationShipIdV1(partyRelationShip.id)
      status <- PartyRelationShipStatusV1
        .fromName(partyRelationShip.status.stringify)
        .toRight(new RuntimeException("Deserialization from protobuf failed"))
    } yield PartyRelationShipV1(
      id = id,
      start = partyRelationShip.start.format(formatter),
      end = partyRelationShip.end.map(_.format(formatter)),
      status = status
    )

  }

  def getToken(tokenV1: TokenV1): ErrorOr[Token] = {
    for {
      legals <- tokenV1.legals.traverse(legal => getPartyRelationShipId(legal))
    } yield Token(
      id = tokenV1.id,
      legals = legals,
      validity = toOffsetDateTime(tokenV1.validity),
      seed = UUID.fromString(tokenV1.seed),
      checksum = tokenV1.checksum
    )
  }

  def getTokenV1(token: Token): ErrorOr[TokenV1] = {
    for {
      legals <- token.legals.traverse(legal => getPartyRelationShipIdV1(legal))
    } yield TokenV1(
      id = token.id,
      legals = legals,
      validity = token.validity.format(formatter),
      seed = token.seed.toString,
      checksum = token.checksum
    )
  }
}
