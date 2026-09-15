package org.nekopur.network;

import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

/**
 * A sidebar objective whose only purpose is to exercise the seamless teardown.
 *
 * <p>When Purroxy keeps a client in PLAY it has to remove, by hand, every objective and team the
 * source backend had shown it - there is no JoinGame to wipe the board. Writing those packets with
 * the client still in PLAY means a wrong packet id is not a silent no-op but an encoder exception
 * that drops the player mid-switch, so the ids matter and only a live switch proves them.
 *
 * <p>Every capture on this network so far has reported "0 objectives and 0 teams", because nothing
 * here displays a scoreboard. That left `set_objective` as the one id in the 1.20.1 table resting
 * on enumeration alone rather than on an observed packet. This puts an objective on the board so
 * that path is taken.
 *
 * <p>Registered on the main scoreboard, which every player uses unless something assigns them
 * another, so it needs no join listener: the server sends the objective and its display slot to
 * each client as they join. Off by default - it is visible to players, and it is a test fixture
 * rather than a feature.
 */
public final class DebugScoreboard {

    /** Short, and prefixed so it cannot collide with an objective a plugin owns. */
    private static final String NAME = "nekopur_dbg";

    private DebugScoreboard() {
    }

    /**
     * Puts the debug objective on the main scoreboard, replacing any previous copy of it.
     *
     * <p>Never throws: this is a diagnostic, and a server must not fail to start because a test
     * fixture could not be installed.
     *
     * @param logger the server logger
     */
    static void install(Logger logger) {
        try {
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                logger.warning("Nekopurr debug scoreboard skipped: no scoreboard manager yet.");
                return;
            }
            Scoreboard board = manager.getMainScoreboard();
            // The main scoreboard is persisted with the world, so a restart finds the previous one
            // still registered. Unregister rather than reuse, so a changed display name takes.
            Objective existing = board.getObjective(NAME);
            if (existing != null) {
                existing.unregister();
            }
            Objective objective = board.registerNewObjective(NAME, "dummy", "Nekopur debug");
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            // A displayed objective with no scores renders as an empty sidebar, and an empty
            // sidebar is not obviously present. One line makes the fixture visible to the tester.
            objective.getScore("seamless").setScore(1);
            logger.info("Nekopurr debug scoreboard installed as '" + NAME
                + "'. This is a test fixture for the seamless objective teardown, visible to"
                + " players; set debug.scoreboard to false in Nekopurr.yaml to remove it.");
        } catch (Exception failure) {
            logger.warning("Nekopurr debug scoreboard could not be installed: " + failure);
        }
    }

    /** Removes the objective if it is present, so turning the flag off actually clears the board. */
    static void remove(Logger logger) {
        try {
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }
            Objective existing = manager.getMainScoreboard().getObjective(NAME);
            if (existing != null) {
                existing.unregister();
                logger.info("Nekopurr debug scoreboard removed.");
            }
        } catch (Exception failure) {
            logger.warning("Nekopurr debug scoreboard could not be removed: " + failure);
        }
    }
}
