package cn.yiweiqiu.homeworkboard.phone.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import android.graphics.BitmapFactory
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import cn.yiweiqiu.homework.core.DavSession
import cn.yiweiqiu.homework.core.SubjectPalette
import cn.yiweiqiu.homeworkboard.phone.EditorState
import cn.yiweiqiu.homeworkboard.phone.ImageTools
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.io.File

private val SUBJECTS = listOf("语文", "数学", "英语", "其他")

@Composable
fun EditorScreen(
    state: EditorState,
    sending: Boolean,
    onBack: () -> Unit,
    onSubject: (String) -> Unit,
    onTitle: (String) -> Unit,
    onDetail: (String) -> Unit,
    onAddImages: (List<ByteArray>) -> Unit,
    onRemoveKept: (String) -> Unit,
    onRemoveNew: (Int) -> Unit,
    onSubmit: () -> Unit,
) {
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    val detailFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // 进页面就弹键盘、光标在文字框：省一次点击，这是"3 次点击发出去"的关键
    LaunchedEffect(Unit) {
        if (!state.isEditing) {
            focus.requestFocus()
            // 焦点是异步建立的：同一帧里 show() 会因为「还没有输入连接」被系统丢掉，
            // 表现就是「有时弹、有时不弹」。等输入连接就绪再补一次。
            delay(200)
            keyboard?.show()
        }
    }

    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9),
    ) { uris ->
        if (uris.isNotEmpty()) onAddImages(uris.mapNotNull { ImageTools.compress(context, it) })
    }

    var shotFile by remember { mutableStateOf<File?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = shotFile
        if (ok && file != null) {
            ImageTools.compress(file)?.let { onAddImages(listOf(it)) }
        }
        file?.delete()
        shotFile = null
    }

    fun takePhoto() {
        val dir = File(context.cacheDir, "shots").apply { mkdirs() }
        val file = File(dir, "shot-${System.currentTimeMillis()}.jpg")
        shotFile = file
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        camera.launch(uri)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = phoneInk)
            }
            Text(
                text = if (state.isEditing) "改这条" else "记一条作业",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // 科目：一排 chips，默认上次用的，点一下切换，不点也合法
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SUBJECTS.forEach { subject ->
                    SubjectChip(
                        subject = subject,
                        selected = state.subject == subject,
                        onClick = { onSubject(subject) },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = state.title,
                onValueChange = onTitle,
                modifier = Modifier
                    .fillMaxWidth()
                    .tapToFocus(focus, keyboard)
                    .focusRequester(focus),
                placeholder = { Text("写一句话，比如「生字抄写」") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
                colors = fieldColors(),
            )

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = state.detail,
                onValueChange = onDetail,
                modifier = Modifier
                    .fillMaxWidth()
                    .tapToFocus(detailFocus, keyboard)
                    .focusRequester(detailFocus)
                    .height(120.dp),
                placeholder = { Text("补充说明（可不填）") },
                textStyle = MaterialTheme.typography.bodyLarge,
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
            )

            Spacer(Modifier.height(14.dp))

            ImageGrid(
                state = state,
                onPickGallery = {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onTakePhoto = { takePhoto() },
                onRemoveKept = onRemoveKept,
                onRemoveNew = onRemoveNew,
            )

            Spacer(Modifier.height(18.dp))
        }

        // 唯一主按钮
        Button(
            onClick = onSubmit,
            enabled = !sending,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 18.dp)
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            if (sending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
                Text("发送中…")
            } else {
                Text(
                    text = if (state.isEditing) "更新" else "发到电视",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun SubjectChip(subject: String, selected: Boolean, onClick: () -> Unit) {
    val color = Color(SubjectPalette.colorOf(subject))
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) color.copy(alpha = 0.14f) else Color.Transparent)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) color else phoneLine,
                shape = CircleShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = subject,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) color else phoneMuted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImageGrid(
    state: EditorState,
    onPickGallery: () -> Unit,
    onTakePhoto: () -> Unit,
    onRemoveKept: (String) -> Unit,
    onRemoveNew: (Int) -> Unit,
) {
    // 点开大图看的是「合并后」的第几张：先新挑的、后已在云端的，和网格里的顺序一致
    var previewAt by remember { mutableStateOf<Int?>(null) }
    // 编辑页进来就自动弹了键盘，点图之前必须先收掉：
    // 真机上键盘会一直压着「删除这张」，按钮根本点不到（模拟器不弹键盘，看不出来）。
    // 只调 keyboard.hide() 不够——输入框还持有焦点，Dialog 一开焦点又回去、键盘又弹起来，
    // 所以先强制清焦点再收键盘。
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "配图",
                style = MaterialTheme.typography.labelMedium,
                color = phoneMuted,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (state.imageCount == 0) "可不加" else "${state.imageCount} 张",
                style = MaterialTheme.typography.labelMedium,
                color = phoneMuted,
            )
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 4,
        ) {
            // 新挑的图：本地字节直接预览
            state.newImages.forEachIndexed { index, bytes ->
                val bmp = remember(bytes) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
                Box(Modifier.size(72.dp)) {
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    focusManager.clearFocus(force = true)
                                    keyboard?.hide()
                                    previewAt = index
                                },
                        )
                    }
                    RemoveBadge { onRemoveNew(index) }
                }
            }

            // 已在云端的图：逐张渲染。
            //
            // 原来这里是一个「已有 N 张」的汇总方块，看着像「有图但不能看」，
            // 删除按钮还只能删第一张——想删第 2 张得先盲删第 1 张。
            // 现在逐张显示真图、点开能放大，删除按 path 精确到位。
            state.keptImages.forEachIndexed { index, path ->
                Box(Modifier.size(72.dp)) {
                    AsyncImage(
                        model = DavSession.absoluteUrl(path),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(PhoneColors.AccentSoft)
                            .clickable {
                                focusManager.clearFocus(force = true)
                                keyboard?.hide()
                                previewAt = state.newImages.size + index
                            },
                    )
                    RemoveBadge { onRemoveKept(path) }
                }
            }

            if (state.imageCount < 9) {
                AddTile(icon = Icons.Filled.AddAPhoto, label = "相册", onClick = onPickGallery)
                AddTile(icon = Icons.Filled.PhotoCamera, label = "拍照", onClick = onTakePhoto)
            }
        }
    }

    // 张数在预览期间可能变化（删掉一张），越界就自动关掉
    val at = previewAt
    if (at != null) {
        if (at >= state.imageCount) {
            previewAt = null
        } else {
            ImagePreview(
                state = state,
                startAt = at,
                onClose = { previewAt = null },
                onRemoveKept = onRemoveKept,
                onRemoveNew = onRemoveNew,
            )
        }
    }
}

