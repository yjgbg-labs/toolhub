package groot.widget

import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*

object WidgetRoutes:
  def register(service: WidgetService)(using CommandsScope): Unit = commands("widget") {
    description("Manage persistent widget items and window settings; subscribe to complete live snapshots")
    val invalid = response.json[ErrorBody](400) { description("Invalid widget item or settings") }
    val large = response.json[ErrorBody](413) { description("Widget exceeds its item or snapshot limit") }
    recover {
      case error: InvalidWidget => invalid(ErrorBody("invalid_widget", error.getMessage))
      case error: WidgetLimit => large(ErrorBody("widget_limit", error.getMessage))
    }
    command[Vector[WidgetItem]]("show") {
      description("Read all items sorted by order and key")
      handle { service.show }
    }
    command[WidgetItem]("set") {
      description("Create or replace an item; omitted/empty value shows three signal lamps")
      val item = body[WidgetUpdate]("Item key, label, optional text value, red/yellow/green color and display order")
      handle { service.set(item.value) }
    }
    command[Boolean]("delete") {
      description("Delete an item; return whether it existed")
      val key = param[String]("key", "Item key") { minLength(1); maxLength(128) }
      handle { service.delete(key.value) }
    }
    commands("config") {
      command[WidgetSettings]("read") {
        description("Read shared window settings; positions are logical points from the main screen's top-left")
        handle { service.config }
      }
      command[WidgetSettings]("write") {
        description("Replace the complete window settings; omitted fields take their defaults")
        val settings = body[WidgetSettings]("Complete window settings")
        handle { service.writeConfig(settings.value) }
      }
    }
    command[SSE[WidgetSnapshot]]("subscribe") {
      exposeOn(cli)
      description("Stream the latest complete snapshot immediately, on changes, and every 15 seconds; reconnect to resynchronize")
      handle { cats.effect.IO.pure(SSE(service.subscribe)) }
    }
  }
