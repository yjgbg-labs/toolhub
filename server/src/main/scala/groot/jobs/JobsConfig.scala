package groot.jobs

import org.snakeyaml.engine.v2.api.{Load, LoadSettings}
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

final class InvalidJobs(message: String) extends RuntimeException(message)
final case class Job(name: String, needs: Vector[String], run: String)

object JobsConfig:
  private def check(condition: Boolean, message: => String): Unit =
    if !condition then throw new InvalidJobs(message)

  private def obj(value: Any, label: String): Map[String, Any] = value match
    case map: java.util.Map[?, ?] =>
      check(map.keySet().asScala.forall(_.isInstanceOf[String]), s"$label: keys must be strings")
      map.asScala.iterator.map((key, value) => key.asInstanceOf[String] -> value).toMap
    case _ => throw new InvalidJobs(s"$label: expected an object")

  private def array(value: Any, label: String): Vector[Any] = value match
    case list: java.util.List[?] => list.asScala.toVector
    case _ => throw new InvalidJobs(s"$label must be an array")

  private def text(value: Any, label: String): String = value match
    case value: String if value.trim.nonEmpty && !value.contains('\u0000') => value
    case _ => throw new InvalidJobs(s"$label must be a non-empty string without NUL characters")

  /** Validate the entire file, then return only the target's graph in topological order. */
  def plan(yaml: String, target: String): Vector[Job] =
    val raw = try new Load(LoadSettings.builder().setLabel("jobs.yaml").setAllowDuplicateKeys(false).build()).loadFromString(yaml)
    catch case error: YamlEngineException => throw new InvalidJobs(s"invalid YAML: ${error.getMessage}")
    val file = obj(raw, "jobs.yaml")
    check(file.get("version").exists {
      case number: Number => number.doubleValue() == 1d
      case _ => false
    }, "jobs.yaml: version must be 1")
    check(!file.contains("imports"), "imports is not supported; use a single jobs.yaml")
    val jobs = array(file.getOrElse("jobs", null), "jobs.yaml: jobs").zipWithIndex.map { (raw, index) =>
      val label = s"jobs[$index]"
      val value = obj(raw, label)
      val name = text(value.getOrElse("name", null), s"$label.name")
      check(!name.contains('@'), s"$label.name cannot contain @; namespaces are not supported")
      val run = text(value.getOrElse("run", null), s"$label.run")
      val needs = value.get("needs").filter(_ != null).fold(Vector.empty[String]) { raw =>
        array(raw, s"$label.needs").map(value => text(value, s"$label.needs entry"))
      }
      check(needs.distinct.size == needs.size, s"$label.needs contains duplicates")
      check(!needs.contains(name), s"job \"$name\" cannot depend on itself")
      Job(name, needs, run)
    }
    val byName = mutable.LinkedHashMap.empty[String, Job]
    jobs.foreach { job =>
      check(!byName.contains(job.name), s"duplicate job \"${job.name}\"")
      byName(job.name) = job
    }
    jobs.foreach(job => job.needs.foreach(name => check(byName.contains(name), s"job \"${job.name}\" depends on missing job \"$name\"")))
    val visiting = mutable.ArrayBuffer.empty[String]
    val visited = mutable.Set.empty[String]
    val ordered = Vector.newBuilder[Job]
    def visit(name: String): Unit =
      check(!visiting.contains(name), s"circular job dependency: ${(visiting.dropWhile(_ != name) :+ name).mkString(" -> ")}")
      if !visited(name) then
        visiting += name
        byName(name).needs.foreach(visit)
        visiting.remove(visiting.size - 1)
        visited += name
        ordered += byName(name)
    check(byName.contains(target), s"no job named \"$target\"")
    visit(target)
    val selected = ordered.result()
    jobs.foreach(job => visit(job.name))
    selected
