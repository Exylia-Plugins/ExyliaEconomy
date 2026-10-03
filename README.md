# ExyliaEconomy

Server currencies for Paper and Folia, kept in the database, shared across a network and edited entirely in game.

- **Stored currencies**: as many as you like (dollars, gems, shards...), each with its name, symbol, decimals, format, starting balance and ceiling. Balances work for offline players and across servers.
- **Network or per server**: a currency is either one balance across the whole network, or a separate balance on each server that shares the database.
- **Safe across servers**: only the server a player is on writes their balance; the others queue the change, and it lands exactly once. Ownership moves between servers with a compare-and-set, so two servers never write over each other.
- **Item and experience currencies**: an exact item in the inventory, or XP levels and points, usable wherever a currency is.
- **Rules per currency**: permission, transfers on or off, least amount and tax per transfer, leaderboard, and exchange rates to other currencies.
- **Ledger**: every change written down with its reason, shown in `/economy history`, with a retention you choose.
- **Vault**: one currency can be the server's Vault economy, for every plugin that only speaks Vault. It stays below EssentialsX or CMI unless you force it.
- **A command per currency**: `/gems`, `/shards`... each with `pay`, `top`, `history` and `exchange`.
- **Screens**: a wallet, leaderboards and the history for players; the whole currency editor for admins, in `/economyadmin`.
- **Placeholders** for balances and leaderboards, and the money supply handed to ExyliaAnalytics when it is installed.

## Requirements

