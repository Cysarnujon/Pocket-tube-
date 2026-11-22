@file:OptIn(ExperimentalMaterial3Api::class)

package com.pockettube.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL

data class Channel(val id: String, val name: String, val group: String)
data class Video(val id: String, val title: String, val channel: String, val published: String)

object Store {
    private const val KEY = "data"

    fun load(ctx: Context): Pair<List<String>, List<Channel>> {
        val s = ctx.getSharedPreferences("pockettube", Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return listOf("Geral") to emptyList()
        return try {
            val o = JSONObject(s)
            val g = o.getJSONArray("groups")
            val c = o.getJSONArray("channels")
            val groups = (0 until g.length()).map { g.getString(it) }
            val channels = (0 until c.length()).map {
                val x = c.getJSONObject(it)
                Channel(x.getString("id"), x.getString("name"), x.getString("group"))
            }
            (if (groups.isEmpty()) listOf("Geral") else groups) to channels
        } catch (e: Exception) {
            listOf("Geral") to emptyList()
        }
    }

    fun save(ctx: Context, groups: List<String>, channels: List<Channel>) {
        val o = JSONObject()
        o.put("groups", JSONArray(groups))
        val arr = JSONArray()
        channels.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("group", it.group))
        }
        o.put("channels", arr)
        ctx.getSharedPreferences("pockettube", Context.MODE_PRIVATE)
            .edit().putString(KEY, o.toString()).apply()
    }
}

fun httpGet(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15000
    conn.readTimeout = 15000
    conn.setRequestProperty(
        "User-Agent",
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    )
    conn.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9")
    return conn.inputStream.bufferedReader().use { it.readText() }
}

fun resolveChannelId(input: String): String? {
    val s = input.trim()
    Regex("UC[\\w-]{22}").find(s)?.let { return it.value }
    val url = when {
        s.startsWith("http") -> s
        s.startsWith("@") -> "https://www.youtube.com/$s"
        else -> "https://www.youtube.com/@$s"
    }
    val html = httpGet(url)
    return Regex("\"(?:externalId|channelId)\":\"(UC[\\w-]{22})\"")
        .find(html)?.groupValues?.get(1)
}

fun fetchFeed(channelId: String): Pair<String, List<Video>> {
    val xml = httpGet("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId")
    val p = XmlPullParserFactory.newInstance().newPullParser()
    p.setInput(StringReader(xml))
    var ev = p.eventType
    var inEntry = false
    var tag = ""
    var feedTitle = ""
    var id = ""
    var title = ""
    var pub = ""
    val list = mutableListOf<Video>()
    while (ev != XmlPullParser.END_DOCUMENT) {
        when (ev) {
            XmlPullParser.START_TAG -> {
                tag = p.name
                if (tag == "entry") {
                    inEntry = true; id = ""; title = ""; pub = ""
                }
            }
            XmlPullParser.TEXT -> {
                val t = p.text
                when {
                    tag == "title" && !inEntry -> if (feedTitle.isEmpty()) feedTitle = t
                    tag == "title" && inEntry -> title += t
                    tag == "yt:videoId" -> id += t
                    tag == "published" && inEntry -> pub += t
                }
            }
            XmlPullParser.END_TAG -> {
                if (p.name == "entry") {
                    inEntry = false
                    if (id.isNotEmpty()) list.add(Video(id, title, feedTitle, pub))
                }
                tag = ""
            }
        }
        ev = p.next()
    }
    return feedTitle to list
}

fun formatDate(iso: String): String =
    if (iso.length >= 10) "${iso.substring(8, 10)}/${iso.substring(5, 7)}/${iso.substring(0, 4)}" else ""

private val thumbCache = mutableMapOf<String, Bitmap>()

@Composable
fun Thumb(videoId: String, modifier: Modifier) {
    val bmp by produceState<Bitmap?>(thumbCache[videoId], videoId) {
        if (value == null) {
            val b = withContext(Dispatchers.IO) {
                try {
                    URL("https://i.ytimg.com/vi/$videoId/mqdefault.jpg").openStream()
                        .use { BitmapFactory.decodeStream(it) }
                } catch (e: Exception) {
                    null
                }
            }
            if (b != null) thumbCache[videoId] = b
            value = b
        }
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(MaterialTheme.shapes.small)
        )
    } else {
        Box(
            modifier = modifier
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { PocketTubeApp() }
            }
        }
    }
}

