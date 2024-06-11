package org.hyperledger.identus.pollux.core.service

import io.circe.Json
import org.hyperledger.identus.castor.core.model.did.CanonicalPrismDID
import org.hyperledger.identus.mercury.model.DidId
import org.hyperledger.identus.mercury.protocol.issuecredential.{IssueCredential, OfferCredential, RequestCredential}
import org.hyperledger.identus.pollux.core.model.{DidCommID, IssueCredentialRecord}
import org.hyperledger.identus.pollux.core.model.error.CredentialServiceError
import org.hyperledger.identus.pollux.core.model.error.CredentialServiceError.*
import org.hyperledger.identus.shared.models.WalletAccessContext
import zio.{mock, Duration, IO, UIO, URIO, URLayer, ZIO, ZLayer}
import zio.mock.{Mock, Proxy}

import java.util.UUID

object MockCredentialService extends Mock[CredentialService] {

  object CreateJWTIssueCredentialRecord
      extends Effect[
        (
            DidId,
            DidId,
            DidCommID,
            Option[String],
            Json,
            Option[Double],
            Option[Boolean],
            CanonicalPrismDID
        ),
        Nothing,
        IssueCredentialRecord
      ]
  object CreateSDJWTIssueCredentialRecord
      extends Effect[
        (
            DidId,
            DidId,
            DidCommID,
            Option[String],
            Json,
            Option[Double],
            Option[Boolean],
            CanonicalPrismDID
        ),
        Nothing,
        IssueCredentialRecord
      ]

  object CreateAnonCredsIssueCredentialRecord
      extends Effect[
        (
            DidId,
            DidId,
            DidCommID,
            UUID,
            Json,
            Option[Double],
            Option[Boolean],
            String
        ),
        Nothing,
        IssueCredentialRecord
      ]

  object ReceiveCredentialOffer extends Effect[OfferCredential, InvalidCredentialOffer, IssueCredentialRecord]
  object AcceptCredentialOffer
      extends Effect[(DidCommID, Option[String]), RecordNotFound | UnsupportedDidFormat, IssueCredentialRecord]
  object GenerateJWTCredentialRequest
      extends Effect[DidCommID, RecordNotFound | UnsupportedDidFormat, IssueCredentialRecord]
  object GenerateSDJWTCredentialRequest
      extends Effect[DidCommID, RecordNotFound | UnsupportedDidFormat, IssueCredentialRecord]
  object GenerateAnonCredsCredentialRequest extends Effect[DidCommID, RecordNotFound, IssueCredentialRecord]
  object ReceiveCredentialRequest
      extends Effect[
        RequestCredential,
        InvalidCredentialRequest | RecordNotFoundForThreadIdAndStates,
        IssueCredentialRecord
      ]
  object AcceptCredentialRequest extends Effect[DidCommID, RecordNotFound, IssueCredentialRecord]
  object GenerateJWTCredential
      extends Effect[(DidCommID, String), RecordNotFound | CredentialRequestValidationFailed, IssueCredentialRecord]
  object GenerateSDJWTCredential
      extends Effect[(DidCommID, Duration), RecordNotFound | ExpirationDateHasPassed, IssueCredentialRecord]
  object GenerateAnonCredsCredential extends Effect[DidCommID, RecordNotFound, IssueCredentialRecord]
  object ReceiveCredentialIssue
      extends Effect[
        IssueCredential,
        InvalidCredentialIssue | RecordNotFoundForThreadIdAndStates,
        IssueCredentialRecord
      ]
  object MarkOfferSent extends Effect[DidCommID, InvalidStateForOperation, IssueCredentialRecord]
  object MarkRequestSent extends Effect[DidCommID, InvalidStateForOperation, IssueCredentialRecord]
  object MarkCredentialSent extends Effect[DidCommID, InvalidStateForOperation, IssueCredentialRecord]
  object MarkCredentialPublicationPending extends Effect[DidCommID, CredentialServiceError, IssueCredentialRecord]
  object MarkCredentialPublicationQueued extends Effect[DidCommID, CredentialServiceError, IssueCredentialRecord]
  object MarkCredentialPublished extends Effect[DidCommID, CredentialServiceError, IssueCredentialRecord]
  object ReportProcessingFailure extends Effect[(DidCommID, Option[String]), Nothing, Unit]

