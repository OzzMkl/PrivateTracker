package org.privatetracker.core.domain.usecase.common

import org.privatetracker.core.common.id.IdGenerator
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.repository.IdentityRepository

/** The id of this phone as a tracker: created on first use, identical afterwards. */
class GetOrCreateDeviceIdentity(
    private val identity: IdentityRepository,
    private val ids: IdGenerator,
) {
    suspend operator fun invoke(): DeviceId = identity.getOrCreate { DeviceId.of(ids.newId()) }
}
