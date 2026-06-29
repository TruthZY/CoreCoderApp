package com.corecoder.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.corecoder.app.core.SkillManager
import com.corecoder.app.data.SkillDao
import com.corecoder.app.data.SkillEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SkillsViewModel @Inject constructor(
    private val skillDao: SkillDao,
    private val skillManager: SkillManager
) : ViewModel() {

    val skills: StateFlow<List<SkillEntity>> = skillDao
        .getAllSkills()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** One-shot event to open a folder in external file manager. */
    private val _openFolderEvent = MutableSharedFlow<String>()
    val openFolderEvent: SharedFlow<String> = _openFolderEvent.asSharedFlow()

    init {
        // Initial scan on creation
        refreshSkills()
    }

    fun toggleSkill(id: String, enabled: Boolean) {
        viewModelScope.launch {
            skillDao.setEnabled(id, enabled)
        }
    }

    fun refreshSkills() {
        viewModelScope.launch {
            _isScanning.value = true
            try {
                skillManager.scanSkills()
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun openSkillFolder(folderPath: String) {
        viewModelScope.launch {
            _openFolderEvent.emit(folderPath)
        }
    }
}
