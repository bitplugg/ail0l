package com.aiia.app.ui.screens.memory

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiia.app.data.entities.FactEntity
import com.aiia.app.data.entities.InboxEntity
import com.aiia.app.dm.Dependencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MemoryViewModel(app: android.app.Application) : AndroidViewModel(app) {

    private val dao = Dependencies.db.dao()

    private val _query = MutableStateFlow("")

    val queryText: StateFlow<String> = _query

    val facts: StateFlow<List<FactEntity>> =
        _query.flatMapLatest { q ->
            if (q.isBlank()) dao.observeFacts() else dao.searchFacts(q)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val inbox: StateFlow<List<InboxEntity>> =
        dao.observeInbox()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setQuery(q: String) {
        _query.value = q
    }

    fun toggleFavorite(fact: FactEntity) {
        viewModelScope.launch { dao.setFavorite(fact.id, !fact.favorite) }
    }

    fun deleteFact(fact: FactEntity) {
        viewModelScope.launch { dao.deleteFact(fact) }
    }
}
