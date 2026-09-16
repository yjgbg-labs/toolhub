package sprouttests

import java.util.ServiceLoader
import munit.FunSuite
import org.slf4j.LoggerFactory
import org.slf4j.helpers.NOPLoggerFactory
import org.slf4j.spi.SLF4JServiceProvider
import scala.jdk.CollectionConverters.*

class LoggingSuite extends FunSuite:
  test("SLF4J 2 loads exactly one Scribe provider rather than silently dropping library logs") {
    val providers = ServiceLoader.load(classOf[SLF4JServiceProvider]).iterator().asScala.toList
    assertEquals(providers.size, 1)
    assert(providers.head.getClass.getName.toLowerCase.contains("scribe"))
    assert(!LoggerFactory.getILoggerFactory.isInstanceOf[NOPLoggerFactory])
    assert(LoggerFactory.getLogger("sprout.logging.test").isInfoEnabled)
  }
