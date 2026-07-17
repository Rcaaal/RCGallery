package com.example.rcgallery.ui.component

import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rcgallery.data.CuratedFolderStore
import com.example.rcgallery.model.Album
import com.example.rcgallery.util.FormatUtil
import com.example.rcgallery.viewmodel.GalleryViewModel.RecentMoveAlbum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private enum class StorageFolderMode(val label: String) {
    CURATED("精选模式"),
    SYSTEM("系统模式"),
    RECENT("最近移动过")
}

enum class AlbumStoragePickPurpose {
    MIGRATE_ALBUM_DIRECTORIES,
    MERGE_MEDIA
}

private fun migrationTimeAgo(movedAt: Long): String {
    val minutes = ((System.currentTimeMillis() - movedAt) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes}分钟前"
        minutes < 1440 -> "${minutes / 60}小时前"
        else -> "${minutes / 1440}天前"
    }
}

/** 分层目录选择器。选择的是迁移父目录，最终路径始终为 parent/sourceAlbumName。 */
@Composable
fun AlbumStoragePickDialog(
    sourceAlbum: Album,
    sourceAlbums: List<Album> = listOf(sourceAlbum),
    recentRoots: List<RecentMoveAlbum>,
    purpose: AlbumStoragePickPurpose = AlbumStoragePickPurpose.MIGRATE_ALBUM_DIRECTORIES,
    onDismiss: () -> Unit,
    onSelectRoot: (File) -> Unit
) {
    val context = LocalContext.current
    val curatedStore = remember { CuratedFolderStore(context.applicationContext) }
    val storageRoot = remember { Environment.getExternalStorageDirectory().canonicalFile }
    val effectiveSourceAlbums = remember(sourceAlbum, sourceAlbums) {
        sourceAlbums.ifEmpty { listOf(sourceAlbum) }.distinctBy { it.bucketId }
    }
    val sourceDirs = remember(effectiveSourceAlbums) {
        effectiveSourceAlbums.map { album ->
            runCatching { File(album.directoryPath).canonicalFile }
                .getOrElse { File(album.directoryPath).absoluteFile }
        }
    }
    var currentDir by remember { mutableStateOf(storageRoot) }
    var mode by remember { mutableStateOf(StorageFolderMode.CURATED) }
    var searchQuery by remember { mutableStateOf("") }
    var showCreateFolder by remember { mutableStateOf(false) }
    var showManageCurated by remember { mutableStateOf(false) }
    var addingCurated by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var curatedPaths by remember { mutableStateOf(curatedStore.load()) }

    fun canonical(file: File): File = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
    fun isHiddenPath(file: File): Boolean {
        val path = canonical(file).path
        val relative = path.removePrefix(storageRoot.path).trim(File.separatorChar)
        return relative.split(File.separatorChar).any { it.startsWith(".") }
    }
    fun isSourceOrChild(file: File): Boolean {
        val path = canonical(file).path
        return sourceDirs.any { sourceDir ->
            path.equals(sourceDir.path, ignoreCase = true) ||
                path.startsWith(sourceDir.path + File.separator, ignoreCase = true)
        }
    }
    fun returnToCurated() {
        addingCurated = false
        mode = StorageFolderMode.CURATED
        currentDir = storageRoot
        searchQuery = ""
    }

    val childDirectories by produceState(
        initialValue = emptyList(),
        currentDir,
        searchQuery,
        refreshTick
    ) {
        value = withContext(Dispatchers.IO) {
            currentDir.listFiles().orEmpty()
                .asSequence()
                .filter { it.isDirectory && it.canRead() && !it.name.startsWith(".") }
                .filter { searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                .toList()
        }
    }

    val curatedDirectories = remember(curatedPaths, searchQuery, refreshTick) {
        curatedPaths.map(::File)
            .filter { it.isDirectory && it.canRead() && !isHiddenPath(it) }
            .filter {
                searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) ||
                    it.path.contains(searchQuery, ignoreCase = true)
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
    val validRecentRoots = remember(recentRoots) {
        recentRoots.mapNotNull { recent ->
            val file = runCatching { File(recent.directoryPath).canonicalFile }.getOrNull()
            file?.takeIf { it.isDirectory && it.canRead() && !isHiddenPath(it) }?.let { recent to it }
        }
    }

    val finalTargets = remember(currentDir, effectiveSourceAlbums) {
        effectiveSourceAlbums.map { album -> File(currentDir, album.bucketName) }
    }
    val hasDuplicateNames = remember(purpose, effectiveSourceAlbums) {
        purpose == AlbumStoragePickPurpose.MIGRATE_ALBUM_DIRECTORIES &&
            effectiveSourceAlbums.groupBy { it.bucketName.lowercase() }.any { it.value.size > 1 }
    }
    val sameParent = purpose == AlbumStoragePickPurpose.MIGRATE_ALBUM_DIRECTORIES && sourceDirs.any { sourceDir ->
        sourceDir.parentFile?.let {
            canonical(it).path.equals(canonical(currentDir).path, ignoreCase = true)
        } == true
    }
    val currentForbidden = isSourceOrChild(currentDir)
    val targetExists = purpose == AlbumStoragePickPurpose.MIGRATE_ALBUM_DIRECTORIES && finalTargets.any { it.exists() }
    val canUseCurrent = !hasDuplicateNames && !sameParent && !currentForbidden &&
        !targetExists && currentDir.canWrite()
    val currentCanonicalPath = canonical(currentDir).path
    val canAddCurrent = currentCanonicalPath != storageRoot.path &&
        !isHiddenPath(currentDir) && currentDir.isDirectory && currentDir.canRead() &&
        curatedPaths.none { it.equals(currentCanonicalPath, ignoreCase = true) }

    BackHandler {
        when {
            mode != StorageFolderMode.RECENT && currentDir.path != storageRoot.path -> {
                currentDir.parentFile?.let { currentDir = canonical(it); searchQuery = "" }
            }
            addingCurated -> returnToCurated()
            else -> onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    addingCurated -> "添加精选文件夹"
                    purpose == AlbumStoragePickPurpose.MERGE_MEDIA -> "选择合并位置"
                    else -> "选择迁移位置"
                },
                fontWeight = FontWeight.Medium
            )
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                if (!addingCurated) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StorageFolderMode.entries.forEach { item ->
                                FilterChip(
                                    selected = mode == item,
                                    onClick = {
                                        mode = item
                                        currentDir = storageRoot
                                        searchQuery = ""
                                    },
                                    label = { Text(item.label) }
                                )
                            }
                        }
                        if (mode == StorageFolderMode.CURATED) {
                            Spacer(Modifier.width(4.dp))
                            TextButton(
                                onClick = { showManageCurated = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) { Text("管理精选", maxLines = 1) }
                        }
                    }
                }

                val browserVisible = addingCurated || mode != StorageFolderMode.RECENT
                if (browserVisible) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val relative = currentDir.path.removePrefix(storageRoot.path).trim(File.separatorChar)
                        val segments = if (relative.isBlank()) emptyList() else relative.split(File.separatorChar)
                        val crumbs = buildList {
                            add("内部存储" to storageRoot)
                            var cursor = storageRoot
                            segments.forEach { segment ->
                                cursor = File(cursor, segment)
                                add(segment to cursor)
                            }
                        }
                        crumbs.forEachIndexed { index, (name, file) ->
                            if (index > 0) Text("  ›  ", color = Color.Gray)
                            Text(
                                name,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    currentDir = canonical(file)
                                    searchQuery = ""
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("搜索文件夹...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                }

                if (addingCurated) {
                    PickerActionCard(
                        enabled = canAddCurrent,
                        title = "＋ 加入精选",
                        hint = if (canAddCurrent) FormatUtil.formatDisplayPath(currentCanonicalPath)
                            else "请选择尚未加入的非隐藏文件夹",
                        onClick = {
                            curatedPaths = curatedStore.save(curatedPaths + currentCanonicalPath)
                            showManageCurated = true
                            returnToCurated()
                        }
                    )
                } else {
                    val finalHint = when {
                        hasDuplicateNames -> "选中的相册存在同名文件夹，无法迁移到同一位置"
                        sameParent -> "相册已经位于当前文件夹"
                        currentForbidden -> "不能把相册迁移到自身或其子目录"
                        targetExists -> "当前目录已存在同名文件夹"
                        !currentDir.canWrite() -> "当前目录没有写入权限"
                        purpose == AlbumStoragePickPurpose.MERGE_MEDIA ->
                            "将 ${effectiveSourceAlbums.size} 个相册中的图片、GIF、视频合并到当前文件夹"
                        effectiveSourceAlbums.size > 1 ->
                            "将在此目录下迁移 ${effectiveSourceAlbums.size} 个相册，并保留原文件夹名"
                        else -> "最终位置：${FormatUtil.formatDisplayPath(finalTargets.first().path)}"
                    }
                    PickerActionCard(
                        enabled = canUseCurrent && mode != StorageFolderMode.RECENT,
                        title = if (purpose == AlbumStoragePickPurpose.MERGE_MEDIA) "✓ 合并至当前文件夹" else "✓ 使用当前文件夹",
                        hint = finalHint,
                        onClick = { onSelectRoot(canonical(currentDir)) }
                    )
                }
                Spacer(Modifier.height(6.dp))

                val showCuratedRoot = !addingCurated && mode == StorageFolderMode.CURATED &&
                    currentDir.path == storageRoot.path
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        !addingCurated && mode == StorageFolderMode.RECENT -> {
                            if (validRecentRoots.isEmpty()) {
                                item { EmptyFolderMessage("暂无最近迁移位置") }
                            }
                            items(validRecentRoots, key = { it.second.path }) { (recent, directory) ->
                                DirectoryRow(
                                    title = directory.name.ifBlank { directory.path },
                                    path = directory.path,
                                    trailing = migrationTimeAgo(recent.movedAt),
                                    enabled = !isSourceOrChild(directory),
                                    onClick = {
                                        currentDir = directory
                                        searchQuery = ""
                                        mode = StorageFolderMode.SYSTEM
                                    }
                                )
                            }
                        }
                        showCuratedRoot -> {
                            if (curatedDirectories.isEmpty()) {
                                item { EmptyFolderMessage("暂无精选文件夹，可点击“管理精选”添加") }
                            }
                            items(curatedDirectories, key = { it.path }) { directory ->
                                DirectoryRow(
                                    title = directory.name,
                                    path = directory.path,
                                    enabled = !isSourceOrChild(directory),
                                    onClick = { currentDir = canonical(directory); searchQuery = "" }
                                )
                            }
                        }
                        else -> {
                            if (childDirectories.isEmpty()) {
                                item { EmptyFolderMessage("当前目录没有子文件夹") }
                            }
                            items(childDirectories, key = { it.path }) { directory ->
                                DirectoryRow(
                                    title = directory.name,
                                    path = directory.path,
                                    enabled = !isSourceOrChild(directory),
                                    onClick = { currentDir = canonical(directory); searchQuery = "" }
                                )
                            }
                        }
                    }
                }

                // 管理精选只选择现有目录；不能从这个入口创建或修改真实文件夹。
                if (!addingCurated && mode != StorageFolderMode.RECENT) {
                    TextButton(onClick = { showCreateFolder = true }) { Text("＋ 新建文件夹") }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Row {
                if (addingCurated) TextButton(onClick = ::returnToCurated) { Text("返回精选") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )

    if (showManageCurated && !addingCurated) {
        AlertDialog(
            onDismissRequest = { showManageCurated = false },
            title = { Text("管理精选文件夹") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    if (curatedPaths.isEmpty()) item { EmptyFolderMessage("精选列表为空") }
                    items(curatedPaths, key = { it.lowercase() }) { path ->
                        val directory = File(path)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(directory.name.ifBlank { path }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (directory.isDirectory) FormatUtil.formatDisplayPath(path) else "目录不存在",
                                    fontSize = 10.sp,
                                    color = Color.Gray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(onClick = {
                                curatedPaths = curatedStore.save(curatedPaths.filterNot {
                                    it.equals(path, ignoreCase = true)
                                })
                            }) { Text("移除") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showManageCurated = false
                    addingCurated = true
                    mode = StorageFolderMode.SYSTEM
                    currentDir = storageRoot
                    searchQuery = ""
                }) { Text("＋ 添加") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { curatedPaths = curatedStore.reset() }) { Text("恢复默认") }
                    TextButton(onClick = { showManageCurated = false }) { Text("完成") }
                }
            }
        )
    }

    if (showCreateFolder) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolder = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    singleLine = true,
                    placeholder = { Text("文件夹名称") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    val name = folderName.trim()
                    if (name.isEmpty() || name.startsWith(".") || name.contains('/') || name.contains('\\')) {
                        Toast.makeText(context, "文件夹名称无效", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val created = File(currentDir, name)
                    if (created.mkdir() || created.isDirectory) {
                        currentDir = canonical(created)
                        searchQuery = ""
                        refreshTick++
                        showCreateFolder = false
                    } else {
                        Toast.makeText(context, "创建文件夹失败", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("创建并进入") }
            },
            dismissButton = { TextButton(onClick = { showCreateFolder = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun PickerActionCard(enabled: Boolean, title: String, hint: String, onClick: () -> Unit) {
    Surface(
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(hint, fontSize = 11.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun EmptyFolderMessage(text: String) {
    Text(text, color = Color.Gray, modifier = Modifier.padding(12.dp))
}

@Composable
private fun DirectoryRow(
    title: String,
    path: String,
    trailing: String = "›",
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("📁", fontSize = 17.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (enabled) Color.Unspecified else Color.Gray)
            Text(FormatUtil.formatDisplayPath(path), fontSize = 10.sp, color = Color.Gray,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(trailing, fontSize = 11.sp, color = Color.Gray)
    }
}
