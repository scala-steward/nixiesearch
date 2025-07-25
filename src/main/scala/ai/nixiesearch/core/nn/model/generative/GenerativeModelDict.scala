package ai.nixiesearch.core.nn.model.generative

import ai.nixiesearch.config.InferenceConfig.CompletionInferenceModelConfig
import ai.nixiesearch.config.InferenceConfig.CompletionInferenceModelConfig.LlamacppInferenceModelConfig
import ai.nixiesearch.config.mapping.FieldName
import ai.nixiesearch.core.Error.UserError
import ai.nixiesearch.core.{Document, Logging}
import ai.nixiesearch.core.metrics.Metrics
import ai.nixiesearch.core.nn.{ModelHandle, ModelRef}
import ai.nixiesearch.core.nn.ModelHandle.{HuggingFaceHandle, LocalModelHandle}
import ai.nixiesearch.core.nn.huggingface.{HuggingFaceClient, ModelFileCache}
import ai.nixiesearch.core.nn.model.generative.GenerativeModel.LlamacppGenerativeModel
import ai.nixiesearch.util.GPUUtils
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.io.file.Path as Fs2Path
import fs2.Stream

case class GenerativeModelDict(models: Map[ModelRef, GenerativeModel], metrics: Metrics) {
  def generate(name: ModelRef, input: String, maxTokens: Int): Stream[IO, String] = models.get(name) match {
    case Some(model) =>
      for {
        _     <- Stream.eval(IO(metrics.inference.completionTotal.labelValues(name.name).inc()))
        start <- Stream.eval(IO(System.currentTimeMillis()))
        token <- model
          .generate(input, maxTokens)
          .evalTap(_ => IO(metrics.inference.completionGeneratedTokensTotal.labelValues(name.name).inc()))
          .onFinalize(
            IO(
              metrics.inference.completionTimeSeconds
                .labelValues(name.name)
                .inc((System.currentTimeMillis() - start) / 1000.0)
            )
          )
      } yield {
        token
      }
    case None =>
      Stream.raiseError(
        UserError(s"RAG model handle ${name} cannot be found among these found in config: ${models.keys.toList}")
      )
  }
  def prompt(
      name: ModelRef,
      instruction: String,
      docs: List[Document],
      maxTokensPerDoc: Int,
      fields: List[FieldName]
  ): IO[String] =
    models.get(name) match {
      case Some(model) => model.prompt(instruction, docs, maxTokensPerDoc, fields)
      case None        =>
        IO.raiseError(
          UserError(s"RAG model handle ${name} cannot be found among these found in config: ${models.keys.toList}")
        )
    }
}

object GenerativeModelDict extends Logging {

  def create(
      models: Map[ModelRef, CompletionInferenceModelConfig],
      cache: ModelFileCache,
      metrics: Metrics
  ): Resource[IO, GenerativeModelDict] =
    for {
      generativeModels <- models.toList.map {
        case (name: ModelRef, conf @ LlamacppInferenceModelConfig(handle: HuggingFaceHandle, _, _, _)) =>
          createHuggingface(handle, name, conf, cache).map(model => name -> model)
        case (name: ModelRef, conf @ LlamacppInferenceModelConfig(handle: LocalModelHandle, _, _, _)) =>
          createLocal(handle, name, conf).map(model => name -> model)
      }.sequence
    } yield {
      GenerativeModelDict(generativeModels.toMap, metrics)
    }

  def createHuggingface(
      handle: HuggingFaceHandle,
      name: ModelRef,
      config: LlamacppInferenceModelConfig,
      cache: ModelFileCache
  ): Resource[IO, GenerativeModel] = for {
    hf        <- HuggingFaceClient.create(cache)
    modelFile <- Resource.eval(for {
      card      <- hf.model(handle)
      modelFile <- chooseModelFile(card.siblings.map(_.rfilename), config.file)
      _         <- info(s"Fetching $handle from HF: model=$modelFile")
      modelPath <- hf.getCached(handle, modelFile)
    } yield {
      modelPath
    })
    isGPU    <- Resource.eval(IO(GPUUtils.isGPUBuild()))
    genModel <- LlamacppGenerativeModel.create(
      path = modelFile,
      options = config.options,
      useGpu = isGPU,
      name = name
    )
  } yield {
    genModel
  }

  def createLocal(
      handle: LocalModelHandle,
      name: ModelRef,
      config: LlamacppInferenceModelConfig
  ): Resource[IO, GenerativeModel] = {
    for {
      modelFile <- Resource.eval(for {
        path      <- IO(Fs2Path(handle.dir))
        files     <- fs2.io.file.Files[IO].list(path).map(_.fileName.toString).compile.toList
        modelFile <- chooseModelFile(files, config.file)
        _         <- info(s"loading $modelFile from $handle")
      } yield {
        path.toNioPath.resolve(modelFile)
      })
      isGPU    <- Resource.eval(IO(GPUUtils.isGPUBuild()))
      genModel <- LlamacppGenerativeModel.create(
        path = modelFile,
        options = config.options,
        useGpu = isGPU,
        name = name
      )
    } yield {
      genModel
    }
  }

  def chooseModelFile(files: List[String], forced: Option[String]): IO[String] = forced match {
    case Some(file) => IO.pure(file)
    case None       =>
      files.find(f => f.toLowerCase().endsWith("gguf")) match {
        case Some(file) => IO.pure(file)
        case None       => IO.raiseError(UserError(s"cannot choose a GGUF model file out of this list: ${files}"))
      }
  }

}
