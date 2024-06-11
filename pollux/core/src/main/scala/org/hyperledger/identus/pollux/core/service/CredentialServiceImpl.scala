package org.hyperledger.identus.pollux.core.service

import io.circe.syntax.*
import io.circe.Json
import org.hyperledger.identus.agent.walletapi.model.{ManagedDIDState, PublicationState}
import org.hyperledger.identus.agent.walletapi.service.ManagedDIDService
import org.hyperledger.identus.agent.walletapi.storage.GenericSecretStorage
import org.hyperledger.identus.castor.core.model.did.*
import org.hyperledger.identus.castor.core.service.DIDService
import org.hyperledger.identus.mercury.model.*
import org.hyperledger.identus.mercury.protocol.issuecredential.*
import org.hyperledger.identus.pollux.*
import org.hyperledger.identus.pollux.anoncreds.*
import org.hyperledger.identus.pollux.core.model.*
import org.hyperledger.identus.pollux.core.model.error.CredentialServiceError
import org.hyperledger.identus.pollux.core.model.error.CredentialServiceError.*
import org.hyperledger.identus.pollux.core.model.presentation.*
import org.hyperledger.identus.pollux.core.model.schema.{CredentialDefinition, CredentialSchema}
import org.hyperledger.identus.pollux.core.model.secret.CredentialDefinitionSecret
import org.hyperledger.identus.pollux.core.model.CredentialFormat.AnonCreds
import org.hyperledger.identus.pollux.core.model.IssueCredentialRecord.ProtocolState.OfferReceived
import org.hyperledger.identus.pollux.core.repository.{CredentialRepository, CredentialStatusListRepository}
import org.hyperledger.identus.pollux.sdjwt.*
import org.hyperledger.identus.pollux.vc.jwt.{ES256KSigner, Issuer as JwtIssuer, *}
import org.hyperledger.identus.shared.crypto.{Ed25519KeyPair, Ed25519PublicKey, Secp256k1KeyPair}
import org.hyperledger.identus.shared.http.{DataUrlResolver, GenericUriResolver}
import org.hyperledger.identus.shared.models.WalletAccessContext
import org.hyperledger.identus.shared.utils.aspects.CustomMetricsAspect
import zio.*
import zio.prelude.ZValidation

import java.net.URI
import java.time.{Instant, ZoneId}
import java.util.UUID
import scala.language.implicitConversions

object CredentialServiceImpl {
  val layer: URLayer[
    CredentialRepository & CredentialStatusListRepository & DidResolver & URIDereferencer & GenericSecretStorage &
      CredentialDefinitionService & LinkSecretService & DIDService & ManagedDIDService,
    CredentialService
  ] = {
    ZLayer.fromZIO {
      for {
        credentialRepo <- ZIO.service[CredentialRepository]
        credentialStatusListRepo <- ZIO.service[CredentialStatusListRepository]
        didResolver <- ZIO.service[DidResolver]
        uriDereferencer <- ZIO.service[URIDereferencer]
        genericSecretStorage <- ZIO.service[GenericSecretStorage]
        credDefenitionService <- ZIO.service[CredentialDefinitionService]
        linkSecretService <- ZIO.service[LinkSecretService]
        didService <- ZIO.service[DIDService]
        manageDidService <- ZIO.service[ManagedDIDService]
        issueCredentialSem <- Semaphore.make(1)
      } yield CredentialServiceImpl(
        credentialRepo,
        credentialStatusListRepo,
        didResolver,
        uriDereferencer,
        genericSecretStorage,
        credDefenitionService,
        linkSecretService,
        didService,
        manageDidService,
        5,
        issueCredentialSem
      )
    }
  }

  //  private val VC_JSON_SCHEMA_URI = "https://w3c-ccg.github.io/vc-json-schemas/schema/2.0/schema.json"
  private val VC_JSON_SCHEMA_TYPE = "CredentialSchema2022"
}

