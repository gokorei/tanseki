# Third-party notices

Tanseki is licensed under the Apache License, Version 2.0 (see [LICENSE](LICENSE)).
This file records the third-party material that Tanseki incorporates, together with
the attribution those licenses require. Build-time dependencies carry their own
upstream licenses and are resolved from Maven Central, crates.io, and PyPI; they
are not reproduced here.

## TOON — Token-Oriented Object Notation

`service/src/main/kotlin/gokorei/tanseki/service/mcp/Toon.kt` encodes tool responses as
TOON. The format is **not implemented in this repository**: Tanseki depends on
JToon, the reference JVM implementation, and converts its own payloads onto that
library's API.

- Maven coordinates: `dev.toonformat:jtoon`
- Specification (implemented at v4.1): <https://github.com/toon-format/spec>
- Reference JVM implementation: <https://github.com/toon-format/toon-java>
- Project documentation: <https://toonformat.dev>

JToon is MIT licensed. It brings `tools.jackson.core:jackson-databind`,
`tools.jackson.module:jackson-module-blackbird`, and `org.jspecify:jspecify`
along with it; those carry their own licenses. The MIT license requires that its
copyright and permission notice be retained in all copies and substantial
portions of the software, and that notice is reproduced below in full.

```text
MIT License

Copyright (c) 2025-PRESENT Johann Schopplich

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

Apache-2.0 and the MIT license are compatible in this direction: an
MIT-licensed component may be distributed as part of an Apache-2.0 licensed
work, provided the MIT notice above travels with it, which is why it is
reproduced here rather than only referenced.

### Conformance

The TOON project publishes a shared conformance test suite at
<https://github.com/toon-format/spec/tree/main/tests>. Tanseki's own golden tests
in `service/src/test/kotlin/gokorei/tanseki/service/mcp/ToonEncodingTest.kt` pin the exact
bytes Tanseki emits, and are what catch a dependency upgrade that changes the MCP
wire format.

## Adding a notice

Any code, data, specification, or asset incorporated into Tanseki that carries an
attribution requirement must be recorded in this file, and — where the license
requires it in every copy — named in the file header of the code that uses it.
Do not remove an entry here to resolve a licensing question; resolve the
question and then record the outcome.