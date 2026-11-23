package groot.graaljs

import java.net.URI
import org.graalvm.polyglot.Source

/** Text adapters use an async entry point to support top-level await. Returns a native Graal Source. */
private[groot] object JavaScript:
  val maxSourceBytes: Int = Limits.maxSourceBytes
  private val prefix = "(async () => {\n"
  private val suffix = "\n})()"
  private[graaljs] def maxExecutableBytesFor(maxBytes: Int): Int = maxBytes + prefix.length + suffix.length
  private[graaljs] val maxExecutableBytes: Int = maxExecutableBytesFor(maxSourceBytes)

  def source(text: String, name: String = "inline.graal.js", cached: Boolean = false, maxBytes: Int = Limits.maxSourceBytes): Source =
    Validation.source(text, maxBytes)
    Source.newBuilder("js", s"$prefix$text$suffix", name)
      .uri(URI.create(s"graaljs:/$name")).cached(cached).buildLiteral()
