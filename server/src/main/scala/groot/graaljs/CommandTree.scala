package groot.graaljs

/** Immutable catalog shared across tasks. Never retains a Context, Value or task callback. */
private[graaljs] final case class CommandTree(command: Option[String], children: Map[String, CommandTree]):
  val keys: Vector[String] = children.keys.toVector.sorted

private[graaljs] object CommandTree:
  def compile(commands: Vector[String]): CommandTree =
    def build(prefix: Vector[String], paths: Vector[Vector[String]]): CommandTree =
      val children = paths.filter(_.size > prefix.size).groupBy(_(prefix.size)).map { (name, descendants) =>
        name -> build(prefix :+ name, descendants)
      }
      CommandTree(Option.when(paths.exists(_.size == prefix.size))(prefix.mkString(".")), children)
    build(Vector.empty, commands.map(_.split("\\.").toVector))
