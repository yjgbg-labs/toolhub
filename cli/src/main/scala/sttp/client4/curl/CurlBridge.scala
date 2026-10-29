// This bridge deliberately lives in sttp's package: its protected extension hook
// exposes a package-private CurlHandle. Keep the version-sensitive code here.
package sttp.client4.curl

import sttp.client4.SyncBackend
import sttp.client4.curl.internal.{CurlCode, CurlOption}
import sttp.client4.curl.internal.CurlApi.*
import sttp.monad.IdentityMonad
import sttp.shared.Identity
import scala.scalanative.unsafe.Zone

class SproutUnixSocketBackend(socket: String)
    extends AbstractCurlBackend[Identity](IdentityMonad, false) with SyncBackend:
  require(socket.startsWith("/") && !socket.contains('\u0000'), "Expected absolute Unix socket path")
  override protected def performCurl(handle: CurlHandle): CurlCode.CurlCode = Zone {
    val configured = handle.option(CurlOption.UnixSocketPath, socket)
    if configured != CurlCode.Ok then configured else handle.perform
  }
