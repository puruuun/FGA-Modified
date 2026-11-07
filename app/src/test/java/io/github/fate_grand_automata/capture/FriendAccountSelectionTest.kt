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
}