private class CredentialServiceImpl(
    credentialRepository: CredentialRepository,
    credentialStatusListRepository: CredentialStatusListRepository,
    didResolver: DidResolver,
    uriDereferencer: URIDereferencer,
    genericSecretStorage: GenericSecretStorage,
    credentialDefinitionService: CredentialDefinitionService,
    linkSecretService: LinkSecretService,
    didService: DIDService,
    managedDIDService: ManagedDIDService,
    maxRetries: Int = 5, // TODO move to config
    issueCredentialSem: Semaphore
) extends CredentialService {

  import CredentialServiceImpl.*
  import IssueCredentialRecord.*

  override def getIssueCredentialRecords(
      ignoreWithZeroRetries: Boolean,
      offset: Option[Int],
      limit: Option[Int]
  ): URIO[WalletAccessContext, (Seq[IssueCredentialRecord], Int)] =
    credentialRepository.findAll(ignoreWithZeroRetries = ignoreWithZeroRetries, offset = offset, limit = limit)

  override def getIssueCredentialRecordByThreadId(
      thid: DidCommID,
      ignoreWithZeroRetries: Boolean
  ): URIO[WalletAccessContext, Option[IssueCredentialRecord]] =
    credentialRepository.findByThreadId(thid, ignoreWithZeroRetries)

  override def findById(
      recordId: DidCommID
  ): URIO[WalletAccessContext, Option[IssueCredentialRecord]] =
    credentialRepository.findById(recordId)

  override def getById(
      recordId: DidCommID
  ): URIO[WalletAccessContext, IssueCredentialRecord] =
    credentialRepository.getById(recordId)

  override def createJWTIssueCredentialRecord(
      pairwiseIssuerDID: DidId,
      pairwiseHolderDID: DidId,
      thid: DidCommID,
      maybeSchemaId: Option[String],
      claims: Json,
      validityPeriod: Option[Double],
      automaticIssuance: Option[Boolean],
      issuingDID: CanonicalPrismDID
  ): URIO[WalletAccessContext, IssueCredentialRecord] = {
    for {
      _ <- validateClaimsAgainstSchemaIfAny(claims, maybeSchemaId)
      attributes <- CredentialService.convertJsonClaimsToAttributes(claims)
      offer <- createDidCommOfferCredential(
        pairwiseIssuerDID = pairwiseIssuerDID,
        pairwiseHolderDID = pairwiseHolderDID,
        maybeSchemaId = maybeSchemaId,
        claims = attributes,
        thid = thid,
        UUID.randomUUID().toString,
        "domain",
        IssueCredentialOfferFormat.JWT
      )
      record <- ZIO.succeed(
        IssueCredentialRecord(
          id = DidCommID(),
          createdAt = Instant.now,
          updatedAt = None,
          thid = thid,
          schemaUri = maybeSchemaId,
          credentialDefinitionId = None,
          credentialDefinitionUri = None,
          credentialFormat = CredentialFormat.JWT,
          role = IssueCredentialRecord.Role.Issuer,
          subjectId = None,
          validityPeriod = validityPeriod,
          automaticIssuance = automaticIssuance,
          protocolState = IssueCredentialRecord.ProtocolState.OfferPending,
          offerCredentialData = Some(offer),
          requestCredentialData = None,
          anonCredsRequestMetadata = None,
          issueCredentialData = None,
          issuedCredentialRaw = None,
          issuingDID = Some(issuingDID),
          metaRetries = maxRetries,
          metaNextRetry = Some(Instant.now()),
          metaLastFailure = None,
        )
      )
      count <- credentialRepository
        .create(record) @@ CustomMetricsAspect
        .startRecordingTime(s"${record.id}_issuer_offer_pending_to_sent_ms_gauge")
    } yield record
  }

  override def createSDJWTIssueCredentialRecord(
      pairwiseIssuerDID: DidId,
      pairwiseHolderDID: DidId,
      thid: DidCommID,
      maybeSchemaId: Option[String],
      claims: io.circe.Json,
      validityPeriod: Option[Double] = None,
      automaticIssuance: Option[Boolean],
      issuingDID: CanonicalPrismDID
  ): URIO[WalletAccessContext, IssueCredentialRecord] =
    for {
      _ <- validateClaimsAgainstSchemaIfAny(claims, maybeSchemaId)
      attributes <- CredentialService.convertJsonClaimsToAttributes(claims)
      offer <- createDidCommOfferCredential(
        pairwiseIssuerDID = pairwiseIssuerDID,
        pairwiseHolderDID = pairwiseHolderDID,
        maybeSchemaId = maybeSchemaId,
        claims = attributes,
        thid = thid,
        UUID.randomUUID().toString,
        "domain",
        IssueCredentialOfferFormat.SDJWT
      )
      record <- ZIO.succeed(
        IssueCredentialRecord(
          id = DidCommID(),
          createdAt = Instant.now,
          updatedAt = None,
          thid = thid,
          schemaUri = maybeSchemaId,
          credentialDefinitionId = None,
          credentialDefinitionUri = None,
          credentialFormat = CredentialFormat.SDJWT,
          role = IssueCredentialRecord.Role.Issuer,
          subjectId = None,
          validityPeriod = validityPeriod,
          automaticIssuance = automaticIssuance,
          protocolState = IssueCredentialRecord.ProtocolState.OfferPending,
          offerCredentialData = Some(offer),
          requestCredentialData = None,
          anonCredsRequestMetadata = None,
          issueCredentialData = None,
          issuedCredentialRaw = None,
          issuingDID = Some(issuingDID),
          metaRetries = maxRetries,
          metaNextRetry = Some(Instant.now()),
          metaLastFailure = None,
        )
      )
      count <- credentialRepository
        .create(record) @@ CustomMetricsAspect
        .startRecordingTime(s"${record.id}_issuer_offer_pending_to_sent_ms_gauge")
    } yield record

  override def createAnonCredsIssueCredentialRecord(
      pairwiseIssuerDID: DidId,
      pairwiseHolderDID: DidId,
      thid: DidCommID,
      credentialDefinitionGUID: UUID,
      credentialDefinitionId: String,
      claims: Json,
      validityPeriod: Option[Double],
      automaticIssuance: Option[Boolean]
  ): URIO[WalletAccessContext, IssueCredentialRecord] = {
    for {
      credentialDefinition <- getCredentialDefinition(credentialDefinitionGUID)
      _ <- CredentialSchema
        .validateAnonCredsClaims(credentialDefinition.schemaId, claims.noSpaces, uriDereferencer)
        .orDieAsUnmanagedFailure
      attributes <- CredentialService.convertJsonClaimsToAttributes(claims)
      offer <- createAnonCredsDidCommOfferCredential(
        pairwiseIssuerDID = pairwiseIssuerDID,
        pairwiseHolderDID = pairwiseHolderDID,
        schemaUri = credentialDefinition.schemaId,
        credentialDefinitionGUID = credentialDefinitionGUID,
        credentialDefinitionId = credentialDefinitionId,
        claims = attributes,
        thid = thid,
      )
      record <- ZIO.succeed(
        IssueCredentialRecord(
          id = DidCommID(),
          createdAt = Instant.now,
          updatedAt = None,
          thid = thid,
          schemaUri = Some(credentialDefinition.schemaId),
          credentialDefinitionId = Some(credentialDefinitionGUID),
          credentialDefinitionUri = Some(credentialDefinitionId),
          credentialFormat = CredentialFormat.AnonCreds,
          role = IssueCredentialRecord.Role.Issuer,
          subjectId = None,
          validityPeriod = validityPeriod,
          automaticIssuance = automaticIssuance,
          protocolState = IssueCredentialRecord.ProtocolState.OfferPending,
          offerCredentialData = Some(offer),
          requestCredentialData = None,
          anonCredsRequestMetadata = None,
          issueCredentialData = None,
          issuedCredentialRaw = None,
          issuingDID = None,
          metaRetries = maxRetries,
          metaNextRetry = Some(Instant.now()),
          metaLastFailure = None,
        )
      )
      count <- credentialRepository
        .create(record) @@ CustomMetricsAspect
        .startRecordingTime(s"${record.id}_issuer_offer_pending_to_sent_ms_gauge")
    } yield record
  }

  override def getIssueCredentialRecordsByStates(
      ignoreWithZeroRetries: Boolean,
      limit: Int,
      states: IssueCredentialRecord.ProtocolState*
  ): URIO[WalletAccessContext, Seq[IssueCredentialRecord]] =
    credentialRepository.findByStates(ignoreWithZeroRetries, limit, states*)

  override def getIssueCredentialRecordsByStatesForAllWallets(
      ignoreWithZeroRetries: Boolean,
      limit: Int,
      states: IssueCredentialRecord.ProtocolState*
  ): UIO[Seq[IssueCredentialRecord]] =
    credentialRepository.findByStatesForAllWallets(ignoreWithZeroRetries, limit, states*)

  override def receiveCredentialOffer(
      offer: OfferCredential
  ): ZIO[WalletAccessContext, InvalidCredentialOffer, IssueCredentialRecord] = {
    for {
      attachment <- ZIO
        .fromOption(offer.attachments.headOption)
        .mapError(_ => InvalidCredentialOffer("No attachment found"))

      format <- ZIO
        .fromOption(attachment.format)
        .mapError(_ => InvalidCredentialOffer("No attachment format found"))

      credentialFormat <- format match
        case value if value == IssueCredentialOfferFormat.JWT.name      => ZIO.succeed(CredentialFormat.JWT)
        case value if value == IssueCredentialOfferFormat.SDJWT.name    => ZIO.succeed(CredentialFormat.SDJWT)
        case value if value == IssueCredentialOfferFormat.Anoncred.name => ZIO.succeed(CredentialFormat.AnonCreds)
        case value => ZIO.fail(InvalidCredentialOffer(s"Unsupported credential format: $value"))

      _ <- validateCredentialOfferAttachment(credentialFormat, attachment)
      record <- ZIO.succeed(
        IssueCredentialRecord(
          id = DidCommID(),
          createdAt = Instant.now,
          updatedAt = None,
          thid = DidCommID(offer.thid.getOrElse(offer.id)),
          schemaUri = None,
          credentialDefinitionId = None,
          credentialDefinitionUri = None,
          credentialFormat = credentialFormat,
          role = Role.Holder,
          subjectId = None,
          validityPeriod = None,
          automaticIssuance = None,
          protocolState = IssueCredentialRecord.ProtocolState.OfferReceived,
          offerCredentialData = Some(offer),
          requestCredentialData = None,
          anonCredsRequestMetadata = None,
          issueCredentialData = None,
          issuedCredentialRaw = None,
          issuingDID = None,
          metaRetries = maxRetries,
          metaNextRetry = Some(Instant.now()),
          metaLastFailure = None,
        )
      )
      count <- credentialRepository.create(record)
    } yield record
  }

  private def validateCredentialOfferAttachment(
      credentialFormat: CredentialFormat,
      attachment: AttachmentDescriptor
  ): IO[InvalidCredentialOffer, Unit] = for {
    _ <- credentialFormat match
      case CredentialFormat.JWT | CredentialFormat.SDJWT =>
        attachment.data match
          case JsonData(json) =>
            ZIO
              .attempt(json.asJson.hcursor.downField("json").as[CredentialOfferAttachment])
              .mapError(e =>
                InvalidCredentialOffer(s"An error occurred when parsing the offer attachment: ${e.toString}")
              )
          case _ =>
            ZIO.fail(InvalidCredentialOffer(s"Only JSON attachments are supported in JWT offers"))
      case CredentialFormat.AnonCreds =>
        attachment.data match
          case Base64(value) =>
            for {
              _ <- ZIO
                .attempt(AnoncredCredentialOffer(value))
                .mapError(e =>
                  InvalidCredentialOffer(s"An error occurred when parsing the offer attachment: ${e.toString}")
                )
            } yield ()
          case _ =>
            ZIO.fail(InvalidCredentialOffer(s"Only Base64 attachments are supported in AnonCreds offers"))
  } yield ()

  private[this] def validatePrismDID(
      did: String
  ): IO[UnsupportedDidFormat, PrismDID] = ZIO
    .fromEither(PrismDID.fromString(did))
    .mapError(_ => UnsupportedDidFormat(did))

  private[this] def validateClaimsAgainstSchemaIfAny(
      claims: Json,
      maybeSchemaId: Option[String]
  ): UIO[Unit] = maybeSchemaId match
    case Some(schemaId) =>
      CredentialSchema
        .validateJWTCredentialSubject(schemaId, claims.noSpaces, uriDereferencer)
        .orDieAsUnmanagedFailure
    case None =>
      ZIO.unit

  private[this] def getCredentialDefinition(
      guid: UUID
  ): UIO[CredentialDefinition] = credentialDefinitionService
    .getByGUID(guid)
    .mapError(e => CredentialDefinitionServiceError(e.toString))
    .orDieAsUnmanagedFailure

  private[this] def getCredentialDefinitionPrivatePart(
      guid: UUID
  ): URIO[WalletAccessContext, CredentialDefinitionSecret] = for {
    maybeCredentialDefinitionSecret <- genericSecretStorage
      .get[UUID, CredentialDefinitionSecret](guid)
      .orDie
    credentialDefinitionSecret <- ZIO
      .fromOption(maybeCredentialDefinitionSecret)
      .mapError(_ => CredentialDefinitionPrivatePartNotFound(guid))
      .orDieAsUnmanagedFailure
  } yield credentialDefinitionSecret

  override def acceptCredentialOffer(
      recordId: DidCommID,
      maybeSubjectId: Option[String]
  ): ZIO[WalletAccessContext, RecordNotFound | UnsupportedDidFormat, IssueCredentialRecord] = {
    for {
      record <- getRecordWithState(recordId, ProtocolState.OfferReceived)
      count <- (record.credentialFormat, maybeSubjectId) match
        case (CredentialFormat.JWT | CredentialFormat.SDJWT, Some(subjectId)) =>
          for {
            _ <- validatePrismDID(subjectId)
            count <- credentialRepository
              .updateWithSubjectId(recordId, subjectId, ProtocolState.RequestPending)
              @@ CustomMetricsAspect.startRecordingTime(
                s"${record.id}_issuance_flow_holder_req_pending_to_generated"
              )
          } yield count
        case (CredentialFormat.AnonCreds, None) =>
          credentialRepository
            .updateProtocolState(recordId, ProtocolState.OfferReceived, ProtocolState.RequestPending)
            @@ CustomMetricsAspect.startRecordingTime(
              s"${record.id}_issuance_flow_holder_req_pending_to_generated"
            )
        case (format, maybeSubjectId) =>
          ZIO.dieMessage(s"Invalid subjectId input for $format offer acceptance: $maybeSubjectId")
      record <- credentialRepository.getById(record.id)
    } yield record
  }

  private def createPresentationPayload(
      record: IssueCredentialRecord,
      subject: JwtIssuer
  ): URIO[WalletAccessContext, PresentationPayload] = {
    for {
      maybeOptions <- getOptionsFromOfferCredentialData(record)
    } yield {
      W3cPresentationPayload(
        `@context` = Vector("https://www.w3.org/2018/presentations/v1"),
        maybeId = None,
        `type` = Vector("VerifiablePresentation"),
        verifiableCredential = IndexedSeq.empty,
        holder = subject.did.value,
        verifier = IndexedSeq.empty ++ maybeOptions.map(_.domain),
        maybeIssuanceDate = None,
        maybeExpirationDate = None
      ).toJwtPresentationPayload.copy(maybeNonce = maybeOptions.map(_.challenge))
    }
  }

  private def getLongForm(
      did: PrismDID,
      allowUnpublishedIssuingDID: Boolean = false
  ): URIO[WalletAccessContext, LongFormPrismDID] = {
    for {
      maybeDidState <- managedDIDService
        .getManagedDIDState(did.asCanonical)
        .orDieWith(e => RuntimeException(s"Error occurred while getting DID from wallet: ${e.toString}"))
      didState <- ZIO
        .fromOption(maybeDidState)
        .mapError(_ => DIDNotFoundInWallet(did))
        .orDieAsUnmanagedFailure
      _ <- (didState match
        case s @ ManagedDIDState(_, _, PublicationState.Published(_)) => ZIO.succeed(s)
        case s => ZIO.cond(allowUnpublishedIssuingDID, s, DIDNotPublished(did, s.publicationState))
      ).orDieAsUnmanagedFailure
      longFormPrismDID = PrismDID.buildLongFormFromOperation(didState.createOperation)
    } yield longFormPrismDID
  }

  private[this] def getKeyId(
      did: PrismDID,
      verificationRelationship: VerificationRelationship,
      ellipticCurve: EllipticCurve
  ): UIO[String] = {
    for {
      maybeDidData <- didService
        .resolveDID(did)
        .orDieWith(e => RuntimeException(s"Error occurred while resolving the DID: ${e.toString}"))
      didData <- ZIO
        .fromOption(maybeDidData)
        .mapError(_ => DIDNotResolved(did))
        .orDieAsUnmanagedFailure
      keyId <- ZIO
        .fromOption(
          didData._2.publicKeys
            .find(pk => pk.purpose == verificationRelationship && pk.publicKeyData.crv == ellipticCurve)
            .map(_.id)
        )
        .mapError(_ => KeyNotFoundInDID(did, verificationRelationship))
        .orDieAsUnmanagedFailure
    } yield keyId
  }

  private def getJwtIssuer(
      jwtIssuerDID: PrismDID,
      verificationRelationship: VerificationRelationship
  ): URIO[WalletAccessContext, JwtIssuer] = {
    for {
      issuingKeyId <- getKeyId(jwtIssuerDID, verificationRelationship, EllipticCurve.SECP256K1)
      ecKeyPair <- managedDIDService
        .javaKeyPairWithDID(jwtIssuerDID.asCanonical, issuingKeyId)
        .someOrFail(KeyPairNotFoundInWallet(jwtIssuerDID, issuingKeyId, "Secp256k1"))
        .orDieAsUnmanagedFailure
      (privateKey, publicKey) = ecKeyPair
      jwtIssuer = JwtIssuer(
        org.hyperledger.identus.pollux.vc.jwt.DID(jwtIssuerDID.toString),
        ES256KSigner(privateKey),
        publicKey
      )
    } yield jwtIssuer
  }

  private def getEd25519SigningKeyPair(
      jwtIssuerDID: PrismDID,
      verificationRelationship: VerificationRelationship
  ): URIO[WalletAccessContext, Ed25519KeyPair] = {
    for {
      issuingKeyId <- getKeyId(jwtIssuerDID, verificationRelationship, EllipticCurve.ED25519)
      ed25519keyPair <- managedDIDService
        .findDIDKeyPair(jwtIssuerDID.asCanonical, issuingKeyId)
        .map(_.collect { case keyPair: Ed25519KeyPair => keyPair })
        .someOrFail(KeyPairNotFoundInWallet(jwtIssuerDID, issuingKeyId, "Ed25519"))
        .orDieAsUnmanagedFailure
    } yield ed25519keyPair
  }

  /** @param jwtIssuerDID
    *   This can holder prism did / issuer prism did
    * @param verificationRelationship
    *   Holder it Authentication and Issuer it is AssertionMethod
    * @return
    *   JwtIssuer
    * @see
    *   org.hyperledger.identus.pollux.vc.jwt.Issuer
    */
  private def getSDJwtIssuer(
      jwtIssuerDID: PrismDID,
      verificationRelationship: VerificationRelationship
  ): URIO[WalletAccessContext, JwtIssuer] = {
    for {
      ed25519keyPair <- getEd25519SigningKeyPair(jwtIssuerDID, verificationRelationship)
    } yield {
      JwtIssuer(
        org.hyperledger.identus.pollux.vc.jwt.DID(jwtIssuerDID.toString),
        EdSigner(ed25519keyPair),
        Ed25519PublicKey.toJavaEd25519PublicKey(ed25519keyPair.publicKey.getEncoded)
      )
    }
  }

  private[this] def generateCredentialRequest(
      recordId: DidCommID,
      getIssuer: (
          did: LongFormPrismDID,
          verificationRelation: VerificationRelationship
      ) => URIO[WalletAccessContext, JwtIssuer]
  ): ZIO[WalletAccessContext, RecordNotFound | UnsupportedDidFormat, IssueCredentialRecord] = {
    for {
      record <- getRecordWithState(recordId, ProtocolState.RequestPending)
      subjectId <- ZIO
        .fromOption(record.subjectId)
        .orDieWith(_ => RuntimeException(s"No 'subjectId' found in record: ${recordId.value}"))
      formatAndOffer <- ZIO
        .fromOption(record.offerCredentialFormatAndData)
        .orDieWith(_ => RuntimeException(s"No 'offer' found in record: ${recordId.value}"))
      subjectDID <- validatePrismDID(subjectId)
      longFormPrismDID <- getLongForm(subjectDID, true)
      jwtIssuer <- getIssuer(longFormPrismDID, VerificationRelationship.Authentication)
      presentationPayload <- createPresentationPayload(record, jwtIssuer)
      signedPayload = JwtPresentation.encodeJwt(presentationPayload.toJwtPresentationPayload, jwtIssuer)
      request = createDidCommRequestCredential(formatAndOffer._1, formatAndOffer._2, signedPayload)
      count <- credentialRepository
        .updateWithJWTRequestCredential(recordId, request, ProtocolState.RequestGenerated)
        @@ CustomMetricsAspect.endRecordingTime(
          s"${record.id}_issuance_flow_holder_req_pending_to_generated",
          "issuance_flow_holder_req_pending_to_generated_ms_gauge"
        ) @@ CustomMetricsAspect.startRecordingTime(s"${record.id}_issuance_flow_holder_req_generated_to_sent")
      record <- credentialRepository.getById(record.id)
    } yield record
  }

  override def generateJWTCredentialRequest(
      recordId: DidCommID
  ): ZIO[WalletAccessContext, RecordNotFound | UnsupportedDidFormat, IssueCredentialRecord] =
    generateCredentialRequest(recordId, getJwtIssuer)

  override def generateSDJWTCredentialRequest(
      recordId: DidCommID
  ): ZIO[WalletAccessContext, RecordNotFound | UnsupportedDidFormat, IssueCredentialRecord] =
    generateCredentialRequest(recordId, getSDJwtIssuer)

  override def generateAnonCredsCredentialRequest(
      recordId: DidCommID
  ): ZIO[WalletAccessContext, RecordNotFound, IssueCredentialRecord] = {
    for {
      record <- getRecordWithState(recordId, ProtocolState.RequestPending)
      offerCredential <- ZIO
        .fromOption(record.offerCredentialData)
        .orDieWith(_ => RuntimeException(s"No 'offer' found in record: ${recordId.value}"))
      body = RequestCredential.Body(goal_code = Some("Request Credential"))
      createCredentialRequest <- createAnonCredsRequestCredential(offerCredential)
      attachments = Seq(
        AttachmentDescriptor.buildBase64Attachment(
          mediaType = Some("application/json"),
          format = Some(IssueCredentialRequestFormat.Anoncred.name),
          payload = createCredentialRequest.request.data.getBytes()
        )
      )
      requestMetadata = createCredentialRequest.metadata
      request = RequestCredential(
        body = body,
        attachments = attachments,
        from = offerCredential.to,
        to = offerCredential.from,
        thid = offerCredential.thid
      )
      count <- credentialRepository
        .updateWithAnonCredsRequestCredential(recordId, request, requestMetadata, ProtocolState.RequestGenerated)
        @@ CustomMetricsAspect.endRecordingTime(
          s"${record.id}_issuance_flow_holder_req_pending_to_generated",
          "issuance_flow_holder_req_pending_to_generated_ms_gauge"
        ) @@ CustomMetricsAspect.startRecordingTime(s"${record.id}_issuance_flow_holder_req_generated_to_sent")
      record <- credentialRepository.getById(record.id)
    } yield record
  }

  private def createAnonCredsRequestCredential(
      offerCredential: OfferCredential
  ): URIO[WalletAccessContext, AnoncredCreateCrendentialRequest] = {
    for {
      attachmentData <- ZIO
        .fromOption(
          offerCredential.attachments
            .find(_.format.contains(IssueCredentialOfferFormat.Anoncred.name))
            .map(_.data)
            .flatMap {
              case Base64(value) => Some(new String(java.util.Base64.getUrlDecoder.decode(value)))
              case _             => None
            }
        )
        .orDieWith(_ => RuntimeException(s"No AnonCreds attachment found in the offer"))
      credentialOffer = anoncreds.AnoncredCredentialOffer(attachmentData)
      credDefContent <- uriDereferencer
        .dereference(new URI(credentialOffer.getCredDefId))
        .orDieAsUnmanagedFailure
      credentialDefinition = anoncreds.AnoncredCredentialDefinition(credDefContent)
      linkSecret <- linkSecretService.fetchOrCreate()
      createCredentialRequest = AnoncredLib.createCredentialRequest(linkSecret, credentialDefinition, credentialOffer)
    } yield createCredentialRequest
  }

  override def receiveCredentialRequest(
      request: RequestCredential
  ): ZIO[WalletAccessContext, InvalidCredentialRequest | RecordNotFoundForThreadIdAndStates, IssueCredentialRecord] = {
    for {
      thid <- ZIO
        .fromOption(request.thid.map(DidCommID(_)))
        .mapError(_ => InvalidCredentialRequest("No 'thid' found"))
      record <- getRecordWithThreadIdAndStates(
        thid,
        ignoreWithZeroRetries = true,
        ProtocolState.OfferPending,
        ProtocolState.OfferSent
      )
      _ <- credentialRepository.updateWithJWTRequestCredential(record.id, request, ProtocolState.RequestReceived)
      record <- credentialRepository.getById(record.id)
    } yield record
  }

  override def acceptCredentialRequest(
      recordId: DidCommID
  ): ZIO[WalletAccessContext, RecordNotFound, IssueCredentialRecord] = {
    for {
      record <- getRecordWithState(recordId, ProtocolState.RequestReceived)
      request <- ZIO
        .fromOption(record.requestCredentialData)
        .orDieWith(_ => RuntimeException(s"No 'requestCredentialData' found in record: ${recordId.value}"))
      issue = createDidCommIssueCredential(request)
      count <- credentialRepository
        .updateWithIssueCredential(recordId, issue, ProtocolState.CredentialPending)
        @@ CustomMetricsAspect.startRecordingTime(
          s"${record.id}_issuance_flow_issuer_credential_pending_to_generated"
        )
      record <- credentialRepository.getById(record.id)
    } yield record
  }

  override def receiveCredentialIssue(
      issueCredential: IssueCredential
  ): ZIO[WalletAccessContext, InvalidCredentialIssue | RecordNotFoundForThreadIdAndStates, IssueCredentialRecord] =
    for {
      thid <- ZIO
        .fromOption(issueCredential.thid.map(DidCommID(_)))
        .mapError(_ => InvalidCredentialIssue("No 'thid' found"))
      record <- getRecordWithThreadIdAndStates(
        thid,
        ignoreWithZeroRetries = true,
        ProtocolState.RequestPending,
        ProtocolState.RequestSent
      )
      attachment <- ZIO
        .fromOption(issueCredential.attachments.headOption)
        .mapError(_ => InvalidCredentialIssue("No attachment found"))

      _ <- {
        val result = attachment match {
          case AttachmentDescriptor(
                id,
                media_type,
                Base64(v),
                Some(IssueCredentialIssuedFormat.Anoncred.name),
                _,
                _,
                _,
                _
              ) =>
            for {
              processedCredential <- processAnonCredsCredential(record, java.util.Base64.getUrlDecoder.decode(v))
              attachment = AttachmentDescriptor.buildBase64Attachment(
                id = id,
                mediaType = media_type,
                format = Some(IssueCredentialIssuedFormat.Anoncred.name),
                payload = processedCredential.data.getBytes
              )
              processedIssuedCredential = issueCredential.copy(attachments = Seq(attachment))
              result <-
                updateWithCredential(
                  processedIssuedCredential,
                  record,
                  attachment,
                  Some(processedCredential.getSchemaId),
                  Some(processedCredential.getCredDefId)
                )
            } yield result
          case attachment =>
            updateWithCredential(issueCredential, record, attachment, None, None)
        }
        result
      }
      record <- credentialRepository.getById(record.id)
    } yield record

  private def updateWithCredential(
      issueCredential: IssueCredential,
      record: IssueCredentialRecord,
      attachment: AttachmentDescriptor,
      schemaId: Option[String],
      credDefId: Option[String]
  ) = {
    credentialRepository
      .updateWithIssuedRawCredential(
        record.id,
        issueCredential,
        attachment.data.asJson.noSpaces,
        schemaId,
        credDefId,
        ProtocolState.CredentialReceived
      )
  }

  private def processAnonCredsCredential(
      record: IssueCredentialRecord,
      credentialBytes: Array[Byte]
  ): URIO[WalletAccessContext, anoncreds.AnoncredCredential] = {
    for {
      credential <- ZIO.succeed(anoncreds.AnoncredCredential(new String(credentialBytes)))
      credDefContent <- uriDereferencer
        .dereference(new URI(credential.getCredDefId))
        .orDieAsUnmanagedFailure
      credentialDefinition = anoncreds.AnoncredCredentialDefinition(credDefContent)
      metadata <- ZIO
        .fromOption(record.anonCredsRequestMetadata)
        .orDieWith(_ => RuntimeException(s"No AnonCreds request metadata found in record: ${record.id.value}"))
      linkSecret <- linkSecretService.fetchOrCreate()
      credential <- ZIO
        .attempt(
          AnoncredLib.processCredential(
            anoncreds.AnoncredCredential(new String(credentialBytes)),
            metadata,
            linkSecret,
            credentialDefinition
          )
        )
        .orDieWith(error => RuntimeException(s"AnonCreds credential processing error: ${error.getMessage}"))
    } yield credential
  }

  override def markOfferSent(
      recordId: DidCommID
  ): ZIO[WalletAccessContext, InvalidStateForOperation, IssueCredentialRecord] =
    updateCredentialRecordProtocolState(
      recordId,
      IssueCredentialRecord.ProtocolState.OfferPending,
      IssueCredentialRecord.ProtocolState.OfferSent
    )

  override def markRequestSent(
      recordId: DidCommID
  ): ZIO[WalletAccessContext, InvalidStateForOperation, IssueCredentialRecord] =
    updateCredentialRecordProtocolState(
      recordId,
      IssueCredentialRecord.ProtocolState.RequestGenerated,
      IssueCredentialRecord.ProtocolState.RequestSent
    ) @@ CustomMetricsAspect.endRecordingTime(
      s"${recordId}_issuance_flow_holder_req_generated_to_sent",
      "issuance_flow_holder_req_generated_to_sent_ms_gauge"
    )

  private def markCredentialGenerated(
      record: IssueCredentialRecord,
      issueCredential: IssueCredential
  ): URIO[WalletAccessContext, IssueCredentialRecord] = {
    for {
      count <- credentialRepository
        .updateWithIssueCredential(record.id, issueCredential, IssueCredentialRecord.ProtocolState.CredentialGenerated)
        @@ CustomMetricsAspect.endRecordingTime(
          s"${record.id}_issuance_flow_issuer_credential_pending_to_generated",
          "issuance_flow_issuer_credential_pending_to_generated_ms_gauge"
        ) @@ CustomMetricsAspect.startRecordingTime(s"${record.id}_issuance_flow_issuer_credential_generated_to_sent")
      record <- credentialRepository.getById(record.id)
    } yield record
  }

  override def markCredentialSent(
      recordId: DidCommID
  ): ZIO[WalletAccessContext, InvalidStateForOperation, IssueCredentialRecord] =
    updateCredentialRecordProtocolState(
      recordId,
      IssueCredentialRecord.ProtocolState.CredentialGenerated,
      IssueCredentialRecord.ProtocolState.CredentialSent
    ) @@ CustomMetricsAspect.endRecordingTime(
      s"${recordId}_issuance_flow_issuer_credential_generated_to_sent",
      "issuance_flow_issuer_credential_generated_to_sent_ms_gauge"
    )

  override def reportProcessingFailure(
      recordId: DidCommID,
      failReason: Option[String]
  ): URIO[WalletAccessContext, Unit] =
    credentialRepository.updateAfterFail(recordId, failReason)

  private def getRecordWithState(
      recordId: DidCommID,
      state: ProtocolState
  ): ZIO[WalletAccessContext, RecordNotFound, IssueCredentialRecord] = {
    for {
      record <- credentialRepository.getById(recordId)
      _ <- record.protocolState match {
        case s if s == state => ZIO.unit
        case s               => ZIO.fail(RecordNotFound(recordId, Some(s)))
      }
    } yield record
  }

  private def getRecordWithThreadIdAndStates(
      thid: DidCommID,
      ignoreWithZeroRetries: Boolean,
      states: ProtocolState*
  ): ZIO[WalletAccessContext, RecordNotFoundForThreadIdAndStates, IssueCredentialRecord] = {
    for {
      record <- credentialRepository
        .findByThreadId(thid, ignoreWithZeroRetries)
        .someOrFail(RecordNotFoundForThreadIdAndStates(thid, states*))
      _ <- record.protocolState match {
        case s if states.contains(s) => ZIO.unit
        case state                   => ZIO.fail(RecordNotFoundForThreadIdAndStates(thid, states*))
      }
    } yield record
  }

  private def createDidCommOfferCredential(
      pairwiseIssuerDID: DidId,
      pairwiseHolderDID: DidId,
      maybeSchemaId: Option[String],
      claims: Seq[Attribute],
      thid: DidCommID,
      challenge: String,
      domain: String,
      offerFormat: IssueCredentialOfferFormat
  ): UIO[OfferCredential] = {
    for {
      credentialPreview <- ZIO.succeed(CredentialPreview(schema_id = maybeSchemaId, attributes = claims))
      body = OfferCredential.Body(
        goal_code = Some("Offer Credential"),
        credential_preview = credentialPreview,
      )
      attachments <- ZIO.succeed(
        Seq(
          AttachmentDescriptor.buildJsonAttachment(
            mediaType = Some("application/json"),
            format = Some(offerFormat.name),
            payload = PresentationAttachment(
              Some(Options(challenge, domain)),
              PresentationDefinition(format = Some(ClaimFormat(jwt = Some(Jwt(alg = Seq("ES256K"), proof_type = Nil)))))
            )
          )
        )
      )
    } yield OfferCredential(
      body = body,
      attachments = attachments,
      from = pairwiseIssuerDID,
      to = pairwiseHolderDID,
      thid = Some(thid.value)
    )
  }

  private def createAnonCredsDidCommOfferCredential(
      pairwiseIssuerDID: DidId,
      pairwiseHolderDID: DidId,
      schemaUri: String,
      credentialDefinitionGUID: UUID,
      credentialDefinitionId: String,
      claims: Seq[Attribute],
      thid: DidCommID
  ): URIO[WalletAccessContext, OfferCredential] = {
    for {
      credentialPreview <- ZIO.succeed(CredentialPreview(schema_id = Some(schemaUri), attributes = claims))
      body = OfferCredential.Body(
        goal_code = Some("Offer Credential"),
        credential_preview = credentialPreview,
      )
      attachments <- createAnonCredsCredentialOffer(credentialDefinitionGUID, credentialDefinitionId).map { offer =>
        Seq(
          AttachmentDescriptor.buildBase64Attachment(
            mediaType = Some("application/json"),
            format = Some(IssueCredentialOfferFormat.Anoncred.name),
            payload = offer.data.getBytes()
          )
        )
      }
    } yield OfferCredential(
      body = body,
      attachments = attachments,
      from = pairwiseIssuerDID,
      to = pairwiseHolderDID,
      thid = Some(thid.value)
    )
  }

  private def createAnonCredsCredentialOffer(
      credentialDefinitionGUID: UUID,
      credentialDefinitionId: String
  ): URIO[WalletAccessContext, AnoncredCredentialOffer] =
    for {
      credentialDefinition <- getCredentialDefinition(credentialDefinitionGUID)
      cd = anoncreds.AnoncredCredentialDefinition(credentialDefinition.definition.toString)
      kcp = anoncreds.AnoncredCredentialKeyCorrectnessProof(credentialDefinition.keyCorrectnessProof.toString)
      credentialDefinitionSecret <- getCredentialDefinitionPrivatePart(credentialDefinition.guid)
      cdp = anoncreds.AnoncredCredentialDefinitionPrivate(credentialDefinitionSecret.json.toString)
      createCredentialDefinition = AnoncredCreateCredentialDefinition(cd, cdp, kcp)
      offer = AnoncredLib.createOffer(createCredentialDefinition, credentialDefinitionId)
    } yield offer

  private[this] def createDidCommRequestCredential(
      format: IssueCredentialOfferFormat,
      offer: OfferCredential,
      signedPresentation: JWT
  ): RequestCredential = {
    RequestCredential(
      body = RequestCredential.Body(
        goal_code = offer.body.goal_code,
        comment = offer.body.comment,
      ),
      attachments = Seq(
        AttachmentDescriptor
          .buildBase64Attachment(
            mediaType = Some("application/json"),
            format = Some(format.name),
            // FIXME copy payload will probably not work for anoncreds!
            payload = signedPresentation.value.getBytes(),
          )
      ),
      thid = offer.thid.orElse(Some(offer.id)),
      from = offer.to,
      to = offer.from
    )
  }

  private def createDidCommIssueCredential(request: RequestCredential): IssueCredential = {
    IssueCredential(
      body = IssueCredential.Body(
        goal_code = request.body.goal_code,
        comment = request.body.comment,
        replacement_id = None,
        more_available = None,
      ),
      attachments = Seq(), // FIXME !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
      thid = request.thid.orElse(Some(request.id)),
      from = request.to,
      to = request.from
    )
  }

  /** this is an auxiliary function.
    *
    * @note
    *   Between updating and getting the CredentialRecord back the CredentialRecord can be updated by other operations
    *   in the middle.
    *
    * TODO: this should be improved to behave exactly like atomic operation.
    */
  private def updateCredentialRecordProtocolState(
      id: DidCommID,
      from: IssueCredentialRecord.ProtocolState,
      to: IssueCredentialRecord.ProtocolState
  ): ZIO[WalletAccessContext, InvalidStateForOperation, IssueCredentialRecord] = {
    for {
      record <- credentialRepository.getById(id)
      updatedRecord <- record.protocolState match
        case currentState if currentState == to => ZIO.succeed(record) // Idempotent behaviour
        case currentState if currentState == from =>
          credentialRepository.updateProtocolState(id, from, to) *> credentialRepository.getById(id)
        case _ => ZIO.fail(InvalidStateForOperation(record.protocolState))
    } yield updatedRecord
  }

  override def generateJWTCredential(
      recordId: DidCommID,
      statusListRegistryUrl: String,
  ): ZIO[WalletAccessContext, RecordNotFound | CredentialRequestValidationFailed, IssueCredentialRecord] = {
    for {
      record <- getRecordWithState(recordId, ProtocolState.CredentialPending)
      issuingDID <- ZIO
        .fromOption(record.issuingDID)
        .orElse(ZIO.dieMessage(s"Issuing DID not found in record: ${recordId.value}"))
      issue <- ZIO
        .fromOption(record.issueCredentialData)
        .orElse(ZIO.dieMessage(s"Issue credential data not found in record: ${recordId.value}"))
      longFormPrismDID <- getLongForm(issuingDID, true)
      maybeOfferOptions <- getOptionsFromOfferCredentialData(record)
      requestJwt <- getJwtFromRequestCredentialData(record)
      offerCredentialData <- ZIO
        .fromOption(record.offerCredentialData)
        .orElse(ZIO.dieMessage(s"Offer credential data not found in record: ${recordId.value}"))
      preview = offerCredentialData.body.credential_preview
      claims <- CredentialService.convertAttributesToJsonClaims(preview.body.attributes).orDieAsUnmanagedFailure
      jwtIssuer <- getJwtIssuer(longFormPrismDID, VerificationRelationship.AssertionMethod)
      jwtPresentation <- validateRequestCredentialDataProof(maybeOfferOptions, requestJwt)
        .tapError(error =>
          credentialRepository
            .updateProtocolState(record.id, ProtocolState.CredentialPending, ProtocolState.ProblemReportPending)
        )
        .orDieAsUnmanagedFailure

      // Custom for JWT
      issuanceDate = Instant.now()
      credentialStatus <- allocateNewCredentialInStatusListForWallet(record, statusListRegistryUrl, jwtIssuer)
      // TODO: get schema when schema registry is available if schema ID is provided
      w3Credential = W3cCredentialPayload(
        `@context` = Set(
          "https://www.w3.org/2018/credentials/v1"
        ), // TODO: his information should come from Schema registry by record.schemaId
        maybeId = None,
        `type` =
          Set("VerifiableCredential"), // TODO: This information should come from Schema registry by record.schemaId
        issuer = jwtIssuer.did,
        issuanceDate = issuanceDate,
        maybeExpirationDate = record.validityPeriod.map(sec => issuanceDate.plusSeconds(sec.toLong)),
        maybeCredentialSchema =
          record.schemaUri.map(id => org.hyperledger.identus.pollux.vc.jwt.CredentialSchema(id, VC_JSON_SCHEMA_TYPE)),
        maybeCredentialStatus = Some(credentialStatus),
        credentialSubject = claims.add("id", jwtPresentation.iss.asJson).asJson,
        maybeRefreshService = None,
        maybeEvidence = None,
        maybeTermsOfUse = None
      )
      signedJwtCredential = W3CCredential.toEncodedJwt(w3Credential, jwtIssuer)
      issueCredential = IssueCredential.build(
        fromDID = issue.from,
        toDID = issue.to,
        thid = issue.thid,
        credentials = Seq(IssueCredentialIssuedFormat.JWT -> signedJwtCredential.value.getBytes)
      )
      // End custom

      record <- markCredentialGenerated(record, issueCredential)
    } yield record
  }

  override def generateSDJWTCredential(
      recordId: DidCommID,
      expirationTime: Duration,
  ): ZIO[WalletAccessContext, RecordNotFound | ExpirationDateHasPassed, IssueCredentialRecord] = {
    for {
      record <- getRecordWithState(recordId, ProtocolState.CredentialPending)
      issuingDID <- ZIO
        .fromOption(record.issuingDID)
        .orElse(ZIO.dieMessage(s"Issuing DID not found in record: ${recordId.value}"))
      issue <- ZIO
        .fromOption(record.issueCredentialData)
        .orElse(ZIO.dieMessage(s"Issue credential data not found in record: ${recordId.value}"))
      longFormPrismDID <- getLongForm(issuingDID, true)
      maybeOfferOptions <- getOptionsFromOfferCredentialData(record)
      requestJwt <- getJwtFromRequestCredentialData(record)
      offerCredentialData <- ZIO
        .fromOption(record.offerCredentialData)
        .orElse(ZIO.dieMessage(s"Offer credential data not found in record: ${recordId.value}"))
      preview = offerCredentialData.body.credential_preview
      claims <- CredentialService.convertAttributesToJsonClaims(preview.body.attributes).orDieAsUnmanagedFailure
      jwtPresentation <- validateRequestCredentialDataProof(maybeOfferOptions, requestJwt)
        .tapError(error =>
          credentialRepository
            .updateProtocolState(record.id, ProtocolState.CredentialPending, ProtocolState.ProblemReportPending)
        )
        .orDieAsUnmanagedFailure

      // Custom for SD JWT
      ed25519KeyPair <- getEd25519SigningKeyPair(longFormPrismDID, VerificationRelationship.AssertionMethod)
      sdJwtPrivateKey = sdjwt.IssuerPrivateKey(ed25519KeyPair.privateKey)
      didDocResult <- didResolver.resolve(jwtPresentation.iss) flatMap {
        case failed: DIDResolutionFailed =>
          ZIO.dieMessage(s"Error occurred while resolving the DID: ${failed.error.toString}")
        case succeeded: DIDResolutionSucceeded => ZIO.succeed(succeeded.didDocument.authentication)
      }
      now = Instant.now.getEpochSecond
      exp = claims("exp").flatMap(_.asNumber).flatMap(_.toLong)
      expInSeconds <- ZIO.fromEither(exp match {
        case Some(e) if e > now => Right(e)
        case Some(e)            => Left(ExpirationDateHasPassed(e))
        case _                  => Right(Instant.now.plus(expirationTime).getEpochSecond)
      })
      claimsUpdated = claims
        .add("iss", issuingDID.did.toString.asJson) // This is issuer did
        .add("sub", jwtPresentation.iss.asJson) // This is subject did
        .add("iat", now.asJson)
        .add("exp", expInSeconds.asJson)
      credential = SDJWT.issueCredential(
        sdJwtPrivateKey,
        claimsUpdated.asJson.noSpaces,
      ) // FIXME TO ADD Key of the Holder This issue is also with JWT

      issueCredential = IssueCredential.build(
        fromDID = issue.from,
        toDID = issue.to,
        thid = issue.thid,
        credentials = Seq(IssueCredentialIssuedFormat.SDJWT -> credential.compact.getBytes)
      )
      // End custom

      record <- markCredentialGenerated(record, issueCredential)
    } yield record

  }

  private def allocateNewCredentialInStatusListForWallet(
      record: IssueCredentialRecord,
      statusListRegistryUrl: String,
      jwtIssuer: JwtIssuer
  ): URIO[WalletAccessContext, CredentialStatus] = {
    val effect = for {
      lastStatusList <- credentialStatusListRepository.getLatestOfTheWallet
      currentStatusList <- lastStatusList
        .fold(credentialStatusListRepository.createNewForTheWallet(jwtIssuer, statusListRegistryUrl))(
          ZIO.succeed(_)
        )
      size = currentStatusList.size
      lastUsedIndex = currentStatusList.lastUsedIndex
      statusListToBeUsed <-
        if lastUsedIndex < size then ZIO.succeed(currentStatusList)
        else credentialStatusListRepository.createNewForTheWallet(jwtIssuer, statusListRegistryUrl)
      _ <- credentialStatusListRepository.allocateSpaceForCredential(
        issueCredentialRecordId = record.id,
        credentialStatusListId = statusListToBeUsed.id,
        statusListIndex = statusListToBeUsed.lastUsedIndex + 1
      )
    } yield CredentialStatus(
      id = s"$statusListRegistryUrl/credential-status/${statusListToBeUsed.id}#${statusListToBeUsed.lastUsedIndex + 1}",
      `type` = "StatusList2021Entry",
      statusPurpose = StatusPurpose.Revocation,
      statusListIndex = lastUsedIndex + 1,
      statusListCredential = s"$statusListRegistryUrl/credential-status/${statusListToBeUsed.id}"
    )
    issueCredentialSem.withPermit(effect)
  }

  override def generateAnonCredsCredential(
      recordId: DidCommID
  ): ZIO[WalletAccessContext, RecordNotFound, IssueCredentialRecord] = {
    for {
      record <- getRecordWithState(recordId, ProtocolState.CredentialPending)
      requestCredential <- ZIO
        .fromOption(record.requestCredentialData)
        .orElse(ZIO.dieMessage(s"No request credential data found in record: ${record.id}"))
      body = IssueCredential.Body(goal_code = Some("Issue Credential"))
      attachments <- createAnonCredsCredential(record).map { credential =>
        Seq(
          AttachmentDescriptor.buildBase64Attachment(
            mediaType = Some("application/json"),
            format = Some(IssueCredentialIssuedFormat.Anoncred.name),
            payload = credential.data.getBytes()
          )
        )
      }
      issueCredential = IssueCredential(
        body = body,
        attachments = attachments,
        from = requestCredential.to,
        to = requestCredential.from,
        thid = requestCredential.thid
      )
      record <- markCredentialGenerated(record, issueCredential)
    } yield record
  }

  private def createAnonCredsCredential(
      record: IssueCredentialRecord
  ): URIO[WalletAccessContext, AnoncredCredential] = {
    for {
      credentialDefinitionId <- ZIO
        .fromOption(record.credentialDefinitionId)
        .orElse(ZIO.dieMessage(s"No credential definition Id found in record: ${record.id}"))
      credentialDefinition <- getCredentialDefinition(credentialDefinitionId)
      cd = anoncreds.AnoncredCredentialDefinition(credentialDefinition.definition.toString)
      offerCredential <- ZIO
        .fromOption(record.offerCredentialData)
        .orElse(ZIO.dieMessage(s"No offer credential data found in record: ${record.id}"))
      offerCredentialAttachmentData <- ZIO
        .fromOption(
          offerCredential.attachments
            .find(_.format.contains(IssueCredentialOfferFormat.Anoncred.name))
            .map(_.data)
            .flatMap {
              case Base64(value) => Some(new String(java.util.Base64.getUrlDecoder.decode(value)))
              case _             => None
            }
        )
        .orElse(ZIO.dieMessage(s"No 'AnonCreds' offer credential attachment found in record: ${record.id}"))
      credentialOffer = anoncreds.AnoncredCredentialOffer(offerCredentialAttachmentData)
      requestCredential <- ZIO
        .fromOption(record.requestCredentialData)
        .orElse(ZIO.dieMessage(s"No request credential data found in record: ${record.id}"))
      requestCredentialAttachmentData <- ZIO
        .fromOption(
          requestCredential.attachments
            .find(_.format.contains(IssueCredentialRequestFormat.Anoncred.name))
            .map(_.data)
            .flatMap {
              case Base64(value) => Some(new String(java.util.Base64.getUrlDecoder.decode(value)))
              case _             => None
            }
        )
        .orElse(ZIO.dieMessage(s"No 'AnonCreds' request credential attachment found in record: ${record.id}"))
      credentialRequest = anoncreds.AnoncredCredentialRequest(requestCredentialAttachmentData)
      attrValues = offerCredential.body.credential_preview.body.attributes.map { attr =>
        (attr.name, attr.value)
      }
      credentialDefinitionSecret <- getCredentialDefinitionPrivatePart(credentialDefinition.guid)
      cdp = anoncreds.AnoncredCredentialDefinitionPrivate(credentialDefinitionSecret.json.toString)
      credential =
        AnoncredLib.createCredential(
          cd,
          cdp,
          credentialOffer,
          credentialRequest,
          attrValues
        )
    } yield credential
  }

  private def getOptionsFromOfferCredentialData(record: IssueCredentialRecord): UIO[Option[Options]] = {
    for {
      offer <- ZIO
        .fromOption(record.offerCredentialData)
        .orElse(ZIO.dieMessage(s"Offer data not found in record: ${record.id}"))
      attachmentDescriptor <- ZIO
        .fromOption(offer.attachments.headOption)
        .orElse(ZIO.dieMessage(s"Attachments not found in record: ${record.id}"))
      json <- attachmentDescriptor.data match
        case JsonData(json) => ZIO.succeed(json.asJson)
        case _              => ZIO.dieMessage(s"Attachment doesn't contain JsonData: ${record.id}")
      maybeOptions <- ZIO
        .fromEither(json.as[PresentationAttachment].map(_.options))
        .flatMapError(df => ZIO.dieMessage(df.getMessage))
    } yield maybeOptions
  }

  private def getJwtFromRequestCredentialData(record: IssueCredentialRecord): UIO[JWT] = {
    for {
      request <- ZIO
        .fromOption(record.requestCredentialData)
        .orElse(ZIO.dieMessage(s"Request data not found in record: ${record.id}"))
      attachmentDescriptor <- ZIO
        .fromOption(request.attachments.headOption)
        .orElse(ZIO.dieMessage(s"Attachment not found in record: ${record.id}"))
      jwt <- attachmentDescriptor.data match
        case Base64(b64) =>
          ZIO.succeed {
            val base64Decoded = new String(java.util.Base64.getUrlDecoder.decode(b64))
            JWT(base64Decoded)
          }
        case _ => ZIO.dieMessage(s"Attachment does not contain Base64Data: ${record.id}")
    } yield jwt
  }

  private def validateRequestCredentialDataProof(
      maybeOptions: Option[Options],
      jwt: JWT
  ): IO[CredentialRequestValidationFailed, JwtPresentationPayload] = {
    for {
      _ <- maybeOptions match
        case None => ZIO.unit
        case Some(options) =>
          JwtPresentation.validatePresentation(jwt, options.domain, options.challenge) match
            case ZValidation.Success(log, value) => ZIO.unit
            case ZValidation.Failure(log, error) =>
              ZIO.fail(CredentialRequestValidationFailed("domain/challenge proof validation failed"))

      clock = java.time.Clock.system(ZoneId.systemDefault)

      genericUriResolver = GenericUriResolver(
        Map(
          "data" -> DataUrlResolver(),
        )
      )
      verificationResult <- JwtPresentation
        .verify(
          jwt,
          JwtPresentation.PresentationVerificationOptions(
            maybeProofPurpose = Some(VerificationRelationship.Authentication),
            verifySignature = true,
            verifyDates = false,
            leeway = Duration.Zero
          )
        )(didResolver, genericUriResolver)(clock)
        .mapError(errors => CredentialRequestValidationFailed(errors*))

      result <- verificationResult match
        case ZValidation.Success(log, value) => ZIO.unit
        case ZValidation.Failure(log, error) =>
          ZIO.fail(CredentialRequestValidationFailed(s"JWT presentation verification failed: $error"))

      jwtPresentation <- ZIO
        .fromTry(JwtPresentation.decodeJwt(jwt))
        .mapError(t => CredentialRequestValidationFailed(s"JWT presentation decoding failed: ${t.getMessage}"))
    } yield jwtPresentation
  }

}
