package groot.fs

import framework.model.JsonModel

enum FileEncoding derives JsonModel:
  case utf8, base64

enum WriteMode derives JsonModel:
  case replace, create, append

enum FileKind derives JsonModel:
  case file, directory, symlink, other

case class FileContent(content: String, encoding: FileEncoding, bytes: Int) derives JsonModel
case class WriteResult(bytes: Int) derives JsonModel
case class FileStat(kind: FileKind, size: Long, modifiedAt: String, linkTarget: Option[String]) derives JsonModel
case class DirectoryEntry(name: String, kind: FileKind) derives JsonModel

case class InvalidFs(message: String) extends RuntimeException(message)
case class FsConflict(code: String, message: String) extends RuntimeException(message)
case class FsLimit(message: String) extends RuntimeException(message)
