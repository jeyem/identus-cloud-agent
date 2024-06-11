package org.hyperledger.identus.api.util

import org.hyperledger.identus.agent.server.http.DocModels
import org.hyperledger.identus.agent.server.AgentHttpServer
import org.hyperledger.identus.castor.controller.{DIDController, DIDRegistrarController}
import org.hyperledger.identus.connect.controller.ConnectionController
import org.hyperledger.identus.credential.status.controller.CredentialStatusController
import org.hyperledger.identus.event.controller.EventController
import org.hyperledger.identus.iam.authentication.DefaultAuthenticator
import org.hyperledger.identus.iam.entity.http.controller.EntityController
import org.hyperledger.identus.iam.wallet.http.controller.WalletManagementController
import org.hyperledger.identus.issue.controller.IssueController
import org.hyperledger.identus.pollux.credentialdefinition.controller.CredentialDefinitionController
import org.hyperledger.identus.pollux.credentialschema.controller.{
  CredentialSchemaController,
  VerificationPolicyController
}
import org.hyperledger.identus.presentproof.controller.PresentProofController
import org.hyperledger.identus.system.controller.SystemController
import org.hyperledger.identus.verification.controller.VcVerificationController
import org.scalatestplus.mockito.MockitoSugar.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import zio.{Scope, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.Using

object Tapir2StaticOAS extends ZIOAppDefault {

  @main override def run: ZIO[Any & ZIOAppArgs & Scope, Any, Any] = {
    val effect = for {
      args <- getArgs
      _ <- ZIO.when(args.length != 2)(ZIO.fail("Usage: Tapir2StaticOAS <output file> <server url>"))
      allEndpoints <- AgentHttpServer.agentRESTServiceEndpoints
    } yield {
      import sttp.apispec.openapi.circe.yaml.*
      val model = DocModels.customiseDocsModel(OpenAPIDocsInterpreter().toOpenAPI(allEndpoints.map(_.endpoint), "", ""))
      val yaml = model.info(model.info.copy(version = args(1))).toYaml
      val path = Path.of(args.head)
      Using(Files.newBufferedWriter(path, StandardCharsets.UTF_8)) { writer => writer.write(yaml) }
    }
    effect.provideSomeLayer(
      ZLayer.succeed(mock[ConnectionController]) ++
        ZLayer.succeed(mock[CredentialDefinitionController]) ++
        ZLayer.succeed(mock[CredentialSchemaController]) ++
        ZLayer.succeed(mock[CredentialStatusController]) ++
        ZLayer.succeed(mock[VerificationPolicyController]) ++
        ZLayer.succeed(mock[DIDRegistrarController]) ++
        ZLayer.succeed(mock[PresentProofController]) ++
        ZLayer.succeed(mock[VcVerificationController]) ++
        ZLayer.succeed(mock[IssueController]) ++
        ZLayer.succeed(mock[DIDController]) ++
        ZLayer.succeed(mock[SystemController]) ++
        ZLayer.succeed(mock[EntityController]) ++
        ZLayer.succeed(mock[WalletManagementController]) ++
        ZLayer.succeed(mock[DefaultAuthenticator]) ++
        ZLayer.succeed(mock[EventController])
    )
  }

}
