package groot.graaljs

import java.net.URI
import org.graalvm.polyglot.Source

/** Text adapters use an async entry point to support top-level await. Returns a native Graal Source. */
private[groot] object JavaScript:
  val maxSourceBytes: Int = Limits.maxSourceBytes
  private val prefix = "(async () => {\n"
  private val suffix = "\n})()"
  private[graaljs] val maxExecutableBytes: Int = maxSourceBytes + prefix.length + suffix.length

  def source(text: String, name: String = "inline.graal.js", cached: Boolean = false): Source =
    Validation.source(text)
    Source.newBuilder("js", s"$prefix$text$suffix", name)
      .uri(URI.create(s"graaljs:/$name")).cached(cached).buildLiteral()
