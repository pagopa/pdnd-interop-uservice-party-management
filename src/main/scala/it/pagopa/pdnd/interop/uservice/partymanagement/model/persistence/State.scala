package it.pagopa.pdnd.interop.uservice.partymanagement.model.persistence

import it.pagopa.pdnd.interop.uservice.partymanagement.model.party._
import org.slf4j.LoggerFactory

import java.util.UUID

final case class State(
  parties: Map[UUID, Party],
  tokens: Map[String, Token],
  relationships: Map[String, PartyRelationship]
) extends Persistable {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def addParty(party: Party): State = {
    logger.error(s"Writing party ${party.id.toString} to state")
    val newState = copy(parties = parties + (party.id -> party))
    logger.info(newState.toString)
    newState
  }

  def deleteParty(party: Party): State = copy(parties = parties - party.id)

  def updateParty(party: Party): State =
    copy(parties = parties + (party.id -> party))

  def addPartyRelationship(relationship: PartyRelationship): State =
    copy(relationships = relationships + (relationship.id.toString -> relationship))

  def confirmPartyRelationship(id: UUID, filePath: String, fileName: String, contentType: String): State = {
    val relationshipId = id.toString
    val updated: Map[String, PartyRelationship] =
      relationships.updated(
        relationshipId,
        relationships(relationshipId).copy(
          status = PartyRelationshipStatus.Active,
          filePath = Some(filePath),
          fileName = Some(fileName),
          contentType = Some(contentType)
        )
      )
    copy(relationships = updated)
  }

  def rejectRelationship(relationshipId: UUID): State =
    updateRelationshipStatus(relationshipId, PartyRelationshipStatus.Rejected)

  def suspendRelationship(relationshipId: UUID): State =
    updateRelationshipStatus(relationshipId, PartyRelationshipStatus.Suspended)

  def activateRelationship(relationshipId: UUID): State =
    updateRelationshipStatus(relationshipId, PartyRelationshipStatus.Active)

  def deleteRelationship(relationshipId: UUID): State =
    updateRelationshipStatus(relationshipId, PartyRelationshipStatus.Deleted)

  private def updateRelationshipStatus(relationshipId: UUID, newStatus: PartyRelationshipStatus): State =
    relationships.get(relationshipId.toString) match {
      case Some(relationship) =>
        val updatedRelationship = relationship.copy(status = newStatus)
        copy(relationships = relationships + (relationship.id.toString -> updatedRelationship))
      case None =>
        this
    }

  def getPartyRelationshipByAttributes(
    from: UUID,
    to: UUID,
    role: PartyRole,
    productRole: String
  ): Option[PartyRelationship] = {
    relationships.values.find(relationship =>
      from.toString == relationship.from.toString
        && to.toString == relationship.to.toString
        && role == relationship.role
        && productRole == relationship.productRole
    )
  }

  def addToken(token: Token): State = copy(tokens = tokens + (token.id -> token))

  def deleteToken(token: Token): State = copy(tokens = tokens - token.id)
}

object State {
  val empty: State =
    State(
      parties = Map.empty[UUID, Party],
      relationships = Map.empty[String, PartyRelationship],
      tokens = Map.empty[String, Token]
    )
}