/**
 * 全屏看图：左右滑动切换，底部显示「第几张 / 共几张」，可以当场删掉这张。
 *
 * 手机屏幕小、9 张图缩在 4 列网格里只有 72dp，靠缩略图判断「这张是不是糊了/是不是选错」根本不现实，
 * 尤其编辑老条目时要先看清再决定删哪张。所以点任意缩略图进全屏。
 */
@Composable
private fun ImagePreview(
    state: EditorState,
    startAt: Int,
    onClose: () -> Unit,
    onRemoveKept: (String) -> Unit,
    onRemoveNew: (Int) -> Unit,
) {
    val total = state.imageCount
    val pager = rememberPagerState(initialPage = startAt.coerceIn(0, (total - 1).coerceAtLeast(0))) { total }
    // 编辑页进来就自动弹了键盘，进全屏看图时必须收掉：
    // 真机上键盘会一直压住底部的「删除这张」，按钮点不到（模拟器不弹键盘，看不出来）
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { keyboard?.hide() }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xF2000000)),
        ) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val isNew = page < state.newImages.size
                if (isNew) {
                    val bytes = state.newImages[page]
                    val bmp = remember(bytes) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                        )
                    }
                } else {
                    val path = state.keptImages[page - state.newImages.size]
                    AsyncImage(
                        model = DavSession.absoluteUrl(path),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                    )
                }
            }

            // 顶部工具栏：页码 + 删除 + 关闭。
            //
            // 删除按钮原来在底部，真机上被输入法键盘压住点不到（键盘收不掉：
            // clearFocus / hide() 都试过，百度输入法自己维持着输入连接）。
            // 挪到顶部之后，键盘在不在都挡不着它，点击区也终于够大了。
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${pager.currentPage + 1} / $total",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x66000000))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )

                Spacer(Modifier.weight(1f))

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xCC3A1F1D))
                        .clickable {
                            val page = pager.currentPage
                            if (page < state.newImages.size) {
                                onRemoveNew(page)
                            } else {
                                onRemoveKept(state.keptImages[page - state.newImages.size])
                            }
                            onClose()
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = Color(0xFFFF9A93),
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "删除这张",
                        color = Color(0xFFFF9A93),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun AddTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, phoneLine, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = phoneMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(3.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = phoneMuted)
    }
}

@Composable
private fun BoxScope.RemoveBadge(onRemove: () -> Unit) {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(3.dp)
            .size(20.dp)
            .clip(CircleShape)
            .background(Color(0xB3000000))
            .clickable(onClick = onRemove),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Close,
            contentDescription = "移除",
            tint = Color.White,
            modifier = Modifier.size(13.dp),
        )
    }
}

@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    focusedIndicatorColor = PhoneColors.Accent,
    unfocusedIndicatorColor = phoneLine,
    cursorColor = PhoneColors.Accent,
)
