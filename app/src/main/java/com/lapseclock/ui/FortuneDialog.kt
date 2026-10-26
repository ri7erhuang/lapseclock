package com.lapseclock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lapseclock.fortune.Fortune
import com.lapseclock.ui.theme.ClockTheme

/**
 * 每日一签的签文弹窗。点遮罩或「收下」都算确认 —— 确认即代表今天看过了，
 * 主页上的「签」图标当天不再出现。
 */
@Composable
fun FortuneDialog(
    fortune: Fortune,
    theme: ClockTheme,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onConfirm) {
        Column(
            modifier = Modifier
                .width(370.dp)
                .background(Color(0xFF141414), RoundedCornerShape(18.dp))
                .padding(horizontal = 28.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = fortune.level,
                color = theme.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 4.sp,
            )

            Spacer(Modifier.height(18.dp))

            // 两句签文各占一行，比挤在一行更像签纸
            Text(
                text = fortune.poem.replace("，", "，\n"),
                color = theme.main,
                fontSize = 19.sp,
                lineHeight = 34.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(18.dp))

            Text(
                text = fortune.reading,
                color = theme.dim,
                fontSize = 14.sp,
                lineHeight = 23.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "宜 ${fortune.good}",
                    color = theme.accent.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                )
                Text(
                    text = "　·　",
                    color = Color(0xFF3A3A3A),
                    fontSize = 13.sp,
                )
                Text(
                    text = "忌 ${fortune.bad}",
                    color = theme.dim,
                    fontSize = 13.sp,
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "收下",
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(theme.accent.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                    .clickable { onConfirm() }
                    .padding(vertical = 12.dp),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = "收下后今天不再出现",
                color = theme.dim.copy(alpha = 0.55f),
                fontSize = 11.sp,
            )
        }
    }
}
