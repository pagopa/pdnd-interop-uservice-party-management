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

case class RelationshipSeed (
  /* person ID */
  from: UUID,
  /* organization ID */
  to: UUID,
  /* represents the generic available role types for the relationship */
  role: RelationshipSeedEnums.Role,
  /* user role in the application context (e.g.: administrator, security user). This MUST belong to the configured set of application specific platform roles */
  platformRole: String
) extends ApiModel

object RelationshipSeedEnums {

  type Role = Role.Value
  object Role extends Enumeration {
    val Manager = Value("Manager")
    val Delegate = Value("Delegate")
    val Operator = Value("Operator")
  }

}
