package groot.usejdk

import cats.effect.IO
import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*

object UseJdkRoutes:
  def register()(using CommandsScope): Unit = command[Shell]("use-jdk") {
    exposeOn(cli)
    description("Generate a local shell script that caches Eclipse Temurin and runs a command with JAVA_HOME and PATH set for the requested JDK")
    val version = param[Int]("version", "Positive JDK major version, for example 21 or 25") { minimum(1) }
    val command = param[String]("command", "Shell command to run locally with the selected JDK, for example 'java --version'") { minLength(1) }
    val invalid = response.json[ErrorBody](400) { description("Invalid JDK version or command") }
    recover { case error: InvalidUseJdk => invalid(ErrorBody("invalid_use_jdk", error.getMessage)) }
    handle { IO(Shell(UseJdkService.compile(version.value, command.value))) }
  }
