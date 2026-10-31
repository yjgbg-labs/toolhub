package groot.sqlite

/** SQLite's public-domain complete.c state machine, used to retain trigger bodies when splitting scripts. */
private[sqlite] object SqlStatements:
  // Tokens: semicolon, whitespace, other, EXPLAIN, CREATE, TEMP, TRIGGER, END.
  private val transitions = Vector(
    Vector(1, 0, 2, 3, 4, 2, 2, 2),
    Vector(1, 1, 2, 3, 4, 2, 2, 2),
    Vector(1, 2, 2, 2, 2, 2, 2, 2),
    Vector(1, 3, 3, 2, 4, 2, 2, 2),
    Vector(1, 4, 2, 2, 2, 4, 5, 2),
    Vector(6, 5, 5, 5, 5, 5, 5, 5),
    Vector(6, 6, 5, 5, 5, 5, 5, 7),
    Vector(1, 7, 5, 5, 5, 5, 5, 5))

  def split(sql: String): Vector[String] =
    if sql.contains('\u0000') then throw InvalidSqlite("SQL must not contain NUL bytes")
    val statements = Vector.newBuilder[String]
    var index = 0
    var start = 0
    var state = 0
    var content = false
    def identifier(c: Char): Boolean = c.isLetterOrDigit || c == '_' || c == '$' || c >= 128
    while index < sql.length do
      val begin = index
      val char = sql(index)
      var token = 2
      if char.isWhitespace || char == '\ufeff' then
        token = 1
        index += 1
      else if sql.startsWith("--", index) then
        val end = sql.indexOf('\n', index + 2)
        index = if end < 0 then sql.length else end + 1
        token = 1
      else if sql.startsWith("/*", index) then
        val end = sql.indexOf("*/", index + 2)
        if end < 0 then throw InvalidSqlite("Unterminated SQL comment")
        index = end + 2
        token = 1
      else if char == '\'' || char == '"' || char == '`' || char == '[' then
        val quote = if char == '[' then ']' else char
        index += 1
        var closed = false
        while index < sql.length && !closed do
          if sql(index) != quote then index += 1
          else if char != '[' && index + 1 < sql.length && sql(index + 1) == quote then index += 2
          else
            index += 1
            closed = true
        if !closed then throw InvalidSqlite("Unterminated SQL string or identifier")
      else if identifier(char) then
        index += 1
        while index < sql.length && identifier(sql(index)) do index += 1
        token = sql.substring(begin, index).toLowerCase(java.util.Locale.ROOT) match
          case "explain" => 3
          case "create" => 4
          case "temp" | "temporary" => 5
          case "trigger" => 6
          case "end" => 7
          case _ => 2
      else
        index += 1
        if char == ';' then token = 0
      if token != 0 && token != 1 then content = true
      state = transitions(state)(token)
      if token == 0 && state == 1 then
        if content then statements += sql.substring(start, index)
        start = index
        state = 0
        content = false
    if content then statements += sql.substring(start)
    val result = statements.result()
    if result.isEmpty then throw InvalidSqlite("SQL must contain at least one statement")
    result
