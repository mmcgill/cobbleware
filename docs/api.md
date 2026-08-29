# Main Worker API

The Main Worker serves an API with some endpoints intended for use directly by the Mod, and other endpoints
intended for use directly by players on the server.

## Transport Security

The Main Worker serves all API endpoints over plaintext HTTP. It does not have direct support for HTTPS/TLS.

This obviously has **significant security implications**. In general, the network path from an arbitrary client
to the Main Worker API over the internet is an **untrusted network path**. It should be assumed that
an attacker can both read and modify TCP packets in both directions.

Consequently:

* The Mod API **must not** be called by e.g. a server administrator over the internet using the shared secret.
  * The Mod API is for use **exclusively** by the Mod, which is running in a different process **on the same host**.
  * As a server administrator running the Mod, you are responsible to ensure that malicious processes on your
    host cannot sniff network traffic on the host. A malicious process with permission to sniff network traffic
    could trivially discover the shared secret and subsequently perform any action that could be performed
    by the Mod.
* The Player API is designed to have **minimal useful attack surface area**. An attacker that is able to observe
  the unencrypted traffic to the Player API will be able to view program metadata, upload programs as if they are
  another player, and delete player programs. Such an attacker can **not**, without additional social engineering,
  * run uploaded programs associated with another player
  * view the contents of programs

For improved security, server operators are encouraged to host the Main Worker API behind a reverse proxy
configured to perform TLS termination. Some popular Minecraft hosting providers (e.g. PebbleHost) explicitly support this capability.

## Authentication and Authorization

Mod API endpoints are authenticated via the shared secret passed to the Runtime from the Mod as the
`COBBLEWARE_API_KEY` environment variable at startup. The Mod supplies this value as an
`Authorization: Bearer <key>` header.

Player API endpoints and protected HTML content are authenticated with an opaque HttpOnly
`cobbleware_session` cookie. The cookie is intentionally not marked `Secure`, because the API supports
plaintext HTTP. Sessions last 30 days from creation, are persisted across Runtime restarts, and are not
extended by ordinary requests.

The canonical authentication page is `/auth.html`. A request for protected HTML without a valid session
receives `302 Location: /auth.html`. A request for a protected API endpoint without a valid session
receives `401` with a `WWW-Authenticate` header pointing to `/auth.html`.

## Player API

The Player API is intended for direct use by Players, typically via a web browser acting as the user agent.

### Authenticate

The unauthenticated `POST /playerapi/v1/authenticate` endpoint accepts:

```json
{
    "code": "XAU1-98B1"
}
```

When the Runtime has previously received a successful `/modapi/v1/authentication` request for the code,
it responds with `201`, no response body, and a `Set-Cookie` header. The cookie has `Path=/`, `HttpOnly`,
`SameSite=Lax`, and `Max-Age=2592000`.

The Runtime forgets the pending code after the first successful exchange. Pending codes expire after five
minutes and are held in memory only; they do not survive a Runtime restart. Malformed, unknown, expired,
or already-consumed codes receive `400` with a JSON body containing an `"error"` property.

## Mod API

### Player Authentication

The authenticated `POST /modapi/v1/authentication` endpoint accepts:

```json
{
    "code": "XAU1-98B1",
    "playerUUID": "<UUID>",
    "playerProfileName": "<profile name>"
}
```

The Mod calls this endpoint when the player executes the `/cobbleware auth XAU1-98B1` slash command.
The code may be supplied with or without its presentation hyphen and is normalized case-insensitively.
The documented code format is eight uppercase alphanumeric characters, displayed as `XXXX-XXXX`.

If the code is new, or is retried for the same player UUID, the endpoint returns `201` with no body.
If the code is already pending for another player UUID, it returns `400` with a JSON response body
containing an `"error"` property. The Runtime trusts `playerProfileName` as supplied by the Mod and does
not validate its contents.
