package groot.kube

import java.io.{ByteArrayInputStream, StringReader}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.{KeyStore, SecureRandom}
import java.security.cert.{Certificate, CertificateFactory}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.{PEMKeyPair, PEMParser}
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import scala.jdk.CollectionConverters.*
import scala.util.Using

private[kube] object KubeTls:
  def context(config: ContextConfig): SSLContext =
    def certificates(pem: String): Array[Certificate] =
      val result = CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(pem.getBytes(UTF_8))).asScala.toArray[Certificate]
      KubeFailure.require(result.nonEmpty, "Certificate PEM is empty")
      result
    def store() =
      val result = KeyStore.getInstance("PKCS12")
      result.load(null, null)
      result
    val trust = config.caCertificate.map { pem =>
      val keys = store()
      certificates(pem).zipWithIndex.foreach((cert, i) => keys.setCertificateEntry(s"ca-$i", cert))
      val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
      factory.init(keys)
      factory.getTrustManagers
    }.orNull
    val identity = config.clientCertificate.map { pem =>
      val info = Using.resource(new PEMParser(new StringReader(config.clientKey.get))) { parser => parser.readObject() match
        case key: PrivateKeyInfo => key
        case pair: PEMKeyPair => pair.getPrivateKeyInfo
        case _ => throw KubeFailure.local(400, "Invalid", "clientKey must be an unencrypted PKCS#8, RSA or EC PEM key")
      }
      val key = new JcaPEMKeyConverter().setProvider(new BouncyCastleProvider()).getPrivateKey(info)
      val keys = store()
      keys.setKeyEntry("client", key, Array.emptyCharArray, certificates(pem))
      val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
      factory.init(keys, Array.emptyCharArray)
      factory.getKeyManagers
    }.orNull
    val result = SSLContext.getInstance("TLS")
    result.init(identity, trust, new SecureRandom())
    result
