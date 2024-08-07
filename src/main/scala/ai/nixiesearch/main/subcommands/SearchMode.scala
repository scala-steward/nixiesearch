package ai.nixiesearch.main.subcommands

import ai.nixiesearch.api.*
import ai.nixiesearch.api.API.info
import ai.nixiesearch.config.Config
import ai.nixiesearch.core.Logging
import ai.nixiesearch.index.Searcher
import ai.nixiesearch.index.sync.Index
import ai.nixiesearch.main.CliConfig.CliArgs.SearchArgs
import ai.nixiesearch.main.Logo
import cats.data.Kleisli
import cats.effect.IO
import cats.implicits.*
import fs2.Stream
import org.http4s.server.websocket.WebSocketBuilder

import scala.concurrent.duration.*

object SearchMode extends Logging {
  def run(args: SearchArgs): IO[Unit] = for {
    _      <- info("Starting in 'search' mode with only searcher")
    config <- Config.load(args.config)
    _ <- config.schema.values.toList
      .map(im =>
        for {
          index    <- Index.forSearch(im, config.core.cache)
          searcher <- Searcher.open(index)
          _ <- Stream
            .repeatEval(index.sync().flatMap {
              case false => IO.unit
              case true  => searcher.sync()
            })
            .metered(1.second)
            .compile
            .drain
            .background
        } yield {
          searcher
        }
      )
      .sequence
      .use(searchers =>
        for {
          searchRoutes <- IO(
            searchers
              .map(s =>
                SearchRoute(s).routes <+> WebuiRoute(s).routes <+> MappingRoute(s.index).routes <+> StatsRoute(s).routes
              )
              .reduce(_ <+> _)
          )
          searchRoutesWss <- IO((wsb: WebSocketBuilder[IO]) =>
            searchers.map(s => SearchRoute(s).wsroutes(wsb)).reduce(_ <+> _)
          )
          health <- IO(HealthRoute())
          errors <- IO(TypicalErrorsRoute(searchers.map(_.index.name.value)))
          routes <- IO(
            searchRoutes <+> health.routes <+> AdminRoute(config).routes <+> MainRoute(
              searchers.map(_.index)
            ).routes <+> errors.routes
          )
          server <- API.start(routes, searchRoutesWss, config.searcher.host, config.searcher.port)
          _      <- Logo.lines.map(line => info(line)).sequence
          _      <- server.use(_ => IO.never)
        } yield {}
      )
  } yield {}

}
