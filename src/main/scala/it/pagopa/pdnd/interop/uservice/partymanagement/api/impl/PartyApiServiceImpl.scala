package it.pagopa.pdnd.interop.uservice.partymanagement.api.impl

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef}
import akka.cluster.sharding.typed.{ClusterShardingSettings, ShardingEnvelope}
import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.server.Directives.{onComplete, onSuccess}
import akka.http.scaladsl.server.Route
import akka.pattern.StatusReply
import it.pagopa.pdnd.interop.uservice.partymanagement.api.PartyApiService
import it.pagopa.pdnd.interop.uservice.partymanagement.common.system.timeout
import it.pagopa.pdnd.interop.uservice.partymanagement.common.utils._
import it.pagopa.pdnd.interop.uservice.partymanagement.model._
import it.pagopa.pdnd.interop.uservice.partymanagement.model.party._
import it.pagopa.pdnd.interop.uservice.partymanagement.model.persistence._
import it.pagopa.pdnd.interop.uservice.partymanagement.service.UUIDSupplier
import org.slf4j.{Logger, LoggerFactory}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

@SuppressWarnings(Array("org.wartremover.warts.Nothing", "org.wartremover.warts.OptionPartial"))
class PartyApiServiceImpl(
  system: ActorSystem[_],
  sharding: ClusterSharding,
  entity: Entity[Command, ShardingEnvelope[Command]],
  uuidSupplier: UUIDSupplier
)(implicit ec: ExecutionContext)
    extends PartyApiService {
  private val logger: Logger = LoggerFactory.getLogger(this.getClass)

  private val settings: ClusterShardingSettings = entity.settings match {
    case None    => ClusterShardingSettings(system)
    case Some(s) => s
  }

  def getCommander(entityId: String): EntityRef[Command] =
    sharding.entityRefFor(PartyPersistentBehavior.TypeKey, getShard(entityId))

  @inline private def getShard(id: String): String = Math.abs(id.hashCode % settings.numberOfShards).toString

  /** Code: 200, Message: successful operation
    * Code: 404, Message: Organization not found
    */
  override def existsOrganization(organizationId: String): Route = {
    logger.info(s"Verify organization $organizationId")

    val result: Future[Option[Party]] =
      getCommander(organizationId).ask(ref => GetPartyByExternalId(organizationId, getShard(organizationId), ref))

    onSuccess(result) {
      case Some(party) => Party.convertToApi(party).swap.fold(_ => existsOrganization404, _ => existsOrganization200)
      case None        => existsOrganization404
    }

  }

  /** Code: 200, Message: successful operation, DataType: Organization
    * Code: 404, Message: Organization not found, DataType: ErrorResponse
    */
  override def getOrganization(organizationId: String)(implicit
    toEntityMarshallerOrganization: ToEntityMarshaller[Organization],
    toEntityMarshallerErrorResponse: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info(s"Retrieve organization $organizationId")

    val result: Future[Option[Party]] =
      getCommander(organizationId).ask(ref => GetPartyByExternalId(organizationId, getShard(organizationId), ref))

    val errorResponse: Problem = Problem(detail = None, status = 404, title = "some error")

    onSuccess(result) {
      case Some(party) =>
        Party.convertToApi(party).swap.fold(_ => getOrganization404(errorResponse), o => getOrganization200(o))
      case None => getOrganization404(errorResponse)
    }

  }

  /** Code: 201, Message: successful operation, DataType: Organization
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def createOrganization(organizationSeed: OrganizationSeed)(implicit
    toEntityMarshallerOrganization: ToEntityMarshaller[Organization],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info(s"Creating organization ${organizationSeed.description}")
    val party: Party = InstitutionParty.fromApi(organizationSeed, uuidSupplier)

    val result: Future[StatusReply[Party]] =
      getCommander(party.externalId).ask(ref => AddParty(party, getShard(party.externalId), ref))

    onSuccess(result) {
      case statusReply if statusReply.isSuccess =>
        Party
          .convertToApi(statusReply.getValue)
          .swap
          .fold(
            _ => createOrganization400(Problem(detail = None, status = 400, title = "some error")),
            organization => createOrganization201(organization)
          )
      case statusReply =>
        createOrganization400(
          Problem(detail = Option(statusReply.getError.getMessage), status = 400, title = "some error")
        )
    }

  }

  /** Code: 200, Message: successful operation, DataType: Organization
    * Code: 404, Message: Organization not found, DataType: Problem
    */
  override def addOrganizationAttributes(organizationId: String, attributeRecord: Seq[AttributeRecord])(implicit
    toEntityMarshallerOrganization: ToEntityMarshaller[Organization],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {

    val result: Future[StatusReply[Party]] =
      getCommander(organizationId).ask(ref => AddAttributes(organizationId, attributeRecord, ref))

    onSuccess(result) {
      case statusReply if statusReply.isSuccess =>
        Party
          .convertToApi(statusReply.getValue)
          .swap
          .fold(
            _ => addOrganizationAttributes404(Problem(detail = None, status = 400, title = "some error")),
            organization => addOrganizationAttributes200(organization)
          )
      case statusReply =>
        addOrganizationAttributes404(
          Problem(detail = Option(statusReply.getError.getMessage), status = 404, title = "some error")
        )
    }

  }

  /** Code: 201, Message: successful operation, DataType: Person
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def createPerson(personSeed: PersonSeed)(implicit
    toEntityMarshallerPerson: ToEntityMarshaller[Person],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info(s"Creating person ${personSeed.name}/${personSeed.surname}")

    val party: Party = PersonParty.fromApi(personSeed, uuidSupplier)

    val result: Future[StatusReply[Party]] =
      getCommander(party.externalId).ask(ref => AddParty(party, getShard(party.externalId), ref))

    onSuccess(result) {
      case statusReply if statusReply.isSuccess =>
        Party
          .convertToApi(statusReply.getValue)
          .fold(
            _ => createPerson400(Problem(detail = None, status = 400, title = "some error")),
            person => createPerson201(person)
          )
      case statusReply =>
        createPerson400(Problem(detail = Option(statusReply.getError.getMessage), status = 400, title = "some error"))
    }

  }

  /** Code: 200, Message: Person exists
    * Code: 404, Message: Person not found
    */
  override def existsPerson(taxCode: String): Route = {
    logger.info(s"Verify person $taxCode")

    val result: Future[Option[Party]] =
      getCommander(taxCode).ask(ref => GetPartyByExternalId(taxCode, getShard(taxCode), ref))

    onSuccess(result) {
      case Some(party) => Party.convertToApi(party).fold(_ => existsPerson404, _ => existsPerson200)
      case None        => existsPerson404
    }

  }

  /** Code: 200, Message: Person exists, DataType: Person
    * Code: 404, Message: Person not found, DataType: ErrorResponse
    */
  override def getPerson(taxCode: String)(implicit
    toEntityMarshallerPerson: ToEntityMarshaller[Person],
    toEntityMarshallerErrorResponse: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info(s"Retrieving person $taxCode")

    val result: Future[Option[Party]] =
      getCommander(taxCode).ask(ref => GetPartyByExternalId(taxCode, getShard(taxCode), ref))

    val errorResponse: Problem = Problem(detail = None, status = 404, title = "some error")

    onSuccess(result) {
      case Some(party) => Party.convertToApi(party).fold(_ => getPerson404(errorResponse), p => getPerson200(p))
      case None        => getPerson404(errorResponse)
    }

  }

  /** Code: 201, Message: successful operation
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def createRelationShip(
    relationShip: RelationShip
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route = {

    val result: Future[StatusReply[State]] = for {
      from <- getCommander(relationShip.from).ask(ref =>
        GetPartyByExternalId(relationShip.from, getShard(relationShip.from), ref)
      )
      to <- getCommander(relationShip.to).ask(ref =>
        GetPartyByExternalId(relationShip.to, getShard(relationShip.to), ref)
      )
      parties <- extractParties(from, to)
      role    <- PartyRole.fromText(relationShip.role).toFuture
      res <- getCommander(relationShip.from).ask(ref =>
        AddPartyRelationShip(UUID.fromString(parties._1.partyId), UUID.fromString(parties._2.partyId), role, ref)
      )
    } yield res

    onComplete(result) {
      case Success(statusReply) if statusReply.isError =>
        createRelationShip400(
          Problem(detail = Option(statusReply.getError.getMessage), status = 404, title = "some error")
        )
      case Success(_) => createRelationShip201
      case Failure(ex) =>
        createRelationShip400(Problem(detail = Option(ex.getMessage), status = 400, title = "some error"))
    }

  }

  /** Code: 200, Message: successful operation, DataType: RelationShips
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def getRelationShips(from: String)(implicit
    toEntityMarshallerRelationShips: ToEntityMarshaller[RelationShips],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {

    logger.error(s"Getting relationships for $from")

    def createRelationships(
      person: Person,
      relationShips: List[PartyRelationShip]
    ): Future[List[Option[RelationShip]]] = {
      Future.traverse(relationShips)(rl =>
        getCommander(rl.id.to.toString)
          .ask(ref => GetParty(rl.id.to, ref))
          .map(p =>
            p.map(r =>
              RelationShip(
                from = person.taxCode,
                to = r.externalId,
                role = rl.id.role.stringify,
                status = Some(rl.status.stringify)
              )
            )
          )
      )

    }
    val result: Future[List[RelationShip]] = for {
      fromParty <- getCommander(from).ask(ref => GetPartyByExternalId(from, getShard(from), ref))
      party <- fromParty.fold(Future.failed[Party](new RuntimeException(s"Party $from not found")))(p =>
        Future.successful(p)
      )
      person = Party.convertToApi(party).toOption.get //TODO remove get
      partyRelationships <- getCommander(person.taxCode).ask(ref =>
        GetPartyRelationShips(UUID.fromString(person.partyId), ref)
      )
      relationships <- createRelationships(person, partyRelationships)
    } yield relationships.flatten

    onComplete(result) {
      case Success(relationships) => getRelationShips200(RelationShips(relationships))

      case Failure(ex) =>
        getRelationShips400(Problem(detail = Option(ex.getMessage), status = 400, title = "some error"))
    }

  }

  /** Code: 201, Message: successful operation, DataType: TokenText
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def createToken(tokenSeed: TokenSeed)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    toEntityMarshallerTokenText: ToEntityMarshaller[TokenText],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info(s"Creating token ${tokenSeed.toString}")

    val result: Future[StatusReply[TokenText]] = for {
      partyRelationShipIds <- Future.traverse(tokenSeed.relationShips.items)(getPartyRelationShipId)
      token                <- getCommander(tokenSeed.seed).ask(ref => AddToken(tokenSeed, partyRelationShipIds, ref))
    } yield token

    manageCreationResponse(result, createToken201, createToken400)

  }

  /** Code: 200, Message: successful operation
    * Code: 404, Message: Token not found, DataType: Problem
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def verifyToken(
    token: String
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route = {

    val result: Future[StatusReply[Option[Token]]] = for {
      token <- Future.fromTry(Token.decode(token))
      res   <- getCommander(token.seed.toString).ask(ref => VerifyToken(token, ref))
    } yield res

    onComplete(result) {
      case Success(statusReply) if statusReply.isError =>
        verifyToken400(Problem(detail = Option(statusReply.getError.getMessage), status = 400, title = "some error"))
      case Success(token) if token.getValue.nonEmpty => verifyToken200
      case Success(_)                                => verifyToken404(Problem(detail = None, status = 404, title = "Token not found"))
      case Failure(ex) =>
        verifyToken400(Problem(detail = Option(ex.getMessage), status = 400, title = "some error"))
    }
  }

  /** Code: 201, Message: successful operation
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def consumeToken(
    token: String
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route = {
    val result: Future[StatusReply[State]] = for {
      token <- Future.fromTry(Token.decode(token))
      commander = getCommander(token.seed.toString)
      res <-
        if (token.isValid) commander.ask(ref => ConsumeToken(token, ref))
        else commander.ask(ref => InvalidateToken(token, ref))
    } yield res

    onComplete(result) {
      case Success(statusReply) if statusReply.isError =>
        consumeToken400(Problem(detail = Option(statusReply.getError.getMessage), status = 400, title = "some error"))
      case Success(_) => consumeToken201
      case Failure(ex) =>
        consumeToken400(Problem(detail = Option(ex.getMessage), status = 400, title = "some error"))
    }

  }

  /** Code: 201, Message: successful operation
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def invalidateToken(
    token: String
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route = {
    val result: Future[StatusReply[State]] = for {
      token <- Future.fromTry(Token.decode(token))
      res   <- getCommander(token.seed.toString).ask(ref => InvalidateToken(token, ref))
    } yield res

    onComplete(result) {
      case Success(statusReply) if statusReply.isError =>
        invalidateToken400(
          Problem(detail = Option(statusReply.getError.getMessage), status = 404, title = "some error")
        )
      case Success(_) => invalidateToken201
      case Failure(ex) =>
        invalidateToken400(Problem(detail = Option(ex.getMessage), status = 404, title = "some error"))
    }

  }

  //TODO Improve this part
  private def extractParties(from: Option[Party], to: Option[Party]): Future[(Person, Organization)] =
    Future.fromTry {
      val parties: Option[(Person, Organization)] = for {
        fromParty    <- from
        person       <- Party.convertToApi(fromParty).toOption
        toParty      <- to
        organization <- Party.convertToApi(toParty).swap.toOption
      } yield (person, organization)
      parties.map(Success(_)).getOrElse(Failure(new RuntimeException("Party extraction from ApiParty failed")))

    }

  private def getPartyRelationShipId(relationShip: RelationShip): Future[PartyRelationShipId] = for {
    from <- getCommander(relationShip.from).ask(ref =>
      GetPartyByExternalId(relationShip.from, getShard(relationShip.from), ref)
    )
    to <- getCommander(relationShip.to).ask(ref =>
      GetPartyByExternalId(relationShip.to, getShard(relationShip.to), ref)
    )
    role = PartyRole.fromText(relationShip.role).toOption
  } yield PartyRelationShipId(from.get.id, to.get.id, role.get)

  private def manageCreationResponse[A](
    result: Future[StatusReply[A]],
    success: A => Route,
    failure: Problem => Route
  ): Route = {
    onComplete(result) {
      case Success(statusReply) if statusReply.isError =>
        logger.error(s"Error trying to create element: ${statusReply.getError.getMessage}")
        failure(Problem(detail = Option(statusReply.getError.getMessage), status = 400, title = "some error"))
      case Success(a) =>
        logger.info(s"Element successfully created")
        success(a.getValue)
      case Failure(ex) =>
        logger.error(s"Error trying to create element: ${ex.getMessage}")
        failure(Problem(detail = Option(ex.getMessage), status = 400, title = "some error"))
    }
  }
}
