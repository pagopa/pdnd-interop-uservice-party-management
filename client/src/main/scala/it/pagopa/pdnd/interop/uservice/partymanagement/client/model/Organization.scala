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

case class Organization (
  id: UUID,
  /* DN */
  institutionId: String,
  /* an accessory code (e.g. codice ipa) */
  code: Option[String] = None,
  description: String,
  digitalAddress: String,
  /* organization fiscal code */
  fiscalCode: String,
  attributes: Seq[String],
  products: Seq[String]
) extends ApiModel

