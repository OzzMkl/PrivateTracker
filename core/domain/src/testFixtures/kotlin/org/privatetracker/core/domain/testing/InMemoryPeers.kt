package org.privatetracker.core.domain.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.privatetracker.core.domain.model.Peer
import org.privatetracker.core.domain.repository.PeerRepository

/** Paired servers in memory, in the order they paired. */
class InMemoryPeers : PeerRepository {
    private val peers = MutableStateFlow<List<Peer>>(emptyList())

    override fun observeAll(): Flow<List<Peer>> = peers

    override suspend fun all(): List<Peer> = peers.value

    override suspend fun get(publicKey: String): Peer? = peers.value.firstOrNull { it.publicKey == publicKey }

    override suspend fun getByLabel(label: String): Peer? = peers.value.firstOrNull { it.label == label }

    override suspend fun save(peer: Peer) = peers.update { all ->
        if (all.any { it.publicKey == peer.publicKey }) {
            all.map { if (it.publicKey == peer.publicKey) it.copy(name = peer.name, addresses = peer.addresses) else it }
        } else {
            all + peer
        }
    }

    override suspend fun update(publicKey: String, transform: (Peer) -> Peer): Boolean {
        var found = false
        peers.update { all -> all.map { if (it.publicKey == publicKey) transform(it).also { found = true } else it } }
        return found
    }

    override suspend fun delete(publicKey: String): Boolean {
        var found = false
        peers.update { all -> all.filterNot { (it.publicKey == publicKey).also { match -> if (match) found = true } } }
        return found
    }
}
