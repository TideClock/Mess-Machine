# The engine in Kotlin Multiplatform: rules

Nov 23 2026. The plain-Java engine (`app/src/main/java/com/tideclock/messmachine/engine/`, 15 files) moves to common
Kotlin in `core/src/commonMain/kotlin/com/tideclock/messmachine/engine/`, same package, so Android, iOS and the web run
one engine. These rules keep the conversion a translation, not a rewrite.

## The two promises

1. **Same behaviour.** Line by line translation. Same maths in the same order, same float/double/int types, same
   constants, same seeds. No clean-ups, no renames, no "improvements". Comments stay (shorten nothing, add nothing
   but a note where a Java-only construct had to change).
2. **Same public API.** Every public class, nested class, field, method, constant and constructor keeps its name,
   parameter order and types, so the Java engine test (`tools/EngineTest.java`), `shared/fixtures/RoundTrip.java` and
   the app's Kotlin code keep compiling. Package-private Java members become `internal` only if nothing outside the
   file's own engine uses them; when in doubt make them public.

## Common Kotlin only

No `java.*`, `javax.*`, `android.*`, `System.*`, `Math.*`, `Integer.*`, `Character.*`, `String.format`,
`synchronized`, `Thread`, checked streams. Everything must compile for JavaScript too (`tools/kt-check.sh js`).
`core/.../Plat.kt` supplies what is missing:

| Java | Kotlin |
| --- | --- |
| `System.nanoTime()` | `nanoTime()` (Plat.kt; only differences mean anything) |
| `java.time.Instant.now().toString()` | `isoNow()` |
| `new java.util.Random(seed)`, `new Random()` | `JavaRandom(seed)`, `JavaRandom()`: bit-exact nextInt, nextInt(n), nextDouble, nextFloat, nextLong, nextBoolean |
| `ConcurrentLinkedQueue` | `ConcurrentQueue<T>`: add, poll, isEmpty, clear (one consumer thread) |
| `ConcurrentHashMap` | `CowMap<K, V>` (a MutableMap; reads never lock; `putIfAbsent`, `snapshot()`) |
| `CopyOnWriteArrayList` | `CowList<T>` (a MutableList; iterates a snapshot; `removeWhere`) |
| `AtomicInteger` | `AtomicCounter`: get, set, incrementAndGet, decrementAndGet, addAndGet |
| `String.format(Locale.US, "%.1f", v)` | `fixed(v, 1)` (matches Java digit for digit, including "-0.0") |
| `Integer.toHexString(i)` | `hexOf(i)` |
| `IOException` | `EngineIOException` (throw it where Java threw IOException) |
| `InputStream` opener | `SoundFiles` (`read(path): ByteArray?`; a path starting with "/" is a full device path) |
| `volatile` | `@Volatile` (kotlin.concurrent.Volatile) |
| `synchronized` method / block | no locks: compute and publish a finished value (an `AtomicReference`, a `CowMap`); a rare duplicate computation is fine, a torn value is not |
| `SoftReference` | a plain reference (say so in a comment) |

Streams become byte arrays: a reader takes `ByteArray`, a writer returns `ByteArray` (or appends to a
`ByteArrayBuilder`-style helper you write in the same file). The JVM caller wraps its own stream.

## Translation traps (each one has bitten someone)

- `Math.round(double)` returns long: `x.roundToLong()`. `Math.round(float)` returns int: `x.roundToInt()`.
  `Math.rint` is `kotlin.math.round`. Never use `kotlin.math.round` for `Math.round`.
