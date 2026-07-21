# Aggregator (mCard) — Credentials, Encrypted Storage & Cert-Based NFC

This document covers the changes that (1) encrypt the **entire** local database,
(2) store the patient's server-issued credential, and (3) use that credential for
certificate-based mutual authentication over NFC.

> **Status:** implemented, **not yet compiled** in this environment (no Android
> SDK was available). Build in Android Studio and address any first-sync issues
> (notably SQLCipher artifact resolution). See the error reference in §6.

Related: the backend that issues credentials is `Nursing-Backend/`
(`/api/patients/register` returns a `credentials` object). The CAD (NursingDevice)
has a mirror of this document.

---

## 1. What changed (files)

| File | Change |
|------|--------|
| `app/build.gradle.kts` | + `net.zetetic:android-database-sqlcipher:4.5.4`, `androidx.sqlite:sqlite-ktx:2.4.0` |
| `AggregatorSecureStore.kt` | **NEW** — `PinCrypto` (PBKDF2), in-memory `AggregatorSession` (passphrase + credential), `Credentials` DTO, `CredentialStore` |
| `AggregatorDatabase.kt` | whole DB SQLCipher-encrypted (`aggregator_secure.db`); + `credentials` table; `getInstance(context)` now requires the provisioned key |
| `PatientManager.kt` | dao made **lazy** (DB must not open before the PIN is provisioned) |
| `PatientRepository.kt` | parses `credentials`; returns `PatientRegistration(message, credentials)` |
| `AuthActivity.kt` + `res/layout/activity_auth.xml` | PIN field; provisions the key **before** any DB access; saves/loads credentials; verifies PIN by opening the DB |
| `MainActivity.kt` | guard: if the session is locked (after process death), redirect to login instead of crashing |
| `CloudSyncWorker.kt` | guard: skip/retry when the DB is locked |
| `CryptoUtils.kt` | `CERT_AUTH_ENABLED`, `getSessionPrivateKey()`, cert getters, `verifyPeerCert()` (+ `PeerCertException`) |
| `NfcAuth.kt` | **NEW** — reader-side certificate exchange |
| `MyHostApduService.kt`, `UpdateActivity.kt` | cert-exchange step wired into the NFC handshake |
| `app/src/main/assets/ca-certificate.pem` | **NEW** — the current CA certificate |

---

## 2. Storage model — whole DB encrypted

```
Encrypted Room (SQLCipher)   aggregator_secure.db     (new file; old plaintext DB abandoned)
  patients(...)          — patient registry
  patient_reports(...)   — received + cloud records, with sync status
  credentials(...)       — this patient device's keypair + cert

Plain SharedPreferences (needed BEFORE unlock, non-sensitive):
  PBKDF2 salt

In memory only (AggregatorSession):
  DB passphrase  = PBKDF2WithHmacSHA256(PIN + ":" + patientId, salt, 100k, 256-bit)
  credential     = loaded at login
```

**Destructive switch:** the encrypted DB uses a new file name, so any pre-existing
plaintext data from the old `aggregator_room.db` is abandoned (acceptable for the
prototype).

**Background-sync tradeoff:** because the key is in memory only, `CloudSyncWorker`
can read the DB only while the app process is alive after an unlock. If the process
was killed it retries; sync resumes on the next unlock. (Guarded — it does not
crash.)

---

## 3. Credential lifecycle

```
REGISTER (AuthActivity)
  provision key from PIN + patientId  (BEFORE any DB write)
  savePatient(...)                     (writes to the encrypted DB)
  POST /api/patients/register -> { patient, credentials }
  CredentialStore.saveFromServer(patientId, credentials)

LOGIN (AuthActivity)
  provision key from PIN + patientId
  open the DB (verifies the PIN) + CredentialStore.loadIntoSession()
  wrong PIN -> "Login failed: incorrect PIN (could not decrypt your data)"
```

---

## 4. NFC handshake (certificate-based)

`CryptoUtils.CERT_AUTH_ENABLED = true`. The tap:

```
SELECT
AUTH_CRT   both sides exchange + CA-verify certificates   (NfcAuth.exchangeCerts / card branch)
AUTH_KEY   session key encrypted to the peer's cert public key
AUTH_SIG   signed with our own private key; peer verifies + decrypts
DATA       AES/XOR-encrypted transfer (unchanged)
```

Implements the "Ideal NFC Handshake" from the repo's `AUTH.md` (cert exchange +
CA-signature + expiry check). Difference from that older sketch: the keypair is
**server-generated** and the private key is protected by the **PIN** (no
Keystore/StrongBox on target hardware). **Revocation is not checked in the offline
tap** — only CA signature + expiry (revocation is online via
`GET /api/credentials/verify/:ownerId`).

**Operational requirement:** because the Aggregator's HCE (card) role reads the
credential from memory, the patient app must be **open and logged in** during a tap
(the legacy hardcoded keys worked with the app closed). Set `CERT_AUTH_ENABLED =
false` in **both** apps to revert.

---

## 5. Build & run

```bash
cd Aggregator
./gradlew assembleDebug     # requires Android SDK + JDK (Android Studio)
```

---

## 6. Error reference (exact message → cause → fix)

| Message (Toast / NFC status / log) | Cause | Fix |
|---|---|---|
| `Login failed: incorrect PIN (could not decrypt your data)` | Wrong PIN for this device | Enter the PIN set at registration |
| `Login failed: could not open your encrypted data: …` | DB open error other than wrong PIN | Check logcat; verify SQLCipher native libs loaded |
| `Registered: … (no credentials)` | Server didn't return creds (e.g. backend CA missing) | Check backend `credentialError`; run `npm run generate-ca` |
| `Database is locked — provision the PIN first…` (crash/log) | DB accessed before login provisioned the key | Ensure the flow goes through `AuthActivity`; the `MainActivity` guard redirects to login |
| `No credential on this device — log in with your PIN first…` (NFC) | Session not unlocked | Open + log into the patient app before tapping |
| `Peer cert rejected: peer certificate is NOT signed by the trusted CA…` | Devices trust different CAs | Re-issue both under the same backend/CA; re-bundle the CA cert |
| `Peer cert rejected: peer certificate expired on …` | Peer credential expired | Rotate the peer's credential |
| `Certificate exchange rejected by peer (status 6A88 / 0000)…` | Peer couldn't complete cert exchange (often not logged in) | Ensure the peer app is open + logged in |
| `Authentication rejected by peer — signature/credential mismatch…` | Keys/certs don't correspond | Confirm both devices registered under the same CA and logged in |

`0000` = `UNKNOWN_CMD_SW` (this app's rejection); `6A88` = CAD's `FILE_NOT_READY_SW`.

---

## 7. Known limitations / follow-ups
- **Not compiled here** — build and fix any import/version issues first.
- **NFC needs both apps open + unlocked**, and background sync only runs while the process is alive post-unlock (both stem from the in-memory key). Optional future work: back the key with Android Keystore.
- Other activities besides `MainActivity` open the DB in `onCreate`; only `MainActivity` has the locked-session guard. Add the same guard elsewhere if a crash appears after process death.
- Changing the PIN re-derives a different key and cannot open the existing DB (no rekey) — prototype limitation.
- After hardware verification, delete the hardcoded keys and the legacy `else` branches.
