package org.hyperledger.identus.pollux.credentialschema.controller

import org.hyperledger.identus.api.http.model.{CollectionStats, Pagination}
import org.hyperledger.identus.api.http.RequestContext
import org.hyperledger.identus.api.util.PaginationUtils
import org.hyperledger.identus.pollux.credentialschema.http.CredentialSchemaResponsePage
import sttp.model.Uri

case class CredentialSchemaControllerLogic(
    ctx: RequestContext,
    pagination: Pagination,
    page: CredentialSchemaResponsePage,
    stats: CollectionStats
) {

  private def composeNextUri(uri: Uri): Option[Uri] =
    PaginationUtils.composeNextUri(uri, page.contents, pagination, stats)

  private def composePreviousUri(uri: Uri): Option[Uri] =
    PaginationUtils.composePreviousUri(uri, page.contents, pagination, stats)

  def result: CredentialSchemaResponsePage = {
    val self = ctx.request.uri.toString
    val pageOf = ctx.request.uri.copy(querySegments = Seq.empty).toString
    val next = composeNextUri(ctx.request.uri).map(_.toString)
    val previous = composePreviousUri(ctx.request.uri).map(_.toString)

    val pageResult = page.copy(
      self = self,
      pageOf = pageOf,
      next = next,
      previous = previous,
      contents = page.contents.map(item =>
        item.withBaseUri(
          ctx.request.uri.copy(querySegments = Seq.empty)
        )
      )
    )

    pageResult
  }
}
