package ai.nixiesearch.util

import org.apache.commons.io.IOUtils

import java.io.ByteArrayInputStream
import java.util.jar.Attributes.Name
import scala.util.Try

object Version {
  def apply(resourceName: String = "/META-INF/MANIFEST.MF") = for {
    bytes <- Try(IOUtils.resourceToByteArray(resourceName)).toOption
    manifest = new java.util.jar.Manifest(new ByteArrayInputStream(bytes))
    attrs   <- Option(manifest.getMainAttributes)
    vendor  <- Option(attrs.get(Name.IMPLEMENTATION_VENDOR)).collect { case s: String => s }
    version <- Option(attrs.get(Name.IMPLEMENTATION_VERSION)).collect { case s: String => s }
    if vendor == "ai.nixiesearch" // to be protected from some other manifests in the classpath (like logback's)
  } yield {
    version
  }

  def isRelease: Boolean = apply().isDefined
}
