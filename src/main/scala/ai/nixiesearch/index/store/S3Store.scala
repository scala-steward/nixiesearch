package ai.nixiesearch.index.store

import ai.nixiesearch.config.StoreConfig.S3StoreConfig
import ai.nixiesearch.config.mapping.IndexMapping
import ai.nixiesearch.index.store.rw.{StoreReader, StoreWriter}
import cats.effect.IO
import org.apache.lucene.index.IndexReader

case class S3Store(config: S3StoreConfig) extends Store {
  override def reader(index: IndexMapping): IO[Option[StoreReader]] = ???
  def mapping(indexName: String): IO[Option[IndexMapping]]          = ???
  override def writer(index: IndexMapping): IO[StoreWriter]         = ???
  override def refresh(index: IndexMapping): IO[Unit]               = ???
}