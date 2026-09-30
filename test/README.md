# Item effect checks

`ItemEffectLifecycleTest` checks effect expiration and modifier calculation.
`ItemShieldEffectTest` checks shield hit consumption and its interaction with
expiration and cleanup. Both run without a game window and need only JDK 17,
with no additional test libraries.

From the repository root, run in PowerShell:

```powershell
javac -encoding UTF-8 -Xlint:all -d bin/effect-tests (Get-ChildItem -Recurse src -Filter *.java).FullName test/item/ItemEffectLifecycleTest.java test/item/ItemShieldEffectTest.java
java "-Djava.awt.headless=true" -cp bin/effect-tests item.ItemEffectLifecycleTest
java "-Djava.awt.headless=true" -cp bin/effect-tests item.ItemShieldEffectTest
```

In Bash:

```bash
javac -encoding UTF-8 -Xlint:all -d bin/effect-tests $(find src -name '*.java') test/item/ItemEffectLifecycleTest.java test/item/ItemShieldEffectTest.java
java -Djava.awt.headless=true -cp bin/effect-tests item.ItemEffectLifecycleTest
java -Djava.awt.headless=true -cp bin/effect-tests item.ItemShieldEffectTest
```

Each suite prints a `PASS` line with its scenario/check counts. Failed checks
throw an `AssertionError` and exit with a nonzero status; `-ea` is not required.

The scenarios cover neutral state, exact and overshot expiration boundaries,
independent timers, effect-ID ordering, level-long boosts, invalid time deltas,
configured magnitudes, repeated read-only queries, and reactivation after expiry.

Shield checks cover absent, active, exhausted, expired, and cleared shields,
configured charge counts, immutable pre-hit views, reapplication, and isolation
from other effects.

These suites do not verify drops, inventory integration, or in-game item behavior.
The existing CI compiles production code only; run this test explicitly.
