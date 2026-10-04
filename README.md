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
| `/pay <player> <amount> [currency] [confirm]` | `exyliaeconomy.pay` | Send money. Above the confirmation amount it asks first: click **[✔ CONFIRM]** or add `confirm` within 30 seconds |
| `/withdraw <amount> [currency]` | `exyliaeconomy.withdraw` | Turn money into a banknote. Right-click it, or `/deposit` while holding it, to redeem |
| `/deposit` | `exyliaeconomy.use` | Redeem the banknote in your hand |
| `/paytoggle` | `exyliaeconomy.paytoggle` | Stop receiving payments, or start again. Kept in the database: every server, every restart |
| `/economy` (`/eco`) `balance\|wallet\|currencies\|top\|history\|exchange` | `exyliaeconomy.use` | Everything above, for any currency. `/eco top 2` is page 2 of the default currency |
| `/<currency> [player]` | `exyliaeconomy.use` | One currency's balance; naming a player needs `exyliaeconomy.others` |
| `/<currency> pay\|top\|history\|exchange` | `exyliaeconomy.use` | That currency's own command, named in `/economyadmin` (`pay` needs `exyliaeconomy.pay`) |
| `/<currency> give\|take\|set\|reset <player> [amount]` | `exyliaeconomy.admin` | Change a balance in that currency |
| `/economyadmin` (`/ecoadmin`, `/eadmin`) | `exyliaeconomy.admin` | Create and edit currencies |
| `/economyadmin currencies` | `exyliaeconomy.admin` | List every currency with its id and kind |
| `/economyadmin give\|take\|set\|reset <player> ...` | `exyliaeconomy.admin` | Change a balance, online or not |
| `/economyadmin giveall <currency> <amount> [confirm]` | `exyliaeconomy.admin` | Give an amount to every player on this server, after a confirmation, with a summary |
| `/economyadmin import <from> <into> [again]` | `exyliaeconomy.admin` | Add every balance of one currency to another |
| `/economyadmin log <player> [currency] [page]` | `exyliaeconomy.admin` | A player's ledger lines with their ids, ten per page |
| `/economyadmin export <currency\|all> [days]` | `exyliaeconomy.admin` | Write ledger lines to `plugins/ExyliaEconomy/exports/ledger-<scope>-<time>.csv`, off the game thread. `days` keeps only the last days; `0` or nothing is everything |
| `/economyadmin rollback <player> <window\|#id> [currency] [confirm]` | `exyliaeconomy.admin` | Revert a player's movements in a window (`1h`, `2d`) or one ledger line (`#42`), after a confirmation showing the net change |
| `/economyadmin reload` | `exyliaeconomy.admin` | Reload files, menus and currencies |

`exyliaeconomy.others` lets a player read somebody else's balance, wallet or history. `exyliaeconomy.use`, `exyliaeconomy.pay` and `exyliaeconomy.paytoggle` are given to everyone by default. `exyliaeconomy.paytoggle.bypass` (operators) pays players who turned payments off. `exyliaeconomy.admin` includes `use`, `pay`, `paytoggle`, `paytoggle.bypass` and `others`, and `exyliaeconomy.*` grants everything. A currency may also name its own permission.

`exyliaeconomy.withdraw` and `exyliaeconomy.interest` are given to everyone by default; `admin` includes `withdraw`.

`exyliaeconomy.top.exempt` leaves a player off every leaderboard, without taking a place. It is given to nobody by default, operators included. A permission cannot be checked for somebody offline, so it is stored as the player joins: granting or removing it applies from their next join, and within a minute once the board refreshes.

A currency whose leaderboard is turned off answers `top` with a message rather than an empty board.

### Notes for admins

- **Imports.** `/economyadmin import <from> <into>` runs once per pair. Adding `again` imports only the players not imported yet, so nobody is paid twice, even after an import that stopped partway.
- **Networked switch.** A stored currency can switch between networked and per-server only while nothing but starting balances would be left behind. Once balances have moved, the switch is locked.
- **Banknotes.** Each note is a row in `exylia_banknotes`, written before the money is taken; the item only carries its id and is never stackable. Redeeming takes the item from the hand, then claims the row in the database before paying, so a duplicated note pays once on the whole network and every other copy disappears with "already redeemed". A payment the currency refuses (its ceiling) gives the claim and the item back. A note of a per-server currency only redeems on the server that printed it. Notes cannot be crafted, traded to villagers or put through an anvil, grindstone, smithing table, loom, cartography table, stonecutter or crafter. `/withdraw` is refused before taking anything when the inventory is full. Ledger reasons: `note:withdraw`, `note:redeem`.
- **Rollback.** Each ledger line is reverted once, through the currency with reason `rollback`, and remembered in `exylia_economy_claims` (`rollback|<id>`), so a second rollback over the same window skips it. Payments (`pay`, `pay:*`), exchanges, banknotes (`note:*`) and earlier rollbacks are never reverted: their other side (the receiver, the other currency, the note) is not in this player's ledger line, so reverting one side would create money. The confirmation counts them as transfers left out. The ledger must be on, and a line merges consecutive changes with one reason, so it is reverted whole. A withdrawal from somebody offline is queued and floored at zero when it lands. A window looks at the newest 500 lines.
- **Interest.** Paid at each interval boundary to the players on each server, claimed per player and slot in `exylia_economy_claims`, so a networked currency never pays twice and a restart never repeats a slot. Claims are deleted after two days.
- **Hard ceiling.** No balance can pass 10^18 (1,000,000,000,000,000,000), whatever the currency's own ceiling, including `-1` for no ceiling. A change that would pass it is refused.

