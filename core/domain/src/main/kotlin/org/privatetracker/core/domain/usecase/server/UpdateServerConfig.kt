package org.privatetracker.core.domain.usecase.server

import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.asSuccess
import org.privatetracker.core.domain.model.ServerConfig
import org.privatetracker.core.domain.network.loopbackUrl
import org.privatetracker.core.domain.repository.ServerConfigRepository
import org.privatetracker.core.domain.repository.TrackerConfigRepository
import org.privatetracker.core.domain.validation.ServerConfigValidator

data class ServerConfigUpdate(val config: ServerConfig, val restartRequired: Boolean)

/**
 * Validates and saves the server settings. A tracker on the same phone that reports to the old
 * port follows the server to the new one.
 */
class UpdateServerConfig(
    private val repository: ServerConfigRepository,
    private val trackerConfig: TrackerConfigRepository,
) {
    suspend operator fun invoke(config: ServerConfig): Outcome<ServerConfigUpdate> {
        val normalized = config.copy(serverName = config.serverName.trim(), bindAddress = config.bindAddress.trim())
        val violations = ServerConfigValidator.validate(normalized)
        if (violations.isNotEmpty()) return DomainError.Validation(violations).asFailure()

        val previous = repository.get()
        repository.update { normalized }
        if (previous.port != normalized.port) {
            val oldUrl = loopbackUrl(previous.port)
            trackerConfig.update { if (it.serverUrl == oldUrl) it.copy(serverUrl = loopbackUrl(normalized.port)) else it }
        }
        val restartRequired = previous.port != normalized.port || previous.bindAddress != normalized.bindAddress
        return ServerConfigUpdate(normalized, restartRequired).asSuccess()
    }
}
