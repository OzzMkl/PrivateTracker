package org.privatetracker.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.privatetracker.core.domain.model.AppMode
import org.privatetracker.core.domain.model.PermissionReport
import org.privatetracker.core.domain.usecase.common.CheckPermissions
import org.privatetracker.core.domain.usecase.common.SetAppMode
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(private val setAppMode: SetAppMode) : ViewModel() {
    /** Saving the mode ends onboarding: the app switches to its main screens. */
    fun onFinish(mode: AppMode) {
        viewModelScope.launch { setAppMode(mode) }
    }
}

/** Permission state is read again whenever the screen resumes, since grants happen in system screens. */
@HiltViewModel
class PermissionsViewModel @Inject constructor(private val checkPermissions: CheckPermissions) : ViewModel() {
    private val _report = MutableStateFlow<PermissionReport?>(null)
    val report: StateFlow<PermissionReport?> = _report.asStateFlow()

    fun refresh(mode: AppMode) {
        _report.value = checkPermissions(mode)
    }
}
