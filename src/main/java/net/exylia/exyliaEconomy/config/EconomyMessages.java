package net.exylia.exyliaEconomy.config;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.ConfigFile;

/** Everything a player or an admin reads, in {@code lang/<code>/messages.yml}: what the currency commands say. */
@Comment("Messages sent by ExyliaEconomy.")
@Comment("")
@Comment("Colours use palette tokens such as {primary}, {error} or {highlight},")
@Comment("so recolouring the server never means editing this file.")
@Comment("")
@Comment("A message may open with an effect tag, which is an instruction and never")
@Comment("reaches the screen: [sound:NAME|volume|pitch] plays a sound to the player.")
@Comment("")
@Comment("%prefix% is replaced by the 'prefix' value below.")
public record EconomyMessages(
        @Comment("Prefix substituted into every %prefix% in this file.")
        String prefix,
        String balance,
        String balanceOther,
        String paid,
        String received,
        String paySelf,
        String payDisabled,
        String payMinimum,
        String notEnough,
        String invalidAmount,
        String noCurrency,
        String noPermission,
        String notAvailable,
        String given,
        String givenNotify,
        String taken,
        String takenNotify,
        String set,
        String reset,
        String queued,
        String topHeader,
        String topLine,
        String topEmpty,
        String historyHeader,
        String historyLine,
        String historyEmpty,
        String exchanged,
        String exchangeFailed,
        String walletHeader,
        String walletLine,
        String currenciesHeader,
        String currenciesLine,
        String importStarted,
        String importDone,
        String usage,
        String currencyCreated,
        String currencyDeleted,
        String currencySaved,
        String currencyNotFound,
        String currencyExists,
        String currencyInvalidId,
        String currencyInvalidRates,
        String stillLoading,
        String importAlready,
        String importSame,
        java.util.Map<String, String> reasons,
        java.util.Map<String, String> kinds,
        String exchangeNotAllowed,
        String exchangeNoRate,
        String exchangeOverCeiling,
        String atCeiling,
        @Comment("A command or button the sender has no permission for.")
        String permissionDenied,
        @Comment("The economy has not started, so there is nothing to edit yet.")
        String economyOff,
        @Comment("Item and experience payments that waited for a player's join. Placeholder: %amount%.")
        String rewardsDelivered,
        @Comment("A deposit or set refused because the balance would pass its ceiling. Placeholder: %amount%.")
        String overCeiling,
        @Comment("An admin change or payment the currency refused. Placeholder: %reason%.")
        String refused,
        @Comment("An import between the same two currencies is already running.")
        String importRunning,
        @Comment("An import stopped partway; running it again skips whoever was already paid.")
        String importFailed,
        @Comment("The networked switch of a currency whose balances would be left behind. Placeholder: %id%.")
        String currencyNetworkedLocked,
        @Comment("A payment with a transfer tax. 'paid' is sent when there is none. Placeholders: %player% %amount% %tax%.")
        String paidTaxed,
        @Comment("Why a payment was refused when the currency named no reason of its own.")
        String payRefused,
        @Comment("A currency whose leaderboard is turned off, or that this plugin does not rank. Placeholder: %currency%.")
        String topDisabled,
        @Comment("A console command that needs a player name. Placeholder: %command%.")
        String playerRequired,
        @Comment("A currency change that could not be written to the database. Placeholder: %id%.")
        String currencyWriteFailed,
        @Comment("A payment another plugin cancelled without saying why.")
        String payCancelled,
        @Comment("An exchange another plugin cancelled without saying why.")
        String exchangeCancelled,
        @Comment("The receiver turned payments off with /paytoggle. Placeholder: %player%.")
        String payDisabledTarget,
        @Comment("/paytoggle, now refusing payments.")
        String payToggleOff,
        @Comment("/paytoggle, now accepting payments again.")
        String payToggleOn,
        @Comment("/paytoggle, or the receiver's setting, could not be read or saved.")
        String payToggleFailed,
        @Comment("A payment above the confirmation amount. Placeholders: %player% %amount% %command%.")
        String payConfirm,
        @Comment("A 'confirm' with nothing open that matches it: expired, or for another amount.")
        String confirmExpired,
        @Comment("On join, one payer paid you while you were away. Placeholders: %player% %amount%.")
        String offlinePaySingle,
        @Comment("On join, several players paid you while you were away. Placeholders: %count% %amount%.")
        String offlinePayMany,
        @Comment("Under the console leaderboard's header: every balance added up. Placeholder: %total%.")
        String topTotal,
        @Comment("/economyadmin giveall asking first. Placeholders: %amount% %count% %command%.")
        String giveAllConfirm,
        @Comment("/economyadmin giveall done. Placeholders: %amount% %count% %skipped%.")
        String giveAllDone,
        @Comment("/economyadmin giveall with nobody online.")
        String giveAllNobody,
        @Comment("")
        @Comment("The currency editor of /economyadmin: its prompts, forms and the words its screens show.")
        CurrencyAdmin admin) {

    public EconomyMessages() {
        this("{primary}&lECONOMY {letters_black}•<reset>",
                "%prefix% {letters}Your {highlight}%currency%{letters}: {success}%amount%",
                "%prefix% {highlight}%player%{letters}'s {highlight}%currency%{letters}: {success}%amount%",
                "[sound:ENTITY_EXPERIENCE_ORB_PICKUP|1.0|1.2]%prefix% {success}You paid {highlight}%amount% {success}to {highlight}%player%{success}.",
                "[sound:ENTITY_EXPERIENCE_ORB_PICKUP|1.0|1.4]%prefix% {highlight}%player% {success}paid you {highlight}%amount%{success}.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}You cannot pay yourself.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}{highlight}%currency% {error}cannot be sent to other players.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}The least you can send is {highlight}%amount%{error}.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}You need {highlight}%amount% {error}more {highlight}%currency%{error}.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}That is not an amount. Try {highlight}100{error}, {highlight}2.5k {error}or {highlight}1m{error}.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}There is no currency called {highlight}%currency%{error}.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}You may not use {highlight}%currency%{error}.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}{highlight}%currency% {error}is not available right now.",
                "%prefix% {success}Gave {highlight}%amount% {success}to {highlight}%player%{success}. {letters_black}Now: %balance%",
                "[sound:ENTITY_EXPERIENCE_ORB_PICKUP|1.0|1.4]%prefix% {success}You received {highlight}%amount%{success}.",
                "%prefix% {warning}Took {highlight}%amount% {warning}from {highlight}%player%{warning}. {letters_black}Now: %balance%",
                "[sound:BLOCK_NOTE_BLOCK_BASS|1.0|0.8]%prefix% {warning}{highlight}%amount% {warning}was taken from you.",
                "%prefix% {success}{highlight}%player%{success}'s {highlight}%currency% {success}is now {highlight}%amount%{success}.",
                "%prefix% {warning}{highlight}%player%{warning}'s {highlight}%currency% {warning}was reset.",
                "%prefix% {letters}Queued %amount% {letters}for {highlight}%player%{letters}. {letters_black}They are away; it lands when they are next seen.",
                "{primary}&lTOP {highlight}%currency% {muted}page %page%",
                " {letters_black}#%position% {highlight}%player% {letters_black}» {success}%amount%",
                " {letters_black}Nobody has any yet.",
                "{primary}&lHISTORY {highlight}%currency% {letters_black}· {letters}%player%",
                " {letters_black}%date% %delta% {letters_black}» {info}%reason% {letters_black}(%balance%)",
                " {letters_black}Nothing yet.",
                "[sound:ENTITY_EXPERIENCE_ORB_PICKUP|1.0|1.2]%prefix% {success}Exchanged {highlight}%from% {success}for {highlight}%to%{success}.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}%reason%",
                "{primary}&lWALLET {letters_black}· {letters}%player%",
                " {letters_black}▎ {letters}%currency% {letters_black}» {success}%amount%",
                "{primary}&lCURRENCIES",
                " {letters_black}▎ {highlight}%id% {letters_black}» {letters}%currency% {letters_black}(%kind%)",
                "%prefix% {letters}Importing balances from {highlight}%from% {letters}into {highlight}%currency%{letters}…",
                "%prefix% {success}Imported {highlight}%count% {success}balances into {highlight}%currency%{success}.",
                "%prefix% {letters}Usage{letters_black}: {highlight}/%command% {letters_black}[pay <player> <amount> | top | history | exchange <amount> <to>]",
                "[sound:ENTITY_EXPERIENCE_ORB_PICKUP|1.0|1.0]%prefix% {success}Currency {highlight}%id% {success}created.",
                "%prefix% {warning}Currency {highlight}%id% {warning}deleted.",
                "[sound:BLOCK_AMETHYST_BLOCK_CHIME|1.0|1.0]%prefix% {success}Currency {highlight}%id% {success}saved.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}Currency {highlight}%id% {error}not found.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}Currency {highlight}%id% {error}already exists.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}{highlight}%id% {error}is not a currency id. Use lowercase letters, digits and underscores, up to 32.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}Those rates could not be read: {highlight}%reason%{error}. Write them as {highlight}shards=0.01, gems=2{error}.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {warning}Your balance is still loading. Try again in a moment.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}Balances from {highlight}%from% {error}were already imported into {highlight}%currency%{error}. Add {highlight}again {error}to the command to import the players not imported yet.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}{highlight}%from% {error}and {highlight}%currency% {error}are the same currency, so there is nothing to import.",
                labels("api", "Plugin", "vault", "Plugin", "pay", "Payment", "pay:tax", "Transfer tax",
                        "pay:tax-refund", "Tax refund", "shop", "Shop", "shop:buy", "Shop purchase",
                        "shop:sell", "Shop sale", "worth", "Worth", "worth:sell", "Worth sale", "market", "Market", "market:buy", "Market purchase",
                        "market:sale", "Market sale", "market:fee", "Market fee", "auctions", "Auctions",
                        "auctions:bid", "Auction bid", "auctions:sale", "Auction sale", "orders", "Orders",
                        "trade", "Trade", "exchange", "Exchange", "admin", "Staff", "import", "Import"),
                labels("stored", "Server currency", "item", "Item", "experience", "Experience",
                        "external", "Another plugin", "unknown", "Unknown"),
                "That currency cannot be exchanged.",
                "There is no rate from %from% to %to%.",
                "That would take your %currency% over the most you can hold.",
                "The balance is at its ceiling of %amount%.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}You don't have permission to do that.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}The economy is not running.",
                "[sound:ENTITY_PLAYER_LEVELUP|1.0|1.4]%prefix% {success}Delivered {highlight}%amount% "
                        + "{success}payments that were waiting for you.",
                "That would take the balance over its ceiling of %amount%.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}%reason%",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {warning}That import is already running.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}The import into {highlight}%currency% {error}stopped partway."
                        + " Run it again with {highlight}again{error}: nobody is paid twice.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}{highlight}%id% {error}already holds balances, so it"
                        + " cannot switch between networked and per-server: they would be left behind.",
                "[sound:ENTITY_EXPERIENCE_ORB_PICKUP|1.0|1.2]%prefix% {success}You paid {highlight}%amount% {success}to"
                        + " {highlight}%player%{success}. {letters_black}Tax: {info}%tax%",
                "The payment was refused.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}{highlight}%currency% {error}has no leaderboard.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}Name a player{letters_black}: {highlight}/%command% <player>",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}Could not save {highlight}%id% {error}to the database."
                        + " Check the console and try again.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}The payment was cancelled.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}The exchange was cancelled.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {highlight}%player% {error}is not accepting payments right now.",
                "[sound:BLOCK_NOTE_BLOCK_BASS|1.0|0.8]%prefix% {warning}You no longer receive payments."
                        + " {letters_black}/paytoggle turns them back on.",
                "[sound:ENTITY_EXPERIENCE_ORB_PICKUP|1.0|1.2]%prefix% {success}You receive payments again.",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}That could not be checked right now. Try again in a moment.",
                "[sound:BLOCK_NOTE_BLOCK_PLING|1.0|1.2]%prefix% {warning}Send {highlight}%amount% {warning}to"
                        + " {highlight}%player%{warning}? <click:run_command:'%command%'><hover:show_text:'{letters}Click to"
                        + " send the payment'>{success}&l[✔ CONFIRM]</hover></click> {letters_black}30s ⌚",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}Nothing to confirm: it expired or changed. Run the command again.",
                "[sound:ENTITY_EXPERIENCE_ORB_PICKUP|1.0|1.4]%prefix% {letters}While you were away, {highlight}%player%"
                        + " {letters}paid you {success}%amount%{letters}.",
                "[sound:ENTITY_EXPERIENCE_ORB_PICKUP|1.0|1.4]%prefix% {letters}While you were away, you received"
                        + " {success}%amount% {letters}from {highlight}%count% {letters}players.",
                " {letters_black}▎ {letters}In circulation {letters_black}» {info}%total%",
                "[sound:BLOCK_NOTE_BLOCK_PLING|1.0|1.2]%prefix% {warning}Give {highlight}%amount% {warning}to each of the"
                        + " {highlight}%count% {warning}players online? <click:run_command:'%command%'><hover:show_text:"
                        + "'{letters}Click to give it to everybody'>{success}&l[✔ CONFIRM]</hover></click> {letters_black}30s ⌚",
                "[sound:ENTITY_EXPERIENCE_ORB_PICKUP|1.0|1.2]%prefix% {success}Gave {highlight}%amount% {success}to"
                        + " {highlight}%count% {success}players. {letters_black}Skipped: %skipped%",
                "[sound:ENTITY_VILLAGER_NO|1.0|1.0]%prefix% {error}Nobody is online.",
                new CurrencyAdmin());
    }

    /** The {@code admin} section: what {@code /economyadmin} asks and shows. */
    public record CurrencyAdmin(
            String iconPrompt,
            String idPrompt,
            String kindPrompt,
            String itemPrompt,
            String deletePrompt,
            String deleteStoredWarning,
            String appearanceTitle,
            String rulesTitle,
            String ratesTitle,
            String commandsTitle,
            String sortTitle,
            String name,
            String nameHint,
            String plural,
            String symbol,
            String symbolHint,
            String decimals,
            String decimalsHint,
            String decimalsProviderHint,
            String format,
            String formatHint,
            String compactFormat,
            String compactFormatHint,
            String start,
            String max,
            String maxHint,
            String permission,
            String permissionHint,
            String minimumTransfer,
            String tax,
            String taxHint,
            String rates,
            String ratesHint,
            String aliases,
            String aliasesHint,
            String sortOrder,
            String sortOrderHint,
            String none,
            String providerDecimals,
            String itemSet,
            String itemNotSet,
            String on,
            String off,
            String ledgerForever,
            String ledgerDays,
            String kindStored,
            String kindItem,
            String kindDisplay) {

        public CurrencyAdmin() {
            this("{warning}Choose an icon",
                    "{primary}Type an id for the currency, such as {highlight}gems{primary}:",
                    "{primary}What kind of currency is {highlight}%id%{primary}?",
                    "{warning}Put in the exact item this currency is",
                    "{error}Delete the currency {highlight}%id%{error}?",
                    " {letters}Balances stay in the database, but nobody can use them until a currency"
                            + " with this id exists again.",
                    "{primary}&lAPPEARANCE {letters_black}» {highlight}%id%",
                    "{primary}&lRULES {letters_black}» {highlight}%id%",
                    "{primary}&lEXCHANGE RATES {letters_black}» {highlight}%id%",
                    "{primary}&lCOMMANDS {letters_black}» {highlight}%id%",
                    "{primary}&lSORT ORDER {letters_black}» {highlight}%id%",
                    "Name",
                    "One unit. Colour placeholders work.",
                    "Plural",
                    "Symbol",
                    "Blank for none.",
                    "Decimals",
                    "0 for whole numbers, up to 8.",
                    "0 to 8, or -1 to keep what the provider says.",
                    "Format",
                    "%amount% %symbol% %name%",
                    "Compact format",
                    "The same for scoreboards, where %amount% reads 1.2k.",
                    "Starting balance",
                    "Balance ceiling",
                    "-1 for no ceiling.",
                    "Permission",
                    "Needed to use its commands. Blank: nobody needs one.",
                    "Least a player may send",
                    "Transfer tax, percent",
                    "Kept back from every transfer. 0 for none.",
                    "Rates",
                    "other_id=rate, separated by commas. shards=0.01 means 1 of this is 0.01 shards.",
                    "Command names",
                    "Separated by commas, without the slash. The first is the command, the rest its aliases.",
                    "Sort order",
                    "Lower is shown first, in wallets and lists.",
                    "{letters_black}None",
                    "{letters_black}Provider's",
                    "{success}Set",
                    "{error}Not set",
                    "{success}✔ On",
                    "{error}✘ Off",
                    "Forever",
                    "%days% days ⌚",
                    "Stored",
                    "Item",
                    "Display only");
        }
    }

    /** A reason or a kind as players read it: the exact id first, then what comes before its colon. */
    public String label(java.util.Map<String, String> labels, String id) {
        if (id == null || id.isBlank()) return labels.getOrDefault("api", "");
        String exact = labels.get(id);
        if (exact != null) return exact;
        String prefix = id.contains(":") ? id.substring(0, id.indexOf(':')) : id;
        String known = labels.get(prefix);
        if (known != null) return known;
        String words = prefix.replace('-', ' ').replace('_', ' ');
        return words.isEmpty() ? id : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static java.util.Map<String, String> labels(String... pairs) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) map.put(pairs[index], pairs[index + 1]);
        return map;
    }

    private static ConfigFile<EconomyMessages> file;

    /** Keeps the loaded file so {@link #get()} reads the current snapshot. */
    public static void install(ConfigFile<EconomyMessages> loaded) {
        file = loaded;
    }

    /** The current snapshot, or the defaults before the file is read: never {@code null}. */
    public static EconomyMessages get() {
        return file == null ? new EconomyMessages() : file.get();
    }
}
