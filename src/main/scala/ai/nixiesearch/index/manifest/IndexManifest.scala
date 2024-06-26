package ai.nixiesearch.index.manifest

import ai.nixiesearch.config.mapping.IndexMapping
import ai.nixiesearch.core.Logging
import ai.nixiesearch.index.manifest.IndexManifest.{ChangedFileOp, IndexFile}
import cats.effect.{IO, Resource}
import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}
import io.circe.syntax.*
import io.circe.parser.*
import org.apache.lucene.store.{Directory, IOContext}
import fs2.Stream

import java.time.Instant

case class IndexManifest(mapping: IndexMapping, files: List[IndexFile], seqnum: Long) extends Logging {
  def diff(target: Option[IndexManifest]): IO[List[ChangedFileOp]] = {
    IO {
      val sourceMap = files.map(f => f.name -> f.updated).toMap
      val destMap   = target.map(_.files.map(f => f.name -> f.updated).toMap).getOrElse(Map.empty)
      val allKeys   = (sourceMap.keySet ++ destMap.keySet ++ Set(IndexManifest.MANIFEST_FILE_NAME)).toList
      val result = for {
        key <- allKeys
        sourceTimeOption = sourceMap.get(key)
        destTimeOption   = destMap.get(key)
      } yield {
        (sourceTimeOption, destTimeOption) match {
          case (Some(st), Some(dt)) if key == IndexManifest.MANIFEST_FILE_NAME => Some(ChangedFileOp.Add(key))
          case (Some(st), Some(dt))                                            => None
          case (Some(st), None)                                                => Some(ChangedFileOp.Add(key))
          case (None, Some(dt))                                                => Some(ChangedFileOp.Del(key))
          case (None, None)                                                    => None
        }
      }
      val ops = result.flatten
      logger.debug(s"source files=$files")
      logger.debug(s"dest files=${target.map(_.files)}")
      logger.debug(s"manifest diff: ${ops}")
      ops
    }
  }

}

object IndexManifest extends Logging {
  val MANIFEST_FILE_NAME = "index.json"

  import IndexMapping.json.given

  given indexManifestEncoder: Encoder[IndexManifest] = deriveEncoder
  given indexManifestDecoder: Decoder[IndexManifest] = deriveDecoder

  given indexFileEncoder: Encoder[IndexFile] = deriveEncoder
  given indexFileDecoder: Decoder[IndexFile] = deriveDecoder

  case class IndexFile(name: String, updated: Long)

  enum ChangedFileOp {
    case Add(fileName: String) extends ChangedFileOp
    case Del(fileName: String) extends ChangedFileOp
  }
}
