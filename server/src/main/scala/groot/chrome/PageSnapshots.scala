package groot.chrome

import cats.effect.IO
import cats.syntax.all.*
import framework.model.JsonModel
import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.util.{Locale, UUID}
import scala.collection.mutable

/** AX supplies semantics and hierarchy; one DOM snapshot per session supplies native click hints. */
private[chrome] final class PageSnapshots(page: ChromePage):
  import ChromeJson.*
  import PageSnapshots.*

  def capture(maxNodes: Int, format: String, interactiveOnly: Boolean): IO[PageSnapshot] = for
    _ <- IO.raiseWhen(maxNodes < 1 || maxNodes > 5000)(ChromeFailure("invalid_chrome", "maxNodes must be between 1 and 5000"))
    _ <- IO.raiseUnless(Set("json", "text").contains(format))(ChromeFailure("invalid_chrome", "format must be json or text"))
    frames <- page.frames.map(_.map(n => PageFrame(text(n, "id"), text(n, "loaderId"), text(n, "_session"))))
    dom <- frames.map(_.session).distinct.traverse { session =>
      page.cdp.call("DOMSnapshot.captureSnapshot", obj("computedStyles" -> Json.arr()), Some(session))
        .map(snapshot => session -> domNodes(snapshot))
    }.map(_.toMap)
    trees <- frames.traverse { frame =>
      page.cdp.call("Accessibility.getFullAXTree", obj("frameId" -> str(frame.id)), Some(frame.session))
        .map(tree => FrameTree(frame, array(tree, "nodes"), dom(frame.session).getOrElse(frame.id, Map.empty)))
    }
    info <- page.info
    result <- IO {
      val built = build(trees, "ref:" + UUID.randomUUID().toString.take(8) + ":", maxNodes, format, interactiveOnly)
      page.refs.clear()
      page.refs ++= built.refs
      PageSnapshot(info.url, info.title, if format == "text" then Vector.empty else built.nodes,
        built.truncated, Option.when(format == "text")(render(built.nodes, built.truncated)))
    }
  yield result

