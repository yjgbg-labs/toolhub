package framework

/** Client surfaces supported by command declarations. */
enum Client:
  case cli, codemode, graaljs

object Client:
  val all: Set[Client] = Client.values.toSet
