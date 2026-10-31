package groot.fs

import framework.http.ErrorBody
import framework.rpc.CommandsScope
import framework.rpcDsl.*
import io.circe.Json
import java.nio.charset.CharacterCodingException
import java.nio.file.*

object FsRoutes:
  private def enumCodec[A: JsonModel]: ParamCodec[A] = ParamCodec.scalar { value =>
    JsonModel[A].decoder.decodeJson(Json.fromString(value)).left.map(_.message)
  }
  private given ParamCodec[FileEncoding] = enumCodec[FileEncoding]
  private given ParamCodec[WriteMode] = enumCodec[WriteMode]

  def register(service: FsService)(using CommandsScope): Unit = commands("fs") {
    description("Read and manage files on the server using absolute paths")
    exposeOn(graaljs)
    val invalid = response.json[ErrorBody](400) { description("Invalid path, encoding or file content") }
    val denied = response.json[ErrorBody](403) { description("Filesystem permission denied") }
    val missing = response.json[ErrorBody](404) { description("File or directory not found") }
    val conflict = response.json[ErrorBody](409) { description("Existing target, incompatible file type, nonempty directory or unsupported move") }
    val large = response.json[ErrorBody](413) { description("File content or directory listing exceeds its limit") }
    val failed = response.json[ErrorBody](500) { description("Filesystem IO failed") }
    recover {
      case error: InvalidFs => invalid(ErrorBody("invalid_fs", error.getMessage))
      case error: InvalidPathException => invalid(ErrorBody("invalid_path", error.getMessage))
      case error: CharacterCodingException => invalid(ErrorBody("invalid_utf8", "File content is not valid UTF-8"))
      case error: AccessDeniedException => denied(ErrorBody("permission_denied", error.getMessage))
      case error: SecurityException => denied(ErrorBody("permission_denied", error.getMessage))
      case error: NoSuchFileException => missing(ErrorBody("not_found", error.getMessage))
      case error: FileAlreadyExistsException => conflict(ErrorBody("already_exists", error.getMessage))
      case error: DirectoryNotEmptyException => conflict(ErrorBody("directory_not_empty", error.getMessage))
      case error: NotDirectoryException => conflict(ErrorBody("not_directory", error.getMessage))
      case error: FileSystemLoopException => conflict(ErrorBody("symlink_loop", error.getMessage))
      case error: AtomicMoveNotSupportedException => conflict(ErrorBody("atomic_move_unsupported", error.getMessage))
      case error: FsConflict => conflict(ErrorBody(error.code, error.getMessage))
      case error: FsLimit => large(ErrorBody("fs_limit", error.getMessage))
      case error: java.io.IOException => failed(ErrorBody("io_error", error.getMessage))
    }

    command[FileContent]("read") {
      description("Read a regular file up to 1 MiB; UTF-8 is strict and base64 preserves binary bytes")
      val path = param[String]("path", "Absolute file path; follows symlinks") { minLength(1) }
      val encoding = param[FileEncoding]("encoding", "Content encoding") { default(FileEncoding.utf8) }
      handle { service.read(path.value, encoding.value) }
    }
    command[WriteResult]("write") {
      description("Write up to 1 MiB; replace is atomic, create is exclusive, append adds bytes; reject target symlinks")
      val path = param[String]("path", "Absolute file path; parent directory must exist") { minLength(1) }
      val encoding = param[FileEncoding]("encoding", "UTF-8 text or base64-encoded binary") { default(FileEncoding.utf8) }
      val mode = param[WriteMode]("mode", "replace, create or append") { default(WriteMode.replace) }
      val content = body.text("File content in the selected encoding; empty content is allowed")
      handle { service.write(path.value, content.value, encoding.value, mode.value) }
    }
    command[FileStat]("stat") {
      description("Read entry type, byte size, modification time and symbolic-link target without following the final link")
      val path = param[String]("path", "Absolute file or directory path") { minLength(1) }
      handle { service.stat(path.value) }
    }
    command[Vector[DirectoryEntry]]("list") {
      description("List at most 1000 direct entries, sorted by name; report symlinks without following them")
      val path = param[String]("path", "Absolute directory path; final entry must be a directory, not a symlink") { minLength(1) }
      handle { service.list(path.value) }
    }
    command[Unit]("mkdir") {
      description("Create a directory; recursive also creates parents and accepts an existing directory")
      val path = param[String]("path", "Absolute directory path") { minLength(1) }
      val recursive = param[Boolean]("recursive", "Create missing parents") { default(false) }
      handle { service.mkdir(path.value, recursive.value) }
    }
    command[Unit]("copy") {
      description("Stream a file copy on the server; follow source symlinks, reject target symlinks, atomically replace when overwriting")
      val from = param[String]("from", "Absolute source file path") { minLength(1) }
      val to = param[String]("to", "Absolute destination file path; parent must exist") { minLength(1) }
      val overwrite = param[Boolean]("overwrite", "Replace an existing regular file") { default(false) }
      handle { service.copy(from.value, to.value, overwrite.value) }
    }
    command[Unit]("move") {
      description("Move or rename an entry on the same filesystem; symlinks are moved as entries")
      val from = param[String]("from", "Absolute source path") { minLength(1) }
      val to = param[String]("to", "Absolute destination path; parent must exist") { minLength(1) }
      val overwrite = param[Boolean]("overwrite", "Replace an existing destination") { default(false) }
      handle { service.move(from.value, to.value, overwrite.value) }
    }
    command[Unit]("remove") {
      description("Remove an entry; recursive removes directory contents without following symlinks")
      val path = param[String]("path", "Absolute entry path") { minLength(1) }
      val recursive = param[Boolean]("recursive", "Remove a directory and all of its contents") { default(false) }
      handle { service.remove(path.value, recursive.value) }
    }
  }
