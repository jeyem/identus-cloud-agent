package org.hyperledger.identus.pollux.core.service.verification

import org.hyperledger.identus.agent.walletapi.service.{ManagedDIDService, MockManagedDIDService}
import org.hyperledger.identus.castor.core.model.did.VerificationRelationship
import org.hyperledger.identus.castor.core.service.{DIDService, MockDIDService}
import org.hyperledger.identus.pollux.core.service.{ResourceURIDereferencerImpl, URIDereferencer}
import org.hyperledger.identus.pollux.vc.jwt.*
import org.hyperledger.identus.shared.models.{WalletAccessContext, WalletId}
import org.hyperledger.identus.shared.models.WalletId.*
import zio.*
import zio.mock.Expectation

trait VcVerificationServiceSpecHelper {
  protected val defaultWalletLayer: ULayer[WalletAccessContext] = ZLayer.succeed(WalletAccessContext(WalletId.default))

  protected val (issuerOp, issuerKp, issuerDidMetadata, issuerDidData) =
    MockDIDService.createDID(VerificationRelationship.AssertionMethod)

  protected val issuer =
    Issuer(
      did = org.hyperledger.identus.pollux.vc.jwt.DID(issuerDidData.id.did.toString),
      signer = ES256KSigner(issuerKp.privateKey.toJavaPrivateKey),
      publicKey = issuerKp.publicKey.toJavaPublicKey
    )

  protected val issuerDidServiceExpectations: Expectation[DIDService] =
    MockDIDService.resolveDIDExpectation(issuerDidMetadata, issuerDidData)

  protected val issuerManagedDIDServiceExpectations: Expectation[ManagedDIDService] =
    MockManagedDIDService.getManagedDIDStateExpectation(issuerOp)
      ++ MockManagedDIDService.javaKeyPairWithDIDExpectation(issuerKp)

  protected val issuerDidResolverLayer: ZLayer[Any, Nothing, PrismDidResolver] = (issuerDidServiceExpectations ++
    issuerManagedDIDServiceExpectations).toLayer >>> ZLayer.fromFunction(PrismDidResolver(_))

  protected val emptyDidResolverLayer: ZLayer[Any, Nothing, PrismDidResolver] = MockDIDService.empty ++
    MockManagedDIDService.empty >>> ZLayer.fromFunction(PrismDidResolver(_))

  protected val vcVerificationServiceLayer: ZLayer[Any, Nothing, VcVerificationService & WalletAccessContext] =
    emptyDidResolverLayer ++ ResourceURIDereferencerImpl.layer >>>
      VcVerificationServiceImpl.layer ++ defaultWalletLayer

  protected val someVcVerificationServiceLayer
      : URLayer[DIDService & ManagedDIDService & URIDereferencer, VcVerificationService] =
    ZLayer.makeSome[DIDService & ManagedDIDService & URIDereferencer, VcVerificationService](
      ZLayer.fromFunction(PrismDidResolver(_)),
      VcVerificationServiceImpl.layer
    )

}
