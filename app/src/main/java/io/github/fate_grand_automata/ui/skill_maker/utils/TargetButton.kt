package io.github.fate_grand_automata.ui.skill_maker.utils

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TargetButton(
    onClick: () -> Unit,
    color: Color,
    enabled: Boolean = true,
    text: String,
    width: Dp = 120.dp,
    height: Dp = 75.dp
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color.White
        ),
        modifier = Modifier
            .height(height)
            .width(width),
        enabled = enabled
    ) {
        Text(
            text,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Clip,
            fontSize = 17.sp
        )
    }
}