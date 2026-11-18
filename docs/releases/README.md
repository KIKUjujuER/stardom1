# Release notes

The prose of each release lives here, one file per version, named after the
**version** — `1.0.0.12.md`, not `v1.0.0.12.md`, the same convention the APK
and the `VERSION` file use.

`release.yml` reads the file that matches the tag it is building and uses it
as the body of the GitHub Release. A release without a file here still gets
published; it just gets the machine facts (the file name, the SHA-256 and the
signing certificate) and no prose.

Two placeholders are substituted before the text is published:

| Placeholder | Becomes | Used for |
|---|---|---|
| `{{TAG}}` | `v1.0.0.12` | the tag, e.g. in a link that is pinned to this release |
| `{{VERSION}}` | `1.0.0.12` | the version, without the `v` |

A screenshot is usually linked with `{{TAG}}`, so the image is pinned to the
release it belongs to and can never drift from it:

```markdown
![The on-screen keyboard](https://raw.githubusercontent.com/whereq/whereq-dosbox/{{TAG}}/resources/screenshot-phone.jpg)
```

Write the notes in the same commit as the code they describe — the release
script only stages `VERSION` and the manifest, so anything else has to be
staged by hand before the release:

```sh
git add docs/releases/1.0.0.12.md
bin/release.sh -m "Fix the native version and the default ABI list"
```

The same text, in the repository's own words, belongs in
[downloads/README.md](../../downloads/README.md) under *What changed in …*.
