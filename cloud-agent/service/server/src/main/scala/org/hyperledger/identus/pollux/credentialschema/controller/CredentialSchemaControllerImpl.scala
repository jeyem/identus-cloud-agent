package org.hyperledger.identus.pollux.credentialschema.controller

import org.hyperledger.identus.agent.walletapi.model.{ManagedDIDState, PublicationState}
import org.hyperledger.identus.agent.walletapi.service.ManagedDIDService
import org.hyperledger.identus.api.http.*
import org.hyperledger.identus.api.http.model.{CollectionStats, Order, Pagination}
import org.hyperledger.identus.castor.core.model.did.{LongFormPrismDID, PrismDID}
import org.hyperledger.identus.pollux.core.model.schema.CredentialSchema.FilteredEntries
import org.hyperledger.identus.pollux.core.service.CredentialSchemaService
import org.hyperledger.identus.pollux.credentialschema.http.{
  CredentialSchemaInput,
  CredentialSchemaResponse,
  CredentialSchemaResponsePage,
  FilterInput
}
import org.hyperledger.identus.pollux.credentialschema.http.CredentialSchemaInput.toDomain
import org.hyperledger.identus.pollux.credentialschema.http.CredentialSchemaResponse.fromDomain
import org.hyperledger.identus.shared.models.WalletAccessContext
import zio.*
import zio.json.ast.Json

import java.util.UUID
import scala.language.implicitConversions

class CredentialSchemaControllerImpl(service: CredentialSchemaService, managedDIDService: ManagedDIDService)
    extends CredentialSchemaController {
  override def createSchema(
      in: CredentialSchemaInput
  )(implicit
      rc: RequestContext
  ): ZIO[WalletAccessContext, ErrorResponse, CredentialSchemaResponse] = {
    for {
      validated <- validatePrismDID(in.author)
      result <- service
        .create(toDomain(in))
        .map(cs => fromDomain(cs).withBaseUri(rc.request.uri))
    } yield result
  }

  override def updateSchema(author: String, id: UUID, in: CredentialSchemaInput)(implicit
      rc: RequestContext
  ): ZIO[WalletAccessContext, ErrorResponse, CredentialSchemaResponse] = {
    for {
      _ <- validatePrismDID(in.author)
      result <- service
        .update(id, toDomain(in).copy(author = author))
        .map(cs => fromDomain(cs).withBaseUri(rc.request.uri))
    } yield result
  }

  override def getSchemaByGuid(guid: UUID)(implicit
      rc: RequestContext
  ): IO[ErrorResponse, CredentialSchemaResponse] = {
    service
      .getByGUID(guid)
      .map(
        fromDomain(_)
          .withSelf(rc.request.uri.toString)
      )
  }

  override def getSchemaJsonByGuid(guid: UUID)(implicit
      rc: RequestContext
  ): IO[ErrorResponse, Json] = {
    service
      .getByGUID(guid)
      .map(
        _.schema
      )
  }

  override def delete(guid: UUID)(implicit
      rc: RequestContext
  ): ZIO[WalletAccessContext, ErrorResponse, CredentialSchemaResponse] = {
    service
      .delete(guid)
      .map(
        fromDomain(_)
          .withBaseUri(rc.request.uri)
      )
  }

  override def lookupSchemas(
      filter: FilterInput,
      pagination: Pagination,
      order: Option[Order]
  )(implicit
      rc: RequestContext
  ): ZIO[WalletAccessContext, ErrorResponse, CredentialSchemaResponsePage] = {
    for {
      filteredEntries: FilteredEntries <- service.lookup(
        filter.toDomain,
        pagination.offset,
        pagination.limit
      )
      entries = filteredEntries.entries
        .map(fromDomain(_).withBaseUri(rc.request.uri))
        .toList
      page = CredentialSchemaResponsePage(entries)
      stats = CollectionStats(filteredEntries.totalCount, filteredEntries.count)
    } yield CredentialSchemaControllerLogic(rc, pagination, page, stats).result
  }

  private def validatePrismDID(author: String) =
    for {
      authorDID <- ZIO
        .fromEither(PrismDID.fromString(author))
        .mapError(ex => ErrorResponse.badRequest(detail = Some(s"Unable to parse Prism DID from '${author}' due: $ex")))
      longFormPrismDID <- getLongForm(authorDID, true)
    } yield longFormPrismDID

  private def getLongForm(
      did: PrismDID,
      allowUnpublishedIssuingDID: Boolean = false
  ): ZIO[WalletAccessContext, ErrorResponse, LongFormPrismDID] = {
    for {
      didState <- managedDIDService
        .getManagedDIDState(did.asCanonical)
        .mapError(e =>
          ErrorResponse.internalServerError(detail =
            Some(s"Error occurred while getting DID from wallet: ${e.toString}")
          )
        )
        .someOrFail(ErrorResponse.notFound(detail = Some(s"Issuer DID does not exist in the wallet: $did")))
        .flatMap {
          case s @ ManagedDIDState(_, _, _: PublicationState.Published) => ZIO.succeed(s)
          case s =>
            ZIO.cond(
              allowUnpublishedIssuingDID,
              s,
              ErrorResponse.badRequest(detail = Some(s"Issuer DID must be published: $did"))
            )
        }
      longFormPrismDID = PrismDID.buildLongFormFromOperation(didState.createOperation)
    } yield longFormPrismDID
  }
}

object CredentialSchemaControllerImpl {
  val layer: URLayer[CredentialSchemaService & ManagedDIDService, CredentialSchemaController] =
    ZLayer.fromFunction(CredentialSchemaControllerImpl(_, _))
}
