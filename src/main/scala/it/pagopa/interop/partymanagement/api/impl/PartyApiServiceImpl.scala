package it.pagopa.interop.partymanagement.api.impl

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef}
import akka.cluster.sharding.typed.{ClusterShardingSettings, ShardingEnvelope}
import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{complete, onComplete, onSuccess}
import akka.http.scaladsl.server.Route
import akka.pattern.StatusReply
import akka.util.Timeout
import cats.implicits.toTraverseOps
import com.typesafe.scalalogging.{Logger, LoggerTakingImplicit}
import it.pagopa.interop.commons.logging.{CanLogContextFields, ContextFieldsToLog}
import it.pagopa.interop.commons.utils.AkkaUtils
import it.pagopa.interop.commons.utils.OpenapiUtils._
import it.pagopa.interop.commons.utils.TypeConversions._
import it.pagopa.interop.commons.utils.service.UUIDSupplier
import it.pagopa.interop.partymanagement.api.PartyApiService
import it.pagopa.interop.partymanagement.common.system._
import it.pagopa.interop.partymanagement.error.PartyManagementErrors._
import it.pagopa.interop.partymanagement.model._
import it.pagopa.interop.partymanagement.model.party._
import it.pagopa.interop.partymanagement.model.persistence._
import it.pagopa.interop.partymanagement.service.OffsetDateTimeSupplier
import org.slf4j.LoggerFactory

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class PartyApiServiceImpl(
  system: ActorSystem[_],
  sharding: ClusterSharding,
  entity: Entity[Command, ShardingEnvelope[Command]],
  uuidSupplier: UUIDSupplier,
  offsetDateTimeSupplier: OffsetDateTimeSupplier
)(implicit ec: ExecutionContext)
    extends PartyApiService {

  val logger: LoggerTakingImplicit[ContextFieldsToLog] =
    Logger.takingImplicit[ContextFieldsToLog](LoggerFactory.getLogger(this.getClass))

  private val settings: ClusterShardingSettings = entity.settings match {
    case None    => ClusterShardingSettings(system)
    case Some(s) => s
  }

  def getCommander(entityId: String): EntityRef[Command] =
    sharding.entityRefFor(PartyPersistentBehavior.TypeKey, AkkaUtils.getShard(entityId, settings.numberOfShards))

  /** Code: 200, Message: successful operation
    * Code: 404, Message: Institution not found
    */
  override def existsInstitutionById(id: String): Route = {
    logger.info(s"Verify institution $id")(Seq.empty)

    val result: Future[Option[Party]] = for {
      uuid <- id.toFutureUUID
      r    <- getCommander(id).ask(ref => GetParty(uuid, ref))
    } yield r

    onSuccess(result) {
      case Some(party) =>
        Party.convertToApi(party).swap.fold(_ => existsInstitutionById404, _ => existsInstitutionById200)
      case None        => existsInstitutionById404
    }

  }

  /** Code: 201, Message: successful operation, DataType: Institution
    * Code: 409, Message: Institution already exists, DataType: Problem
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def createInstitution(institutionSeed: InstitutionSeed)(implicit
    toEntityMarshallerInstitution: ToEntityMarshaller[Institution],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info(s"Creating institution ${institutionSeed.description}")

    val commanders = (0 until settings.numberOfShards)
      .map(shard => sharding.entityRefFor(PartyPersistentBehavior.TypeKey, shard.toString))
      .toList

    val institution: Future[StatusReply[Party]] = for {
      shardOrgs <- commanders.traverse(_.ask(ref => GetInstitutionByExternalId(institutionSeed.institutionId, ref)))
      maybeExistingOrg = shardOrgs.flatten.headOption
      newOrg <- maybeExistingOrg
        .toLeft(InstitutionParty.fromApi(institutionSeed, uuidSupplier, offsetDateTimeSupplier))
        .left
        .map(_ => InstitutionAlreadyExists(institutionSeed.institutionId))
        .toFuture
      result <- getCommander(newOrg.id.toString).ask(ref => AddParty(newOrg, ref))
    } yield result

    onComplete(institution) {
      case Success(statusReply) if statusReply.isSuccess =>
        Party
          .convertToApi(statusReply.getValue)
          .swap
          .fold(
            _ => {
              logger.error(s"Creating institution ${institutionSeed.description} - Bad request")
              createInstitution400(problemOf(StatusCodes.BadRequest, CreateInstitutionBadRequest))
            },
            institution => createInstitution201(institution)
          )
      case Success(_)                                    =>
        val errorResponse: Problem = problemOf(StatusCodes.Conflict, CreateInstitutionConflict)
        createInstitution409(errorResponse)
      case Failure(ex: InstitutionAlreadyExists)         =>
        logger.error(s"Creating institution ${institutionSeed.description} - ${ex.getMessage}")
        val errorResponse: Problem = problemOf(StatusCodes.Conflict, ex)
        createInstitution409(errorResponse)
      case Failure(ex)                                   =>
        logger.error(s"Creating institution ${institutionSeed.description} - ${ex.getMessage}")
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, CreateInstitutionError(ex.getMessage))
        createInstitution400(errorResponse)
    }

  }

  /** Code: 200, Message: successful operation, DataType: Institution
   * Code: 405, Message: Institution not found, DataType: Problem
   * Code: 400, Message: Invalid ID supplied, DataType: Problem
   */
  override def updateInstitutionById(id: String, institution: Institution)(implicit
    toEntityMarshallerInstitution: ToEntityMarshaller[Institution],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info(s"Updating institution $id")

    val commander: EntityRef[Command] = getCommander(id)

    val updatedInstitution: Future[StatusReply[Party]] = for {
      uuid       <- id.toFutureUUID
      found      <- commander.ask(ref => GetParty(uuid, ref))
      party      <- found.toFuture(UpdateInstitutionNotFound(id)).map(InstitutionParty.extractInstitutionParty)
      updatedOrg <- party
        .map(p => {
          if (p.externalId != institution.institutionId) {
            Left(
              Failure(
                UpdateInstitutionBadRequest(
                  id,
                  s"Cannot update externalId ${p.externalId} -> ${institution.institutionId}"
                )
              )
            )
          } else {
            Right(
              InstitutionParty(
                id = uuid,
                externalId = institution.institutionId,
                description = institution.description,
                digitalAddress = institution.digitalAddress,
                taxCode = institution.taxCode,
                address = institution.address,
                zipCode = institution.zipCode,
                origin = institution.origin,
                institutionType = institution.institutionType,
                start = p.start,
                end = p.end,
                attributes = institution.attributes.map(InstitutionAttribute.fromApi).toSet
              )
            )
          }
        })
      result     <- updatedOrg
        .fold(e => e.toFuture, p => commander.ask(ref => UpdateParty(p, ref)))
    } yield result

    onComplete(updatedInstitution) {
      case Success(statusReply) if statusReply.isSuccess =>
        Party
          .convertToApi(statusReply.getValue)
          .swap
          .fold(
            _ => {
              logger.error(s"Updating institution $id - Bad request")
              updateInstitutionById400(problemOf(StatusCodes.BadRequest, CreateInstitutionBadRequest))
            },
            institution => updateInstitutionById200(institution)
          )
      case Success(_)                                    =>
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, CreateInstitutionConflict)
        updateInstitutionById400(errorResponse)
      case Failure(ex: UpdateInstitutionNotFound)        =>
        logger.error(s"Updating institution $id - ${ex.getMessage}")
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        updateInstitutionById404(errorResponse)
      case Failure(ex: UpdateInstitutionBadRequest)      =>
        logger.error(s"Updating institution $id - ${ex.getMessage}")
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, ex)
        updateInstitutionById400(errorResponse)
      case Failure(ex)                                   =>
        logger.error(s"Updating institution $id - ${ex.getMessage}")
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, CreateInstitutionError(ex.getMessage))
        updateInstitutionById400(errorResponse)
    }

  }

  /** Code: 200, Message: successful operation, DataType: Institution
    * Code: 404, Message: Institution not found, DataType: Problem
    */
  override def addInstitutionAttributes(id: String, attribute: Seq[Attribute])(implicit
    toEntityMarshallerInstitution: ToEntityMarshaller[Institution],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info(s"Adding attributes to institution {}", id)
    val result: Future[StatusReply[Party]] = for {
      uuid <- id.toFutureUUID
      r    <- getCommander(id).ask(ref => AddAttributes(uuid, attribute, ref))
    } yield r

    onSuccess(result) {
      case statusReply if statusReply.isSuccess =>
        Party
          .convertToApi(statusReply.getValue)
          .swap
          .fold(
            _ => {
              logger.error(s"Error adding attributes to institution $id - Bad request")
              addInstitutionAttributes404(problemOf(StatusCodes.BadRequest, AddAttributesBadRequest))
            },
            institution => addInstitutionAttributes200(institution)
          )
      case statusReply                          =>
        logger.error(s"Error adding attributes to institution $id - Not found - ${statusReply.getError.getMessage}")
        addInstitutionAttributes404(problemOf(StatusCodes.NotFound, AddAttributesError))
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
    logger.info("Creating person {}", personSeed.id.toString)

    val party: Party = PersonParty.fromApi(personSeed, offsetDateTimeSupplier)

    val result: Future[StatusReply[Party]] =
      getCommander(party.id.toString).ask(ref => AddParty(party, ref))

    onComplete(result) {
      case Success(statusReply) if statusReply.isSuccess =>
        Party
          .convertToApi(statusReply.getValue)
          .fold(
            _ => {
              logger.error(s"Creating person ${personSeed.id} - Bad request")
              createPerson400(problemOf(StatusCodes.BadRequest, CreatePersonBadRequest))
            },
            person => createPerson201(person)
          )
      case Success(_)                                    =>
        logger.error(s"Creating person ${personSeed.id} - Conflict")
        val errorResponse: Problem = problemOf(StatusCodes.Conflict, CreatePersonConflict)
        createPerson409(errorResponse)
      case Failure(ex)                                   =>
        logger.error(s"Creating person ${personSeed.id} - ${ex.getMessage}")
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, CreatePersonError)
        createPerson400(errorResponse)

    }

  }

  /** Code: 200, Message: Person exists
    * Code: 404, Message: Person not found
    */
  override def existsPersonById(id: String): Route = {
    logger.info("Verify if person with the following id exists: {}", id)(Seq.empty)

    val result: Future[Option[Party]] = for {
      uuid <- id.toFutureUUID
      r    <- getCommander(id).ask(ref => GetParty(uuid, ref))
    } yield r

    onSuccess(result) {
      case Some(party) => Party.convertToApi(party).fold(_ => existsPersonById404, _ => existsPersonById200)
      case None        =>
        logger.error(s"Error while verifying if person with the following id exists $id - Not found")(Seq.empty)
        existsPersonById404
    }

  }

  /** Code: 201, Message: successful operation
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def createRelationship(seed: RelationshipSeed)(implicit
    toEntityMarshallerRelationship: ToEntityMarshaller[Relationship],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info("Creating relationship")
    val commanders = (0 until settings.numberOfShards)
      .map(shard => sharding.entityRefFor(PartyPersistentBehavior.TypeKey, shard.toString))
      .toList

    val result: Future[StatusReply[PersistedPartyRelationship]] = for {
      from <- getParty(seed.from)
      to   <- getParty(seed.to)
      role = PersistedPartyRole.fromApi(seed.role)
      _ <- isMissingRelationship(from.id, to.id, role, seed.product)
      partyRelationship = PersistedPartyRelationship.create(uuidSupplier, offsetDateTimeSupplier)(
        from.id,
        to.id,
        role,
        seed.product,
        pricingPlan = seed.pricingPlan,
        billing = seed.billing,
        institutionUpdate = seed.institutionUpdate
      )
      currentPartyRelationships <- commanders
        .traverse(
          _.ask(ref =>
            GetPartyRelationshipsByTo(
              to = to.id,
              roles = List.empty,
              states = List.empty,
              products = List.empty,
              productRoles = List.empty,
              ref
            )
          )
        )
        .map(_.flatten)
      verified                  <- isRelationshipAllowed(currentPartyRelationships, partyRelationship)
      partyRelationship         <- getCommander(from.id.toString).ask(ref => AddPartyRelationship(verified, ref))
    } yield partyRelationship

    onComplete(result) {
      case Success(statusReply) if statusReply.isSuccess => createRelationship201(statusReply.getValue.toRelationship)
      case Success(statusReply)                          =>
        logger.error(s"Error while creating relationship - Conflict - ${statusReply.getError.getMessage}")
        createRelationship409(problemOf(StatusCodes.Conflict, CreateRelationshipConflict))
      case Failure(ex: RelationshipAlreadyExists)        =>
        logger.error(s"Error while creating relationship - ${ex.getMessage}")
        createRelationship409(problemOf(StatusCodes.Conflict, ex))
      case Failure(ex)                                   =>
        logger.error(s"Error while creating relationship - ${ex.getMessage}")
        createRelationship400(problemOf(StatusCodes.BadRequest, CreateRelationshipError(ex.getMessage)))
    }

  }

  /** Code: 200, Message: successful operation, DataType: Relationships
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def getRelationships(
    from: Option[String],
    to: Option[String],
    roles: String,
    states: String,
    products: String,
    productRoles: String
  )(implicit
    toEntityMarshallerRelationships: ToEntityMarshaller[Relationships],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {

    logger.info(s"Getting relationships for from: ${from.getOrElse("Empty")} / to: ${to
        .getOrElse("Empty")}/ roles: $roles/ states: $states/ products: $products/ productRoles: $productRoles")

    def retrieveRelationshipsByTo(
      id: UUID,
      roles: List[PartyRole],
      states: List[RelationshipState],
      product: List[String],
      productRoles: List[String]
    ): Future[List[Relationship]] = {
      val commanders: List[EntityRef[Command]] = (0 until settings.numberOfShards)
        .map(shard => sharding.entityRefFor(PartyPersistentBehavior.TypeKey, shard.toString))
        .toList

      for {
        re <- commanders.traverse(
          _.ask[List[PersistedPartyRelationship]](ref =>
            GetPartyRelationshipsByTo(id, roles, states, product, productRoles, ref)
          )
        )
      } yield re.flatten.map(_.toRelationship)
    }

    def retrieveRelationshipsByFrom(
      id: UUID,
      roles: List[PartyRole],
      states: List[RelationshipState],
      product: List[String],
      productRoles: List[String]
    ): Future[List[Relationship]] =
      for {
        re <- getCommander(id.toString).ask(ref =>
          GetPartyRelationshipsByFrom(id, roles, states, product, productRoles, ref)
        )
      } yield re.map(_.toRelationship)

    def relationshipsFromParams(
      from: Option[UUID],
      to: Option[UUID],
      roles: List[PartyRole],
      states: List[RelationshipState],
      product: List[String],
      productRoles: List[String]
    ): Future[List[Relationship]] = (from, to) match {
      case (Some(f), Some(t)) =>
        retrieveRelationshipsByFrom(f, roles, states, product, productRoles).map(_.filter(_.to == t))
      case (Some(f), None)    => retrieveRelationshipsByFrom(f, roles, states, product, productRoles)
      case (None, Some(t))    => retrieveRelationshipsByTo(t, roles, states, product, productRoles)
      case _ => Future.failed(new RuntimeException("At least one query parameter between [from, to] must be passed"))
    }

    val result: Future[List[Relationship]] = for {
      fromUuid    <- from.traverse(_.toFutureUUID)
      toUuid      <- to.traverse(_.toFutureUUID)
      rolesArray  <- parseArrayParameters(roles).traverse(PartyRole.fromValue).toFuture
      statesArray <- parseArrayParameters(states).traverse(RelationshipState.fromValue).toFuture
      productsArray     = parseArrayParameters(products)
      productRolesArray = parseArrayParameters(productRoles)
      r <- relationshipsFromParams(fromUuid, toUuid, rolesArray, statesArray, productsArray, productRolesArray)
    } yield r

    onComplete(result) {
      case Success(relationships) => getRelationships200(Relationships(relationships))
      case Failure(ex)            =>
        logger.error(s"Error while getting relationships - ${ex.getMessage}")
        getRelationships400(problemOf(StatusCodes.BadRequest, GetRelationshipsError))
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
    logger.info("Creating token {}", tokenSeed.toString)

    val result: Future[StatusReply[TokenText]] = for {
      partyRelationships <- Future.traverse(tokenSeed.relationships.items)(getPartyRelationship)
      token              <- Token.generate(tokenSeed, partyRelationships, offsetDateTimeSupplier.get).toFuture
      tokenTxt           <- getCommander(tokenSeed.id).ask(ref => AddToken(token, ref))
    } yield tokenTxt

    manageCreationResponse(result, createToken201, createToken400)

  }

  private def getParty(id: UUID)(implicit ec: ExecutionContext, timeout: Timeout): Future[Party] = for {
    found <- getCommander(id.toString).ask(ref => GetParty(id, ref))
    party <- found.fold(Future.failed[Party](new RuntimeException(s"Party ${id.toString} not found")))(p =>
      Future.successful(p)
    )
  } yield party

  private def getPartyRelationship(relationship: Relationship): Future[PersistedPartyRelationship] =
    relationshipByInvolvedParties(
      from = relationship.from,
      to = relationship.to,
      role = PersistedPartyRole.fromApi(relationship.role),
      productId = relationship.product.id,
      productRole = relationship.product.role
    )

  private def isRelationshipAllowed(
    currentPartyRelationships: List[PersistedPartyRelationship],
    partyRelationships: PersistedPartyRelationship
  ): Future[PersistedPartyRelationship] = Future.fromTry {
    Either
      .cond(
        currentPartyRelationships.exists(_.state == PersistedPartyRelationshipState.Active) ||
          Set[PersistedPartyRole](PersistedPartyRole.Manager, PersistedPartyRole.Delegate)
            .contains(partyRelationships.role),
        partyRelationships,
        new RuntimeException("Operator without active manager")
      )
      .toTry
  }

  private def manageCreationResponse[A](result: Future[StatusReply[A]], success: A => Route, failure: Problem => Route)(
    implicit contexts: Seq[(String, String)]
  ): Route = {
    onComplete(result) {
      case Success(statusReply) if statusReply.isError =>
        logger.error(s"Error trying to create element - ${statusReply.getError.getMessage}")
        failure(problemOf(StatusCodes.BadRequest, CreateTokenBadRequest(statusReply.getError.getMessage)))
      case Success(a)                                  =>
        logger.info(s"Element successfully created")
        success(a.getValue)
      case Failure(ex)                                 =>
        logger.error(s"Error trying to create element - ${ex.getMessage}")
        failure(problemOf(StatusCodes.BadRequest, CreateTokenError(ex.getMessage)))
    }
  }

  /** Code: 200, Message: Party Attributes, DataType: Seq[String]
    * Code: 400, Message: Bad Request, DataType: Problem
    * Code: 404, Message: Party not found, DataType: Problem
    */
  override def getPartyAttributes(id: String)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    toEntityMarshallerAttributearray: ToEntityMarshaller[Seq[Attribute]],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info("Getting party {} attributes", id)
    val attributes: Future[StatusReply[Seq[InstitutionAttribute]]] = for {
      uuid                  <- id.toFutureUUID
      institutionAttributes <- getCommander(id).ask(ref => GetPartyAttributes(uuid, ref))
    } yield institutionAttributes

    onComplete(attributes) {
      case Success(result) if result.isSuccess =>
        getPartyAttributes200(result.getValue.map(InstitutionAttribute.toApi))
      case Success(_)                          =>
        val error = problemOf(StatusCodes.InternalServerError, GetPartyAttributesError)
        logger.error(s"Error while getting party $id attributes - Internal server error")
        complete((error.status, error))
      case Failure(ex)                         =>
        logger.error(s"Error while getting party $id attributes - ${ex.getMessage}")
        getPartyAttributes404(problemOf(StatusCodes.NotFound, PartyAttributesNotFound))
    }
  }

  /* does a recursive lookup through the shards until it finds the existing relationship for the involved parties */
  private def relationshipByInvolvedParties(
    from: UUID,
    to: UUID,
    role: PersistedPartyRole,
    productId: String,
    productRole: String
  ): Future[PersistedPartyRelationship] = {
    for {
      maybeRelationship <- getCommander(from.toString).ask(ref =>
        GetPartyRelationshipByAttributes(
          from = from,
          to = to,
          role = role,
          product = productId,
          productRole = productRole,
          ref
        )
      )
      result            <- maybeRelationship.toFuture(new RuntimeException("Relationship not found"))
    } yield result

  }

  /** flips result of relationship retrieval from the cluster.
    *
    * @return successful future if no relationship has been found in the cluster.
    */
  private def isMissingRelationship(
    from: UUID,
    to: UUID,
    role: PersistedPartyRole,
    product: RelationshipProductSeed
  ): Future[Boolean] = {
    relationshipByInvolvedParties(from, to, role, product.id, product.role).transformWith {
      case Success(relationship) => Future.failed(RelationshipAlreadyExists(relationship.id))
      case Failure(_)            => Future.successful(true)
    }
  }

  /** Code: 200, Message: Institution, DataType: Institution
    * Code: 400, Message: Bad Request, DataType: Problem
    * Code: 404, Message: Institution not found, DataType: Problem
    */
  override def getInstitutionById(id: String)(implicit
    toEntityMarshallerInstitution: ToEntityMarshaller[Institution],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info("Getting institution with id {}", id)
    def notFound: Route = getInstitutionById404(problemOf(StatusCodes.NotFound, GetInstitutionNotFound(id)))

    val institutions = for {
      institutionUUID <- id.toFutureUUID
      results         <- getCommander(id).ask(ref => GetParty(institutionUUID, ref))
    } yield results

    onComplete(institutions) {
      case Success(result) =>
        result
          .fold(notFound) { reply =>
            Party.convertToApi(reply).swap.fold(_ => notFound, p => getInstitutionById200(p))
          }
      case Failure(ex)     =>
        logger.error(s"Error getting institution with id $id - ${ex.getMessage}")
        getInstitutionById404(problemOf(StatusCodes.NotFound, GetInstitutionError))
    }
  }

  /** Code: 200, Message: Person, DataType: Person
    * Code: 400, Message: Bad Request, DataType: Problem
    * Code: 404, Message: Person not found, DataType: Problem
    */
  override def getPersonById(id: String)(implicit
    toEntityMarshallerPerson: ToEntityMarshaller[Person],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info("Getting person with id {}", id)
    def notFound: Route = getPersonById404(problemOf(StatusCodes.NotFound, GetPersonNotFound(id)))

    val persons = for {
      personUUID <- id.toFutureUUID
      results    <- getCommander(id).ask(ref => GetParty(personUUID, ref))
    } yield results

    onComplete(persons) {
      case Success(result) =>
        result.fold(notFound) { reply =>
          Party.convertToApi(reply).fold(_ => notFound, p => getPersonById200(p))
        }
      case Failure(ex)     =>
        logger.error(s"Error while getting person with id $id - ${ex.getMessage}")
        getPersonById404(problemOf(StatusCodes.NotFound, GetPersonError))
    }
  }

  /** Code: 200, Message: successful operation, DataType: Relationship
    * Code: 404, Message: Relationship not found, DataType: Problem
    */
  override def getRelationshipById(relationshipId: String)(implicit
    toEntityMarshallerRelationship: ToEntityMarshaller[Relationship],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info("Getting relationship with id {}", relationshipId)

    val commanders = (0 until settings.numberOfShards)
      .map(shard => sharding.entityRefFor(PartyPersistentBehavior.TypeKey, shard.toString))
      .toList

    val result: Future[Option[Relationship]] =
      for {
        uuid    <- relationshipId.toFutureUUID
        results <- commanders.traverse(_.ask(ref => GetPartyRelationshipById(uuid, ref)))
        maybePartyRelationship = results.find(_.isDefined).flatten
        partyRelationship      = maybePartyRelationship.map(_.toRelationship)
      } yield partyRelationship

    onComplete(result) {
      case Success(Some(relationship)) => getRelationshipById200(relationship)
      case Success(None)               =>
        logger.error(s"Error while getting relationship with id $relationshipId - Not found")
        getRelationshipById404(problemOf(StatusCodes.NotFound, GetRelationshipNotFound(relationshipId)))
      case Failure(ex)                 =>
        logger.error(s"Error while getting relationship with id $relationshipId - ${ex.getMessage}")
        getRelationshipById400(problemOf(StatusCodes.BadRequest, GetRelationshipError))
    }
  }

  /** Code: 200, Message: collection of institutions, DataType: BulkInstitutions
    * Code: 400, Message: Bad Request, DataType: Problem
    * Code: 404, Message: Institutions not found, DataType: Problem
    */
  override def bulkInstitutions(bulkPartiesSeed: BulkPartiesSeed)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    toEntityMarshallerBulkInstitutions: ToEntityMarshaller[BulkInstitutions],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info("Bulk institutions")

    def getParty(id: UUID): Future[Option[Party]] =
      getCommander(id.toString).ask(ref => GetParty(id, ref))

    val result = bulkPartiesSeed.partyIdentifiers.traverse(getParty)

    onComplete(result) {
      case Success(replies) =>
        val institutions: Seq[Institution] =
          replies.flatten.flatMap(p => Party.convertToApi(p).swap.fold(_ => None, inst => Some(inst)))

        val response = BulkInstitutions(
          found = institutions,
          notFound = bulkPartiesSeed.partyIdentifiers.diff(institutions.map(_.id)).map(_.toString)
        )
        bulkInstitutions200(response)
      case Failure(ex)      =>
        logger.error(s"Error while processing bulk institutions - ${ex.getMessage}")
        bulkInstitutions404(problemOf(StatusCodes.NotFound, GetBulkInstitutionsError))
    }

  }

  /** Code: 204, Message: Relationship activated
    * Code: 400, Message: Bad Request, DataType: Problem
    * Code: 404, Message: Relationship not found, DataType: Problem
    */
  override def activatePartyRelationshipById(
    relationshipId: String
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route = {
    logger.info("Activating relationship with id {}", relationshipId)
    val commanders = (0 until settings.numberOfShards)
      .map(shard => sharding.entityRefFor(PartyPersistentBehavior.TypeKey, shard.toString))
      .toList

    val result = for {
      uuid              <- relationshipId.toFutureUUID
      resultsCollection <- Future.traverse(commanders)(
        _.ask(ref => ActivatePartyRelationship(uuid, ref)).transform(Success(_))
      )
      _                 <- resultsCollection.reduce((r1, r2) => if (r1.isSuccess) r1 else r2).toFuture
    } yield ()

    onComplete(result) {
      case Success(_)  =>
        activatePartyRelationshipById204
      case Failure(ex) =>
        logger.error(s"Error while activating relationship with id $relationshipId - ${ex.getMessage}")
        activatePartyRelationshipById404(problemOf(StatusCodes.NotFound, ActivateRelationshipError(relationshipId)))
    }
  }

  /** Code: 204, Message: Relationship suspended
    * Code: 400, Message: Bad Request, DataType: Problem
    * Code: 404, Message: Relationship not found, DataType: Problem
    */
  override def suspendPartyRelationshipById(
    relationshipId: String
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route = {
    logger.info("Suspending relationship with id {}", relationshipId)
    val commanders = (0 until settings.numberOfShards)
      .map(shard => sharding.entityRefFor(PartyPersistentBehavior.TypeKey, shard.toString))
      .toList

    val result = for {
      uuid              <- relationshipId.toFutureUUID
      resultsCollection <- Future.traverse(commanders)(
        _.ask(ref => SuspendPartyRelationship(uuid, ref)).transform(Success(_))
      )
      _                 <- resultsCollection.reduce((r1, r2) => if (r1.isSuccess) r1 else r2).toFuture
    } yield ()

    onComplete(result) {
      case Success(_)  =>
        suspendPartyRelationshipById204
      case Failure(ex) =>
        logger.error(s"Error while suspending relationship with id $relationshipId - ${ex.getMessage}")
        suspendPartyRelationshipById404(problemOf(StatusCodes.NotFound, SuspendingRelationshipError))
    }
  }

  /** Code: 204, Message: relationship deleted
    * Code: 400, Message: Bad Request, DataType: Problem
    * Code: 404, Message: Relationship not found, DataType: Problem
    */
  override def deleteRelationshipById(
    relationshipId: String
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route = {
    logger.info("Deleting relationship with id {}", relationshipId)

    val commanders = (0 until settings.numberOfShards)
      .map(shard => sharding.entityRefFor(PartyPersistentBehavior.TypeKey, shard.toString))
      .toList

    val result: Future[Option[StatusReply[Unit]]] =
      for {
        uuid    <- relationshipId.toFutureUUID
        results <- commanders.traverse(_.ask(ref => DeletePartyRelationship(uuid, ref)))
        maybeDeletion = results.find(_.isSuccess)
      } yield maybeDeletion

    onComplete(result) {
      case Success(Some(reply)) =>
        if (reply.isSuccess) {
          deleteRelationshipById204
        } else {
          logger.error(s"Error while deleting relationship with id $relationshipId - Not found")
          deleteRelationshipById404(problemOf(StatusCodes.NotFound, DeletingRelationshipError(relationshipId)))
        }
      case Success(None)        =>
        logger.error(s"Error while deleting relationship with id $relationshipId - Not found")
        deleteRelationshipById404(problemOf(StatusCodes.NotFound, DeletingRelationshipNotFound(relationshipId)))
      case Failure(ex)          =>
        logger.error(s"Error while deleting relationship with id $relationshipId - ${ex.getMessage}")
        deleteRelationshipById400(problemOf(StatusCodes.BadRequest, DeletingRelationshipBadRequest(relationshipId)))
    }
  }
}