  override val compose: URLayer[mock.Proxy, CredentialService] = ZLayer {
    for {
      proxy <- ZIO.service[Proxy]
    } yield new CredentialService {

      override def createJWTIssueCredentialRecord(
          pairwiseIssuerDID: DidId,
          pairwiseHolderDID: DidId,
          thid: DidCommID,
          maybeSchemaId: Option[String],
          claims: Json,
          validityPeriod: Option[Double],
          automaticIssuance: Option[Boolean],
          issuingDID: CanonicalPrismDID
      ): URIO[WalletAccessContext, IssueCredentialRecord] =
        proxy(
          CreateJWTIssueCredentialRecord,
          pairwiseIssuerDID,
          pairwiseHolderDID,
          thid,
          maybeSchemaId,
          claims,
          validityPeriod,
          automaticIssuance,
          issuingDID
        )

      override def createSDJWTIssueCredentialRecord(
          pairwiseIssuerDID: DidId,
          pairwiseHolderDID: DidId,
          thid: DidCommID,
          maybeSchemaId: Option[String],
          claims: Json,
          validityPeriod: Option[Double],
          automaticIssuance: Option[Boolean],
          issuingDID: CanonicalPrismDID
      ): URIO[WalletAccessContext, IssueCredentialRecord] =
        proxy(
          CreateSDJWTIssueCredentialRecord,
          pairwiseIssuerDID,
          pairwiseHolderDID,
          thid,
          maybeSchemaId,
          claims,
          validityPeriod,
          automaticIssuance,
          issuingDID
        )

      override def createAnonCredsIssueCredentialRecord(
          pairwiseIssuerDID: DidId,
          pairwiseHolderDID: DidId,
          thid: DidCommID,
          credentialDefinitionGUID: UUID,
          credentialDefinitionId: String,
          claims: Json,
          validityPeriod: Option[Double],
          automaticIssuance: Option[Boolean]
      ): URIO[WalletAccessContext, IssueCredentialRecord] =
        proxy(
          CreateAnonCredsIssueCredentialRecord,
          pairwiseIssuerDID,
          pairwiseHolderDID,
          thid,
          credentialDefinitionGUID,
          claims,
          validityPeriod,
          automaticIssuance,
          credentialDefinitionId
        )

      override def receiveCredentialOffer(offer: OfferCredential): IO[InvalidCredentialOffer, IssueCredentialRecord] =
        proxy(ReceiveCredentialOffer, offer)

      override def acceptCredentialOffer(
          recordId: DidCommID,
          subjectId: Option[String]
      ): IO[RecordNotFound | UnsupportedDidFormat, IssueCredentialRecord] =
        proxy(AcceptCredentialOffer, recordId, subjectId)

      override def generateJWTCredentialRequest(
          recordId: DidCommID
      ): ZIO[WalletAccessContext, RecordNotFound | UnsupportedDidFormat, IssueCredentialRecord] =
        proxy(GenerateJWTCredentialRequest, recordId)

      override def generateSDJWTCredentialRequest(
          recordId: DidCommID
      ): ZIO[WalletAccessContext, RecordNotFound | UnsupportedDidFormat, IssueCredentialRecord] =
        proxy(GenerateSDJWTCredentialRequest, recordId)

      override def generateAnonCredsCredentialRequest(
          recordId: DidCommID
      ): ZIO[WalletAccessContext, RecordNotFound, IssueCredentialRecord] =
        proxy(GenerateAnonCredsCredentialRequest, recordId)

      override def receiveCredentialRequest(
          request: RequestCredential
      ): IO[InvalidCredentialRequest | RecordNotFoundForThreadIdAndStates, IssueCredentialRecord] =
        proxy(ReceiveCredentialRequest, request)

      override def acceptCredentialRequest(recordId: DidCommID): IO[RecordNotFound, IssueCredentialRecord] =
        proxy(AcceptCredentialRequest, recordId)

      override def generateJWTCredential(
          recordId: DidCommID,
          statusListRegistryUrl: String,
      ): ZIO[WalletAccessContext, RecordNotFound | CredentialRequestValidationFailed, IssueCredentialRecord] =
        proxy(GenerateJWTCredential, recordId, statusListRegistryUrl)

      override def generateSDJWTCredential(
          recordId: DidCommID,
          expirationTime: Duration,
      ): ZIO[WalletAccessContext, RecordNotFound | ExpirationDateHasPassed, IssueCredentialRecord] =
        proxy(GenerateSDJWTCredential, recordId, expirationTime)

      override def generateAnonCredsCredential(
          recordId: DidCommID
      ): ZIO[WalletAccessContext, RecordNotFound, IssueCredentialRecord] =
        proxy(GenerateAnonCredsCredential, recordId)

      override def receiveCredentialIssue(
          issueCredential: IssueCredential
      ): IO[InvalidCredentialIssue | RecordNotFoundForThreadIdAndStates, IssueCredentialRecord] =
        proxy(ReceiveCredentialIssue, issueCredential)

      override def markOfferSent(
          recordId: DidCommID
      ): ZIO[WalletAccessContext, InvalidStateForOperation, IssueCredentialRecord] =
        proxy(MarkOfferSent, recordId)

      override def markRequestSent(
          recordId: DidCommID
      ): ZIO[WalletAccessContext, InvalidStateForOperation, IssueCredentialRecord] =
        proxy(MarkRequestSent, recordId)

      override def markCredentialSent(
          recordId: DidCommID
      ): ZIO[WalletAccessContext, InvalidStateForOperation, IssueCredentialRecord] =
        proxy(MarkCredentialSent, recordId)

      override def reportProcessingFailure(
          recordId: DidCommID,
          failReason: Option[String]
      ): URIO[WalletAccessContext, Unit] =
        proxy(ReportProcessingFailure, recordId, failReason)

      override def getIssueCredentialRecords(
          ignoreWithZeroRetries: Boolean,
          offset: Option[Int] = None,
          limit: Option[Int] = None
      ): URIO[WalletAccessContext, (Seq[IssueCredentialRecord], Int)] =
        ???

      override def getIssueCredentialRecordsByStates(
          ignoreWithZeroRetries: Boolean,
          limit: Int,
          states: IssueCredentialRecord.ProtocolState*
      ): URIO[WalletAccessContext, Seq[IssueCredentialRecord]] =
        ???

      override def getIssueCredentialRecordsByStatesForAllWallets(
          ignoreWithZeroRetries: Boolean,
          limit: Int,
          states: IssueCredentialRecord.ProtocolState*
      ): UIO[Seq[IssueCredentialRecord]] =
        ???

      override def findById(
          recordId: DidCommID
      ): URIO[WalletAccessContext, Option[IssueCredentialRecord]] =
        ???

      override def getById(recordId: DidCommID): URIO[WalletAccessContext, IssueCredentialRecord] = ???

      override def getIssueCredentialRecordByThreadId(
          thid: DidCommID,
          ignoreWithZeroRetries: Boolean
      ): URIO[WalletAccessContext, Option[IssueCredentialRecord]] = ???
    }
  }
}
