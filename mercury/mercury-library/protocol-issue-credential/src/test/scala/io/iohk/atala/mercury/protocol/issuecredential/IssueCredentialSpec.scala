package io.iohk.atala.mercury.protocol.issuecredential

import io.circe.Json
import io.circe.parser.*
import io.circe.syntax.*
import io.iohk.atala.mercury.model.AttachmentDescriptor
import io.iohk.atala.mercury.model.AttachmentDescriptor.attachmentDescriptorEncoderV2
import munit.*
import io.iohk.atala.mercury.model._
import zio.*

class IssueCredentialSpec extends ZSuite {

  test("Issuer IssueCredential") {

    val attribute1 = Attribute(name = "name", value = "Joe Blog")
    val attribute2 = Attribute(name = "dob", value = "01/10/1947")
    val credentialPreview = CredentialPreview(attributes = Seq(attribute1, attribute2))
    val body = IssueCredential.Body(goal_code = Some("Issued Credential"))
    val attachmentDescriptor = AttachmentDescriptor.buildAttachment[CredentialPreview](payload = credentialPreview)
    val attachmentDescriptorJson = attachmentDescriptor.asJson.deepDropNullValues.noSpaces

    val expectedProposalJson = parse(s"""{
                         |    "id": "061bf917-2cbe-460b-8d12-b1a9609505c2",
                         |    "type": "https://didcomm.org/issue-credential/2.0/issue-credential",
                         |    "body":
                         |    {
                         |        "goal_code": "Issued Credential",
                         |        "formats":
                         |        []
                         |    },
                         |    "attachments":
                         |    [
                         |    $attachmentDescriptorJson
                         |    ],
                         |    "to" : "did:prism:test123",
                         |    "from" : "did:prism:test123"
                         |}""".stripMargin).getOrElse(Json.Null)

    val issueCredential = IssueCredential(
      id = "061bf917-2cbe-460b-8d12-b1a9609505c2",
      body = body,
      attachments = Seq(attachmentDescriptor),
      to = DidId("did:prism:test123"),
      from = DidId("did:prism:test123")
    )

    val did = DidId("did:prism:test123")
    println("************************")
    println(did.asJson.noSpaces)
    println("************************")

    val result = issueCredential.asJson.deepDropNullValues
    assertEquals(result, expectedProposalJson)
  }
}