## Placeholders

| Placeholder | |
| --- | --- |
| `%economy_balance%`, `%economy_balance_<currency>%` | Balance, formatted |
| `%economy_compact_<currency>%` | Balance, short (`1.2k`) |
| `%economy_raw_<currency>%` | The number alone |
| `%economy_name_<currency>%`, `%economy_symbol_<currency>%` | How the currency is called (`name` is the plural) |
| `%economy_name_singular_<currency>%`, `%economy_name_plural_<currency>%` | One unit's name, several units' name |
| `%economy_rank_<currency>%` | Your place on the leaderboard, `—` when you are not in its first 100 |
| `%economy_total_<currency>%` | Every balance added up, the money supply, formatted. Leaderboard currencies only |
| `%economy_top_name_<place>_<currency>%`, `%economy_top_amount_<place>_<currency>%` | Leaderboard |

Leave the currency out for the default one. The leaderboard, rank and total are cached for a minute. MiniPlaceholders is not supported: ExyliaLib only bridges to PlaceholderAPI.

## Configuration

Everything is edited in game with `/economyadmin` and stored in the database, so every server of a network reads the same settings and an edit on one applies on all of them at once. The plugin writes no `config.yml`; the menus and messages stay files under `lang/`.

| Where | Setting | |
| --- | --- | --- |
| Settings | Language | `default` follows ExyliaLib, or `en`, `es`, `pt`; applies at once |
| Settings | Debug | Explain in the console what the plugin does |
| Settings | Offline pay notice | On join, tell a player what they were paid while they were offline (a player online on another server of the network gets no notice): one line per currency, with the payer's name or how many paid |
| Currency | Pay confirmation | The amount above which `/pay` asks for a confirmation; `0` never asks |
| Currency | Banknotes | Whether `/withdraw` may print it. Stored currencies only |
| Currency | Interest | Percent of the balance paid every interval (at least `1m`), capped per payout; `0` pays nothing. Online players need `exyliaeconomy.interest` |
| Currency | Interest while offline | Also pays every balance of players not online here, queued for whoever holds them; every server sweeps, each balance claimed once per slot, so a crash mid-sweep leaves nobody unpaid. Their permission cannot be checked |

A server upgrading from a version that had a `config.yml` has it imported into the database on the first start, where nothing is set yet, and renamed to `config.yml.migrated`. A `config.yml` holding only `language` is imported the same way. The language is handed to ExyliaLib from the database, so no `config.yml` is ever written again.

The banknote's look is the `banknote` section of `messages.yml` (`material`, `name`, `lore`), with `%amount%`, `%currency%`, `%issuer%` and `%date%`.

The confirmation is bound to the exact payer, receiver, currency and amount, and is used once. The offline notice is kept in its own table (`exylia_pay_notices`), not read from the ledger, so it works with the ledger turned off. The leaderboard screen shows the money supply in slot 45 of `menus/user/top.yml`; a file written before this version keeps its layout until you add the item or delete the file.

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

### Developer API

Depend on ExyliaEconomy (`depend` or `softdepend`) to listen to what its own commands are about to do. Both events are cancellable and fire on the player's thread, before any money moves:

| Event | When | |
| --- | --- | --- |
| `net.exylia.exyliaEconomy.api.event.EconomyPayEvent` | A player pays another with `/pay` or a currency's `pay`, after every rule passed | `payer()`, `receiver()`, `receiverName()`, `currency()`, `amount()`, `tax()` |
| `net.exylia.exyliaEconomy.api.event.EconomyExchangeEvent` | A player exchanges currencies | `getPlayer()`, `from()`, `to()`, `amount()` |

A cancelled event tells the player it was cancelled, unless you set `cancelMessage(line)`: your line instead, or `""` to say nothing because you already did.

```java
@EventHandler
public void onPay(EconomyPayEvent event) {
    if (inCombat(event.payer())) {
        event.setCancelled(true);
        event.cancelMessage("{error}You cannot pay anybody during combat.");
    }
}
```

What happened afterwards is ExyliaLib's `BalanceChangeEvent`, one per side of a payment; there is no separate post-payment event.

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
