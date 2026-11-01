package groot.zshonmac

import cats.effect.IO
import cats.syntax.all.*
import framework.rpcDsl.*
import groot.graaljs.{GraalCommandSdk, GraalManager, JavaScript}
import groot.graaljs.GraalTestSupport.*
import io.circe.Json
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}
import scala.concurrent.duration.*

class ZshOnMacSuite extends CatsEffectSuite:
  // Exercise actual subprocess IO and ownership without depending on a Mac in CI.
  private def transport(home: Path): IO[Path] = IO.blocking {
    val file = home.resolve("ssh-test")
    Files.writeString(file, """#!/usr/bin/env bun
      import {appendFile, unlink} from 'node:fs/promises';
      import {dirname} from 'node:path';
      const root=dirname(import.meta.path), args=process.argv.slice(2);
      const socket=args[args.indexOf('-S')+1], alive=socket+'.alive';
      if(args.includes('-O')) {
        const op=args[args.indexOf('-O')+1];
        if(op==='check')process.exit(await Bun.file(alive).exists()?0:1);
        if(op==='exit'){await unlink(alive).catch(()=>{});await Bun.write(root+'/closed','yes');process.exit(0);}
      }
      if(args.includes('-fN')) {
        if(!args.includes('ControlPersist=300'))throw Error('Unexpected idle timeout');
        await Bun.write(alive,'yes');await appendFile(root+'/masters','start\n');process.exit(0);
      }
      const remote=args.at(-1);
      if(!remote.startsWith('exec /bin/zsh -f -c '))throw Error('Unexpected remote shell');
      if(remote.includes('kill -TERM')){await appendFile(root+'/cancelled','cancel\n');process.exit(0);}
      const bytes=await Bun.stdin.bytes();
      if(!remote.includes('length='+bytes.length+'\n'))throw Error('Incorrect byte count');
      await Bun.write(root+'/received',bytes);
      const script=new TextDecoder().decode(bytes).trim();
      if(script==='slow'){await Bun.write(root+'/pid',String(process.pid));await Bun.sleep(30000);}
      if(script==='large'){await Bun.stdout.write('x'.repeat(65537));await Bun.sleep(30000);}
      await Bun.stdout.write('中文🙂\n');await Bun.stderr.write('stderr\n');process.exit(7);
      """)
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"))
    file
  }

  test("JSON string body and timeout parameter work through HTTP and GraalJS with reusable SSH") {
    temporary.use { home => for
      ssh <- transport(home)
      source = "print -r -- '中文🙂'\nprintf '%s\\n' \"$HOME\""
      _ <- ZshOnMacService.resource(home.resolve("pool"), ssh = ssh.toString).use { service =>
        val app = httpApp { ZshOnMacRoutes.register(service) }
        val sdk = GraalCommandSdk(app)
        for
          _ <- IO(assertEquals(app.endpoints.head.exposedOn, Set(framework.Client.cli, framework.Client.graaljs, framework.Client.codemode)))
          operation = io.circe.parser.parse(app.openApiJson).toOption.get.hcursor.downField("paths").downField("/zsh-on-mac").downField("post")
          _ = assertEquals(operation.downField("requestBody").downField("content").downField("application/json").downField("schema").get[String]("type"), Right("string"))
          timeout = operation.get[Vector[Json]]("parameters").toOption.get.find(_.hcursor.get[String]("name") == Right("timeout")).get.hcursor
          _ = assertEquals(timeout.get[String]("in"), Right("query"))
          _ = assertEquals(timeout.get[Boolean]("required"), Right(false))
          _ = assertEquals(timeout.downField("schema").get[String]("default"), Right("30s"))
          response <- app.httpApp(Request[IO](Method.POST, Uri.unsafeFromString("/zsh-on-mac?timeout=10s"))
            .withEntity(Json.fromString(source).noSpaces)
            .putHeaders(org.http4s.Header.Raw(org.typelevel.ci.CIString("Content-Type"), "application/json")))
          body <- response.as[String]
          _ = assertEquals(response.status, Status.Ok)
          _ = assertEquals(io.circe.parser.parse(body).toOption.get.hcursor.get[Int]("exitCode"), Right(7))
          payload <- IO.blocking(Files.readString(home.resolve("received")))
          _ = assertEquals(payload, source + "\n")
          _ <- GraalManager.resource(home.resolve("tasks"), IO.pure(sdk)).use { manager => for
            task <- manager.start(JavaScript.source("const r=(await tools['zsh-on-mac']({body:'pwd'})).body;if(r.exitCode!==7||r.stdout!=='中文🙂\\n'||r.stderr!=='stderr\\n')throw Error(JSON.stringify(r));"), "1s", "10s")
            done <- manager.await(task.id)
            _ = assertEquals(done.state, "succeeded", done.error.toString)
          yield () }
          masters <- IO.blocking(Files.readString(home.resolve("masters")))
          _ = assertEquals(masters, "start\n")
        yield ()
      }
      closed <- IO.blocking(Files.readString(home.resolve("closed")))
      _ = assertEquals(closed, "yes")
    yield () }
  }

  test("invalid requests do not connect; limits, timeout and cancellation clean up each request") {
    temporary.use { home => for
      ssh <- transport(home)
      _ <- ZshOnMacService.resource(home.resolve("pool"), ssh = ssh.toString).use { service =>
        val sdk = GraalCommandSdk(httpApp { ZshOnMacRoutes.register(service) })
        def call(script: String, timeout: String = "30s") = sdk.call("zsh-on-mac", Json.obj("body" -> Json.fromString(script), "timeout" -> Json.fromString(timeout)))
        for
          _ <- List((" ", "30s"), ("x\u0000", "30s"), ("x", ""), ("x", "0s"), ("x", "oops"))
            .traverse_((script, timeout) => call(script, timeout).map(result => assertEquals(result.hcursor.get[Int]("status"), Right(400))))
          largeInput <- call("x" * (ZshOnMacService.maxBytes + 1))
          _ = assertEquals(largeInput.hcursor.get[Int]("status"), Right(413))
          connected <- IO.blocking(Files.exists(home.resolve("masters")))
          _ = assert(!connected)
          largeOutput <- call("large").timeout(5.seconds)
          _ = assertEquals(largeOutput.hcursor.get[Int]("status"), Right(413))
          timed <- call("slow", "500ms")
          _ = assertEquals(timed.hcursor.get[Int]("status"), Right(504))
          pid <- IO.blocking(Files.readString(home.resolve("pid")).toLong)
          _ <- awaitUntil(IO(ProcessHandle.of(pid).map[Boolean](_.isAlive).orElse(false)))(!_)
          _ <- IO.blocking(Files.delete(home.resolve("pid")))
          _ <- service("slow").background.use(_ => awaitUntil(IO.blocking(Files.exists(home.resolve("pid"))))(identity).void)
          cancelledPid <- IO.blocking(Files.readString(home.resolve("pid")).toLong)
          _ <- awaitUntil(IO(ProcessHandle.of(cancelledPid).map[Boolean](_.isAlive).orElse(false)))(!_)
          after <- call("pwd")
          _ = assertEquals(after.hcursor.get[Int]("status"), Right(200))
          cancelled <- IO.blocking(Files.readString(home.resolve("cancelled")))
          _ = assertEquals(cancelled, "cancel\ncancel\ncancel\n")
        yield ()
      }
    yield () }
  }
