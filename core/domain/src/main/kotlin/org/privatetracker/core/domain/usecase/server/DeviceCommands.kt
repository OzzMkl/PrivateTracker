package org.privatetracker.core.domain.usecase.server

import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.asSuccess
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.port.TransactionRunner
import org.privatetracker.core.domain.repository.DeviceRepository
import org.privatetracker.core.domain.validation.validateDeviceName

/** Changes the name shown on the server. The tracker's next registration overwrites it with its own. */
class RenameDevice(
    private val devices: DeviceRepository,
    private val transaction: TransactionRunner,
) {
    suspend operator fun invoke(id: DeviceId, name: String): Outcome<Unit> {
        validateDeviceName("name", name)?.let { return DomainError.Validation(listOf(it)).asFailure() }
        return transaction.run<Outcome<Unit>> {
            val device = devices.get(id) ?: return@run DomainError.DeviceNotFound.asFailure()
            devices.update(device.copy(name = name.trim()))
            Unit.asSuccess()
        }
    }
}

/** Forgets a device with its locations and sessions. */
class RemoveDevice(private val devices: DeviceRepository) {
    suspend operator fun invoke(id: DeviceId): Outcome<Unit> =
        if (devices.delete(id)) Unit.asSuccess() else DomainError.DeviceNotFound.asFailure()
}
