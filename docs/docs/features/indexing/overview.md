# Building an index

Nixiesearch index is just a regular index like in [Elastic](https://www.elastic.co/blog/what-is-an-elasticsearch-index)/[OpenSearch](https://docs.opensearch.org)/[SOLR](https://solr.apache.org/), but with the following differences:

* Indexes are created by defining their schemas in a [config file](../../reference/config.md). It is deliberately not possible to create an index at runtime using [REST API](../../api.md), as Nixiesearch instances have an immutable configuration.
* Index always has a [strict schema](mapping.md) defined. Schemaless approach is user-friendly, but you will eventually have 10 different ways to store a boolean field, like it usually happens in MongoDB 🫠.

To add a set of documents to an index, you need to perform these two steps:

* define an [index mapping](#index-mapping) [in a config file](mapping.md). Nixiesearch is a strongly-typed document storage system, so [dynamic mapping](https://www.elastic.co/guide/en/elasticsearch/reference/current/dynamic-mapping.html) is not supported.
* write documents to the index, either with push-based REST API or with pull-based stream ingestion.

!!! note 

    [Dynamic mapping](https://www.elastic.co/guide/en/elasticsearch/reference/current/dynamic-mapping.html) in most search engines is considered an anti-pattern: the engine cannot correctly guess how are you going to query documents, so by default all fields are marked as searchable, facetable, filterable and suggestable. This results in slow ingestion throughput and huge index size.

## Index mapping

To define an index mapping, you need to add an index-specific block to the `schema` section of the [configuration file](../../reference/config.md):

```yaml
schema:
  my-first-index:
    fields:
      title:
        type: text
        search: 
          lexical:
            analyze: english
      price:
        type: float
        filter: true
```

In the example above we defined an index `my-first-index` with two fields title and price. Index is stored on disk by default.

Each field definition in a static mapping has two groups of settings:

* Field type specific parameters - like how it's going to be searched for text fields. In the example above we only allowed `lexical` search over the `title` field. See [the mapping reference for text fields](mapping.md#semantic-search) to see how to enable semantic and hybrid search.
* Global parameters - is this field can be stored, filtered, faceted and sorted.

Go to [the mapping reference](mapping.md) section for more details on all field parameters.

## Writing documents to an index

Internally Nixiesearch implements a pull-based indexing - the service itself asks for a next chunk of documents from an upstream source. See [File-based indexing](../../deployment/distributed/indexing/file.md) and [Stream indexing from Apache Kafka](../../deployment/distributed/indexing/kafka.md) for more examples on this approach.

![push pull](../../img/pullpush.png)

For convenience, Nixiesearch can emulate a push-based approach via [REST API](../../api.md) you probably got used with other search engines - your app should submit a JSON payload with documents and wait for an acknowledgement.

### Starting Nixiesearch

Nixiesearch has multiple ways of running indexing:

* [Offline indexing](../../reference/cli/index.md#offline-indexing). Useful when performing full reindexing from static document source, like from a set of files, or from Kafka topic.
* [Online indexing](../../reference/cli/index.md#online-indexing). For folks who got used to Elasticsearch with REST API.

For the sake of simplicity we can start Nixiesearch in a [standalone](../../deployment/standalone.md) mode, which bundles both searcher and indexer in a single process with a shared [REST API](../../api.md).

```shell
docker run -it -p 8080:8080 -v .:/data nixiesearch/nixiesearch:latest standalone --config /path/to/conf.yml
```

!!! note

    Standalone mode is intended for small-scale local deployments and developer environments, not for a production use. If you plan to use Nixiesearch with real customer traffic, consider using a [distributed](../../deployment/distributed/overview.md) deployment with [S3-based storage](../../deployment/distributed/persistence/s3.md).

### Indexing REST API

Each Nixiesearch index has an `/v1/index/<index-name>` REST endpoint where you can [HTTP POST](https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/POST) your documents to.

This endpoint expects a JSON payload in [one of the following formats](../../features/indexing/format.md):

* JSON object: just a single document.
* JSON array of objects: a batch of documents.
* JSON-Line array of objects: also a batch of documents, but simpler wire format.

For example, writing a single document to an `dev` index can be done with a cURL command:

```bash
curl -XPUT -d '{"title": "hello", "color": ["red"], "meta": {"sku":"a123"}}'\
  http://localhost:8080/dev/_index
```

!!! note

    To have proper back-pressure mechanism, prefer using a pull-based indexing with [Apache Kafka](../../deployment/distributed/indexing/kafka.md) or with [offline file-based ingestion](../../reference/cli/index.md#offline-indexing).

### Streaming document indexing

With pull-based streaming indexing supported natively, it becomes trivial to implement these typical scenarios:

1. **Batch full re-indexing**: take all documents from a datasource and periodically re-build index from scratch.
2. **Distributed journal as a single source of truth**: use [Kafka compacted topics](https://developer.confluent.io/courses/architecture/compaction/) as a view over last versions of documents, with real-time updates.
3. **Large dataset import**: import a complete set of documents from local/S3 files, maintaining optimal throughput and batching.

![kafka streaming](../../img/kafka.png)

Nixiesearch supports [Apache Kafka](https://kafka.apache.org/), [AWS S3](https://aws.amazon.com/s3/) (and also compatible object stores) and local files as a source of documents for indexing.

If you have your dataset in a JSON file, instead of making HTTP PUT with very large payload using REST API, you can invoke a [`nixiesearch index`](../../reference/cli/index.md) sub-command to perform streaming indexing in a separate process:

```shell
docker run -itv .:/data nixiesearch/nixiesearch:latest index file \
  --config /data/conf.yml --index <index name> \
  --url file:///data/docs.json
```

Where `<your-local-dir>` is a directory containing the `conf.yml` config file and a `docs.json` with documents for indexing. 


See [index CLI reference](../../reference/cli/index.md) and [Supported URL formats](../../reference/url.md) for more details.

### Next steps

In the next section, learn how you can create an [index mapping](mapping.md).