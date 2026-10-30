package framework.rpc

/** A shell script returned for execution by the CLI on the client machine. */
opaque type Shell <: String = String

object Shell:
  def apply(value: String): Shell = value
