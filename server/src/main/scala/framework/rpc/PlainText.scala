package framework.rpc

/** An explicitly unquoted text response; ordinary String responses are JSON. */
opaque type PlainText <: String = String

object PlainText:
  def apply(value: String): PlainText = value
