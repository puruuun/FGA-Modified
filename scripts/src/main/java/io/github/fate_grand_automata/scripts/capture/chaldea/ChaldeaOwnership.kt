package io.github.fate_grand_automata.scripts.capture.chaldea

/** Owner, group and permission bits of one file, as `stat` reports them. */
data class ChaldeaFileOwner(val uid: Int, val gid: Int, val mode: Int)

/**
 * The ownership guard of the root mode (issue #362, R4): a write by root must leave each Chaldea file with the owner
 * and mode it had, or Chaldea (running as its own app uid) could no longer read it.
 */
object ChaldeaOwnership {
    /**
     * @param before the files that existed before the write, with their owner
     * @param after the same files after the write; a missing key or a null value means the file is gone or cannot be read
     * @return the names of files whose owner or mode is not the original. A file that did not exist before is not checked.
     */
    fun changed(before: Map<String, ChaldeaFileOwner>, after: Map<String, ChaldeaFileOwner?>): List<String> =
        before.filter { (name, owner) -> after[name] != owner }.keys.toList()
}
