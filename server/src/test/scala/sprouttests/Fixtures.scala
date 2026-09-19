package sprouttests

import sprout.JsonModel

case class User(id: Long, name: String, nickname: Option[String] = None) derives JsonModel
case class CreateUser(name: String, active: Boolean = true, nickname: Option[String] = None) derives JsonModel
case class Page[A](items: List[A]) derives JsonModel
case class Tree(name: String, children: List[Tree] = Nil) derives JsonModel
case class Branch(children: List[Leaf]) derives JsonModel
case class Leaf(branch: Option[Branch]) derives JsonModel

enum Color derives JsonModel:
  case Red, Green, Blue

enum Event derives JsonModel:
  case Created(id: Long)
  case Renamed(name: String)

case class Defaults(count: Int = 3, label: String = "default") derives JsonModel
case class Interval(start: Int, end: Int) derives JsonModel