- Paper or Folia 1.21+, Java 21
- [ExyliaLib](https://github.com/DiGround-s/ExyliaLib/releases), the version in `gradle.properties` or newer

Optional: Vault, PlaceholderAPI, ExyliaAnalytics.

## Commands and permissions

| Command | Permission | |
| --- | --- | --- |
| `/balance [player]` (`/bal`, `/money`) | `exyliaeconomy.use` | Your balance in the default currency |
| `/wallet [player]` (`/balances`) | `exyliaeconomy.use` | Every balance on one screen |
| `/baltop [page]` (`/balancetop`, `/moneytop`) | `exyliaeconomy.use` | The richest players: a screen for players, ten lines per page for the console |
| `/pay <player> <amount> [currency]` | `exyliaeconomy.pay` | Send money |
| `/economy` (`/eco`) `balance\|wallet\|currencies\|top\|history\|exchange` | `exyliaeconomy.use` | Everything above, for any currency. `/eco top 2` is page 2 of the default currency |
| `/<currency> [player]` | `exyliaeconomy.use` | One currency's balance; naming a player needs `exyliaeconomy.others` |
| `/<currency> pay\|top\|history\|exchange` | `exyliaeconomy.use` | That currency's own command, named in `/economyadmin` (`pay` needs `exyliaeconomy.pay`) |
| `/<currency> give\|take\|set\|reset <player> [amount]` | `exyliaeconomy.admin` | Change a balance in that currency |
| `/economyadmin` (`/ecoadmin`, `/eadmin`) | `exyliaeconomy.admin` | Create and edit currencies |
| `/economyadmin currencies` | `exyliaeconomy.admin` | List every currency with its id and kind |
| `/economyadmin give\|take\|set\|reset <player> ...` | `exyliaeconomy.admin` | Change a balance, online or not |
| `/economyadmin import <from> <into> [again]` | `exyliaeconomy.admin` | Add every balance of one currency to another |
| `/economyadmin reload` | `exyliaeconomy.admin` | Reload files, menus and currencies |

`exyliaeconomy.others` lets a player read somebody else's balance, wallet or history. `exyliaeconomy.use` and `exyliaeconomy.pay` are given to everyone by default. `exyliaeconomy.admin` includes `use`, `pay` and `others`, and `exyliaeconomy.*` grants everything. A currency may also name its own permission.

A currency whose leaderboard is turned off answers `top` with a message rather than an empty board.

### Notes for admins

- **Imports.** `/economyadmin import <from> <into>` runs once per pair. Adding `again` imports only the players not imported yet, so nobody is paid twice, even after an import that stopped partway.
- **Networked switch.** A stored currency can switch between networked and per-server only while nothing but starting balances would be left behind. Once balances have moved, the switch is locked.
- **Hard ceiling.** No balance can pass 10^18 (1,000,000,000,000,000,000), whatever the currency's own ceiling, including `-1` for no ceiling. A change that would pass it is refused.

## Placeholders

| Placeholder | |
| --- | --- |
| `%economy_balance%`, `%economy_balance_<currency>%` | Balance, formatted |
| `%economy_compact_<currency>%` | Balance, short (`1.2k`) |
| `%economy_raw_<currency>%` | The number alone |
| `%economy_name_<currency>%`, `%economy_symbol_<currency>%` | How the currency is called |
| `%economy_top_name_<place>_<currency>%`, `%economy_top_amount_<place>_<currency>%` | Leaderboard |

Leave the currency out for the default one.

## For developers

A plugin needs only **ExyliaLib** to read and pay money, never ExyliaEconomy. Every currency registers with ExyliaLib's `Economy` facade, and so does Vault, PlayerPoints or anything else that does:

```java
import net.exylia.lib.economy.Economy;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.economy.Transaction;

// Pay 50 gems; the reason is what the ledger and the logs show.
EconomyResponse paid = Economy.of("gems").deposit(player.getUniqueId(), new BigDecimal("50"),
        Transaction.of("myplugin:reward").by(player.getUniqueId()));

// Charge the default currency, whichever it is.
String money = Economy.defaultId();
if (Economy.of(money).withdraw(uuid, price, Transaction.of("myplugin:buy")).isSuccess()) { ... }

// List the currencies a player may use, in the order the admin arranged them.
for (String id : Economy.ordered()) {
    if (Economy.canUse(player, id)) { ... }
}
```

`Economy.kind(id)` says whether a currency is stored, an item, experience or another plugin's. Every change to a stored balance fires `BalanceChangeEvent`, once, on the server that wrote it, with its `Transaction`.

## Moving from ExyliaSurvivalCore

ExyliaEconomy is the economy module of ExyliaSurvivalCore, as its own plugin. On its first start next to ExyliaSurvivalCore it brings over, once:

- **The database.** `plugins/ExyliaSurvivalCore/database.yml` is copied here, so ExyliaEconomy opens **the same database** the survival core used, with the same Redis server id; an embedded H2 file stays where it is. The balances, pending changes and ledger are read from the tables they are already in. Do not point ExyliaEconomy at another database: the balances are in that one.
- **The currencies and settings**, copied from `sc_currencies` and `sc_economy_settings` into `exylia_currencies` and `exylia_economy_settings`. The old tables are never deleted.
- **Messages and the players' menus** (wallet, top, history), where this plugin has none of its own.

**Update every server of a network together.** Servers tell each other about balance changes on a Redis channel whose key includes the plugin's name, so a server still running the old module does not hear one running ExyliaEconomy, and the reverse.

The old permissions (`exyliasurvivalcore.economy`, `.pay`, `.others`, `.admin`) still grant the new ones.

## Download

Download `ExyliaEconomy.jar` from the [latest release](https://github.com/Exylia-Plugins/ExyliaEconomy/releases/latest) and put it in `plugins/`.

ExyliaLib is installed automatically on the first start if it is missing: the plugin downloads it and asks for one restart. From then on ExyliaLib keeps itself and ExyliaEconomy up to date, applying new releases on restart. Turn that off with `plugin-updates` in `plugins/ExyliaLib/config.yml`.

## Building

```
./gradlew build
```

The jar is written to `build/libs/`. See [CONTRIBUTING.md](CONTRIBUTING.md) for building against a local ExyliaLib.

## License

ExyliaEconomy is free software under the [GNU General Public License v3.0](LICENSE).