@Composable
fun PocketTubeApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember { Store.load(ctx) }

    var groups by remember { mutableStateOf(initial.first) }
    var channels by remember { mutableStateOf(initial.second) }
    var tab by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf("Todos") }
    var videos by remember { mutableStateOf<List<Video>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    var showAddChannel by remember { mutableStateOf(false) }
    var showAddGroup by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf<Channel?>(null) }

    fun save(g: List<String>, c: List<Channel>) {
        groups = g
        channels = c
        Store.save(ctx, g, c)
    }

    LaunchedEffect(channels, selected, reload) {
        loading = true
        val targets = channels.filter { selected == "Todos" || it.group == selected }
        videos = withContext(Dispatchers.IO) {
            targets.map { ch ->
                async {
                    try {
                        fetchFeed(ch.id).second
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten().sortedByDescending { it.published }
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PocketTube") },
                actions = { TextButton(onClick = { reload++ }) { Text("Atualizar") } }
            )
        },
        floatingActionButton = {
            if (tab == 1) {
                ExtendedFloatingActionButton(onClick = { showAddChannel = true }) { Text("+ Canal") }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Vídeos") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Canais") })
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(listOf("Todos") + groups) { g ->
                    FilterChip(
                        selected = selected == g,
                        onClick = { selected = g },
                        label = { Text(g) }
                    )
                }
                item {
                    AssistChip(onClick = { showAddGroup = true }, label = { Text("+ Grupo") })
                }
            }

            if (tab == 0) {
                when {
                    channels.isEmpty() -> Text(
                        "Nenhum canal ainda. Vá na aba Canais e toque em + Canal.",
                        modifier = Modifier.padding(16.dp)
                    )
                    loading -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    videos.isEmpty() -> Text(
                        "Nenhum vídeo encontrado neste grupo.",
                        modifier = Modifier.padding(16.dp)
                    )
                    else -> LazyColumn(contentPadding = PaddingValues(12.dp)) {
                        items(videos, key = { it.id }) { v ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        ctx.startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("https://www.youtube.com/watch?v=${v.id}")
                                            )
                                        )
                                    }
                                    .padding(vertical = 6.dp)
                            ) {
                                Thumb(v.id, Modifier.width(150.dp).height(85.dp))
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        v.title,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        v.channel,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1
                                    )
                                    Text(
                                        formatDate(v.published),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                val shown = channels.filter { selected == "Todos" || it.group == selected }
                LazyColumn(contentPadding = PaddingValues(12.dp)) {
                    if (selected != "Todos" && selected != "Geral") {
                        item {
                            TextButton(onClick = {
                                val g = selected
                                selected = "Todos"
                                save(
                                    groups - g,
                                    channels.map { if (it.group == g) it.copy(group = "Geral") else it }
                                )
                            }) { Text("Excluir grupo \"$selected\"") }
                        }
                    }
                    if (shown.isEmpty()) {
                        item { Text("Nenhum canal aqui. Toque em + Canal.") }
                    }
                    items(shown, key = { it.id }) { ch ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(ch.name, style = MaterialTheme.typography.titleMedium)
                                Text("Grupo: ${ch.group}", style = MaterialTheme.typography.bodySmall)
                                Row {
                                    TextButton(onClick = { moveTarget = ch }) { Text("Mover") }
                                    TextButton(onClick = { save(groups, channels - ch) }) { Text("Remover") }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    if (showAddChannel) {
        var text by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var adding by remember { mutableStateOf(false) }
        val targetGroup = if (selected == "Todos") "Geral" else selected
        AlertDialog(
            onDismissRequest = { if (!adding) showAddChannel = false },
            title = { Text("Adicionar canal") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Link, @handle ou ID do canal") },
                        singleLine = true
                    )
                    Text("Vai para o grupo: $targetGroup", style = MaterialTheme.typography.bodySmall)
                    if (adding) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !adding && text.isNotBlank(), onClick = {
                    scope.launch {
                        adding = true
                        error = null
                        try {
                            val id = withContext(Dispatchers.IO) { resolveChannelId(text) }
                            if (id == null) {
                                error = "Não encontrei o canal. Tente o link /channel/UC..."
                            } else if (channels.any { it.id == id }) {
                                error = "Esse canal já foi adicionado."
                            } else {
                                val name = withContext(Dispatchers.IO) { fetchFeed(id).first }
                                save(groups, channels + Channel(id, name.ifBlank { id }, targetGroup))
                                showAddChannel = false
                            }
                        } catch (e: Exception) {
                            error = "Falha de rede ou canal inválido."
                        }
                        adding = false
                    }
                }) { Text("Adicionar") }
            },
            dismissButton = {
                TextButton(enabled = !adding, onClick = { showAddChannel = false }) { Text("Cancelar") }
            }
        )
    }

    if (showAddGroup) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddGroup = false },
            title = { Text("Novo grupo") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome do grupo") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = name.trim()
                    if (n.isNotEmpty() && n != "Todos" && n !in groups) {
                        save(groups + n, channels)
                        selected = n
                    }
                    showAddGroup = false
                }) { Text("Criar") }
            },
            dismissButton = { TextButton(onClick = { showAddGroup = false }) { Text("Cancelar") } }
        )
    }

    moveTarget?.let { ch ->
        AlertDialog(
            onDismissRequest = { moveTarget = null },
            title = { Text("Mover \"${ch.name}\" para:") },
            text = {
                Column {
                    groups.forEach { g ->
                        TextButton(onClick = {
                            save(groups, channels.map { if (it.id == ch.id) it.copy(group = g) else it })
                            moveTarget = null
                        }) { Text(g) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moveTarget = null }) { Text("Cancelar") } }
        )
    }
}
