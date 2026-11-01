package groot.kube

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.sun.net.httpserver.{HttpsConfigurator, HttpsParameters, HttpsServer}
import groot.graaljs.GraalTestSupport.*
import java.io.StringWriter
import java.math.BigInteger
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.Date
import munit.CatsEffectSuite
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.{BasicConstraints, Extension, GeneralName, GeneralNames, KeyPurposeId, ExtendedKeyUsage}
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.io.pem.PemObject

class KubeTlsSuite extends CatsEffectSuite:
  private def credentials(host: String = "127.0.0.1"): IO[(String, String, String)] = IO.blocking {
    val pair = KeyPairGenerator.getInstance("RSA").generateKeyPair()
    val subject = new X500Name("CN=kube-test")
    val builder = new JcaX509v3CertificateBuilder(subject, BigInteger.ONE, Date.from(Instant.now().minusSeconds(60)),
      Date.from(Instant.now().plusSeconds(3600)), subject, pair.getPublic)
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true))
    builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName(GeneralName.iPAddress, host)))
    builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(Array(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth)))
    val provider = new BouncyCastleProvider()
    val cert = new JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(
      new JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(pair.getPrivate)))
    def pem(value: Object): String =
      val buffer = new StringWriter()
      val writer = new JcaPEMWriter(buffer)
      try writer.writeObject(value) finally writer.close()
      buffer.toString
    (pem(cert), pem(new PemObject("PRIVATE KEY", pair.getPrivate.getEncoded)), pem(pair))
  }

  private def server(cert: String, key: String): Resource[IO, String] = Resource.make(IO.blocking {
    val ssl = KubeTls.context(ContextConfig("https://127.0.0.1", caCertificate = Some(cert), clientCertificate = Some(cert), clientKey = Some(key)))
    val server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.setHttpsConfigurator(new HttpsConfigurator(ssl):
      override def configure(parameters: HttpsParameters): Unit =
        val settings = ssl.getDefaultSSLParameters
        settings.setNeedClientAuth(true)
        parameters.setSSLParameters(settings)
    )
    server.createContext("/version", exchange => {
      val bytes = "{\"gitVersion\":\"test\"}".getBytes(UTF_8)
      exchange.sendResponseHeaders(200, bytes.length)
      try exchange.getResponseBody.write(bytes) finally exchange.close()
    })
    server.start()
    server
  })(server => IO.blocking(server.stop(0))).map(server => s"https://127.0.0.1:${server.getAddress.getPort}")

  test("custom CA and mutual TLS work with PKCS#8 and RSA PEM keys; untrusted, unauthenticated and hostname-mismatched peers fail") {
    temporary.use { home => for
      identity <- credentials()
      (cert, pkcs8, pkcs1) = identity
      _ <- server(cert, pkcs8).use { url => KubeService.resource(home.resolve("first")).use { service =>
        val config = ContextConfig(url, caCertificate = Some(cert), clientCertificate = Some(cert), clientKey = Some(pkcs8))
        for
          _ <- service.putContext("test", config)
          version <- service.request("test", "/version")
          _ = assertEquals(version.hcursor.get[String]("gitVersion"), Right("test"))
          _ <- service.putContext("test", config.copy(clientKey = Some(pkcs1)))
          _ <- service.request("test", "/version")
          _ <- service.putContext("test", config.copy(caCertificate = None))
          untrusted <- service.request("test", "/version").attempt
          _ = assertEquals(untrusted.swap.toOption.get.asInstanceOf[KubeFailure].status, 502)
          _ <- service.putContext("test", config.copy(clientCertificate = None, clientKey = None))
          unauthenticated <- service.request("test", "/version").attempt
          _ = assertEquals(unauthenticated.swap.toOption.get.asInstanceOf[KubeFailure].status, 502)
        yield ()
      } }
      wrongName <- credentials("127.0.0.2")
      _ <- server(wrongName._1, wrongName._2).use { url => KubeService.resource(home.resolve("second")).use { service =>
        service.putContext("test", ContextConfig(url, caCertificate = Some(wrongName._1), clientCertificate = Some(wrongName._1), clientKey = Some(wrongName._2))) *>
          service.request("test", "/version").attempt.map(result => assertEquals(result.swap.toOption.get.asInstanceOf[KubeFailure].status, 502))
      } }
    yield () }
  }
