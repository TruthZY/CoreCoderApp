package com.corecoder.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.corecoder.app.core.AppConfig
import com.corecoder.app.data.ProviderConfigDao
import com.corecoder.app.data.ProviderConfigEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val providerConfigDao: ProviderConfigDao
) : ViewModel() {

    val providers: StateFlow<List<ProviderConfigEntity>> = providerConfigDao
        .getAllProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val defaultProvider: StateFlow<ProviderConfigEntity?> = providerConfigDao
        .getDefaultFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _editingProvider = MutableStateFlow<ProviderConfigEntity?>(null)
    val editingProvider: StateFlow<ProviderConfigEntity?> = _editingProvider.asStateFlow()

    fun startEdit(provider: ProviderConfigEntity?) {
        _editingProvider.value = provider
    }

    fun cancelEdit() {
        _editingProvider.value = null
    }

    fun saveProvider(
        id: String?,
        name: String,
        apiKey: String,
        baseUrl: String?,
        model: String,
        isDefault: Boolean
    ) {
        viewModelScope.launch {
            val provider = ProviderConfigEntity(
                id = id ?: UUID.randomUUID().toString(),
                name = name,
                apiKey = apiKey,
                baseUrl = baseUrl?.ifBlank { null },
                model = model,
                isDefault = isDefault
            )
            providerConfigDao.insert(provider)

            if (isDefault) {
                providerConfigDao.setAsDefault(provider.id)
            }
            _editingProvider.value = null
        }
    }

    fun deleteProvider(provider: ProviderConfigEntity) {
        viewModelScope.launch {
            providerConfigDao.delete(provider)
        }
    }

    fun setDefaultProvider(id: String) {
        viewModelScope.launch {
            providerConfigDao.setAsDefault(id)
        }
    }

    /** Apply a preset configuration. */
    fun applyPreset(presetName: String) {
        val preset = AppConfig.PRESETS[presetName] ?: return
        viewModelScope.launch {
            val provider = ProviderConfigEntity(
                id = UUID.randomUUID().toString(),
                name = presetName,
                apiKey = preset.apiKey,
                baseUrl = preset.baseUrl,
                model = preset.model,
                isDefault = true
            )
            providerConfigDao.insert(provider)
            providerConfigDao.setAsDefault(provider.id)
        }
    }
}
