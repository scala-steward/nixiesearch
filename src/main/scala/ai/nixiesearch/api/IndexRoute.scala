package ai.nixiesearch.api

import ai.nixiesearch.config.mapping.IndexMapping
import ai.nixiesearch.core.{Document, Logging}
import ai.nixiesearch.index.IndexRegistry
import cats.effect.IO
import io.circe.{Codec, Encoder, Json}
import org.http4s.{EntityDecoder, EntityEncoder, HttpRoutes, Request, Response}
import org.http4s.dsl.io.*
import org.http4s.circe.*
import io.circe.generic.semiauto.*

case class IndexRoute(registry: IndexRegistry) extends Route with Logging {
  import IndexRoute._

  val routes = HttpRoutes.of[IO] {
    case POST -> Root / indexName / "_flush"          => flush(indexName)
    case request @ PUT -> Root / indexName / "_index" => index(request, indexName)
    case GET -> Root / indexName / "_mapping"         => mapping(indexName)
  }

  def index(request: Request[IO], indexName: String): IO[Response[IO]] = for {
    start <- IO(System.currentTimeMillis())
    docs  <- request.as[List[Document]].handleErrorWith(_ => request.as[Document].flatMap(doc => IO.pure(List(doc))))
    _     <- info(s"PUT /$indexName/_index, payload: ${docs.size} docs")
    mapping <- registry.mapping(indexName).flatMap {
      case Some(existing) =>
        existing.config.mapping.dynamic match {
          case false => IO.pure(existing)
          case true =>
            for {
              updated <- IndexMapping.fromDocument(docs, indexName)
              merged  <- existing.dynamic(updated)
              writer  <- registry.writer(merged)
              _       <- IO.whenA(merged != existing)(writer.refreshMapping(merged))
            } yield {
              merged
            }
        }

      case None =>
        for {
          _ <- warn(s"Index '$indexName' mapping not found, using dynamic mapping")
          _ <- warn("Dynamic mapping is only recommended for testing. Prefer explicit mapping definition in config.")
          generated <- IndexMapping.fromDocument(docs, indexName).map(_.withDynamicMapping(true))
          _         <- info(s"Generated mapping $generated")
          writer    <- registry.writer(generated)
          _         <- writer.refreshMapping(generated)
        } yield {
          generated
        }
    }
    writer   <- registry.writer(mapping)
    _        <- writer.addDocuments(docs)
    response <- Ok(IndexResponse.withStartTime("created", start))
  } yield {
    response
  }

  def mapping(indexName: String): IO[Response[IO]] =
    registry.mapping(indexName).flatMap {
      case Some(index) => info(s"GET /$indexName/_mapping") *> Ok(index)
      case None        => NotFound(s"index $indexName is missing in config file")
    }

  def flush(indexName: String): IO[Response[IO]] = {
    registry.mapping(indexName).flatMap {
      case None => NotFound(s"index $indexName is missing in config file")
      case Some(mapping) =>
        registry.writer(mapping).flatMap { writer =>
          for {
            start    <- IO(System.currentTimeMillis())
            _        <- info(s"POST /$indexName/_flush")
            _        <- writer.flush()
            response <- Ok(IndexResponse.withStartTime("flushed", start))
          } yield {
            response
          }

        }

    }
  }
}

object IndexRoute {
  case class IndexResponse(result: String, took: Int = 0)
  object IndexResponse {
    def withStartTime(result: String, start: Long) = IndexResponse(result, (System.currentTimeMillis() - start).toInt)
  }
  implicit val indexResponseCodec: Codec[IndexResponse] = deriveCodec

  import ai.nixiesearch.config.mapping.IndexMapping.json._

  implicit val schemaJson: EntityEncoder[IO, IndexMapping]                = jsonEncoderOf
  implicit val singleDocJson: EntityDecoder[IO, Document]                 = jsonOf
  implicit val docListJson: EntityDecoder[IO, List[Document]]             = jsonOf
  implicit val indexResponseEncoderJson: EntityEncoder[IO, IndexResponse] = jsonEncoderOf
  implicit val indexResponseDecoderJson: EntityDecoder[IO, IndexResponse] = jsonOf
}
