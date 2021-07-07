package it.pagopa.pdnd.interop.uservice.partymanagement.model.persistence

import it.pagopa.pdnd.interop.uservice.partymanagement.model.party._
import org.slf4j.LoggerFactory

import java.util.UUID

@SuppressWarnings(Array("org.wartremover.warts.Nothing", "org.wartremover.warts.Equals"))
final case class State(
  parties: Map[UUID, Party],  //TODO use String instead of UUID
  indexes: Map[String, UUID], //TODO use String instead of UUID
  tokens: Map[String, Token],
  relationShips: Map[PartyRelationShipId, PartyRelationShip]
) extends Persistable {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def addParty(party: Party): State = {
    logger.error(s"Writing party ${party.externalId} to state")
    val newState = copy(parties = parties + (party.id -> party), indexes = indexes + (party.externalId -> party.id))
    logger.info(newState.toString)
    newState
  }

  def deleteParty(party: Party): State = copy(parties = parties - party.id, indexes = indexes - party.externalId)

  def updateParty(party: Party): State =
    copy(parties = parties + (party.id -> party))

  def addPartyRelationShip(relationShip: PartyRelationShip): State =
    copy(relationShips = relationShips + (relationShip.id -> relationShip))

  def deletePartyRelationShip(relationShipId: PartyRelationShipId): State =
    copy(relationShips = relationShips - relationShipId)

  def addToken(token: Token): State = copy(tokens = tokens + (token.id -> token))

  def invalidateToken(token: Token): State =
    changeTokenStatus(token, Invalid)

  def consumeToken(token: Token): State =
    changeTokenStatus(token, Consumed)

  private def changeTokenStatus(token: Token, status: TokenStatus): State = {
    val modified = tokens.get(token.id).map(t => t.copy(status = status))

    modified match {
      case Some(t) if status == Consumed =>
        val updated: Seq[PartyRelationShip] =
          token.legals.map(legal => relationShips(legal).copy(status = PartyRelationShipStatus.Active))
        copy(relationShips = relationShips ++ updated.map(p => p.id -> p).toMap, tokens = tokens + (t.id -> t))
      case Some(t) => copy(tokens = tokens + (t.id -> t))
      case None    => this
    }

  }

}

object State {
  val empty: State =
    State(
      parties = Map.empty[UUID, Party],
      indexes = Map.empty[String, UUID],
      relationShips = Map.empty[PartyRelationShipId, PartyRelationShip],
      tokens = Map.empty[String, Token]
    )
}
