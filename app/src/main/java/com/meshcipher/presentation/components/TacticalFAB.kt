package com.meshcipher.presentation.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.meshcipher.R
import com.meshcipher.presentation.theme.OnSecureGreen
import com.meshcipher.presentation.theme.SecureGreen
import com.meshcipher.presentation.theme.TextPrimary

@Composable
fun TacticalFAB(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FloatingActionButton(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        containerColor = SecureGreen,
        contentColor = TextPrimary,
        modifier = modifier.size(56.dp)
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_shield_plus),
            contentDescription = "New secure conversation",
            tint = OnSecureGreen,
            modifier = Modifier.size(24.dp)
        )
    }
}
