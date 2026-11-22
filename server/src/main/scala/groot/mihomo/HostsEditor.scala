package groot.mihomo

/** Line-preserving editor for top-level `hosts:` and parser for controller metadata in Mihomo YAML. */
object HostsEditor:
  private val ManagedStart = "# external-dns-mihomo:start"
  private val ManagedEnd = "# external-dns-mihomo:end"
  private val EntryPattern = """^\s{2}(?:'((?:''|[^'])*)'|"((?:\\.|[^"\\])*)"|([^:#\s][^:#]*?))\s*:\s*(.*?)\s*$""".r

  private final case class Block(start: Int, end: Int, insertAt: Int)
  private final case class ParsedLine(index: Int, host: String, value: String, managed: Boolean)

  def list(configText: String): Vector[DnsHost] =
    val normalized = normalizeNewlines(configText)
    findBlock(normalized.split("\n", -1).toVector) match
      case None => Vector.empty
      case Some(block) =>
        parseBlockLines(normalized.split("\n", -1).toVector, block).map { entry =>
          DnsHost(entry.host, entry.value, entry.managed)
        }

  def upsert(configText: String, rawHost: String, rawValue: String, force: Boolean = false): String =
    val host = validateHost(rawHost)
    val value = validateValue(rawValue)
    val normalized = normalizeNewlines(configText)
    val lines = normalized.split("\n", -1).toVector
    val rendered = s"  '${escapeSingleQuoted(host)}': ${renderYamlValue(value)}"

    findBlock(lines) match
      case None =>
        val trimmedEnd = lines.reverse.dropWhile(_.isEmpty).reverse
        val updated =
          if trimmedEnd.isEmpty then Vector("hosts:", rendered, "")
          else trimmedEnd ++ Vector("hosts:", rendered, "")
        updated.mkString("\n")
      case Some(block) =>
        val entries = parseBlockLines(lines, block)
        entries.find(_.host == host) match
          case Some(existing) =>
            if existing.managed && !force then
              throw MihomoFailure("mihomo_conflict", s"Host '$host' is managed by external-dns-mihomo; pass force=true to override")
            if lines(existing.index) == rendered then normalized
            else lines.updated(existing.index, rendered).mkString("\n")
          case None =>
            val (before, after) = lines.splitAt(block.insertAt)
            (before ++ Vector(rendered) ++ after).mkString("\n")

  def delete(configText: String, rawHost: String, force: Boolean = false): (String, Boolean) =
    val host = validateHost(rawHost)
    val normalized = normalizeNewlines(configText)
    val lines = normalized.split("\n", -1).toVector
    findBlock(lines) match
      case None => (normalized, false)
      case Some(block) =>
        val matching = parseBlockLines(lines, block).filter(_.host == host)
        if matching.isEmpty then (normalized, false)
        else
          if matching.exists(_.managed) && !force then
            throw MihomoFailure("mihomo_conflict", s"Host '$host' is managed by external-dns-mihomo; pass force=true to delete")
          val removeIndices = matching.map(_.index).toSet
          val updated = lines.zipWithIndex.collect { case (line, idx) if !removeIndices.contains(idx) => line }
          (updated.mkString("\n"), true)

  def parseApiEndpoint(
    configText: String,
    defaultHost: String,
    apiUrlOverride: Option[String] = None
  ): Option[ApiEndpoint] =
    val lines = normalizeNewlines(configText).split("\n", -1).toVector
    val secret = findTopLevelScalar(lines, "secret").getOrElse("")
    apiUrlOverride.map(_.trim.stripSuffix("/")).filter(_.nonEmpty) match
      case Some(overrideUrl) => Some(ApiEndpoint(overrideUrl, secret))
      case None =>
        findTopLevelScalar(lines, "external-controller").filter(_.nonEmpty).flatMap { controller =>
          if controller.startsWith("/") then None
          else parseHostPort(controller, defaultHost).map { (host, port) =>
            val formattedHost = if host.contains(':') && !host.startsWith("[") then s"[$host]" else host
            ApiEndpoint(s"http://$formattedHost:$port", secret)
          }
        }

  def extractSshHost(target: Option[String]): String =
    target.map(_.trim).filter(_.nonEmpty) match
      case None => "127.0.0.1"
      case Some(raw) =>
        val hostPart = raw.lastIndexOf('@') match
          case -1 => raw
          case idx => raw.substring(idx + 1)
        hostPart.stripPrefix("[").stripSuffix("]")

  private def parseHostPort(controller: String, defaultHost: String): Option[(String, Int)] =
    val (rawHost, rawPort) =
      if controller.startsWith("[") then
        val close = controller.indexOf(']')
        if close < 0 || close + 1 >= controller.length || controller.charAt(close + 1) != ':' then return None
        (controller.substring(1, close), controller.substring(close + 2))
      else
        val colon = controller.lastIndexOf(':')
        if colon < 0 then return None
        (controller.substring(0, colon), controller.substring(colon + 1))
    rawPort.toIntOption.filter(p => p >= 1 && p <= 65535).map { port =>
      val cleanHost = rawHost.trim
      val wildcard = cleanHost.isEmpty || cleanHost == "0.0.0.0" || cleanHost == "::" || cleanHost == "*"
      val resolvedHost = if wildcard then defaultHost else cleanHost
      (resolvedHost, port)
    }

  private def findTopLevelScalar(lines: Vector[String], key: String): Option[String] =
    val prefix = s"$key:"
    lines.collectFirst {
      case line if line.startsWith(prefix) =>
        parseYamlScalar(line.substring(prefix.length).trim)
    }

  private def findBlock(lines: Vector[String]): Option[Block] =
    val start = lines.indexWhere { line =>
      val trimmed = stripInlineComment(line).trim
      !line.startsWith(" ") && !line.startsWith("\t") && trimmed == "hosts:"
    }
    if start < 0 then None
    else
      var end = lines.length
      var index = start + 1
      while index < lines.length && end == lines.length do
        val line = lines(index)
        if line.nonEmpty && !line.startsWith(" ") && !line.startsWith("\t") && !line.startsWith("#") then
          end = index
        index += 1

      // Insert right after the last indented line inside `hosts:` (and outside any unclosed managed block),
      // avoiding trailing blank lines or top-level comments before the next section.
      var managedDepth = false
      var managedStartIdx = -1
      var lastIndented = start
      var cursor = start + 1
      while cursor < end do
        val line = lines(cursor)
        val trimmed = line.trim
        if trimmed == ManagedStart then
          managedDepth = true
          if managedStartIdx < 0 then managedStartIdx = cursor
        else if trimmed == ManagedEnd then
          managedDepth = false
        if (line.startsWith(" ") || line.startsWith("\t")) && trimmed.nonEmpty then
          lastIndented = cursor
        cursor += 1

      val insertAt =
        if managedDepth && managedStartIdx > start then managedStartIdx
        else lastIndented + 1

      Some(Block(start, end, insertAt))

  private def parseBlockLines(lines: Vector[String], block: Block): Vector[ParsedLine] =
    val buffer = Vector.newBuilder[ParsedLine]
    var managed = false
    var idx = block.start + 1
    while idx < block.end do
      val line = lines(idx)
      val trimmed = line.trim
      if trimmed == ManagedStart then managed = true
      else if trimmed == ManagedEnd then managed = false
      else
        line match
          case EntryPattern(singleQ, doubleQ, bare, rawVal) =>
            val host =
              if singleQ != null then singleQ.replace("''", "'")
              else if doubleQ != null then unescapeDoubleQuoted(doubleQ)
              else bare.trim
            val value = parseYamlScalar(rawVal)
            if host.nonEmpty then buffer += ParsedLine(idx, host, value, managed)
          case _ => ()
      idx += 1
    buffer.result()

  private def parseYamlScalar(raw: String): String =
    val trimmed = raw.trim
    if trimmed.length >= 2 && trimmed.startsWith("'") && trimmed.endsWith("'") then
      trimmed.substring(1, trimmed.length - 1).replace("''", "'")
    else if trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"") then
      unescapeDoubleQuoted(trimmed.substring(1, trimmed.length - 1))
    else
      stripInlineComment(trimmed).trim

  private def stripInlineComment(value: String): String =
    var inSingle = false
    var inDouble = false
    var escaped = false
    var i = 0
    while i < value.length do
      val c = value.charAt(i)
      if escaped then escaped = false
      else if c == '\\' && inDouble then escaped = true
      else if c == '\'' && !inDouble then inSingle = !inSingle
      else if c == '"' && !inSingle then inDouble = !inDouble
      else if c == '#' && !inSingle && !inDouble && (i == 0 || value.charAt(i - 1).isWhitespace) then
        return value.substring(0, i)
      i += 1
    value

  private def unescapeDoubleQuoted(value: String): String =
    val sb = new StringBuilder(value.length)
    var i = 0
    while i < value.length do
      val c = value.charAt(i)
      if c == '\\' && i + 1 < value.length then
        i += 1
        value.charAt(i) match
          case 'n' => sb.append('\n')
          case 'r' => sb.append('\r')
          case 't' => sb.append('\t')
          case '"' => sb.append('"')
          case '\\' => sb.append('\\')
          case other => sb.append(other)
      else sb.append(c)
      i += 1
    sb.toString

  private def renderYamlValue(value: String): String =
    if value.startsWith("[") && value.endsWith("]") then value
    else if value.matches("""[A-Za-z0-9._:%/-]+""") && !value.startsWith(":") then value
    else s"'${escapeSingleQuoted(value)}'"

  private def escapeSingleQuoted(value: String): String =
    value.replace("'", "''")

  private def normalizeNewlines(text: String): String =
    text.replace("\r\n", "\n").replace('\r', '\n')

  private def validateHost(raw: String): String =
    val trimmed = raw.trim
    if trimmed.isEmpty || trimmed.length > 253 || trimmed.exists(c => c.isWhitespace || c.isControl || c == '\u0000') then
      throw MihomoFailure("invalid_mihomo", "Host must be nonblank, without whitespace or control characters, and at most 253 characters")
    trimmed

  private def validateValue(raw: String): String =
    val trimmed = raw.trim
    if trimmed.isEmpty || trimmed.length > 512 || trimmed.exists(c => c == '\n' || c == '\r' || c == '\u0000' || c.isControl) then
      throw MihomoFailure("invalid_mihomo", "Host target value must be nonblank, single-line, and at most 512 characters")
    trimmed
