package org.hyperledger.identus.pollux.schema

import com.dimafeng.testcontainers.PostgreSQLContainer
import org.hyperledger.identus.agent.server.http.CustomServerInterceptors
import org.hyperledger.identus.agent.walletapi.model.{BaseEntity, ManagedDIDState, PublicationState}
import org.hyperledger.identus.agent.walletapi.service.{ManagedDIDService, MockManagedDIDService}
import org.hyperledger.identus.api.http.ErrorResponse
import org.hyperledger.identus.castor.core.model.did.PrismDIDOperation
import org.hyperledger.identus.iam.authentication.{AuthenticatorWithAuthZ, DefaultEntityAuthenticator}
import org.hyperledger.identus.pollux.core.model.schema.`type`.CredentialJsonSchemaType
import org.hyperledger.identus.pollux.core.repository.CredentialSchemaRepository
import org.hyperledger.identus.pollux.core.service.{CredentialSchemaService, CredentialSchemaServiceImpl}
import org.hyperledger.identus.pollux.credentialschema.controller.{
  CredentialSchemaController,
  CredentialSchemaControllerImpl
}
import org.hyperledger.identus.pollux.credentialschema.http.{
  CredentialSchemaInput,
  CredentialSchemaResponse,
  CredentialSchemaResponsePage
}
import org.hyperledger.identus.pollux.credentialschema.SchemaRegistryServerEndpoints
import org.hyperledger.identus.pollux.sql.repository.JdbcCredentialSchemaRepository
import org.hyperledger.identus.shared.models.WalletAccessContext
import org.hyperledger.identus.sharedtest.containers.PostgresTestContainerSupport
import sttp.client3.{basicRequest, DeserializationException, Response, UriContext}
import sttp.client3.testing.SttpBackendStub
import sttp.client3.ziojson.*
import sttp.monad.MonadError
import sttp.tapir.server.interceptor.CustomiseInterceptors
import sttp.tapir.server.stub.TapirStubInterpreter
import sttp.tapir.ztapir.RIOMonadError
import zio.*
import zio.json.{DecoderOps, EncoderOps}
import zio.json.ast.Json
import zio.json.ast.Json.*
import zio.mock.Expectation
import zio.test.{Assertion, Gen, ZIOSpecDefault}

import java.time.OffsetDateTime

trait CredentialSchemaTestTools extends PostgresTestContainerSupport {
  self: ZIOSpecDefault =>

  type SchemaBadRequestResponse =
    Response[Either[DeserializationException[String], ErrorResponse]]
  type SchemaResponse =
    Response[Either[DeserializationException[String], CredentialSchemaResponse]]
  type SchemaPageResponse =
    Response[
      Either[DeserializationException[String], CredentialSchemaResponsePage]
    ]

  val mockManagedDIDServiceLayer: Expectation[ManagedDIDService] = MockManagedDIDService
    .GetManagedDIDState(
      assertion = Assertion.anything,
      result = Expectation.value(
        Some(
          ManagedDIDState(
            PrismDIDOperation.Create(Nil, Nil, Nil),
            0,
            PublicationState.Published(scala.collection.immutable.ArraySeq.empty)
          )
        )
      )
    )

  val authenticatorLayer: TaskLayer[AuthenticatorWithAuthZ[BaseEntity]] = DefaultEntityAuthenticator.layer

  lazy val testEnvironmentLayer =
    ZLayer.makeSome[
      ManagedDIDService,
      CredentialSchemaController & CredentialSchemaRepository & CredentialSchemaService & PostgreSQLContainer &
        AuthenticatorWithAuthZ[BaseEntity]
    ](
      CredentialSchemaControllerImpl.layer,
      CredentialSchemaServiceImpl.layer,
      JdbcCredentialSchemaRepository.layer,
      contextAwareTransactorLayer,
      systemTransactorLayer,
      pgContainerLayer,
      authenticatorLayer
    )

  val credentialSchemaUriBase = uri"http://test.com/schema-registry/schemas"

  def bootstrapOptions[F[_]](monadError: MonadError[F]) = {
    new CustomiseInterceptors[F, Any](_ => ())
      .exceptionHandler(CustomServerInterceptors.exceptionHandler)
      .rejectHandler(CustomServerInterceptors.rejectHandler)
      .decodeFailureHandler(CustomServerInterceptors.decodeFailureHandler)
  }

