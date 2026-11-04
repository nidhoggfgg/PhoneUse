# Transport security

**English** | [简体中文](transport-security.zh-CN.md)

## Trust and user interaction

Port 8443 detects HTTP and TLS on the same listener. HTTPS (TLS 1.2/1.3) does not require a transport switch. The default HTTP exception is limited to connections whose actual source and receiving IP are both `127.0.0.1`, with `Host: 127.0.0.1:8443`. All other HTTP, including `::1`, other loopback IPs and whitelisted domains routed through localhost, requires the persistent, default-off **Allow insecure HTTP connections** setting. Forwarding headers cannot grant this exception. Every HTTP and HTTPS request also passes the access address allowlist below: listing an address does not enable HTTP, and enabling HTTP does not bypass the allowlist. Disallowed HTTP is rejected before pairing, authentication or route handling. Disabling the setting closes HTTP sockets that depend on it and invalidates their queued work and ongoing observations, without affecting HTTPS or the local `127.0.0.1` console.

Android Keystore holds an independent, non-exportable EC P-256 key for each installation. Python pins SHA-256 of the certificate's DER SubjectPublicKeyInfo (SPKI), not its IP address or a JSON field. Native clients retain HTTPS-only behavior: no HTTP fallback, redirects, shared private key, or trust-all authenticated connection. The initial credential-free identity probe is unauthenticated; its observed pin is only a candidate until pairing is confirmed.

First pairing requires the user to compare **all eight digits** shown independently
by the phone and bridge, approve on the phone, and confirm the match on the
controller. CLI asks for `yes`; MCP accepts `confirm_pairing:true` only after the
user explicitly confirms. Agents must not read/approve through phone-control tools,
infer consent from a server response, or pre-confirm a new pairing. A malicious
server can claim it was approved, so phone approval alone cannot establish trust.
Subsequent connections verify the persisted pin without further prompts.

## Native wire protocol: phoneuse-sas-v1

Transport encryption and proof of private-key possession come from TLS. This
application-specific SAS exchange authenticates the candidate server key using a
hash commitment followed by independent random contributions. It is not a ZRTP
implementation or a replacement for TLS. The general commitment/SAS rationale is
described in [RFC 6189](https://www.rfc-editor.org/rfc/rfc6189.html#section-4.4.1.1).

All fields below are 32-byte values encoded as exactly 64 lowercase hex characters.
`P` is the observed SPKI pin. `C` and `S` are independently generated 256-bit random
nonces. Hash inputs are ASCII strings joined with a single NUL byte, with no
trailing NUL. Domain and purpose separators are literal strings:

```text
commitment = SHA256("phoneuse-sas-v1" || NUL || "commit" || NUL || P || NUL || C)
digest     = SHA256("phoneuse-sas-v1" || NUL || "sas" || NUL || P || NUL || C || NUL || S)
code       = zero_pad_8(big_endian_uint32(digest[0:4]) mod 100000000)
```

1. The bridge observes `P` in TLS, generates `C`, and sends only the commitment in
   `/pair/request` with `protocol` and `client_name`. It pins every following TLS
   socket to `P` before any HTTP bytes or secrets are sent.
2. After accepting the commitment, the phone generates `S`. It returns `S`, the
   protocol, pairing ID, claim secret, expiry, and polling interval. The request
   is `committed`, hidden from the approval list, and cannot issue credentials.
3. The bridge freezes `P,C,S`, independently computes its code, and sends `C` in
   `/pair/reveal`. It never displays a remotely supplied verification code.
4. The phone checks the commitment against **its own TLS public key** and `C`.
   A mismatch permanently denies that request. A match computes the phone code
   and changes the request to `pending`, making it visible for local approval.
5. After the user confirms both displays, the bridge permits `/pair/status` to
   claim a locally approved credential and atomically saves it with the pin.

The client commits before learning the phone nonce, and does not reveal until the
other contribution is fixed. Relaying a commitment for a substituted key fails;
using two independent transcripts requires guessing the compared code (roughly
one in 100 million per attempt). Limits: one new request per five seconds globally,
at most 16 live requests, two-minute expiry, one reveal value per request, no
automatic retry of a failed pairing. Denial, expiry, or service restart cannot
carry human confirmation into a new transcript. These limits also bound resources;
they do not eliminate denial of service. The human comparison and controller-side
confirmation remain part of the authentication mechanism.

Cross-language test vector: `P=00*32`, `C=11*32`, `S=22*32` (hex bytes):
commitment `9752824c0f5f6e1fb8abbea0ef2960d94e93db4125093766cdcb87bdf8b589e1`,
code `17242944`.

## Persistence, migration, and key changes

The phone key survives service/app restarts. The HTTPS migration uses a separate credential namespace from historical HTTP-only releases; tokens from those releases remain invalid. Enabling optional HTTP does not reset existing pairings. The Python
registry migrates old addresses to HTTPS but discards unpinned credentials and
pending claims. Both sides must be updated and paired again once.

A changed IP does not change key identity. Key mismatch fails closed before HTTP
data is sent and never replaces the saved pin. Independently verify an intended
reinstall/reset, revoke the old client on the phone, then explicitly remove the
local binding with `python CLIENT forget-device --device-id DEVICE_ID` and pair
again. Never automatically forget/rebind in response to a network error. SPKI
pinning permits certificate renewal with the same key; this native trust model
does not depend on public CA chains, hostnames, or certificate validity dates.
The phone key and identity/credential preferences are not backed up or migrated.

## Browser boundary

HTTPS browser sessions retain Secure, HttpOnly, SameSite=Strict cookies named `phoneuse_client_PORT`. HTTP sessions use a separate `phoneuse_http_client_PORT` cookie with HttpOnly and SameSite=Strict, without Secure. Each transport only reads its own cookie. HTTP still requires normal pairing and authorization, but provides neither encryption nor TLS server authentication; network observers may read screen content, actions and credentials. Native clients keep their existing TLS pinning flow.

With an empty access address allowlist, Host is restricted to the receiving IP and service port, so arbitrary domains are rejected. A nonempty list replaces this default with exact configured IP/domain matches at the service port; phone IPs and loopback must also be listed to remain usable. The list is checked for each request, including the landing page, identity, pairing, API and MCP. It checks the destination Host, not the client's source IP, and does not replace authentication or act as a source-IP firewall. Origin, when present, must match the request scheme, Host and port; same-site and cross-site API requests are rejected, with no CORS bypass. Only `GET /` permits cross-site navigation from Android's browser launcher, never pairing, scripts or APIs. An unlisted domain resolving to the phone is not allowed; allowing a domain does not allow its subdomains. IPv4 and IPv6 literals are supported. Allowlist entries do not provision DNS, routing, port forwarding, a reverse proxy or a public CA certificate.

Browser JavaScript cannot authenticate an untrusted HTTPS certificate on behalf of the browser. The browser's six-digit authorization code is not the native eight-digit SAS protocol and cannot replace certificate trust. Establish HTTPS browser certificate trust independently; do not blindly bypass its warning. The local app button opens `http://127.0.0.1:8443/` and is disabled if a nonempty allowlist omits `127.0.0.1`. Add that address or clear the list in Settings to restore it.

Validation includes Kotlin/Python transcript vectors, actual loopback TLS pinning,
substituted-key rejection before HTTP data, confirmation gating, migration, and
Android Keystore TLS/pairing/API/MCP instrumentation. These checks are regression
evidence, not an independent cryptographic audit.
