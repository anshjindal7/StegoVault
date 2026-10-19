# StegoVault

A command-line password vault that **encrypts** each login and then **hides** it inside an ordinary-looking
image. Every website gets its own PNG; without the master password, the image is just a picture.

```
====== StegoVault ======
1. Save a login
2. Show a login
3. List saved websites
4. Delete a login
5. Exit
```

## Quick start

You need Java 17 or newer (`brew install openjdk@21` on macOS).

```bash
./build.sh run            # start the app; logins are kept in ./vault
./build.sh run ~/secrets  # use a different vault folder
./build.sh test           # run the unit tests
```

The first time, you choose a master password. When saving a login you can drag a photo into the terminal to
hide it there, or press Enter to have StegoVault generate an abstract image. The result is always saved as
PNG, because JPEG compression would destroy the hidden bits.

## How it works

Saving a login runs through three layers:

```
 username + password
        │  Credential.toBytes()     length-prefixed UTF-8 fields
        ▼
   plaintext bytes
        │  VaultCrypto.encrypt()    PBKDF2-HMAC-SHA256 → 256-bit key → AES-256-GCM
        ▼
 encrypted blob (random-looking)
        │  LsbStego.embed()         one bit in the lowest bit of each R, G, B value
        ▼
   cover image → vault/<website>.png
```

Reading a login runs the same layers backwards.

### 1. Key derivation: PBKDF2

A password isn't a key: it's too short and too predictable. **PBKDF2** stretches it into a 256-bit AES key
by running HMAC-SHA256 600,000 times. That takes a fraction of a second for you, once, but every guess an
attacker makes costs them the same work, so trying millions of passwords becomes very slow.

A random 16-byte **salt** is mixed in, and it's different for every image. The same password gives a
different key each time, and an attacker can't precompute a table of common passwords' keys.

### 2. Encryption: AES-256-GCM

**AES** is the standard block cipher. **GCM** is a mode that encrypts and also *authenticates*: it adds a
16-byte tag that only someone with the key could have computed. On decryption the tag is checked first, so:

- a wrong master password is detected, instead of quietly decrypting to garbage;
- changing even one bit of the hidden data is detected as well.

GCM needs a 12-byte **nonce** (number used once) that must never repeat under the same key. StegoVault makes
a new random one for every save, and the key is new every time anyway because the salt is.

That's also how the app checks your master password at startup: it tries to decrypt one image. There is no
stored password or hash to steal; the encrypted images are the only check.

### 3. Hiding: LSB steganography

Each colour value is a number from 0 to 255. Its **least significant bit** (LSB) only decides whether the
value is even or odd, so changing it moves the colour by 1, which nobody can see. Every pixel has red, green
and blue, so it carries 3 hidden bits. StegoVault writes a 32-bit length followed by the encrypted bytes,
pixel by pixel from the top-left.

**Steganography alone is not security.** Anyone who knows the layout can read the bits back out. Before this
version, StegoVault hid the password with no encryption at all, so anyone with the image could read it.
Hiding is the second layer: it keeps the vault from *looking* like a vault.

### File format

The bits hidden in the image decode to:

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | Length of everything below (bytes) |
| 4 | 4 | Magic `SVLT` |
| 8 | 1 | Format version (`1`) |
| 9 | 4 | PBKDF2 iteration count |
| 13 | 16 | Salt |
| 29 | 12 | AES-GCM nonce |
| 41 | n + 16 | Ciphertext + authentication tag |

The magic, version, iteration count, salt and nonce aren't secret, but they're passed to GCM as
"additional authenticated data", so they can't be changed without the tag check failing.

## Limitations (good next steps)

- **File names reveal which sites you have logins for** (`github.com.png`). Naming files by an HMAC of the
  website would hide that.
- **Sequential LSB replacement is detectable** by statistical tests (chi-square, RS analysis) when someone
  is specifically looking. Spreading the bits over pseudo-random pixels chosen by a key, or using
  *LSB matching* (±1 instead of overwriting), makes it harder.
- **Images must stay lossless.** Re-saving as JPEG, resizing or screenshotting destroys the data. Sending
  the PNG through most messaging apps does too, because they recompress images.
- **Passwords are briefly in memory as Java `String`s**, which can't be wiped. The master password is kept
  as a `char[]` and cleared on exit.
- **There's no way to change the master password** yet. It would mean decrypting and re-encrypting every image.

## Project layout

```
src/
  MainApp.java         command-line menu
  Terminal.java        input; hides typed passwords in a real terminal
  Vault.java           folder of images: store, retrieve, list, delete
  Credential.java      one login and its byte format
  VaultCrypto.java     PBKDF2 + AES-GCM
  LsbStego.java        hide/extract bytes in pixel LSBs
  CoverImage.java      load a photo or generate a cover image
  VaultException.java  wrong password / not a vault image
  Deletable.java       interface Vault implements
test/                  JUnit 5 tests for each layer
lib/                   JUnit standalone runner
build.sh               build, test and run without an IDE
```

The project also opens directly in VS Code with the Java extension pack.
