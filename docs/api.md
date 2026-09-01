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
  the unencrypted traffic to the Player API will be able to view program metadata and upload or replace programs as if
  they are another player. Such an attacker can **not**, without additional social engineering,
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

The authenticated `GET /playerapi/v1/whoami` endpoint returns `200` with a JSON body containing the profile name
associated with the current session:

```json
{
    "playerProfileName": "<Minecraft profile name for logged-in player>"
}
```

The endpoint does not extend the session lifetime. Calls without a valid session cookie receive `401` with the
Player API `WWW-Authenticate` header. Other methods receive `405` with `Allow: GET`.

### Session management

The authenticated `POST /playerapi/v1/logout` endpoint accepts no request body. It requires a same-origin
`Origin` header, removes the current session from the durable authentication store, and returns `204` with no
response body. The response also clears the session cookie:

```http
Set-Cookie: cobbleware_session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0
```

The cookie name is the configured session cookie name. The cookie deletion is browser-side cleanup; the server-side
session is invalidated as part of the same operation, so the old cookie cannot be used for subsequent
authentication. Calls without a valid session cookie receive `401` with the Player API `WWW-Authenticate` header.
Requests without the required same-origin `Origin` header, or with a different origin, receive `403` with a JSON
error body. Other methods receive `405` with `Allow: POST`.

### Programs

All program operations require player authentication via session cookie.

#### Upload

The `POST /playerapi/v1/programs` operation uploads a new program, replacing the player's active program if one
exists. The request body is a
`multipart/form-data` body with the following parts:

* `program`: required, the ESZip bundle. The part should use `application/octet-stream` as its content type.
* `name`: optional UTF-8 text containing the program name.

The caller supplies the dropped file as the `program` part and its filename as the multipart filename.

If `name` is omitted, the Runtime uses the multipart filename as the program name. Any directory components
are removed, and the filename extension is retained. For example, a file named `hello-world.eszip` gets the
program name `hello-world.eszip`. If no name is supplied, the `program` part must have a non-empty filename.
An explicitly supplied name takes precedence over the filename.

Program names are display metadata and are not used as storage paths. A name must be non-empty after trimming,
must be no longer than 128 Unicode characters, and must not contain NUL or other control characters.
Duplicate program names are allowed; program ids are the unique identifiers.

The Runtime stores the uploaded bytes without validating the ESZip structure; validation is deferred until the
program is loaded for execution. It enforces the player's program quota, configured by the
`COBBLEWARE_PLAYER_PROGRAM_QUOTA_BYTES` environment variable and defaulting to 10 MiB. Because a player has at most
one active program, quota usage is the size of that active program; the quota remains a maximum size for the active
program. The `sizeBytes` value and quota usage count only the ESZip payload, not multipart encoding overhead.

Replacement is performed in a fault-tolerant order. The Runtime first attempts to remove any program files for the
player that are not referenced by `programs.json`, to recover leftovers from an earlier failed replacement. It then
stores the new program, updates `programs.json` so that its `programs` array contains the new program metadata, and
finally deletes the old program file. `programs.json` is the source of truth for which program is active; its
`programs` array contains zero or one record. If a later replacement step fails, a subsequent upload repeats the
cleanup of files not referenced by `programs.json`. In particular, a failed old-file deletion does not change which
program is active.

The upload is not required to leave the program directory free of unreferenced files after every failure, but an
unsuccessful upload does not make a newly stored program active unless `programs.json` is updated successfully.

On success, the Runtime returns `201` with the created program's metadata and a `Location` header:

```http
Location: /playerapi/v1/programs/abc123
Content-Type: application/json; charset=utf-8
```

```json
{
    "id": "abc123",
    "name": "hello-world.eszip",
    "uploadedAt": "2026-08-30T14:25:00Z",
    "sizeBytes": 12345
}
```

`uploadedAt` is an RFC 3339 timestamp in UTC. Upload failures use a JSON body containing an `error` property:

* `400` for a malformed multipart request or missing required part
* `403` for a request rejected by the browser-facing cross-origin request policy
* `413` when the bundle exceeds the player's quota
* `415` when the request is not `multipart/form-data`
* `409` when another program operation for the player is already in progress

The program id is the uppercase, unpadded RFC 4648 Base32 encoding of the SHA-256 hash of the exact uploaded
bytes. It is therefore 52 characters long and uses only `A-Z` and `2-7`. Re-uploading identical bytes for the
same player while that program is active returns `200` with its existing metadata; it does not consume additional
quota or replace its name.

#### List

The `GET /playerapi/v1/programs` operation returns a JSON body listing metadata about the user's active program
on the server. The `programs` array contains zero or one item. The response includes the player's quota usage:

```json
{
    "programs": [
        {
            "id": "abc123",
            "name": "hello-world.eszip",
            "uploadedAt": "2026-08-30T14:25:00Z",
            "sizeBytes": 12345
        }
    ],
    "quotaBytes": 56789,
    "usedBytes": 45678,
    "remainingBytes": 11111
}
```

The `id` field is a content-derived, opaque identifier. Program files are stored at
`$COBBLEWARE_GAME_DIR/cobbleware-data/programs/{playerUUID}/{program-id}.eszip`, with metadata stored in that player's
`programs.json` manifest. A player can see only their own program. The Player API does not provide an endpoint for
downloading program contents. Files not referenced by `programs.json` are not active and may be removed during a
later upload's recovery step.
Listing returns `409` with a JSON `error` property if another program operation for the same player is in progress.
The Player API does not provide an operation for deleting or deactivating the active program; uploading a replacement
is the only way to change it.

Because these operations authenticate with a cookie and are intended for browser use, the Runtime validates
that upload and logout requests have a same-origin `Origin` header. Cross-origin requests are rejected
with `403`; the API does not rely solely on the cookie's `SameSite` attribute for CSRF protection.

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
The Runtime treats `playerUUID` as an opaque value supplied by the Mod and does not validate that it has UUID
syntax. It normalizes the value to lowercase before using it as the player's identity and program-directory
component.

If the code is new, or is retried for the same player UUID, the endpoint returns `201` with no body.
If the code is already pending for another player UUID, it returns `400` with a JSON response body
containing an `"error"` property. The Runtime trusts `playerProfileName` as supplied by the Mod and does
not validate its contents.
