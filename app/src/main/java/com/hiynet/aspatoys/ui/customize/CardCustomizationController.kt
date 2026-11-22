package com.hiynet.aspatoys.ui.customize

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hiynet.aspatoys.core.parseJsonObjectOrNull
import com.hiynet.aspatoys.core.string
import com.hiynet.aspatoys.data.AppContainer
import com.hiynet.aspatoys.data.cards.CardPackageStore
import com.hiynet.aspatoys.data.cards.GameCardProfiles
import com.hiynet.aspatoys.data.loader.ShizukuLoaderFs
import com.hiynet.aspatoys.ui.components.Operations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

class CardCustomizationController(private val container: AppContainer, private val scope: CoroutineScope, context: Context) {
    private val app = context.applicationContext
    val store = CardPackageStore(File(app.filesDir, "customization/cards")) { file ->
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth !in 1..16384 || bounds.outHeight !in 1..16384) false
        else {
            val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 512).coerceAtLeast(1) }
            val decoded = BitmapFactory.decodeFile(file.path, options)
            (decoded != null).also { decoded?.recycle() }
        }
    }
    private val game = GameCardProfiles(ShizukuLoaderFs(), store,
        { path -> app.assets.open(path).use { it.readBytes() } },
        { path -> app.assets.list(path).orEmpty().toList() })

    data class CatalogCard(val key: String, val name: String, val category: String)
    data class EditorCard(val key: String, val name: String, val category: String, val file: File?, val animated: Boolean, val binding: String = key)
    var profiles by mutableStateOf<List<CardPackageStore.Profile>>(emptyList()); private set
    var selected by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var progress by mutableStateOf(""); private set
    var notice by mutableStateOf<String?>(null); private set
    var failed by mutableStateOf(false); private set
    var connectionProblem by mutableStateOf<String?>(null); private set
    var editing by mutableStateOf<String?>(null); private set
    var editorCards by mutableStateOf<List<EditorCard>>(emptyList()); private set
    var rootPath by mutableStateOf(container.settings.loaderRootPath); private set
    private var catalog = emptyList<CatalogCard>()
    private var lastRoot = ""

    fun enter() {
        if (lastRoot != container.settings.loaderRootPath || profiles.isEmpty()) refresh()
    }
    fun refresh() = operation("正在读取卡牌方案") { refreshNow() }
    fun clearNotice() { notice = null }
    fun closeEditor() { editing = null; editorCards = emptyList() }
    fun hasLocal(id: String) = store.directory(id).isDirectory
    fun pending(id: String) = File(store.directory(id), ".pending").isFile
    fun cover(id: String) = if (hasLocal(id)) store.cards(id).firstOrNull { it.file?.isFile == true }?.file else null

    private suspend fun refreshNow() {
        val root = container.settings.loaderRootPath
        val local = withContext(Dispatchers.IO) { store.profiles() }
        val snapshot = withContext(Dispatchers.IO) { runCatching { game.snapshot(root) }.getOrElse { GameCardProfiles.Snapshot("", emptyList(), it.message ?: "读取游戏方案失败。") } }
        profiles = (local + snapshot.profiles.filterNot { remote -> local.any { it.id == remote.id } }).sortedBy { it.name }
        selected = snapshot.selected
        connectionProblem = snapshot.problem
        rootPath = root; lastRoot = root
    }

    fun importZip(uri: Uri) = operation("正在读取方案 ZIP", title = "导入卡牌方案") {
        val name = withContext(Dispatchers.IO) { displayName(uri) }
        val file = File(store.root.apply { mkdirs() }, ".archive-${UUID.randomUUID()}.zip")
        try {
            val imported = withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output -> input.copyTo(output) } } ?: error("无法读取 ZIP 文件。")
                store.importZip(file, name) { step(it) }
            }
            refreshNow()
            notice = "已导入 ${imported.size} 个方案，选择「应用到游戏」后生效。"
        } finally { file.delete() }
    }

    fun create(name: String) = operation("正在创建方案") {
        val profile = withContext(Dispatchers.IO) { store.create(name) }
        refreshNow(); editing = profile.id; loadEditor(profile.id)
        notice = "方案已创建，点卡牌即可替换图片。"
    }

    fun apply(id: String) = operation(
        "正在应用卡面",
        title = if (id == CardPackageStore.GameDefault) "恢复游戏默认卡面" else "应用卡牌方案「${profileName(id)}」",
    ) {
        val root = container.settings.loaderRootPath
        withContext(Dispatchers.IO) { game.apply(root, id) { step(it) } }
        refreshNow()
        notice = if (id == CardPackageStore.GameDefault) "已选择游戏默认卡面。请完全重启游戏。" else "方案已写入游戏。请完全重启游戏后使用。"
    }

    fun edit(id: String) = operation("正在打开卡牌方案") {
        if (!hasLocal(id)) withContext(Dispatchers.IO) { game.fetch(container.settings.loaderRootPath, id) { step(it) } }
        editing = id; loadEditor(id)
    }

    private suspend fun loadEditor(id: String) {
        val result = withContext(Dispatchers.IO) {
            if (catalog.isEmpty()) catalog = runCatching {
                val array = Json.parseToJsonElement(app.assets.open("cards/catalog.json").bufferedReader().use { it.readText() }) as JsonArray
                array.mapNotNull { it as? JsonObject }.map { CatalogCard(it.string("key"), it.string("name"), it.string("category")) }
            }.getOrDefault(emptyList())
            val own = store.cards(id).associateBy { it.key.lowercase() }
            val used = hashSetOf<String>()
            fun cardId(key: String): String? = Regex("^(?:UT_HandCard_|UT_Destiny_|UT_MapEvent_|UT_Event_|UT_Item_AltArt_|UT_IAltArt_|Card_)?([0-9]+)(?:_sfw)?$", RegexOption.IGNORE_CASE).matchEntire(key)?.groupValues?.get(1)
            catalog.map { info ->
                val number = cardId(info.key)
                val exact = own[info.key.lowercase()]
                val actual = if (info.key.endsWith("_sfw", true) && exact != null) exact else
                    number?.let { own[it] } ?: own.values.firstOrNull { number != null && cardId(it.key) == number && !it.key.endsWith("_sfw", true) } ?:
                    own.values.firstOrNull { number != null && cardId(it.key) == number } ?: exact ?: own[info.name.lowercase()]
                actual?.let { used += it.key.lowercase() }
                EditorCard(info.key, info.name.ifBlank { info.key }, info.category, actual?.file, actual?.animated == true, actual?.key ?: info.key)
            } + own.values.filter { it.key.lowercase() !in used }.map { EditorCard(it.key, label(it.key), "其他卡面", it.file, it.animated) }

        }
        editorCards = result
    }

    fun replace(id: String, key: String, uri: Uri) = operation("正在保存卡面") {
        val file = File(store.root.apply { mkdirs() }, ".image-${UUID.randomUUID()}.png")
        try {
            withContext(Dispatchers.IO) {
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, info, _ ->
                    require(info.size.width > 0 && info.size.height > 0) { "图片尺寸无效。" }
                    val factor = minOf(1f, 4096f / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize((info.size.width * factor).roundToInt().coerceAtLeast(1), (info.size.height * factor).roundToInt().coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "无法保存图片。" } } }
                finally { bitmap.recycle() }
                store.replace(id, key, file, "png")
                File(store.directory(id), ".pending").writeText("1")
            }
            refreshNow(); loadEditor(id)
            notice = "卡面已保存。应用到游戏后，完全重启游戏即可使用。"
        } finally { file.delete() }
    }

    fun restoreCard(id: String, key: String) = operation("正在恢复原卡面") {
        withContext(Dispatchers.IO) { store.restoreCard(id, key) }
        refreshNow(); loadEditor(id)
        notice = "已移除此卡面替换。应用到游戏后，完全重启游戏使用原图。"
    }

    fun remove(id: String) = operation("正在删除方案") {
        require(selected != id) { "请先切换到游戏默认卡面，再删除当前方案。" }
        withContext(Dispatchers.IO) {
            if (game.snapshot(container.settings.loaderRootPath).profiles.any { it.id == id }) game.remove(container.settings.loaderRootPath, id)
            if (store.directory(id).exists()) store.delete(id)
        }
        if (editing == id) closeEditor()
        refreshNow(); notice = "方案已删除。"
    }

    fun export(id: String, uri: Uri) = operation("正在导出 Win 兼容 ZIP", title = "导出卡牌方案「${profileName(id)}」") {
        if (!hasLocal(id)) withContext(Dispatchers.IO) { game.fetch(container.settings.loaderRootPath, id) { step(it) } }
        val temp = File(store.root.apply { mkdirs() }, ".export-${UUID.randomUUID()}.zip")
        try {
            withContext(Dispatchers.IO) {
                store.export(id, temp)
                app.contentResolver.openOutputStream(uri, "wt")?.use { output -> temp.inputStream().use { it.copyTo(output) } } ?: error("无法写入导出文件。")
            }
            notice = "已导出 ZIP，可在 Win 版导入。"
        } finally { temp.delete() }
    }

    /** 当前操作对应的进度面板；没有面板的小操作为 null。 */
    @Volatile private var panel: Operations.Operation? = null

    /** 底层的一行进度：页面底部的状态条和进度面板（如果有）都跟着走。可能在后台线程上调用。 */
    private fun step(text: String) {
        progress = text
        panel?.log(text)
    }

    /**
     * 执行一个操作。传了 [title] 的（导入、应用、导出这些会写文件、要等一会儿的）画进全局进度面板，
     * 结果留在面板里、不自动关；不传的小操作照旧只用页面底部的状态条和提示。
     */
    private fun operation(message: String, title: String? = null, block: suspend () -> Unit) {
        if (busy) return
        val sheet = if (title != null) Operations.start(title) ?: return else null
        sheet?.status(message)
        panel = sheet
        busy = true; progress = message; notice = null; failed = false
        scope.launch {
            try {
                block()
                // 有面板时结果写进面板，页面底部就不再重复一条。
                if (sheet != null) { sheet.succeed(notice ?: "已完成。"); notice = null }
            }
            catch (cancelled: CancellationException) { sheet?.fail("已中断。"); throw cancelled }
            catch (error: Exception) {
                val text = error.message ?: "操作失败。"
                if (sheet != null) sheet.fail(text) else { failed = true; notice = text }
            }
            finally { busy = false; progress = ""; panel = null }
        }
    }

    private fun profileName(id: String) = profiles.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() } ?: id
    private fun displayName(uri: Uri): String = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: "cards.zip"
    private fun label(key: String): String = when {
        key.startsWith("UT_HandCard_") -> "手牌 #" + key.removePrefix("UT_HandCard_")
        key.startsWith("UT_Destiny_") -> "命运 #" + key.removePrefix("UT_Destiny_")
        key.startsWith("UT_Event_") -> "事件 #" + key.removePrefix("UT_Event_")
        else -> key
    }
}
