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

Mod API endpoints are authenticated via a shared secret passed to the runtime from the Mod as an env var, at startup time.
The Mod authenticates by passing the shared secret as a Bearer token.

Player API endpoints (and requests for HTML content) are authenticated via session cookie.
When the session cookie is not present in a request, the
request is denied with a 401 and a WWW-Authenticate header redirecting to an `/authenticate` URL. This
endpoint displays a slash comamnd with an embedded short-lived one-time-use code that can be run by the player on the
server to link their Minecraft UUID to a session cookie.

## Player API

The Player API is intended for direct use by Players, typically via a web browser
acting as the user agent.

### Authenticate

The `POST /playerapi/v1/authenticate` endpoint accepts a JSON body like:

```json
{
    "code": "XAU1-98B1"
}
```

When the server has previously received a successful `/modapi/v1/authentication` request
for the given code, the server responds with a 201 with no response body
and a `set-cookie` header with a session cookie tied to the identity of the player
that successfully authenticated via the Mod. The server "forgets" the code after the
first successful `/playerapi/v1/authenticate` response for that code. Subsequent
requests with the same code behave as if the code has never been used to authenticate.

When the server has not received a successful `/modapi/v1/authentication` request for the `"code"`,
the server responds with a `400` and a JSON error body.

The call succeeds when the server has previously received a `/modapi/v1/authentication` 

## Mod API

### Player Authentication

The `POST /modapi/v1/authentication` endpoint accepts a JSON body like:

```json
{
    "code": "XAU1-98B1",
    "playerUUID": "<UUID>",
    "playerProfileName": "<profile name>"
}
```

The Mod calls this endpoint when the player executes the `/cobbleware auth XAU1-98B1` slash command.
In the case where the code has not already been used by a different player, the endpoint returns 201 on success.
The endpoint returns 400 with a JSON response body containing an `"error"` property in the case where the code
has already been used with another playerUUID.