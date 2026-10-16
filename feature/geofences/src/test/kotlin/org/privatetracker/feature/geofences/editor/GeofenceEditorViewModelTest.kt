package org.privatetracker.feature.geofences.editor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.privatetracker.core.common.id.IdGenerator
import org.privatetracker.core.domain.model.GeoPoint
import org.privatetracker.core.domain.testing.FakeClock
import org.privatetracker.core.domain.testing.InMemoryGeofences
import org.privatetracker.core.domain.testing.InMemoryServerConfigRepository
import org.privatetracker.core.domain.testing.InMemoryServerStore
import org.privatetracker.core.domain.usecase.server.DeleteGeofence
import org.privatetracker.core.domain.usecase.server.GetGeofence
import org.privatetracker.core.domain.usecase.server.ObserveDeviceOverviews
import org.privatetracker.core.domain.usecase.server.SaveGeofence

@OptIn(ExperimentalCoroutinesApi::class)
class GeofenceEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val geofences = InMemoryGeofences()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun newEditor(): GeofenceEditorViewModel {
        val clock = FakeClock()
        return GeofenceEditorViewModel(
            rawId = "",
            observeDevices = ObserveDeviceOverviews(InMemoryServerStore(), InMemoryServerConfigRepository(), clock),
            getGeofence = GetGeofence(geofences),
            saveGeofence = SaveGeofence(geofences, IdGenerator.Random, clock),
            deleteGeofence = DeleteGeofence(geofences),
        )
    }

    @Test
    fun aSecondTapOnSaveWhileTheFirstIsOnItsWayMakesNoSecondGeofence() = runTest(dispatcher) {
        val editor = newEditor()
        advanceUntilIdle()
        editor.onMapTap(GeoPoint(19.4326, -99.1332))
        editor.onName("Casa")

        editor.onSave()
        editor.onSave()
        advanceUntilIdle()

        assertEquals(1, geofences.all().size)
    }

    @Test
    fun aRefusedSaveLetsTheOwnerFixTheFormAndSaveAgain() = runTest(dispatcher) {
        val editor = newEditor()
        advanceUntilIdle()
        editor.onMapTap(GeoPoint(19.4326, -99.1332))

        editor.onSave()
        advanceUntilIdle()
        editor.onName("Casa")
        editor.onSave()
        advanceUntilIdle()

        assertEquals(listOf("Casa"), geofences.all().map { it.name })
    }
}
