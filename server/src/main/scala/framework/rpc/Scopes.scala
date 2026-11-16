package framework.rpc

import framework.http.{ConfigurationGate, DeclarationScope, EndpointScope, LogScope, Output, RoutesScope}

sealed trait CommandsScope extends LogScope:
  private[framework] def http: DeclarationScope

sealed trait CommandScope[A] extends CommandsScope:
  private[framework] def http: EndpointScope
  private[framework] def success: Output[A]

private[framework] final class CommandGroup(
    val routes: RoutesScope,
    val namespace: Vector[String],
    val ancestors: Vector[DeclarationScope],
    val metadata: Metadata
) extends DeclarationScope(routes.gate) with CommandsScope:
  routes.gate.check()
  routes.sharedScopes += this
  if namespace.nonEmpty then metadata.commands += this
  val http: DeclarationScope = this

private[framework] final class Command[A](
    val http: EndpointScope,
    val success: Output[A]
) extends CommandScope[A]:
  private[framework] def gate: ConfigurationGate = http.gate
  private[framework] def logSkipValue: Boolean = http.logSkipValue
  private[framework] def logSkipValue_=(value: Boolean): Unit = http.logSkipValue = value
