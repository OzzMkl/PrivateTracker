package org.privatetracker.core.common.id

import java.util.UUID

/** Creates identifiers for new entities, injected so tests get predictable ids. */
fun interface IdGenerator {
    /** Returns a new UUID in canonical lowercase form. */
    fun newId(): String

    companion object {
        val Random: IdGenerator = IdGenerator { UUID.randomUUID().toString() }
    }
}