private[chrome] object PageSnapshots:
  import ChromeJson.*
  case class DomNode(nodeType: Int, tagName: Option[String], clickable: Boolean)
  case class FrameTree(frame: PageFrame, nodes: Vector[Json], dom: Map[Int, DomNode])
  case class Built(nodes: Vector[SnapshotNode], refs: Map[String, ElementRef], truncated: Boolean)
  private val interactiveRoles = Set("button", "link", "textbox", "searchbox", "combobox", "listbox",
    "checkbox", "radio", "switch", "slider", "spinbutton", "tab", "option", "treeitem",
    "menuitem", "menuitemcheckbox", "menuitemradio")

  def domNodes(snapshot: Json): Map[String, Map[Int, DomNode]] =
    val strings = array(snapshot, "strings").map(_.asString.getOrElse(""))
    def string(index: Json): String = index.asNumber.flatMap(_.toInt).flatMap(strings.lift).getOrElse("")
    array(snapshot, "documents").map { doc =>
      val nodes = field(doc, "nodes")
      val types = array(nodes, "nodeType")
      val names = array(nodes, "nodeName")
      val clickable = array(field(nodes, "isClickable"), "index").flatMap(_.asNumber.flatMap(_.toInt)).toSet
      val entries = array(nodes, "backendNodeId").zipWithIndex.flatMap { (backend, i) =>
        backend.asNumber.flatMap(_.toInt).map { id =>
          val nodeType = types.lift(i).flatMap(_.asNumber.flatMap(_.toInt)).getOrElse(0)
          id -> DomNode(nodeType, Option.when(nodeType == 1)(names.lift(i).map(string).getOrElse("")),
            nodeType == 1 && clickable(i))
        }
      }
      string(field(doc, "frameId")) -> entries.toMap
    }.toMap

  def build(trees: Vector[FrameTree], prefix: String, maxNodes: Int, format: String, interactiveOnly: Boolean): Built =
    var index = 0
    val references = mutable.Map.empty[String, ElementRef]
    val rows = Vector.newBuilder[SnapshotNode]
    trees.foreach { tree =>
      val byId = tree.nodes.map(n => text(n, "nodeId") -> n).toMap
      val children = tree.nodes.groupBy(n => text(n, "parentId"))
      def childIds(n: Json): Vector[String] =
        n.hcursor.get[Vector[String]]("childIds").getOrElse(children.getOrElse(text(n, "nodeId"), Vector.empty).map(text(_, "nodeId")))
      val labels = mutable.Map.empty[String, String]
      def label(id: String, seen: Set[String] = Set.empty): String = labels.getOrElse(id, {
        val value = if seen(id) || seen.size >= 100 then "" else byId.get(id).fold("") { n =>
          val name = text(field(n, "name"), "value")
          if text(field(n, "role"), "value") == "InlineTextBox" then ""
          else if name.nonEmpty then name.take(4096)
          else childIds(n).map(child => label(child, seen + id)).filter(_.nonEmpty).mkString(" ").take(4096)
        }
        labels(id) = value
        value
      })
      // Pre-order guarantees that truncating the result never leaves a child before its parent.
      val roots = tree.nodes.filter(n => !byId.contains(text(n, "parentId"))).map(text(_, "nodeId"))
      val pending = mutable.Stack.empty[(String, Option[String], Int)]
      roots.reverse.foreach(id => pending.push((id, None, 0)))
      val visited = mutable.Set.empty[String]
      while pending.nonEmpty do
        val (id, parent, depth) = pending.pop()
        if !visited(id) then byId.get(id).foreach { n =>
          visited += id
          val backend = n.hcursor.get[Int]("backendDOMNodeId").toOption
          val dom = backend.flatMap(tree.dom.get)
          val role = text(field(n, "role"), "value")
          val keep = !flag(n, "ignored")
          val ref = if !keep then None else backend.map { b =>
            index += 1
            val key = prefix + index
            references(key) = ElementRef(tree.frame.id, tree.frame.loader, b)
            key
          }
          if keep then
            val name = text(field(n, "name"), "value")
            val clickable = dom.exists(_.clickable)
            rows += SnapshotNode(ref, tree.frame.id, role,
              (if name.isEmpty && clickable then label(id) else name).take(4096),
              n.hcursor.downField("value").downField("value").focus.map(v => if v.isString then str(v.asString.get.take(4096)) else v),
              depth, parent, dom.map(_.nodeType), dom.flatMap(_.tagName), clickable)
          childIds(n).reverse.foreach(child => pending.push((child, ref.orElse(parent), depth + (if ref.nonEmpty then 1 else 0))))
        }
    }
    val all = rows.result()
    val byRef = all.flatMap(n => n.ref.map(_ -> n)).toMap
    val candidates = all.filter { n =>
      if interactiveOnly then n.nodeType.contains(1) && (n.clickable || interactiveRoles(n.role))
      else format != "text" || (n.role != "InlineTextBox" && !(n.role == "generic" && n.name.isEmpty && !n.clickable))
    }
    val kept = candidates.flatMap(_.ref).toSet
    val depths = mutable.Map.empty[String, Int]
    var bytes = 0
    val selected = candidates.take(maxNodes).map { n =>
      var parent = n.parentRef
      while parent.exists(p => !kept(p)) do parent = parent.flatMap(byRef.get).flatMap(_.parentRef)
      val depth = parent.flatMap(depths.get).fold(0)(_ + 1)
      n.ref.foreach(r => depths(r) = depth)
      n.copy(parentRef = parent, depth = depth)
    }.takeWhile { n =>
      bytes += math.max(JsonModel[SnapshotNode].encoder(n).noSpaces.getBytes(UTF_8).length,
        line(n).getBytes(UTF_8).length + n.frameId.getBytes(UTF_8).length + 16)
      bytes <= 768 * 1024
    }
    Built(selected, selected.flatMap(_.ref).map(r => r -> references(r)).toMap, selected.size < candidates.size)

  private def line(n: SnapshotNode): String =
    val role = if n.role == "generic" then n.tagName.fold("generic")(_.toLowerCase(Locale.ROOT))
      else if n.role == "StaticText" then "text" else n.role
    val name = if n.name.isEmpty then "" else " " + str(n.name).noSpaces
    val ref = n.ref.fold("")(r => s" [ref=$r]")
    val click = if n.clickable then " [clickable]" else ""
    val value = n.value.fold("")(v => s" [value=${v.noSpaces}]")
    "  " * n.depth + s"- $role$name$ref$click$value"

  def render(nodes: Vector[SnapshotNode], truncated: Boolean): String =
    val result = new StringBuilder
    var frame = ""
    nodes.foreach { n =>
      if n.frameId != frame then
        frame = n.frameId
        result.append(s"frame ${str(frame).noSpaces}\n")
      result.append(line(n)).append('\n')
    }
    if truncated then result.append("[truncated]\n")
    result.result().stripSuffix("\n")
