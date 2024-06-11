package org.hyperledger.identus.mercury.protocol.issuecredential

import io.circe.parser.*
import io.circe.syntax.*
import io.circe.Decoder
import org.hyperledger.identus.mercury.model.{AttachmentDescriptor, Base64, JsonData, JwsData, LinkData}

private trait ReadAttachmentsUtils {

  def attachments: Seq[AttachmentDescriptor]

  // TODO this formatName shoud be type safe
  lazy val getCredentialFormatAndCredential: Seq[(String, String, Array[Byte])] =
    attachments
      .flatMap(attachment =>
        attachment.format.map { formatName =>
          attachment.data match {
            case obj: JwsData  => ??? // TODO
            case obj: Base64   => (attachment.id, formatName, obj.base64.getBytes())
            case obj: LinkData => ??? // TODO
            case obj: JsonData =>
              (
                attachment.id,
                formatName,
                java.util.Base64
                  .getUrlEncoder()
                  .encode(obj.json.asJson.noSpaces.getBytes())
              )
          }
        }
      )

  /** @return
    *   credential data (of a certain format type) in an array of Bytes encoded in base 64
    */
  def getCredential[A](credentialFormatName: String)(using decodeA: Decoder[A]): Seq[A] =
    getCredentialFormatAndCredential
      .filter(_._2 == credentialFormatName)
      .map(e => java.util.Base64.getUrlDecoder().decode(e._3))
      .map(String(_))
      .map(e => decode[A](e))
      .flatMap(_.toOption)

}
