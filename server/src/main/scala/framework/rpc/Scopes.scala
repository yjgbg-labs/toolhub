package framework.rpc

import framework.http.{DeclarationScope, EndpointScope, Output, RoutesScope}

sealed trait CommandsScope:
  private[framework] def http: DeclarationScope
  private[framework] def namespace: Vector[String]
  private[framework] def ancestors: Vector[DeclarationScope]

sealed trait CommandScope[A] extends CommandsScope:
  private[framework] def http: EndpointScope
  private[framework] def success: Output[A]

private[framework] final class CommandGroup(
    val http: RoutesScope,
    val namespace: Vector[String],
    val ancestors: Vector[DeclarationScope]
) extends CommandsScope

private[framework] final class Command[A](
    val http: EndpointScope,
    val namespace: Vector[String],
    val ancestors: Vector[DeclarationScope],
    val success: Output[A]
) extends CommandScope[A]