- `Math.floorMod(a, b)` is `a.mod(b)`; `Math.floorDiv` is `a.floorDiv(b)`. `%` stays `%`.
- `Math.log` is `ln`, `Math.pow(a, b)` is `a.pow(b)` on Double (promote first if Java promoted), `Math.abs/min/max`
  are `abs`, `minOf`, `maxOf` (types must match Java's chosen overload: `Math.max(int, float)` is float).
- Java promotes `float op double` to double and `int op long` to long. Kotlin does the same in arithmetic, but
  assignment needs `.toFloat()` / `.toInt()`: Java's `f += d` is `f = (f + d).toFloat()`, `i += 0.5f` is
  `i = (i + 0.5f).toInt()`. Integer division stays integer.
- Casts: `(int) d` is `d.toInt()`, `(float) d` is `d.toFloat()`, `(byte) i` is `i.toByte()`, `(short)` `toShort()`,
  `(char) i` is `i.toChar()`, `(long)` `toLong()`. `char + int` in Java is an int; in Kotlin it is a Char, so write
  `c.code + 1`. `c - '0'` is fine in both.
- Bits: `>>>` is `ushr`, `>>` `shr`, `<<` `shl`, `&` `and`, `|` `or`, `^` `xor`, `~` `inv()`. Mind precedence:
  `(a and b) == 0` needs the parentheses.
- Bytes are signed in both; `b & 0xFF` is `b.toInt() and 0xFF`.
- `str.split(",")` in Java is a regex and **drops trailing empty strings**; Kotlin's `split(",")` is literal and keeps
  them. Java `split(",")` is `split(",").dropLastWhile { it.isEmpty() }`; Java `split(",", -1)` is `split(",")`;
  Java `split("\\.")` is `split(".")`. A regex split is `split(Regex(...))`.
- `==` on objects in Java is identity: `===`. `equals` is `==`.
- `s.charAt(i)` is `s[i]`; `sb.setLength(0)` is `sb.clear()`; `new String(b, UTF_8)` is `b.decodeToString()`;
  `s.getBytes(UTF_8)` is `s.encodeToByteArray()`; `toUpperCase()` is `uppercase()`; `Character.isDigit(c)`
  is `c.isDigit()`; `Integer.parseInt(s)` is `s.toInt()`; `Long.parseLong(h, 16)` is `h.toLong(16)`;
  `Float.parseFloat` is `toFloat()`; `Double.parseDouble` is `toDouble()`; `Float.isNaN(x)` is `x.isNaN()`.
  `String.valueOf(x)` is `x.toString()`.
- Arrays: `new float[n]` is `FloatArray(n)`, `new float[a][b]` is `Array(a) { FloatArray(b) }`, `new float[n][]`
  is `arrayOfNulls<FloatArray>(n)`, `Arrays.fill(a, v)` is `a.fill(v)`, `System.arraycopy(s, sp, d, dp, n)` is
  `s.copyInto(d, dp, sp, sp + n)`, `a.clone()` is `a.copyOf()`, `Arrays.sort(a)` is `a.sort()`,
  `Collections.sort(l)` is `l.sort()`, `TreeSet` is a set you sort when reading.
- `for (Map.Entry<K, V> e : m.entrySet())` is `for ((k, v) in m)` (or `m.entries`), `keySet().removeIf(p)` is
  `keys.removeAll(p)`.
- A Java `switch` with fall-through needs care; `switch` on strings is `when`.
- `Object[]` command arrays stay `Array<Any?>`; unboxing `(Integer) c[1]` is `c[1] as Int`.

## API shape (so Java and the app keep compiling)

- `public static final` primitive or String constant: `const val` in the companion (Java still sees `Project.NPADS`).
  Other static finals (arrays, maps, objects): `@JvmField val` in the companion.
- Static methods: `@JvmStatic fun` in the companion. A class with only statics (Json, Theory, Wav, Flac, MidiOut,
  Bake, KeyFinder) becomes an `object` with `@JvmStatic` on every public function and `@JvmField` / `const` on fields.
- Public fields: `@JvmField var` (or `val` for final). Never turn a field into a property Java would see as
  `getX()`.
- Java overloads become one function with defaults plus `@JvmOverloads`, or stay separate overloads.
- Interfaces with one method used as lambdas: `fun interface`.
- Nested static classes: plain nested classes. Inner (non-static) classes: `inner class`.
- Methods that throw: `@Throws(Exception::class)`.
- Nullability: a return is nullable only where the Java can return null; a parameter is nullable only where Java
  callers pass null. Check how `app/src/main/java/com/tideclock/messmachine/**/*.kt` and
  `app/src/test/**/*.kt` use the API: they must keep compiling. Arrays of nullable things stay nullable.
- Public fields that are arrays keep their exact type (`Array<String>`, `FloatArray`, `Array<PadOpts?>`, ...).

## Checking

`tools/kt-check.sh Name1 Name2` compiles your Kotlin files with the Java versions of the rest, compiles the Java
engine test against the result and runs it plus the shared fixture round trip. Done means `ALL GOOD` and
`ALL PASS`. `tools/kt-check.sh js` proves the whole of `core/src/commonMain` is common Kotlin (run once everything is
converted). For a test that needs a different call (a stream became bytes), copy the test to your own scratch file,
change only those calls, and run with `ENGINETEST=<copy>` or `ROUNDTRIP=<copy>`; report the exact lines changed.
