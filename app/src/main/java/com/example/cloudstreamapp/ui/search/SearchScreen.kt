package com.example.cloudstreamapp.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.cloudstreamapp.domain.model.CloudItem

@Composable
fun SearchScreen(onPlayMedia: (CloudItem) -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Поиск — Phase 2")
    }
}
