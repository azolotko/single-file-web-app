//> using scala "3.3.1"
//> using dep "org.http4s::http4s-dsl::1.0.0-M40"
//> using dep "org.http4s::http4s-ember-server::1.0.0-M40"
//> using dep "org.http4s::http4s-scalatags::1.0.0-M38"
//> using dep "com.lihaoyi::scalatags::0.12.0"
//> using dep "org.typelevel::log4cats-noop::2.6.0"
//> using dep "org.tpolecat::doobie-core::1.0.0-RC4"
//> using dep "org.tpolecat::doobie-h2::1.0.0-RC4"

import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.implicits.given
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.scalatags.*
import scalatags.Text.all.*
import org.typelevel.log4cats.LoggerFactory
import com.comcast.ip4s.*
import doobie.*
import doobie.implicits.*
import cats.effect.{IO, ResourceApp, ResourceIO}
import cats.syntax.all.*
import doobie.h2.H2Transactor
import doobie.util.transactor.Transactor

import scala.concurrent.ExecutionContext.global

object WebApp extends ResourceApp.Forever:
  given loggerFactory: LoggerFactory[IO] = org.typelevel.log4cats.noop.NoOpFactory[IO]

  override def run(args: List[String]): ResourceIO[Unit] =
    for {
      xa <- H2Transactor.newH2Transactor[IO](
        "jdbc:h2:file:./sample",
        "sa",
        "",
        global
        )
      _ <- initDb(xa).toResource
      _ <- EmberServerBuilder.default[IO]
        .withHost(ipv4"0.0.0.0")
        .withPort(port"8080")
        .withHttpApp(service(xa))
        .build
      _ <- IO.println("Listening on http://0.0.0.0:8080").toResource
    } yield ()

private def initDb(xa: Transactor[IO]): IO[Unit] =
  val createTable = sql"""
      create table if not exists messages (
        id identity not null primary key,
        content text not null
      )
    """.update.run

  val insertSampleData = sql"""
      insert into messages (content) values ('hello from scala and sqlite!')
    """.update.run

  (createTable >> insertSampleData).void.transact(xa).recoverWith {
    case e: Exception => IO(e.printStackTrace()) >> IO.raiseError(e)
  }

private def service(xa: Transactor[IO]) = HttpRoutes.of[IO] {
  case GET -> Root =>
    val fetchMessage = sql"select content from messages limit 1".query[String].unique.transact(xa)
    fetchMessage.flatMap(message =>
      Ok(
        html(
          head(
            title := "Hello with Doobie"
          ),
          body(
            backgroundColor := "green",
            h1("Message from the database:"),
            p(message)
          )
        )
      )
    )
}.orNotFound
