package net.exylia.exyliaEconomy.common;

import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.lib.ui.UiEntry;
import net.exylia.lib.ui.UiSession;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Redrawing a screen the player is already looking at.
 *
 * <p>Every admin screen is drawn from a snapshot: the context map and the rows
 * are decided when the menu opens. Reopening it re-sends every slot, replays the
 * open sound and drops whoever was on page two back onto page one; refreshing
 * the session alone redraws the same snapshot. {@link #update} writes the new
 * snapshot into the open session, then redraws it.
 *
 * <p>It answers {@code false} when the player is not looking at that menu —
 * after a prompt closed it, for instance — which is the caller's cue to open it.
 */
public final class Screens {

    private Screens() {
        throw new AssertionError("No instances.");
    }

    /**
     * Redraws an open menu from a new snapshot.
     *
     * @param player  who is looking
     * @param menuId  the menu's id, as it was opened with
     * @param context the values the menu draws from, or {@code null} to keep the ones it has
     * @param rows    the list's rows, or {@code null} for a menu with no list
     * @return {@code false} when that menu is not the one open, so nothing was drawn
     */
    public static boolean update(Player player, String menuId, Values context, List<UiEntry> rows) {
        UiSession session = ExyliaEconomy.getInstance().getMenus().session(player)
                .filter(open -> open.menuId().endsWith(menuId))
                .orElse(null);
        if (session == null) {
            return false;
        }
        if (context != null) {
            context.map().forEach(session::context);
        }
        if (rows != null) {
            // Keeps the page the reader was on, clamped to what still exists.
            session.entries(rows);
        }
        session.refresh();
        return true;
    }

    /** Redraws an open menu that has no list. */
    public static boolean update(Player player, String menuId, Values context) {
        return update(player, menuId, context, null);
    }
}
