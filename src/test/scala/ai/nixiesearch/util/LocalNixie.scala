package ai.nixiesearch.util

import ai.nixiesearch.config.CacheConfig
import ai.nixiesearch.config.StoreConfig.MemoryStoreConfig
import ai.nixiesearch.config.mapping.IndexMapping
import ai.nixiesearch.index.Index
import ai.nixiesearch.index.cluster.{Indexer, Searcher}
import cats.effect.IO

case class LocalNixie(searcher: Searcher, indexer: Indexer) {
  def close(): IO[Unit] = searcher.close() *> indexer.close()
}

object LocalNixie {
  def create(mapping: IndexMapping): IO[LocalNixie] = for {
    index    <- Index.create(mapping, MemoryStoreConfig(), CacheConfig())
    searcher <- Searcher.create(List(index))
    indexer  <- Indexer.create(List(index))
  } yield {
    LocalNixie(searcher, indexer)
  }
}
