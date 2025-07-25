package ai.nixiesearch.core.codec.compat

import ai.nixiesearch.config.FieldSchema.TextLikeFieldSchema
import ai.nixiesearch.config.mapping.SearchParams.QuantStore.Int1
import ai.nixiesearch.config.mapping.SearchParams.{QuantStore, SemanticParams}
import ai.nixiesearch.config.mapping.{FieldName, IndexConfig, IndexMapping}
import ai.nixiesearch.core.Error.BackendError
import ai.nixiesearch.core.Logging
import ai.nixiesearch.core.field.TextField
import org.apache.lucene.backward_codecs.lucene912.Lucene912Codec
import org.apache.lucene.codecs.lucene101.Lucene101Codec
import org.apache.lucene.codecs.lucene102.Lucene102HnswBinaryQuantizedVectorsFormat
import org.apache.lucene.codecs.{Codec, FilterCodec, KnnVectorsFormat, PostingsFormat}
import org.apache.lucene.codecs.lucene99.{Lucene99HnswScalarQuantizedVectorsFormat, Lucene99HnswVectorsFormat}
import org.apache.lucene.codecs.perfield.{PerFieldKnnVectorsFormat, PerFieldPostingsFormat}
import org.apache.lucene.search.suggest.document.{
  Completion101PostingsFormat,
  Completion912PostingsFormat,
  CompletionPostingsFormat
}

import scala.collection.mutable

class Nixiesearch101Codec(parent: Codec, mapping: IndexMapping)
    extends FilterCodec("Nixiesearch101", parent)
    with Logging {

  def this() = {
    this(new Lucene101Codec(), null)
    logger.warn("empty codec constructor called, this should not happen!")
  }
  val suggestPostingsFormat = new Completion101PostingsFormat()

  override def postingsFormat(): PostingsFormat = new PerFieldPostingsFormat {
    override def getPostingsFormatForField(field: String): PostingsFormat =
      if (field.endsWith(TextField.SUGGEST_SUFFIX)) {
        suggestPostingsFormat
      } else {
        delegate.postingsFormat().asInstanceOf[PerFieldPostingsFormat].getPostingsFormatForField(field)
      }
  }

  val cache: mutable.Map[String, KnnVectorsFormat]  = mutable.Map()
  override def knnVectorsFormat(): KnnVectorsFormat = new PerFieldKnnVectorsFormat() {
    def getKnnVectorsFormatForField(field: String): KnnVectorsFormat = {
      cache.get(field) match {
        case Some(fmt) => fmt
        case None      =>
          val fmt = mapping.fieldSchemaOf[TextLikeFieldSchema[?]](field) match {
            case Some(schema) =>
              schema.search.semantic match {
                case Some(conf) =>
                  val fmt = conf.quantize match {
                    case QuantStore.Float32 => new Lucene99HnswVectorsFormat(conf.m, conf.ef, conf.workers, null)
                    case QuantStore.Int8    =>
                      new Lucene99HnswScalarQuantizedVectorsFormat(conf.m, conf.ef, conf.workers, 7, false, 0, null)
                    case QuantStore.Int4 =>
                      new Lucene99HnswScalarQuantizedVectorsFormat(conf.m, conf.ef, conf.workers, 4, true, 0, null)
                    case QuantStore.Int1 =>
                      new Lucene102HnswBinaryQuantizedVectorsFormat(conf.m, conf.ef, conf.workers, null)
                  }
                  cache.put(field, fmt)
                  fmt
                case None =>
                  throw BackendError(
                    s"field $field expected to be a vector field, but it's ${mapping.fieldSchema(field)}"
                  )
              }
            case None => throw BackendError(s"cannot find field format for '$field'")
          }
          cache.put(field, fmt)
          fmt
      }
    }
  };

}

object Nixiesearch101Codec {
  def apply(mapping: IndexMapping): Nixiesearch101Codec = {
    new Nixiesearch101Codec(new Lucene101Codec(), mapping)
  }
}
