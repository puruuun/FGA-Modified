package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.AccountSummary
import io.github.fate_grand_automata.scripts.capture.FriendRoster
import io.github.fate_grand_automata.ui.more.FriendManagerAccount
import io.github.fate_grand_automata.ui.more.defaultFriendAccount
import kotlin.test.*

class FriendAccountSelectionTest {
    private fun account(id: String, updated: Long) = FriendManagerAccount(
        CaptureAccount(id, id, AccountSummary.from(dashboardState(), 0), 0), FriendRoster(updated))
    @Test fun currentWithRosterWinsOverNewest() {
        assertEquals("old", defaultFriendAccount("old", listOf(account("new", 200), account("old", 100))))
    }
    @Test fun currentWithoutRosterUsesNewestRosterAndNoRostersIsEmpty() {
        val accounts = listOf(account("old", 100), account("new", 200))
        assertEquals("new", defaultFriendAccount("missing", accounts))
        assertEquals("new", defaultFriendAccount(null, accounts))
        assertNull(defaultFriendAccount("missing", emptyList()))
    }
    @Test fun requestedWithRosterWinsOverCurrent() {
        val accounts = listOf(account("new", 200), account("cur", 100), account("req", 50))
        assertEquals("req", defaultFriendAccount("cur", accounts, "req"))
    }
    @Test fun requestedWithoutRosterFallsBackToCurrentThenNewest() {
        val accounts = listOf(account("old", 100), account("new", 200))
        assertEquals("old", defaultFriendAccount("old", accounts, "missing"))
        assertEquals("new", defaultFriendAccount("gone", accounts, "missing"))
    }
    @Test fun noRequestedBehavesAsBefore() {
        val accounts = listOf(account("old", 100), account("new", 200))
        assertEquals("old", defaultFriendAccount("old", accounts, null))
        assertEquals("new", defaultFriendAccount(null, accounts, null))
    }
}
