package groot.widget

import framework.model.JsonModel

enum WidgetColor derives JsonModel:
  case red, yellow, green

case class WidgetUpdate(key: String, label: String, value: Option[String] = None,
    color: WidgetColor = WidgetColor.green, order: Int = 0) derives JsonModel
case class WidgetItem(key: String, label: String, value: Option[String], color: WidgetColor,
    order: Int, updatedAt: String) derives JsonModel
case class WidgetSettings(x: Option[Int] = None, y: Option[Int] = None, width: Int = 440,
    topMost: Boolean = true, opacity: Double = 0.96, fontSize: Int = 14, rowHeight: Int = 40) derives JsonModel
case class WidgetSnapshot(revision: String, serverTime: String, items: Vector[WidgetItem],
    settings: WidgetSettings) derives JsonModel
private[widget] case class WidgetState(revision: Long = 0, items: Vector[WidgetItem] = Vector.empty,
    settings: WidgetSettings = WidgetSettings()) derives JsonModel

case class InvalidWidget(message: String) extends RuntimeException(message)
case class WidgetLimit(message: String) extends RuntimeException(message)
