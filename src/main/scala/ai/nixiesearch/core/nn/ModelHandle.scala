package ai.nixiesearch.core.nn

import ai.nixiesearch.core.nn.ModelHandle.HuggingFaceHandle
import io.circe.{Decoder, Encoder, Json}

import scala.util.{Failure, Success}

sealed trait ModelHandle {
  def name: String
  def asList: List[String]
  def toURL: String
}

object ModelHandle {
  def apply(ns: String, name: String) = HuggingFaceHandle(ns, name)

  case class HuggingFaceHandle(ns: String, name: String) extends ModelHandle {
    override def asList: List[String] = List(ns, name)

    override def toURL: String = s"hf://$ns/$name"
  }

  case class LocalModelHandle(dir: String) extends ModelHandle {
    override def toURL: String        = s"file://$dir"
    override def name: String         = dir
    override def asList: List[String] = List(dir)
  }

  val huggingFacePattern = "(hf://)?([a-zA-Z0-9\\-]+)/([0-9A-Za-z\\-_\\.]+)".r
  val localPattern       = "file://?(/[^\\?]*)".r

  given modelHandleDecoder: Decoder[ModelHandle] = Decoder.decodeString.emapTry {
    case huggingFacePattern(_, ns, name) => Success(HuggingFaceHandle(ns, name))
    case localPattern(path)              => Success(LocalModelHandle(path))
    case other                           => Failure(InternalError(s"cannot parse model handle '$other'"))
  }

  given modelHandleEncoder: Encoder[ModelHandle] = Encoder.instance {
    case HuggingFaceHandle(ns, name) => Json.fromString(s"$ns/$name")
    case LocalModelHandle(path)      => Json.fromString(s"file://$path")
  }
}
