package it.pagopa.pdnd.interop.uservice.partymanagement.model.party

sealed trait PartyRelationShipStatus {
  def stringify: String = this match {
    case Pending  => "Pending"
    case Active   => "Active"
    case Inactive => "Inactive"
    case Deleted  => "Deleted"
  }
}

case object Pending  extends PartyRelationShipStatus
case object Active   extends PartyRelationShipStatus
case object Inactive extends PartyRelationShipStatus
case object Deleted  extends PartyRelationShipStatus

object PartyRelationShipStatus {
  def apply(str: String): Either[Throwable, PartyRelationShipStatus] = str match {
    case "Pending"  => Right(Pending)
    case "Active"   => Right(Active)
    case "Inactive" => Right(Inactive)
    case "Deleted"  => Right(Deleted)
    case _          => Left(new RuntimeException("Invalid party status")) //TODO meaningful error
  }
}