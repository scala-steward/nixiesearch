package ai.nixiesearch.index.store

import ai.nixiesearch.config.mapping.IndexName
import org.apache.lucene.store.ByteBuffersDirectory

class DirectoryStateClientTest extends StateClientSuite[DirectoryStateClient] {
  override def client() = DirectoryStateClient.create(new ByteBuffersDirectory(), IndexName.unsafe("test"))
}
