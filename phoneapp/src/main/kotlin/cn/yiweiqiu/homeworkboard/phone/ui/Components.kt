package cn.yiweiqiu.homeworkboard.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * 设置页的版式组件，和课时簿 / 动账保持同一套骨架：
 *
 *   SectionHeader（标题 + 一句解释）  →  AppCard（圆角 16、1dp 描边、白底）
 *   卡片内部用 HorizontalDivider 切行，行内左边是说明、右边是动作。
 *
 * 这套骨架不是装饰：每个分区自带一句「为什么」，家长不用点进去猜。
 * 作业板原来的设置页是一列裸输入框，四个字段平权、没有主次，也看不出当前到底连没连上。
 */

/** 圆角 16 的白卡片，全站唯一的容器单位 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, phoneLine.copy(alpha = 0.85f), shape)
            .padding(contentPadding),
        content = content,
    )
}

/** 分区标题：一句话说清这块管什么，别让家长靠猜 */
@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = phoneMuted,
            )
        }
    }
}

/**
 * 设置页顶栏：返回箭头 + 标题 + 一句副标题。
 *
 * 必须垫一层不透明底色。顶栏和滚动区是 Column 里的兄弟节点，内容滚到顶时会被视口
 * 裁在顶栏下边缘——不留底色的话，卡片边框和半行字会紧贴着副标题露出来，
 * 看着像标题被划了一道。这里补上底色 + 底部留白，滚动边界才是干净的。
 */
@Composable
fun SettingsTopBar(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = phoneInk,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = phoneMuted,
                )
            }
        }
    }
}

/** 行首的小方图标：34dp 圆角方块 + 12% 透明度的同色底 */
@Composable
fun IconTile(
    icon: ImageVector,
    tint: Color,
    size: Int = 34,
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(tint.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size((size * 0.54f).dp),
        )
    }
}

/** 卡片内的一条分隔线 */
@Composable
fun CardDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(vertical = 12.dp),
        color = phoneLine.copy(alpha = 0.75f),
    )
}

/**
 * 状态条：成功 / 提醒 / 失败都走这一个。
 *
 * 以前「测试连接」的结果是一行裸文字，成功和失败只靠颜色区分——
 * 深色主题下那行绿字放在白卡片上根本看不出来，而且失败时的完整原因会被挤成一长条。
 */
@Composable
fun StatusBanner(
    text: String,
    ok: Boolean,
    title: String? = null,
    modifier: Modifier = Modifier,
) {
    val tone = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val soft = if (ok) phoneSuccessSoft else phoneErrorSoft
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(soft)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = tone,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (title != null) phoneMuted else tone,
        )
    }
}

/**
 * 一排动作按钮里的文字：强制单行、统一字号。
 *
 * 「保存」「测试连接」「取消」字数差一倍，各自用默认 padding 会高矮不齐；
 * 等宽 + 等高 + 同一字号之后，靠「实心 / 描边 / 纯文字」区分主次就够了。
 */
@Composable
fun ActionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
    )
}

/** 说明小字，统一成这个间距，避免每处自己写 Spacer */
@Composable
fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = phoneMuted,
        modifier = modifier,
    )
}

/** 竖排间距的简写 */
@Composable
fun VGap(height: Int) = Spacer(Modifier.height(height.dp))

/** 横排间距的简写 */
@Composable
fun HGap(width: Int) = Spacer(Modifier.width(width.dp))

/** 一行「左边说明、右边动作」的横排容器 */
@Composable
fun SplitRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) = Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
    content = content,
)
