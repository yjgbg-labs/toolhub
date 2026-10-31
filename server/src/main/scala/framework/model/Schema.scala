package framework.model

final case class Schema[A](node: () => SchemaNode)

enum SchemaNode:
  case AnyValue
  case Primitive(kind: String, format: Option[String] = None)
  case ArrayOf(element: () => SchemaNode)
  case Dictionary(value: () => SchemaNode)
  case Nullable(value: () => SchemaNode)
  case Product(id: String, fields: () => Vector[SchemaField])
  case Sum(id: String, variants: () => Vector[(String, SchemaNode)])
  case Enumeration(id: String, values: Vector[String])

final case class SchemaField(
    name: String,
    schema: () => SchemaNode,
    required: Boolean,
    default: Option[() => io.circe.Json]
)
