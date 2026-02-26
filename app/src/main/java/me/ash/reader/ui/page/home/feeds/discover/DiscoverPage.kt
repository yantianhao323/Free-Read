package me.ash.reader.ui.page.home.feeds.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.ui.component.FeedIcon
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.page.home.feeds.subscribe.SubscribeDialog
import me.ash.reader.ui.page.home.feeds.subscribe.SubscribeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverPage(
    discoverViewModel: DiscoverViewModel = hiltViewModel(),
    subscribeViewModel: SubscribeViewModel,
    onBack: () -> Unit,
) {
    val uiState by discoverViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showSubscribeDialog by remember { mutableStateOf(false) }

    if (showSubscribeDialog) {
        SubscribeDialog(subscribeViewModel = subscribeViewModel)
    }

    RYScaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (uiState.isSearching) {
                        OutlinedTextField(
                            value = uiState.searchQuery,
                            onValueChange = discoverViewModel::onSearchQueryChanged,
                            placeholder = { Text(stringResource(R.string.search)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            )
                        )
                    } else {
                        Text("Discover")
                    }
                },
                navigationIcon = {
                    FeedbackIconButton(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        onClick = onBack
                    )
                },
                actions = {
                    if (!uiState.isSearching) {
                        FeedbackIconButton(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = stringResource(R.string.search),
                            onClick = discoverViewModel::toggleSearch
                        )
                        FeedbackIconButton(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Manual Subscribe",
                            onClick = {
                                subscribeViewModel.showDrawer()
                                showSubscribeDialog = true
                            }
                        )
                    } else {
                        TextButton(onClick = discoverViewModel::toggleSearch) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            )
        }
    ,
        content = {
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            val isSearching = uiState.searchQuery.isNotBlank()
            
            val filteredFeatured = if (isSearching) {
                discoverViewModel.featuredSites.filter { 
                    it.name.contains(uiState.searchQuery, ignoreCase = true) || 
                    it.url.contains(uiState.searchQuery, ignoreCase = true)
                }
            } else {
                discoverViewModel.featuredSites
            }
            
            val filteredOther = if (isSearching) {
                uiState.otherSites.filter { 
                    it.name.contains(uiState.searchQuery, ignoreCase = true) ||
                    it.url.contains(uiState.searchQuery, ignoreCase = true)
                }
            } else {
                uiState.otherSites
            }

            if (filteredFeatured.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.featured),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                items(filteredFeatured) { site ->
                    SiteListItem(
                        site = site,
                        isSubscribed = uiState.subscribedUrls.contains(site.url),
                        onSubscribe = {
                            coroutineScope.launch {
                                discoverViewModel.subscribe(site)
                            }
                        }
                    )
                }
            }

            if (filteredOther.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.other),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                items(filteredOther) { site ->
                    SiteListItem(
                        site = site,
                        isSubscribed = uiState.subscribedUrls.contains(site.url),
                        onSubscribe = {
                            coroutineScope.launch {
                                discoverViewModel.subscribe(site)
                            }
                        }
                    )
                }
            }
        }
    },
    )
}

@Composable
private fun SiteListItem(
    site: RecommendedSite,
    isSubscribed: Boolean,
    onSubscribe: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(site.name) },
        supportingContent = { Text(site.url, maxLines = 1) },
        leadingContent = {
            FeedIcon(
                feedName = site.name,
                iconUrl = site.icon,
                placeholderIcon = Icons.Rounded.RssFeed
            )
        },
        trailingContent = {
            if (isSubscribed) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Subscribed",
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                FilledTonalButton(onClick = onSubscribe) {
                    Text(stringResource(R.string.subscribe))
                }
            }
        },
        modifier = Modifier.clickable { }
    )
    HorizontalDivider()
}
