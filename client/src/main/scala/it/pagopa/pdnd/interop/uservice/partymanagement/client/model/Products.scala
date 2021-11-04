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

import it.pagopa.pdnd.interop.uservice.partymanagement.client.invoker.ApiModel

case class Products (
  /* set of products to define for this organization */
  products: Seq[String]
) extends ApiModel

