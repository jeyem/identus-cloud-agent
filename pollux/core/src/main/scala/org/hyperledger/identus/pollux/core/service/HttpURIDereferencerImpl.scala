package org.hyperledger.identus.pollux.core.service

import org.hyperledger.identus.pollux.core.service.URIDereferencerError.*
import zio.*
import zio.http.*

import java.net.URI
import java.nio.charset.StandardCharsets

class HttpURIDereferencerImpl(client: Client) extends URIDereferencer {

  override def dereference(uri: URI): IO[URIDereferencerError, String] = {
    val program = for {
      url <- ZIO.fromOption(URL.fromURI(uri)).mapError(_ => InvalidURI(uri))
      response <- client
        .request(Request(url = url))
        .mapError(t => ConnectionError(t.getMessage))
      body <- response.status match {
        case Status.Ok =>
          response.body.asString.mapError(t => ResponseProcessingError(t.getMessage))
        case Status.NotFound =>
          ZIO.fail(ResourceNotFound(uri))
        case status if status.isError =>
          response.body.asStream
            .take(1024) // Only take the first 1024 bytes from the response body (if any).
            .runCollect
            .map(c => new String(c.toArray, StandardCharsets.UTF_8))
            .orDie
            .flatMap(errorMessage => ZIO.fail(UnexpectedUpstreamResponseReceived(status.code, Some(errorMessage))))
        case status =>
          ZIO.fail(UnexpectedUpstreamResponseReceived(status.code))
      }
    } yield body
    program.provideSomeLayer(zio.Scope.default)
  }

}

object HttpURIDereferencerImpl {
  val layer: URLayer[Client, URIDereferencer] = ZLayer.fromFunction(HttpURIDereferencerImpl(_))
}
