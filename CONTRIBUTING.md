# Contributing to ExyliaEconomy

Thanks for helping. Issues and pull requests are welcome.

## Building

You need Java 21. Then:

```
./gradlew build
```

The plugin jar is written to `build/libs/`. ExyliaLib is downloaded from its GitHub releases at the version in `gradle.properties`; a copy published locally with `./gradlew publishToMavenLocal` in ExyliaLib takes its place, so a change to both can be built together.

To have the jar written straight into a test server, set `exyliaOutputDir=/path/to/server` in `~/.gradle/gradle.properties`: the jar goes to its `plugins/` folder.

## Guidelines

- Everything written down is in English: code, comments, configuration, messages, commits.
- Player-facing text uses ExyliaLib's palette tokens (`{primary}`, `{letters}`, `{highlight}`...) rather than colours, so a server can recolour every Exylia plugin at once.
- Keep public configuration keys, placeholders, permissions and API stable. If one has to change, say so in the pull request.
- Generic mechanisms belong in [ExyliaLib](https://github.com/DiGround-s/ExyliaLib), not in one plugin.
- Add a test for logic that can break: parsing, money, migrations.
- Commit messages are one line: `type: short description` (`feat`, `fix`, `refactor`, `docs`, `test`, `chore`).

## License

By contributing you agree that your contribution is licensed under the GNU General Public License v3.0, like the rest of the project.
