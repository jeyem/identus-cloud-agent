package org.hyperledger.identus.shared.db

import doobie.*
import doobie.postgres.implicits.*
import doobie.syntax.ConnectionIOOps
import doobie.util.transactor.Transactor
import org.hyperledger.identus.shared.models.{WalletAccessContext, WalletId}
import zio.*
import zio.interop.catz.*

import java.util.UUID

trait ContextAware
type ContextAwareTask[T] = Task[T] & ContextAware

object Implicits {

  given walletIdGet: Get[WalletId] = Get[UUID].map(WalletId.fromUUID)
  given walletIdPut: Put[WalletId] = Put[UUID].contramap(_.toUUID)

  private val WalletIdVariableName = "app.current_wallet_id"

  extension [A](ma: ConnectionIO[A]) {
    def transactWithoutContext(xa: Transactor[ContextAwareTask]): Task[A] = {
      ConnectionIOOps(ma).transact(xa.asInstanceOf[Transactor[Task]])
    }

    def transactWallet(xa: Transactor[ContextAwareTask]): RIO[WalletAccessContext, A] = {
      def walletCxnIO(ctx: WalletAccessContext) = {
        for {
          _ <- doobie.free.connection.createStatement.map { statement =>
            statement.execute(s"SET LOCAL $WalletIdVariableName TO '${ctx.walletId}'")
          }
          result <- ma
        } yield result
      }

      for {
        ctx <- ZIO.service[WalletAccessContext]
        result <- ConnectionIOOps(walletCxnIO(ctx)).transact(xa.asInstanceOf[Transactor[Task]])
      } yield result
    }

  }

  extension [Int](ma: RIO[WalletAccessContext, Int]) {
    def ensureOneAffectedRowOrDie: URIO[WalletAccessContext, Unit] = ma.flatMap {
      case 1     => ZIO.unit
      case count => ZIO.fail(RuntimeException(s"Unexpected affected row count: $count"))
    }.orDie
  }

}
