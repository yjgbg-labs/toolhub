package groot.adb

import cats.effect.IO
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets.{US_ASCII, UTF_8}
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import java.security.{KeyFactory, KeyPairGenerator, MessageDigest, Signature}
import java.security.interfaces.{RSAPrivateCrtKey, RSAPublicKey}
import java.security.spec.{PKCS8EncodedKeySpec, RSAPublicKeySpec}
import java.util.Base64
import scala.util.control.NonFatal

final case class AdbKeyMaterial(
    keyPath: Path,
    privateKey: RSAPrivateCrtKey,
    publicKey: RSAPublicKey,
    androidPublicKeyLine: String,
    fingerprintSha256: String
):
  /** Sign a 20-byte ADB AUTH_TOKEN using RSA PKCS#1 v1.5 with SHA-1 DigestInfo prefix. */
  def signToken(token: Array[Byte]): Array[Byte] =
    val sig = Signature.getInstance("NONEwithRSA")
    sig.initSign(privateKey)
    sig.update(AdbCrypto.Sha1DigestInfoPrefix)
    sig.update(token)
    sig.sign()

  /** Null-terminated Android RSAPublicKey payload for ADB AUTH_RSAPUBLICKEY (arg0 = 3). */
  def publicKeyPayload: Array[Byte] =
    val raw = androidPublicKeyLine.trim.getBytes(US_ASCII)
    val out = new Array[Byte](raw.length + 1)
    System.arraycopy(raw, 0, out, 0, raw.length)
    out(raw.length) = 0.toByte
    out

