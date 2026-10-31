package groot.fs

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import framework.rpcDsl.*
import groot.fetch.{FetchRoutes, FetchService}
import groot.graaljs.{GraalCommandSdk, GraalManager}
import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Response, Status, Uri}
import org.http4s.client.Client
import scala.concurrent.duration.*

class FsSuite extends CatsEffectSuite:
  private val service = new FsService
  private def temporary(root: Path = Path.of("/tmp/opencode")): Resource[IO, Path] = Resource.make(
    IO.blocking(Files.createTempDirectory(root, "fs-test-"))
  )(path => service.remove(path.toString, recursive = true))
  private def commands: GraalCommandSdk = GraalCommandSdk(httpApp { FsRoutes.register(service) })
  private def args(values: (String, String)*): Json = Json.obj(values.map((k, v) => k -> Json.fromString(v))*)
  private def status(name: String, input: Json, expected: Int, code: String): IO[Unit] =
    commands.call(s"fs.$name", input).map { response =>
      assertEquals(response.hcursor.get[Int]("status"), Right(expected), response.noSpaces)
      assertEquals(response.hcursor.downField("body").get[String]("code"), Right(code), response.noSpaces)
    }
  private def awaitUntil[A](action: IO[A])(check: A => Boolean): IO[A] =
    def loop: IO[A] = action.flatMap(value => if check(value) then IO.pure(value) else IO.sleep(10.millis) *> IO.defer(loop))
    loop.timeout(10.seconds)

  test("text, binary and empty files round-trip through the declared mixed-body command") {
    temporary().use { home =>
      val file = home.resolve("中文 ?# &.txt").toString
      val sdk = commands
      val text = "\ufeff中文🙂\n\u0000body=query"
      for
        written <- sdk.call("fs.write", args("path" -> file, "body" -> text))
        _ = assertEquals(written.hcursor.downField("body").get[Int]("bytes"), Right(text.getBytes(UTF_8).length))
        read <- service.read(file)
        _ = assertEquals(read, FileContent(text, FileEncoding.utf8, text.getBytes(UTF_8).length))
        _ <- sdk.call("fs.write", args("path" -> file, "encoding" -> "base64", "body" -> "AH+A/w=="))
        binary <- service.read(file, FileEncoding.base64)
        _ = assertEquals(binary, FileContent("AH+A/w==", FileEncoding.base64, 4))
        _ <- sdk.call("fs.write", args("path" -> file, "body" -> ""))
        empty <- service.read(file)
      yield assertEquals(empty, FileContent("", FileEncoding.utf8, 0))
    }
  }

  test("invalid encodings and oversized writes fail before modifying the target") {
    temporary().use { home =>
      val file = home.resolve("keep").toString
      val binary = home.resolve("binary").toString
      for
        _ <- service.write(file, "keep")
        _ <- status("write", args("path" -> file, "body" -> "!", "encoding" -> "base64"), 400, "invalid_fs")
        tooLarge <- service.write(file, "x" * (FsService.maxBytes + 1)).attempt
        _ = assert(tooLarge.swap.toOption.exists(_.isInstanceOf[FsLimit]))
        multibyte <- service.write(file, "中" * (FsService.maxBytes / 3 + 1)).attempt
        _ = assert(multibyte.swap.toOption.exists(_.isInstanceOf[FsLimit]))
        encoded <- service.write(file, Base64.getEncoder.encodeToString(Array.fill[Byte](FsService.maxBytes + 1)(65)), FileEncoding.base64).attempt
        _ = assert(encoded.swap.toOption.exists(_.isInstanceOf[FsLimit]))
        current <- service.read(file)
        _ = assertEquals(current.content, "keep")
        _ <- service.write(binary, "/w==", FileEncoding.base64)
        _ <- status("read", args("path" -> binary), 400, "invalid_utf8")
        preserved <- service.read(binary, FileEncoding.base64)
      yield assertEquals(preserved.content, "/w==")
    }
  }

  test("bounded reads enforce both the file-byte limit and generic SDK serialization limit") {
    temporary().use { home =>
      val file = home.resolve("large")
      for
        _ <- IO.blocking(Files.write(file, Array.fill[Byte](FsService.maxBytes)(65)))
        exact <- service.read(file.toString)
        _ = assertEquals(exact.bytes, FsService.maxBytes)
        _ <- IO.blocking(Files.write(file, Array.fill[Byte](FsService.maxBytes + 1)(65)))
        _ <- status("read", args("path" -> file.toString), 413, "fs_limit")
        _ <- IO.blocking(Files.write(file, Array.fill[Byte](FsService.maxBytes * 3 / 4)(65)))
        sdkLimit <- commands.call("fs.read", args("path" -> file.toString, "encoding" -> "base64")).attempt
      yield assert(sdkLimit.swap.toOption.exists(_.getMessage.contains("SDK response exceeds 1 MiB")))
    }
  }

  test("create is exclusive under contention, append adds data, and replace publishes a new inode with retained permissions") {
    temporary().use { home =>
      val file = home.resolve("value")
      val alias = home.resolve("old-inode")
      val permissions = PosixFilePermissions.fromString("rw-r-----")
      for
        attempts <- (1 to 12).toList.parTraverse(index => service.write(file.toString, s"$index", mode = WriteMode.create).attempt)
        _ = assertEquals(attempts.count(_.isRight), 1)
        _ = assert(attempts.collect { case Left(error) => error }.forall(_.isInstanceOf[java.nio.file.FileAlreadyExistsException]))
        before <- service.read(file.toString)
        _ <- service.write(file.toString, " appended", mode = WriteMode.append)
        appended <- service.read(file.toString)
        _ = assertEquals(appended.content, before.content + " appended")
        _ <- IO.blocking { Files.setPosixFilePermissions(file, permissions); Files.createLink(alias, file) }
        _ <- service.write(file.toString, "replacement")
        replaced <- service.read(file.toString)
        original <- service.read(alias.toString)
      yield
        assertEquals(replaced.content, "replacement")
        assertEquals(original.content, appended.content)
        assertEquals(Files.getPosixFilePermissions(file), permissions)
    }
  }

  test("replacement failure and cancellation close the writer and remove staging files before returning") {
    temporary().use { home =>
      val file = home.resolve("keep")
      for
        _ <- service.write(file.toString, "old")
        failed <- service.replace(file)(stage => IO.blocking(Files.writeString(stage, "partial")) *>
          IO.raiseError(new java.io.IOException("write failed"))).attempt
        _ = assert(failed.isLeft)
        started <- Deferred[IO, Unit]
        active <- Ref.of[IO, Int](0)
        fiber <- service.replace(file) { stage =>
          Resource.make(active.update(_ + 1))(_ => active.update(_ - 1)).use { _ =>
            IO.blocking(Files.writeString(stage, "partial")) *> started.complete(()) *> IO.never[Unit]
          }
        }.start
        _ <- (started.get.timeout(5.seconds) *> fiber.cancel).guarantee(fiber.cancel)
        count <- active.get
        current <- service.read(file.toString)
        entries <- service.list(home.toString)
      yield
        assertEquals(count, 0)
        assertEquals(current.content, "old")
        assertEquals(entries.map(_.name), Vector("keep"))
    }
  }

  test("atomic replacement can retain read-only permissions and rejects a target changed into a symlink") {
    temporary().use { home =>
      val file = home.resolve("target")
      val referent = home.resolve("referent")
      val permissions = PosixFilePermissions.fromString("r--r-----")
      for
        _ <- service.write(file.toString, "old")
        _ <- IO.blocking(Files.setPosixFilePermissions(file, permissions))
        _ <- service.write(file.toString, "new")
        result <- service.read(file.toString)
        _ = assertEquals(result.content, "new")
        _ = assertEquals(Files.getPosixFilePermissions(file), permissions)
        _ <- service.write(referent.toString, "keep")
        changed <- service.replace(file) { stage => IO.blocking {
          Files.writeString(stage, "replacement")
          Files.delete(file)
          Files.createSymbolicLink(file, referent)
        }.void }.attempt
        _ = assert(changed.swap.toOption.exists(_.isInstanceOf[FsConflict]))
        kept <- service.read(referent.toString)
        entries <- service.list(home.toString)
      yield
        assertEquals(kept.content, "keep")
        assert(Files.isSymbolicLink(file))
        assertEquals(entries.map(_.name), Vector("referent", "target"))
    }
  }

  test("absolute paths preserve filesystem resolution of a symlink followed by parent traversal") {
    temporary().use { home =>
      val actual = home.resolve("actual")
      val path = home.resolve("shortcut/../value").toString
      for
        _ <- service.mkdir(actual.resolve("child").toString, recursive = true)
        _ <- IO.blocking(Files.createSymbolicLink(home.resolve("shortcut"), actual.resolve("child")))
        _ <- service.write(path, "correct location")
        value <- service.read(path)
      yield
        assertEquals(value.content, "correct location")
        assertEquals(Files.readString(actual.resolve("value")), "correct location")
        assert(!Files.exists(home.resolve("value")))
    }
  }

  test("stat and sorted directory lists preserve symlink and dangling-link identity") {
    temporary().use { home =>
      val file = home.resolve("中文")
      for
        _ <- service.write(file.toString, "abc")
        _ <- service.mkdir(home.resolve("directory").toString)
        _ <- IO.blocking {
          Files.createSymbolicLink(home.resolve("link"), Path.of("中文"))
          Files.createSymbolicLink(home.resolve("dangling"), Path.of("absent"))
        }
        data <- service.stat(file.toString)
        link <- service.stat(home.resolve("link").toString)
        dangling <- service.stat(home.resolve("dangling").toString)
        entries <- service.list(home.toString)
        followed <- service.read(home.resolve("link").toString)
      yield
        assertEquals((data.kind, data.size, data.linkTarget), (FileKind.file, 3L, None))
        java.time.Instant.parse(data.modifiedAt)
        assertEquals((link.kind, link.linkTarget), (FileKind.symlink, Some("中文")))
        assertEquals(dangling.kind, FileKind.symlink)
        assertEquals(entries, Vector(DirectoryEntry("dangling", FileKind.symlink), DirectoryEntry("directory", FileKind.directory),
          DirectoryEntry("link", FileKind.symlink), DirectoryEntry("中文", FileKind.file)))
        assertEquals(followed.content, "abc")
    }
  }

  test("directory limits fail explicitly and mkdir controls parent creation and existing targets") {
    temporary().use { home =>
      val directory = home.resolve("parent/child")
      for
        _ <- status("mkdir", args("path" -> directory.toString), 404, "not_found")
        _ <- status("write", args("path" -> directory.resolve("file").toString, "body" -> "x"), 404, "not_found")
        _ <- service.mkdir(directory.toString, recursive = true)
        _ <- service.mkdir(directory.toString, recursive = true)
        _ <- status("mkdir", args("path" -> directory.toString), 409, "already_exists")
        _ <- IO.blocking((0 until FsService.maxEntries).foreach(index => Files.createFile(directory.resolve(s"$index"))))
        full <- service.list(directory.toString)
        _ = assertEquals(full.size, FsService.maxEntries)
        _ <- IO.blocking(Files.createFile(directory.resolve("extra")))
        _ <- status("list", args("path" -> directory.toString), 413, "fs_limit")
      yield ()
    }
  }

  test("copy streams files larger than the SDK limit and never overwrites unless requested") {
    temporary().use { home =>
      val source = home.resolve("source")
      val target = home.resolve("target")
      val data = Array.tabulate[Byte](FsService.maxBytes * 2 + 17)(index => index.toByte)
      for
        _ <- IO.blocking(Files.write(source, data))
        _ <- service.write(target.toString, "keep")
        _ <- status("copy", args("from" -> source.toString, "to" -> target.toString), 409, "already_exists")
        unchanged <- service.read(target.toString)
        _ = assertEquals(unchanged.content, "keep")
        _ <- service.copy(source.toString, target.toString, overwrite = true)
        equal <- IO.blocking(Files.mismatch(source, target))
        _ = assertEquals(equal, -1L)
        _ <- IO.blocking(Files.createSymbolicLink(home.resolve("link"), source))
        _ <- service.copy(home.resolve("link").toString, home.resolve("via-link").toString)
        copied <- service.stat(home.resolve("via-link").toString)
        _ = assertEquals((copied.kind, copied.size), (FileKind.file, data.length.toLong))
        _ <- status("copy", args("from" -> home.resolve("missing").toString, "to" -> target.toString), 404, "not_found")
      yield assertEquals(Files.mismatch(source, target), -1L)
    }
  }

  test("move handles files, directories and link entries with explicit overwrite semantics") {
    temporary().use { home =>
      val source = home.resolve("source").toString
      val target = home.resolve("target").toString
      for
        _ <- service.write(source, "new")
        _ <- service.write(target, "old")
        _ <- status("move", args("from" -> source, "to" -> target), 409, "already_exists")
        _ <- service.move(source, target, overwrite = true)
        result <- service.read(target)
        _ = assertEquals(result.content, "new")
        _ = assert(!Files.exists(Path.of(source)))
        _ <- service.mkdir(home.resolve("dir").toString)
        _ <- service.write(home.resolve("dir/entry").toString, "data")
        _ <- service.move(home.resolve("dir").toString, home.resolve("renamed").toString)
        _ <- IO.blocking(Files.createSymbolicLink(home.resolve("link"), Path.of("target")))
        _ <- service.move(home.resolve("link").toString, home.resolve("moved-link").toString)
        link <- service.stat(home.resolve("moved-link").toString)
      yield
        assertEquals(link.kind, FileKind.symlink)
        assert(Files.isRegularFile(home.resolve("renamed/entry")))
        assertEquals(Files.readString(Path.of(target)), "new")
    }
  }

  test("cross-filesystem moves fail without copying or removing either entry") {
    val other = Path.of("/dev/shm")
    assume(Files.isDirectory(other) && Files.isWritable(other))
    assume(Files.getFileStore(other) != Files.getFileStore(Path.of("/tmp/opencode")))
    (temporary(), temporary(other)).tupled.use { (left, right) =>
      val source = left.resolve("source").toString
      val target = right.resolve("target").toString
      for
        _ <- service.write(source, "keep")
        _ <- status("move", args("from" -> source, "to" -> target), 409, "cross_device")
        current <- service.read(source)
      yield
        assertEquals(current.content, "keep")
        assert(!Files.exists(Path.of(target)))
    }
  }

  test("recursive remove deletes symlink entries but preserves their external targets") {
    temporary().use { home =>
      val outside = home.resolve("outside")
      val tree = home.resolve("tree")
      for
        _ <- service.mkdir(outside.toString)
        _ <- service.write(outside.resolve("keep").toString, "retained")
        _ <- service.mkdir(tree.resolve("nested").toString, recursive = true)
        _ <- service.write(tree.resolve("nested/file").toString, "delete")
        _ <- IO.blocking {
          Files.createSymbolicLink(tree.resolve("escape"), outside)
          Files.createSymbolicLink(tree.resolve("cycle"), tree)
          Files.createSymbolicLink(tree.resolve("dangling"), Path.of("missing"))
        }
        _ <- status("remove", args("path" -> tree.toString), 409, "directory_not_empty")
        _ <- service.remove(tree.toString, recursive = true)
        _ <- IO.blocking(Files.createSymbolicLink(home.resolve("root-link"), outside))
        _ <- service.remove(home.resolve("root-link").toString, recursive = true)
        retained <- service.read(outside.resolve("keep").toString)
      yield
        assertEquals(retained.content, "retained")
        assert(!Files.exists(tree))
        assert(!Files.exists(home.resolve("root-link"), LinkOption.NOFOLLOW_LINKS))
    }
  }

  test("invalid paths, incompatible types and target links have structured errors without modifying referents") {
    temporary().use { home =>
      val file = home.resolve("file").toString
      val link = home.resolve("link").toString
      for
        _ <- service.write(file, "keep")
        _ <- IO.blocking(Files.createSymbolicLink(Path.of(link), Path.of(file)))
        _ <- status("read", args("path" -> "relative"), 400, "invalid_fs")
        _ <- status("stat", args("path" -> "/invalid\u0000path"), 400, "invalid_path")
        _ <- status("stat", args("path" -> home.resolve("absent").toString), 404, "not_found")
        _ <- status("read", args("path" -> home.toString), 409, "is_directory")
        _ <- status("list", args("path" -> file), 409, "not_directory")
        _ <- status("list", args("path" -> link), 409, "not_directory")
        _ <- status("read", args("path" -> "/dev/null"), 409, "not_regular_file")
        _ <- List("replace", "create", "append").traverse_ { mode =>
          status("write", args("path" -> link, "body" -> "new", "mode" -> mode), 409, "not_regular_file")
        }
        _ <- status("copy", args("from" -> file, "to" -> link), 409, "not_regular_file")
        retained <- service.read(file)
      yield assertEquals(retained.content, "keep")
    }
  }

  test("filesystem access failures map to permission_denied") {
    temporary().use { home =>
      val file = home.resolve("private")
      Resource.make(IO.blocking {
        Files.writeString(file, "secret")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"))
      })(_ => IO.blocking(Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))).void).use { _ =>
        assume(!Files.isReadable(file), "Permission test requires an unprivileged user")
        status("read", args("path" -> file.toString), 403, "permission_denied")
      }
    }
  }

  test("all fs commands are declaration-generated and GraalJS-only; mixed input validation is preserved") {
    val app = httpApp { FsRoutes.register(service) }
    val sdk = GraalCommandSdk(app)
    val names = Vector("copy", "list", "mkdir", "move", "read", "remove", "stat", "write").map("fs." + _)
    assertEquals(sdk.commands, names)
    assert(app.endpoints.forall(_.exposedOn == Set(graaljs)))
    val paths = io.circe.parser.parse(app.openApiJson).toOption.get.hcursor.downField("paths")
    app.endpoints.toList.traverse_ { endpoint =>
      assertEquals(paths.downField(endpoint.path).downField(endpoint.method.toLowerCase)
        .get[List[String]]("x-expose-on"), Right(List("graaljs")))
      List(Method.GET, Method.POST).traverse_ { method =>
        app.httpApp(Request[IO](method, Uri.unsafeFromString(endpoint.path))).map(response => assertEquals(response.status, Status.NotFound))
      }
    } *> temporary().use { home =>
      val file = home.resolve("file").toString
      for
        missing <- sdk.call("fs.write", args("path" -> file))
        invalid <- sdk.call("fs.read", args("path" -> file, "encoding" -> "unknown"))
        _ = assertEquals(missing.hcursor.get[Int]("status"), Right(400))
        _ = assertEquals(invalid.hcursor.get[Int]("status"), Right(400))
      yield assert(!Files.exists(Path.of(file)))
    }
  }

  test("GraalJS uses the ordinary fs SDK for fetch downloads and a complete file workflow") {
    temporary().use { home =>
      val client = Client[IO](_ => Resource.pure(Response[IO](Status.Ok)
        .withBodyStream(fs2.Stream.emits(Array[Byte](0, 127, -128, -1)))))
      val app = httpApp { FsRoutes.register(service); FetchRoutes.register(new FetchService(client)) }
      GraalManager.resource(home.resolve("tasks"), IO.pure(GraalCommandSdk(app))).use { manager =>
        val root = Json.fromString(home.resolve("workflow").toString).noSpaces
        for
          task <- manager.start(s"""
            if (typeof fs !== "undefined" || typeof fetch !== "undefined") throw Error("unexpected business global");
            const root = $root;
            await tools.fs.mkdir({path: root});
            const reply = await tools.fetch({url: "https://example.com/binary"});
            await tools.fs.write({path: root + "/binary", body: reply.body.bodyBase64, encoding: "base64"});
            const data = await tools.fs.read({path: root + "/binary", encoding: "base64"});
            if (data.body.content !== "AH+A/w==" || data.body.bytes !== 4) throw Error("binary mismatch");
            await tools.fs.copy({from: root + "/binary", to: root + "/copied"});
            await tools.fs.move({from: root + "/copied", to: root + "/moved"});
            const stat = await tools.fs.stat({path: root + "/moved"});
            const list = await tools.fs.list({path: root});
            if (stat.body.kind !== "file" || stat.body.size !== 4 || list.body.length !== 2) throw Error("metadata mismatch");
            try { await tools.fs.read({path: root + "/missing"}); throw Error("missing error"); }
            catch (e) { if (e.name !== "ApiError" || e.response.status !== 404 || e.response.body.code !== "not_found") throw e; }
            await tools.fs.remove({path: root, recursive: true});
          """, "5s", "10s")
          done <- awaitUntil(manager.status(task.id))(_.terminal)
          log <- manager.logs(task.id)
        yield
          assertEquals(done.state, "succeeded", s"${done.error}\n$log")
          assert(!Files.exists(home.resolve("workflow")))
      }
    }
  }
