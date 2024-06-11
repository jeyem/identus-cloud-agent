package org.hyperledger.identus.pollux.core.service

import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import org.hyperledger.identus.castor.core.model.did.CanonicalPrismDID
import org.hyperledger.identus.mercury.model.DidId
import org.hyperledger.identus.mercury.protocol.issuecredential.{
  Attribute,
  IssueCredential,
  OfferCredential,
  RequestCredential
}
import org.hyperledger.identus.pollux.core.model.*
import org.hyperledger.identus.pollux.core.model.error.CredentialServiceError
import org.hyperledger.identus.pollux.core.model.error.CredentialServiceError.*
import org.hyperledger.identus.shared.models.WalletAccessContext
import zio.{Duration, IO, UIO, URIO, ZIO}

import java.nio.charset.StandardCharsets
import java.util.UUID

trait CredentialService {

  def createJWTIssueCredentialRecord(
      pairwiseIssuerDID: DidId,
      pairwiseHolderDID: DidId,
      thid: DidCommID,
      maybeSchemaId: Option[String],
      claims: io.circe.Json,
      validityPeriod: Option[Double] = None,
      automaticIssuance: Option[Boolean],
      issuingDID: CanonicalPrismDID
  ): URIO[WalletAccessContext, IssueCredentialRecord]

  def createSDJWTIssueCredentialRecord(
      pairwiseIssuerDID: DidId,
      pairwiseHolderDID: DidId,
      thid: DidCommID,
      maybeSchemaId: Option[String],
      claims: io.circe.Json,
      validityPeriod: Option[Double] = None,
      automaticIssuance: Option[Boolean],
      issuingDID: CanonicalPrismDID
  ): URIO[WalletAccessContext, IssueCredentialRecord]

  def createAnonCredsIssueCredentialRecord(
      pairwiseIssuerDID: DidId,
      pairwiseHolderDID: DidId,
      thid: DidCommID,
      credentialDefinitionGUID: UUID,
      credentialDefinitionId: String,
      claims: io.circe.Json,
      validityPeriod: Option[Double] = None,
      automaticIssuance: Option[Boolean]
  ): URIO[WalletAccessContext, IssueCredentialRecord]

  /** Return a list of records as well as a count of all filtered items */
  def getIssueCredentialRecords(
      ignoreWithZeroRetries: Boolean,
      offset: Option[Int] = None,
      limit: Option[Int] = None
  ): URIO[WalletAccessContext, (Seq[IssueCredentialRecord], Int)]

  def getIssueCredentialRecordsByStates(
      ignoreWithZeroRetries: Boolean,
      limit: Int,
      states: IssueCredentialRecord.ProtocolState*
  ): URIO[WalletAccessContext, Seq[IssueCredentialRecord]]

  def getIssueCredentialRecordsByStatesForAllWallets(
      ignoreWithZeroRetries: Boolean,
      limit: Int,
      states: IssueCredentialRecord.ProtocolState*
  ): UIO[Seq[IssueCredentialRecord]]

  def findById(
      recordId: DidCommID
  ): URIO[WalletAccessContext, Option[IssueCredentialRecord]]

  def getById(
      recordId: DidCommID
  ): URIO[WalletAccessContext, IssueCredentialRecord]

  def getIssueCredentialRecordByThreadId(
      thid: DidCommID,
      ignoreWithZeroRetries: Boolean
  ): URIO[WalletAccessContext, Option[IssueCredentialRecord]]

  def receiveCredentialOffer(
      offer: OfferCredential
  ): ZIO[WalletAccessContext, InvalidCredentialOffer, IssueCredentialRecord]

  def acceptCredentialOffer(
      recordId: DidCommID,
      subjectId: Option[String]
  ): ZIO[WalletAccessContext, RecordNotFound | UnsupportedDidFormat, IssueCredentialRecord]

  def generateJWTCredentialRequest(
      recordId: DidCommID
  ): ZIO[WalletAccessContext, RecordNotFound | UnsupportedDidFormat, IssueCredentialRecord]