object AdbCrypto:
  // ASN.1 DER DigestInfo header for SHA-1 (15 bytes): 30 21 30 09 06 05 2b 0e 03 02 1a 05 00 04 14
  val Sha1DigestInfoPrefix: Array[Byte] = Array(
    0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
    0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
  ).map(_.toByte)

  private val ModulusBits = 2048
  private val ModulusBytes = ModulusBits / 8 // 256
  private val ModulusWords = ModulusBits / 32 // 64
  private val AndroidPubKeyStructBytes = 4 + 4 + ModulusBytes + ModulusBytes + 4 // 524

  /** Resolve or generate the ADB RSA-2048 keypair.
    * Resolution order when `configuredKeyFile` is blank:
    *   1. `$GROOT_HOME/adb/adbkey` (if already present)
    *   2. `~/.android/adbkey` (reuse existing host ADB key already trusted by LAN/USB devices)
    *   3. Generate and persist a new 2048-bit keypair at `$GROOT_HOME/adb/adbkey` (`0600`)
    */
  def loadOrCreate(grootHome: Path, configuredKeyFile: String): IO[AdbKeyMaterial] = IO.blocking {
    val explicit = Option(configuredKeyFile).map(_.trim).filter(_.nonEmpty)
    val defaultManaged = grootHome.toAbsolutePath.normalize().resolve("adb").resolve("adbkey")
    val userAndroidKey = Option(System.getProperty("user.home"))
      .map(h => Path.of(h).resolve(".android").resolve("adbkey").toAbsolutePath.normalize())

    val targetPath = explicit match
      case Some(raw) =>
        val p = Path.of(raw)
        (if p.isAbsolute then p else grootHome.resolve(p)).toAbsolutePath.normalize()
      case None =>
        if Files.isRegularFile(defaultManaged) then defaultManaged
        else userAndroidKey.filter(Files.isRegularFile(_)).getOrElse(defaultManaged)

    if Files.isRegularFile(targetPath) then loadFromFile(targetPath)
    else generateAndPersist(targetPath)
  }

  def loadFromFile(keyPath: Path): AdbKeyMaterial =
    try
      val pem = Files.readString(keyPath, UTF_8)
      val priv = decodePkcs8Pem(pem)
      val kf = KeyFactory.getInstance("RSA")
      val pub = kf.generatePublic(new RSAPublicKeySpec(priv.getModulus, priv.getPublicExponent)).asInstanceOf[RSAPublicKey]
      val companionPub = Path.of(keyPath.toString + ".pub")
      val pubLine =
        if Files.isRegularFile(companionPub) then
          val existing = Files.readString(companionPub, US_ASCII).trim
          if existing.nonEmpty then existing else encodeAndroidPublicKeyLine(pub, "groot@localhost")
        else encodeAndroidPublicKeyLine(pub, "groot@localhost")
      AdbKeyMaterial(
        keyPath = keyPath,
        privateKey = priv,
        publicKey = pub,
        androidPublicKeyLine = pubLine,
        fingerprintSha256 = sha256Hex(pub.getEncoded)
      )
    catch
      case e: AdbFailure => throw e
      case NonFatal(e) =>
        throw AdbFailure("invalid_adb_key", s"Failed to load ADB RSA private key at $keyPath: ${e.getMessage}")

  def generateAndPersist(keyPath: Path): AdbKeyMaterial =
    val parent = Option(keyPath.toAbsolutePath.normalize().getParent)
      .getOrElse(throw AdbFailure("invalid_adb_key", s"Key path has no parent directory: $keyPath"))
    Files.createDirectories(parent)
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(ModulusBits)
    val kp = kpg.generateKeyPair()
    val priv = kp.getPrivate.asInstanceOf[RSAPrivateCrtKey]
    val pub = kp.getPublic.asInstanceOf[RSAPublicKey]

    val pem = encodePkcs8Pem(priv.getEncoded)
    val pubLine = encodeAndroidPublicKeyLine(pub, "groot@localhost")
    val pubPath = Path.of(keyPath.toString + ".pub")

    writeAtomic(keyPath, pem, ownerOnly = true)
    writeAtomic(pubPath, pubLine + "\n", ownerOnly = false)

    AdbKeyMaterial(
      keyPath = keyPath,
      privateKey = priv,
      publicKey = pub,
      androidPublicKeyLine = pubLine,
      fingerprintSha256 = sha256Hex(pub.getEncoded)
    )

  def decodePkcs8Pem(pem: String): RSAPrivateCrtKey =
    val body = pem
      .linesIterator
      .map(_.trim)
      .filter(line => line.nonEmpty && !line.startsWith("-----"))
      .mkString
    if body.isEmpty then throw AdbFailure("invalid_adb_key", "PEM private key body is empty")
    val der = Base64.getDecoder.decode(body)
    val kf = KeyFactory.getInstance("RSA")
    kf.generatePrivate(new PKCS8EncodedKeySpec(der)) match
      case crt: RSAPrivateCrtKey => crt
      case other =>
        throw AdbFailure("invalid_adb_key", s"Unsupported RSA key representation: ${other.getClass.getSimpleName}")

  def encodePkcs8Pem(der: Array[Byte]): String =
    val b64 = Base64.getMimeEncoder(64, "\n".getBytes(US_ASCII)).encodeToString(der)
    s"-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n"

  /** Encode an RSA-2048 public key into AOSP `libcrypto_utils` 524-byte `RSAPublicKey` binary struct + Base64 + comment. */
  def encodeAndroidPublicKeyStruct(pub: RSAPublicKey): Array[Byte] =
    val n = BigInt(1, pub.getModulus.toByteArray)
    if n.bitLength != ModulusBits then
      throw AdbFailure("invalid_adb_key", s"ADB requires a $ModulusBits-bit RSA key, got ${n.bitLength} bits")
    val e = pub.getPublicExponent.intValue()
    val r32 = BigInt(1) << 32
    val n0 = n.mod(r32)
    val n0inv = (r32 - n0.modInverse(r32)).mod(r32).longValue

    val r = BigInt(1) << ModulusBits
    val rr = r.modPow(BigInt(2), n)

    val buf = ByteBuffer.allocate(AndroidPubKeyStructBytes).order(ByteOrder.LITTLE_ENDIAN)
    buf.putInt(ModulusWords)
    buf.putInt(n0inv.toInt)
    buf.put(toLittleEndianUnsigned(n, ModulusBytes))
    buf.put(toLittleEndianUnsigned(rr, ModulusBytes))
    buf.putInt(e)
    buf.array()

  def encodeAndroidPublicKeyLine(pub: RSAPublicKey, comment: String = "groot@localhost"): String =
    val struct = encodeAndroidPublicKeyStruct(pub)
    s"${Base64.getEncoder.encodeToString(struct)} $comment"

  private def toLittleEndianUnsigned(value: BigInt, length: Int): Array[Byte] =
    val beRaw = value.bigInteger.toByteArray
    val beTrimmed =
      if beRaw.length > length && beRaw(0) == 0.toByte then beRaw.drop(1)
      else beRaw
    if beTrimmed.length > length then
      throw AdbFailure("invalid_adb_key", s"Unsigned integer exceeds $length bytes (${beTrimmed.length})")
    val le = new Array[Byte](length)
    var i = 0
    while i < beTrimmed.length do
      le(i) = beTrimmed(beTrimmed.length - 1 - i)
      i += 1
    le

  private def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  private def writeAtomic(target: Path, content: String, ownerOnly: Boolean): Unit =
    val parent = target.getParent
    val tmp = Files.createTempFile(parent, ".adbkey-", ".tmp")
    try
      Files.writeString(tmp, content, UTF_8, StandardOpenOption.TRUNCATE_EXISTING)
      if ownerOnly then
        try Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
        catch case NonFatal(_) => ()
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    finally
      Files.deleteIfExists(tmp)
      ()
