# Item effect checks

`ItemEffectLifecycleTest` checks effect expiration and modifier calculation without
opening a game window. It needs only JDK 17, with no additional test libraries.

From the repository root, run in PowerShell:

```powershell
javac -encoding UTF-8 -Xlint:all -d bin/effect-tests (Get-ChildItem -Recurse src -Filter *.java).FullName test/item/ItemEffectLifecycleTest.java
java "-Djava.awt.headless=true" -cp bin/effect-tests item.ItemEffectLifecycleTest
```

In Bash:

```bash
javac -encoding UTF-8 -Xlint:all -d bin/effect-tests $(find src -name '*.java') test/item/ItemEffectLifecycleTest.java
java -Djava.awt.headless=true -cp bin/effect-tests item.ItemEffectLifecycleTest
```

Expected output: `PASS: 7 scenarios, 107 checks`. Failed checks throw an
`AssertionError` and exit with a nonzero status; `-ea` is not required.

The scenarios cover neutral state, exact and overshot expiration boundaries,
independent timers, effect-ID ordering, level-long boosts, invalid time deltas,
configured magnitudes, repeated read-only queries, and reactivation after expiry.

This first part does not test `tryBlockHit()`, which remains unimplemented.
It also does not verify drops, inventory integration, or in-game item behavior.
The existing CI compiles production code only; run this test explicitly.