  def generateSDJWTCredentialRequest(
      recordId: DidCommID
  ): ZIO[WalletAccessContext, RecordNotFound | UnsupportedDidFormat, IssueCredentialRecord]

  def generateAnonCredsCredentialRequest(
      recordId: DidCommID
  ): ZIO[WalletAccessContext, RecordNotFound, IssueCredentialRecord]

  def receiveCredentialRequest(
      request: RequestCredential
  ): ZIO[WalletAccessContext, InvalidCredentialRequest | RecordNotFoundForThreadIdAndStates, IssueCredentialRecord]

  def acceptCredentialRequest(
      recordId: DidCommID
  ): ZIO[WalletAccessContext, RecordNotFound, IssueCredentialRecord]

  def generateJWTCredential(
      recordId: DidCommID,
      statusListRegistryUrl: String,
  ): ZIO[WalletAccessContext, RecordNotFound | CredentialRequestValidationFailed, IssueCredentialRecord]

  def generateSDJWTCredential(
      recordId: DidCommID,
      expirationTime: Duration,
  ): ZIO[WalletAccessContext, RecordNotFound | ExpirationDateHasPassed, IssueCredentialRecord]

  def generateAnonCredsCredential(
      recordId: DidCommID
  ): ZIO[WalletAccessContext, RecordNotFound, IssueCredentialRecord]

  def receiveCredentialIssue(
      issueCredential: IssueCredential
  ): ZIO[WalletAccessContext, InvalidCredentialIssue | RecordNotFoundForThreadIdAndStates, IssueCredentialRecord]

  def markOfferSent(
      recordId: DidCommID
  ): ZIO[WalletAccessContext, InvalidStateForOperation, IssueCredentialRecord]

  def markRequestSent(
      recordId: DidCommID
  ): ZIO[WalletAccessContext, InvalidStateForOperation, IssueCredentialRecord]

  def markCredentialSent(
      recordId: DidCommID
  ): ZIO[WalletAccessContext, CredentialServiceError, IssueCredentialRecord]

  def reportProcessingFailure(
      recordId: DidCommID,
      failReason: Option[String]
  ): URIO[WalletAccessContext, Unit]

}

object CredentialService {
  def convertJsonClaimsToAttributes(
      claims: io.circe.Json
  ): UIO[Seq[Attribute]] = {
    for {
      fields <- ZIO.succeed(claims.asObject.map(_.toMap).getOrElse(Map.empty).toList)
      res <- ZIO.foreach(fields) {
        case (k, v) if v.isString =>
          ZIO.succeed(Attribute(name = k, value = v.asString.get))
        case (k, v) =>
          ZIO.succeed {
            val jsonValue = v.noSpaces
            Attribute(
              name = k,
              value = java.util.Base64.getUrlEncoder.encodeToString(jsonValue.getBytes(StandardCharsets.UTF_8)),
              media_type = Some("application/json"),
            )
          }
      }
    } yield res
  }

  def convertAttributesToJsonClaims(
      attributes: Seq[Attribute]
  ): IO[CredentialServiceError, JsonObject] = {
    for {
      claims <- ZIO.foldLeft(attributes)(JsonObject()) { case (jsonObject, attr) =>
        attr.media_type match
          case None =>
            ZIO.succeed(jsonObject.add(attr.name, attr.value.asJson))

          case Some("application/json") =>
            val jsonBytes = java.util.Base64.getUrlDecoder.decode(attr.value.getBytes(StandardCharsets.UTF_8))
            io.circe.parser.parse(new String(jsonBytes, StandardCharsets.UTF_8)) match
              case Right(value) => ZIO.succeed(jsonObject.add(attr.name, value))
              case Left(error)  => ZIO.fail(VCClaimsValueParsingError(error.message))

          case Some(media_type) =>
            ZIO.fail(UnsupportedVCClaimsMediaType(media_type))
      }
    } yield claims
  }
}