  def httpBackend(controller: CredentialSchemaController, authenticator: AuthenticatorWithAuthZ[BaseEntity]) = {
    val schemaRegistryEndpoints = SchemaRegistryServerEndpoints(controller, authenticator, authenticator)

    val backend =
      TapirStubInterpreter(
        bootstrapOptions(new RIOMonadError[Any]),
        SttpBackendStub(new RIOMonadError[Any])
      )
        .whenServerEndpoint(schemaRegistryEndpoints.createSchemaServerEndpoint)
        .thenRunLogic()
        .whenServerEndpoint(schemaRegistryEndpoints.getSchemaByIdServerEndpoint)
        .thenRunLogic()
        .whenServerEndpoint(schemaRegistryEndpoints.getRawSchemaByIdServerEndpoint)
        .thenRunLogic()
        .whenServerEndpoint(
          schemaRegistryEndpoints.lookupSchemasByQueryServerEndpoint
        )
        .thenRunLogic()
        .backend()
    backend
  }

  def deleteAllCredentialSchemas: RIO[CredentialSchemaRepository & WalletAccessContext, Unit] = {
    for {
      repository <- ZIO.service[CredentialSchemaRepository]
      result <- repository.deleteAll()
    } yield result
  }
}

trait CredentialSchemaGen {
  self: ZIOSpecDefault & CredentialSchemaTestTools =>
  object Generator {
    val schemaName = Gen.alphaNumericStringBounded(4, 12)
    val majorVersion = Gen.int(1, 9)
    val minorVersion = Gen.int(0, 9)
    val patchVersion = Gen.int(0, 9)
    val schemaVersion = majorVersion <*> minorVersion <*> patchVersion map (v => s"${v._1}.${v._2}.${v._3}")
    val schemaDescription = Gen.alphaNumericStringBounded(5, 30)
    val schemaAttribute = Gen.alphaNumericStringBounded(3, 9)
    val schemaAttributes = Gen.setOfBounded(1, 4)(schemaAttribute).map(_.toList)
    val schemaAuthored = Gen.offsetDateTime(
      min = OffsetDateTime.now().minusMonths(6),
      max = OffsetDateTime.now()
    )
    val schemaTag: Gen[Any, String] = Gen.alphaNumericStringBounded(3, 5)
    val schemaTags: Gen[Any, List[String]] =
      Gen.setOfBounded(0, 3)(schemaTag).map(_.toList)

    val schemaAuthor =
      Gen.int(1000000, 9999999).map(i => s"did:prism:4fb06243213500578f59588de3e1dd9b266ec1b61e43b0ff86ad0712f$i")

    val jsonSchema =
      """
        |{
        |    "$schema": "https://json-schema.org/draft/2020-12/schema",
        |    "description": "Driving License",
        |    "type": "object",
        |    "properties": {
        |        "name" : {
        |          "type": "string"
        |        }
        |    },
        |    "required": [
        |        "name"
        |    ]
        |}
        |""".stripMargin

    val schemaInput = for {
      name <- schemaName
      version <- schemaVersion
      description <- schemaDescription
      author <- schemaAuthor
      tags <- schemaTags
    } yield CredentialSchemaInput(
      name = name,
      version = version,
      description = Some(description),
      `type` = CredentialJsonSchemaType.`type`,
      schema = jsonSchema.fromJson[Json].getOrElse(Json.Null),
      tags = tags,
      author = author
    )
  }

  def generateSchemasN(
      count: Int
  ): ZIO[CredentialSchemaController & AuthenticatorWithAuthZ[BaseEntity], Throwable, List[CredentialSchemaInput]] =
    for {
      controller <- ZIO.service[CredentialSchemaController]
      authenticator <- ZIO.service[AuthenticatorWithAuthZ[BaseEntity]]
      backend = httpBackend(controller, authenticator)
      inputs <- Generator.schemaInput.runCollectN(count)
      _ <- inputs
        .map(in =>
          basicRequest
            .post(credentialSchemaUriBase)
            .body(in.toJsonPretty)
            .response(asJsonAlways[CredentialSchemaResponse])
            .send(backend)
        )
        .reduce((l, r) => l.flatMap(_ => r))
    } yield inputs
}
