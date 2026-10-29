package sprout.cli

import java.io.{BufferedReader, InputStream, InputStreamReader}
import java.nio.charset.StandardCharsets.UTF_8

/** Decode complete SSE data events incrementally, retaining only the current event. */
private[cli] object ServerSentEvents:
  def consume(input: InputStream, emit: String => Unit): Unit =
    val reader = new BufferedReader(new InputStreamReader(input, UTF_8))
    val data = new StringBuilder
    var hasData = false
    var firstLine = true
    var line = reader.readLine()
    while line != null do
      if firstLine then
        line = line.stripPrefix("\uFEFF")
        firstLine = false
      if line.isEmpty then
        if hasData then emit(data.toString)
        data.clear()
        hasData = false
      else if line == "data" || line.startsWith("data:") then
        if hasData then data.append('\n')
        data.append(if line == "data" then "" else line.drop(5).stripPrefix(" "))
        hasData = true
      // Comments, event names, IDs and reconnect hints are not console data.
      line = reader.readLine()
    // SSE dispatches on blank lines; an incomplete final event is discarded.
