package ai.nixiesearch.index.sync

import ai.nixiesearch.config.{CacheConfig, InferenceConfig, StoreConfig}
import ai.nixiesearch.config.mapping.IndexMapping
import ai.nixiesearch.core.Logging
import ai.nixiesearch.index.Models
import ai.nixiesearch.index.manifest.IndexManifest
import ai.nixiesearch.index.store.StateClient.StateError
import ai.nixiesearch.index.store.{DirectoryStateClient, StateClient}
import cats.effect.{IO, Ref, Resource}
import org.apache.lucene.store.Directory
import fs2.{Chunk, Stream}
import io.circe.syntax.*

import java.nio.ByteBuffer

case class LocalIndex(
    mapping: IndexMapping,
    models: Models,
    master: StateClient,
    directory: Directory,
    seqnum: Ref[IO, Long]
) extends Index {
  override def sync(): IO[Boolean] = for {
    manifest <- master.readManifest().flatMap {
      case None =>
        IO.raiseError(StateError.InconsistentStateError("missing manifest for opened index: this should never happen!"))
      case Some(m) => IO.pure(m)
    }
    _ <- seqnum.set(manifest.seqnum)
    _ <- debug(s"local index '${mapping.name}' sync done, seqnum=${manifest.seqnum}")
  } yield {
    true
  }

  override def local: StateClient   = master
  override def replica: StateClient = master
}

object LocalIndex extends Logging {
  def create(
      configMapping: IndexMapping,
      config: StoreConfig.LocalStoreConfig,
      models: Models
  ): Resource[IO, LocalIndex] = {
    for {
      directory <- LocalDirectory.fromLocal(config.local, configMapping.name)
      state     <- Resource.pure(DirectoryStateClient(directory, configMapping.name))
      manifest  <- Resource.eval(readOrCreateManifest(state, configMapping))
      _         <- Resource.eval(info(s"Local index ${manifest.mapping.name.value} opened"))
      seqnum    <- Resource.eval(Ref.of[IO, Long](manifest.seqnum))
      index     <- Resource.pure(
        LocalIndex(
          mapping = manifest.mapping,
          models = models,
          master = state,
          directory = directory,
          seqnum = seqnum
        )
      )
    } yield {
      index
    }
  }

  def readOrCreateManifest(state: StateClient, configMapping: IndexMapping): IO[IndexManifest] = {
    state.readManifest().attempt.flatMap {
      case Left(e) =>
        error(s"cannot decode index.json for index ${configMapping.name.value}", e) *> IO.raiseError(e)
      case Right(None) =>
        for {
          _        <- info("index dir does not contain manifest, creating...")
          manifest <- state.createManifest(configMapping, 0L)
          _        <- state.write(
            IndexManifest.MANIFEST_FILE_NAME,
            Stream.chunk(Chunk.byteBuffer(ByteBuffer.wrap(manifest.asJson.spaces2.getBytes())))
          )
        } yield {
          manifest
        }
      case Right(Some(oldManifest)) if oldManifest.mapping == configMapping =>
        for {
          _ <- info("index manifest has no changes")
        } yield {
          oldManifest
        }
      case Right(Some(indexManifest)) =>
        for {
          mergedMapping <- indexManifest.mapping.migrate(configMapping)
          manifest      <- state.createManifest(mergedMapping, indexManifest.seqnum)
          _             <- state.write(
            IndexManifest.MANIFEST_FILE_NAME,
            Stream.chunk(Chunk.byteBuffer(ByteBuffer.wrap(manifest.asJson.spaces2.getBytes())))
          )
        } yield {
          manifest
        }
    }
  }
}
