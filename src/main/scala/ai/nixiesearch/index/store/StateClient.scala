package ai.nixiesearch.index.store

import ai.nixiesearch.config.StoreConfig.BlockStoreLocation
import ai.nixiesearch.config.mapping.{IndexMapping, IndexName}
import ai.nixiesearch.index.manifest.IndexManifest
import cats.effect.{IO, Resource}
import fs2.{Chunk, Stream}
import io.circe.syntax.*

import java.nio.ByteBuffer

trait StateClient {
  def createManifest(mapping: IndexMapping, seqnum: Long): IO[IndexManifest]
  def readManifest(): IO[Option[IndexManifest]]
  def read(fileName: String): Stream[IO, Byte]
  def write(fileName: String, stream: Stream[IO, Byte]): IO[Unit]
  def delete(fileName: String): IO[Unit]

  def writeManifest(manifest: IndexManifest): IO[Unit] =
    write(
      fileName = IndexManifest.MANIFEST_FILE_NAME,
      stream = Stream.chunk(Chunk.byteBuffer(ByteBuffer.wrap(manifest.asJson.spaces2.getBytes())))
    )
}

object StateClient {
  enum StateError extends Exception {
    case FileMissingError(file: String)         extends StateError
    case FileExistsError(file: String)          extends StateError
    case InconsistentStateError(reason: String) extends StateError
  }

  def createRemote(config: BlockStoreLocation, indexName: IndexName): Resource[IO, StateClient] = config match {
    case s: BlockStoreLocation.S3Location         => S3StateClient.create(s, indexName)
    case s: BlockStoreLocation.RemoteDiskLocation => RemotePathStateClient.create(s.path, indexName)
  }

}
