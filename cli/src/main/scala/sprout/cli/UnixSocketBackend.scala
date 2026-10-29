package sprout.cli

/** Native HTTP-over-Unix transport for a local Sprout daemon. */
final class UnixSocketBackend(socket: String)
    extends _root_.sttp.client4.curl.SproutUnixSocketBackend(socket)
