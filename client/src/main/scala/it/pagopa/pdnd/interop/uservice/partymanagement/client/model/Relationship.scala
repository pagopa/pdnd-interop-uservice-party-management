/**
 * Party Management Micro Service
 * This service is the party manager
 *
 * The version of the OpenAPI document: {{version}}
 * Contact: support@example.com
 *
 * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech).
 * https://openapi-generator.tech
 * Do not edit the class manually.
 */
package it.pagopa.pdnd.interop.uservice.partymanagement.client.model

import java.util.UUID
import it.pagopa.pdnd.interop.uservice.partymanagement.client.invoker.ApiModel

case class Relationship (
  id: UUID,
  /* person ID */
  from: UUID,
  /* organization ID */
  to: UUID,
  /* path of the file containing the signed onboarding document */
  filePath: Option[String] = None,
  /* name of the file containing the signed onboarding document */
  fileName: Option[String] = None,
  /* content type of the file containing the signed onboarding document */
  contentType: Option[String] = None,
  /* represents the generic available role types for the relationship */
  role: RelationshipEnums.Role,
  /* user role in the application context (e.g.: administrator, security user). This MUST belong to the configured set of application specific platform roles */
  platformRole: String,
  status: RelationshipEnums.Status
) extends ApiModel

object RelationshipEnums {

  type Role = Role.Value
  type Status = Status.Value
  object Role extends Enumeration {
    val Manager = Value("Manager")
    val Delegate = Value("Delegate")
    val Operator = Value("Operator")
  }

  object Status extends Enumeration {
    val Pending = Value("Pending")
    val Active = Value("Active")
    val Suspended = Value("Suspended")
  }

}