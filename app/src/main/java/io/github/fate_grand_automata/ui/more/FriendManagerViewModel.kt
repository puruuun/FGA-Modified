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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class FriendManagerAccount(val account: CaptureAccount, val roster: FriendRoster)

internal fun defaultFriendAccount(current: String?, accounts: List<FriendManagerAccount>, requested: String? = null): String? =
    accounts.firstOrNull { requested != null && it.account.id == requested }?.account?.id
        ?: accounts.firstOrNull { it.account.id == current }?.account?.id
        ?: accounts.sortedWith(compareByDescending<FriendManagerAccount> { it.roster.updatedAt }.thenBy { it.account.id })
            .firstOrNull()?.account?.id

@HiltViewModel
class FriendManagerViewModel @Inject constructor(@ApplicationContext context: Context) : ViewModel() {
    val currentId = CaptureTracker.currentId
    val accounts = combine(CaptureTracker.accounts, CaptureTracker.rosters) { accounts, rosters ->
        accounts.mapNotNull { account -> rosters[account.id]?.let { FriendManagerAccount(account, it) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val names = MutableStateFlow(GameNameMap())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            CaptureTracker.init(context)
            val cache = GameNames(File(context.filesDir, "capture/names"))
            accounts.collectLatest { accounts ->
                val slots = accounts.flatMap { it.roster.friends.values }.flatMap { it.slots }
                if (slots.isEmpty()) return@collectLatest
                names.value = cache.friends(slots.map { it.svtId }.toSet(), slots.map { it.ceId }.filter { it != 0L }.toSet())
            }
        }
    }
}
