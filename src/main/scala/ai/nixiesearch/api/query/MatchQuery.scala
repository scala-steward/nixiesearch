package ai.nixiesearch.api.query

import ai.nixiesearch.api.query.MatchQuery.Operator
import ai.nixiesearch.api.query.MatchQuery.Operator.OR
import ai.nixiesearch.core.Logging
import io.circe.{Decoder, DecodingFailure, Encoder, Json, JsonObject}
import org.apache.lucene.search.BooleanClause

import io.circe.generic.semiauto.*
import org.apache.lucene.search.BooleanClause.Occur

import scala.util.{Failure, Success}

case class MatchQuery(field: String, query: String, operator: Operator = OR) extends Query with Logging

object MatchQuery {
  sealed trait Operator {
    def occur: BooleanClause.Occur
  }
  object Operator {
    case object AND extends Operator {
      val occur = Occur.MUST
    }
    case object OR extends Operator {
      val occur = Occur.SHOULD
    }

    implicit val operatorDecoder: Decoder[Operator] = Decoder.decodeString.emapTry {
      case "and" | "AND" => Success(AND)
      case "or" | "OR"   => Success(OR)
      case other         => Failure(new Exception(s"cannot parse operator '$other', use AND|OR"))
    }

    implicit val operatorEncoder: Encoder[Operator] = Encoder.encodeString.contramap {
      case AND => "and"
      case OR  => "or"
    }
  }

  case class FieldQuery(query: String, operator: Option[Operator])

  implicit val fieldQueryDecoder: Decoder[FieldQuery] = deriveDecoder
  implicit val fieldQueryEncoder: Encoder[FieldQuery] = deriveEncoder

  implicit val matchQueryEncoder: Encoder[MatchQuery] = Encoder.instance(q =>
    Json.fromJsonObject(
      JsonObject.fromIterable(List(q.field -> fieldQueryEncoder(FieldQuery(q.query, Some(q.operator)))))
    )
  )
  implicit val matchQueryDecoder: Decoder[MatchQuery] = Decoder.instance(c =>
    c.as[Map[String, FieldQuery]].map(_.toList) match {
      case Left(_) =>
        c.as[Map[String, String]].map(_.toList) match {
          case Left(error)                  => Left(error)
          case Right((field, query) :: Nil) => Right(MatchQuery(field, query, OR))
          case Right(other)                 => Left(DecodingFailure(s"cannot decode query $other", c.history))
        }
      case Right((field, query) :: Nil) => Right(MatchQuery(field, query.query, query.operator.getOrElse(OR)))
      case Right(other)                 => Left(DecodingFailure(s"cannot decode query $other", c.history))
    }
  )

}
