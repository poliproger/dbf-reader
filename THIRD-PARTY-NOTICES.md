# Third-Party Notices

This plugin ("DBF Reader") bundles and redistributes the following third-party
software. Each component is the property of its respective owners and is used
under the terms of its own license, reproduced or referenced below.

---

## javadbf

- **Project:** javadbf
- **Coordinates:** `com.github.albfernandez:javadbf:1.14.1`
- **Source:** https://github.com/albfernandez/javadbf
- **Copyright:** © Alberto Fernández and the original javadbf authors
- **License:** GNU Lesser General Public License, version 3.0 (LGPL-3.0)
  - https://www.gnu.org/licenses/lgpl-3.0.html
  - (LGPL-3.0 incorporates the terms of the GNU GPL-3.0:
    https://www.gnu.org/licenses/gpl-3.0.html)

**Usage in this plugin:** javadbf is used **unmodified** and is **dynamically
linked** as a separate, self-contained library JAR located in the `lib/`
directory of the plugin distribution. It is not merged, shaded, or statically
combined with the plugin's own code. End users may replace the bundled JAR with
their own (including a modified) build of javadbf of a compatible version.

The complete corresponding source code of javadbf, including any obligations
under LGPL-3.0, is available at the project URL above.

---

The plugin's own source code is not covered by the licenses of the third-party
components listed here.