package sproutexample

import cats.effect.{IO, IOApp, Ref, Resource}
import sprout.dsl.*
import sprout.schedule.{Scheduler, ScheduleRoutes}

case class User(id: Long, name: String) derives JsonModel
case class CreateUser(name: String) derives JsonModel
case class ApiError(code: String, message: String) derives JsonModel

object Main extends IOApp.Simple:
  def run: IO[Unit] = (for
    scheduler <- Scheduler.resource()
    users <- Resource.eval(Ref.of[IO, Map[Long, User]](Map(1L -> User(1L, "Groot"))))
  yield (scheduler, users)).use { (scheduler, users) =>
    val app = httpApp {
      server {
        host("127.0.0.1")
        port(sys.env.get("SPROUT_PORT").fold(8080)(_.toInt))
      }

      openapi {
        endpoint("/openapi.json")
        info {
          title("Sprout")
          version("1.0.0")
        }
      }

      routes {
        ScheduleRoutes.register(scheduler)
        operation("health") {
          val ok = response.text(200) {}
          handle { ok("ok") }
        }

        group("users") {
          description("Manage users")

          operation("get") {
            summary("Find a user")
            val id = path[Long]("id") { minimum(1L) }
            val ok = response.json[User](200) { description("User found") }
            val missing = response.json[ApiError](404) { description("User not found") }
            handle {
              users.get.map(_.get(id.value) match
                case Some(user) => ok(user)
                case None => missing(ApiError("not_found", "User not found"))
              )
            }
          }

          operation("list") {
            val limit = query[Int]("limit") {
              default(20)
              minimum(1)
              maximum(100)
            }
            val ok = response.json[List[User]](200) {}
            handle { users.get.map(all => ok(all.values.toList.sortBy(_.id).take(limit.value))) }
          }

          operation("create") {
            val input = body.json[CreateUser] {
              constraint[CreateUser]("Name must not be blank")(_.name.trim.nonEmpty)
            }
            val created = response.json[User](201) { description("User created") }
            handle {
              users.modify { all =>
                val id = all.keys.maxOption.getOrElse(0L) + 1
                val user = User(id, input.value.name)
                all.updated(id, user) -> created(user)
              }
            }
          }
        }
      }
    }
    app.serve
  }
