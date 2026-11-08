package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.AccountState
import io.github.fate_grand_automata.scripts.capture.FriendRoster

/** Test-only views that load every account, which the production code never does (issue #258). */
internal val CaptureAccounts.states: Map<String, AccountState>
    get() = ids.sorted().mapNotNull { id -> stateOf(id)?.let { id to it } }.toMap()

internal val CaptureAccounts.rosters: Map<String, FriendRoster>
    get() = ids.sorted().mapNotNull { id -> roster(id)?.let { id to it } }.toMap()

internal class Held<T>(val value: T)

internal val CaptureTracker.rosters: Held<Map<String, FriendRoster>>
    get() = Held(accounts.value.map { it.id }.sorted().mapNotNull { id -> loadRoster(id)?.let { id to it } }.toMap())
