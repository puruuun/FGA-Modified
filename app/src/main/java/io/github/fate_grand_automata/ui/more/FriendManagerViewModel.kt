package io.github.fate_grand_automata.ui.more

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.fate_grand_automata.capture.CaptureTracker
import io.github.fate_grand_automata.capture.GameNameMap
import io.github.fate_grand_automata.capture.GameNames
import io.github.fate_grand_automata.capture.CaptureAccount
import io.github.fate_grand_automata.scripts.capture.FriendRoster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * One account of the friend manager. Only [roster] of the selected account is loaded (issue #258); the others carry
 * the friend count and update time from the account index.
 */
data class FriendManagerAccount(val account: CaptureAccount, val friendCount: Int, val updatedAt: Long, val roster: FriendRoster?) {
    constructor(account: CaptureAccount, roster: FriendRoster) : this(account, roster.friends.size, roster.updatedAt, roster)
}

internal fun defaultFriendAccount(current: String?, accounts: List<FriendManagerAccount>, requested: String? = null): String? =
    accounts.firstOrNull { requested != null && it.account.id == requested }?.account?.id
        ?: accounts.firstOrNull { it.account.id == current }?.account?.id
        ?: accounts.sortedWith(compareByDescending<FriendManagerAccount> { it.updatedAt }.thenBy { it.account.id })
            .firstOrNull()?.account?.id

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FriendManagerViewModel @Inject constructor(@ApplicationContext context: Context) : ViewModel() {
    val currentId = CaptureTracker.currentId
    private val selected = MutableStateFlow<String?>(null)
    private val listed = CaptureTracker.accounts.map { accounts ->
        accounts.mapNotNull { account -> account.friendCount?.let { FriendManagerAccount(account, it, account.friendUpdatedAt, null) } }
    }

    /** The account the screen shows; its roster is read from disk when it is selected or changes. */
    fun select(id: String?) { selected.value = id }

    val accounts = combine(listed, selected, currentId, CaptureTracker.rosterRevision) { list, chosen, current, _ ->
        Triple(list, chosen, current)
    }.mapLatest { (list, chosen, current) ->
        val target = defaultFriendAccount(current, list, chosen)
        list.map { item ->
            if (item.account.id == target) item.copy(roster = withContext(Dispatchers.IO) { CaptureTracker.loadRoster(item.account.id) }) else item
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val names = MutableStateFlow(GameNameMap())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            CaptureTracker.init(context)
            val cache = GameNames(File(context.filesDir, "capture/names"))
            accounts.collectLatest { accounts ->
                val active = accounts.filter { it.roster != null }
                val friends = active.flatMap { it.roster!!.friends.values }
                val slots = friends.flatMap { friend -> friend.slots + friend.grandSlots.map { it.supportSlot() } }
                if (slots.isEmpty()) return@collectLatest
                names.value = cache.friends(slots.map { it.svtId }.toSet(), slots.flatMap { listOfNotNull(it.ceId, it.ce2Id) }.filter { it != 0L }.toSet(),
                    hasJpAccount = active.any { it.account.server == "JP" })
            }
        }
    }
}
