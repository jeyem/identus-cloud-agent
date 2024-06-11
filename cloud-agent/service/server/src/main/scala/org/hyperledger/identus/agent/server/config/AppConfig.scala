package org.hyperledger.identus.agent.server.config

import org.hyperledger.identus.castor.core.model.did.VerificationRelationship
import org.hyperledger.identus.iam.authentication.AuthenticationConfig
import org.hyperledger.identus.pollux.vc.jwt.*
import org.hyperledger.identus.shared.db.DbConfig
import zio.config.*
import zio.config.magnolia.*
import zio.Config

import java.net.URL
import java.time.Duration
import scala.util.Try

final case class AppConfig(
    pollux: PolluxConfig,
    agent: AgentConfig,
    connect: ConnectConfig,
    prismNode: PrismNodeConfig,
) {
  def validate: Either[String, Unit] =
    for {
      _ <- agent.validate
    } yield ()
}

object AppConfig {
  given Config[java.net.URL] = Config.string.mapOrFail(url =>
    val urlRegex = """^(http|https)://[a-zA-Z0-9-]+(\.[a-zA-Z0-9-]+)*(:[0-9]{1,5})?(/.*)?$""".r
    urlRegex.findFirstMatchIn(url) match
      case Some(_) =>
        Try(java.net.URI(url).toURL()).toEither.left.map(ex /*java.net.MalformedURLException*/ =>
          Config.Error.InvalidData(zio.Chunk.empty, ex.getMessage())
        )
      case _ => Left(Config.Error.InvalidData(zio.Chunk.empty, s"Invalid URL: $url"))
  )

  val config: Config[AppConfig] = deriveConfig[AppConfig]

}

final case class VaultConfig(
    address: String,
    token: Option[String],
    appRoleRoleId: Option[String],
    appRoleSecretId: Option[String],
    useSemanticPath: Boolean
) {
  def validate: Either[String, ValidatedVaultConfig] =
    val tokenConfig = token.map(ValidatedVaultConfig.TokenAuth(address, _))
    val appRoleConfig =
      for {
        roleId <- appRoleRoleId
        secretId <- appRoleSecretId
      } yield ValidatedVaultConfig.AppRoleAuth(address, roleId, secretId)

    tokenConfig
      .orElse(appRoleConfig)
      .toRight("Vault configuration is invalid. Vault token or AppRole authentication must be provided.")
}

sealed trait ValidatedVaultConfig

object ValidatedVaultConfig {
  final case class TokenAuth(address: String, token: String) extends ValidatedVaultConfig
  final case class AppRoleAuth(address: String, roleId: String, secretId: String) extends ValidatedVaultConfig
}

final case class PolluxConfig(
    database: DatabaseConfig,
    credentialSdJwtExpirationTime: Duration,
    statusListRegistry: StatusListRegistryConfig,
    issueBgJobRecordsLimit: Int,
    issueBgJobRecurrenceDelay: Duration,
    issueBgJobProcessingParallelism: Int,
    presentationBgJobRecordsLimit: Int,
    presentationBgJobRecurrenceDelay: Duration,
    presentationBgJobProcessingParallelism: Int,
    syncRevocationStatusesBgJobRecurrenceDelay: Duration,
    syncRevocationStatusesBgJobProcessingParallelism: Int,
)
final case class ConnectConfig(
    database: DatabaseConfig,
    connectBgJobRecordsLimit: Int,
    connectBgJobRecurrenceDelay: Duration,
    connectBgJobProcessingParallelism: Int,
    connectInvitationExpiry: Duration,
)

final case class PrismNodeConfig(service: GrpcServiceConfig)

final case class GrpcServiceConfig(host: String, port: Int, usePlainText: Boolean)

final case class StatusListRegistryConfig(publicEndpointUrl: java.net.URL)

final case class DatabaseConfig(
    host: String,
    port: Int,
    databaseName: String,
    username: String,
    password: String,
    appUsername: String,
    appPassword: String,
    awaitConnectionThreads: Int
) {
  def dbConfig(appUser: Boolean): DbConfig = {
    DbConfig(
      username = if (appUser) appUsername else username,
      password = if (appUser) appPassword else password,
      jdbcUrl = s"jdbc:postgresql://${host}:${port}/${databaseName}",
      awaitConnectionThreads = awaitConnectionThreads
    )
  }
}

final case class PresentationVerificationConfig(
    verifySignature: Boolean,
    verifyDates: Boolean,
    verifyHoldersBinding: Boolean,
    leeway: Duration,
)

final case class CredentialVerificationConfig(
    verifySignature: Boolean,
    verifyDates: Boolean,
    leeway: Duration,
)

final case class Options(credential: CredentialVerificationConfig, presentation: PresentationVerificationConfig)

final case class VerificationConfig(options: Options) {
  def toPresentationVerificationOptions(): JwtPresentation.PresentationVerificationOptions = {
    JwtPresentation.PresentationVerificationOptions(
      maybeProofPurpose = Some(VerificationRelationship.Authentication),
      verifySignature = options.presentation.verifySignature,
      verifyDates = options.presentation.verifyDates,
      verifyHoldersBinding = options.presentation.verifyHoldersBinding,
      leeway = options.presentation.leeway,
      maybeCredentialOptions = Some(
        CredentialVerification.CredentialVerificationOptions(
          verifySignature = options.credential.verifySignature,
          verifyDates = options.credential.verifyDates,
          leeway = options.credential.leeway,
          maybeProofPurpose = Some(VerificationRelationship.AssertionMethod)
        )
      )
    )
  }
}

final case class WebhookPublisherConfig(
    url: Option[URL],
    apiKey: Option[String],
    parallelism: Option[Int]
)

final case class DefaultWalletConfig(
    enabled: Boolean,
    seed: Option[String],
    webhookUrl: Option[URL],
    webhookApiKey: Option[String],
    authApiKey: String
)

final case class AgentConfig(
    httpEndpoint: HttpEndpointConfig,
    didCommEndpoint: DidCommEndpointConfig,
    httpClient: HttpClientConfig,
    authentication: AuthenticationConfig,
    database: DatabaseConfig,
    verification: VerificationConfig,
    secretStorage: SecretStorageConfig,
    webhookPublisher: WebhookPublisherConfig,
    defaultWallet: DefaultWalletConfig
) {
  def validate: Either[String, Unit] =
    for {
      _ <- Either.cond(
        defaultWallet.enabled || authentication.isEnabledAny,
        (),
        "The default wallet must be enabled if all the authentication methods are disabled. Default wallet is required for the single-tenant mode."
      )
      _ <- secretStorage.validate
    } yield ()

}

final case class HttpEndpointConfig(http: HttpConfig, publicEndpointUrl: java.net.URL)

final case class DidCommEndpointConfig(http: HttpConfig, publicEndpointUrl: java.net.URL)

final case class HttpConfig(port: Int)

final case class HttpClientConfig(connectionPoolSize: Int, idleTimeout: Duration, connectionTimeout: Duration)

final case class SecretStorageConfig(
    backend: SecretStorageBackend,
    vault: Option[VaultConfig],
) {
  def validate: Either[String, Unit] =
    backend match {
      case SecretStorageBackend.vault =>
        vault
          .toRight("SecretStorage backend is set to 'vault', but vault config is not provided.")
          .flatMap(_.validate)
          .map(_ => ())
      case _ => Right(())
    }
}

enum SecretStorageBackend {
  case vault, postgres
}
