package groot.schedule

import java.nio.file.{Files, Path}
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*

/** Real script files for existing scheduler behaviour tests. */
trait ScriptFixtures extends CatsEffectSuite:
  private val scripts = Files.createTempDirectory(Path.of("/tmp/opencode"), "schedule-fixtures-")

  protected def job(source: String = "console.log('hello')", cpu: Double = 0): ScheduleJob =
    val name = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
      .digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
    val path = scripts.resolve(s"$name.js")
    if !Files.exists(path) then Files.writeString(path, source)
    ScheduleJob("1h", path.toString, resource = if cpu == 0 then Map.empty else Map("cpu" -> cpu))

  override def afterAll(): Unit =
    val files = Files.walk(scripts)
    try files.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)
    finally files.close()
    super.afterAll()
