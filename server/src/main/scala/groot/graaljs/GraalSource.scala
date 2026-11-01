package groot.graaljs

import java.net.URI
import java.nio.file.Path
import org.graalvm.polyglot.Source

/** An immutable admission snapshot. Keep the actual Source alive for the engine's code cache. */
private[groot] final case class GraalSource(text: String, value: Source)

private[groot] object GraalSource:
  def build(text: String, name: String, uri: URI, cached: Boolean): GraalSource =
    Validation.source(text)
    GraalSource(text, Source.newBuilder("js", s"(async () => {\n$text\n})()", name)
      .uri(uri).cached(cached).buildLiteral())

  def inline(text: String, id: String): GraalSource =
    build(text, s"$id.graal.js", URI.create(s"graaljs:/$id.graal.js"), cached = false)

  def file(text: String, path: Path): GraalSource =
    build(text, path.toString, path.toUri, cached = true)
