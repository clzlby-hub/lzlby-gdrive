package org.moontechlab.gdrivetv.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.moontechlab.gdrivetv.data.auth.AuthState
import org.moontechlab.gdrivetv.data.auth.GoogleOAuthManager
import org.moontechlab.gdrivetv.data.server.PhonePairingServer
import org.moontechlab.gdrivetv.data.store.PreferenceStore
import org.moontechlab.gdrivetv.ui.util.QrCodeGenerator

@Composable
fun LoginScreen(
    prefs: PreferenceStore,
    authManager: GoogleOAuthManager,
    pairingServer: PhonePairingServer,
    onLoginSuccess: () -> Unit
) {
    val authState by authManager.authState.collectAsState()
    val localIp = remember { pairingServer.getLocalIpAddress() }
    val pairingUrl = "http://$localIp:${pairingServer.port}"

    LaunchedEffect(authState) {
        if (authState is AuthState.Authorized || prefs.isLoggedIn()) {
            onLoginSuccess()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF131826),
                        Color(0xFF090B10),
                        Color(0xFF040508)
                    ),
                    radius = 1600f
                )
            )
            .padding(horizontal = 64.dp, vertical = 44.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(52.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Column: Apple TV Setup Guide
            Column(
                modifier = Modifier
                    .weight(1.25f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center
            ) {
                // Apple TV Pill Header
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0x1A2997FF))
                        .border(1.dp, Color(0x332997FF), CircleShape)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Cloud,
                        contentDescription = null,
                        tint = Color(0xFF2997FF),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "GOOGLE DRIVE · 授权连接",
                        color = Color(0xFF2997FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Big Apple Typography Title
                Text(
                    text = "登录您的 Google 账号",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF5F5F7),
                    letterSpacing = (-0.5).sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "随时扫码换账号登录 · 全程在手机端安全操作，无需在电视输入繁琐密码。",
                    fontSize = 15.sp,
                    color = Color(0xFFA1A1A6),
                    lineHeight = 22.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Apple TV Frosted Glass Steps Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0x14FFFFFF))
                        .border(1.dp, Color(0x20FFFFFF), RoundedCornerShape(22.dp)),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0x14FFFFFF))
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Step 1
                        AppleStepItem(
                            stepNumber = "1",
                            icon = Icons.Outlined.QrCodeScanner,
                            title = "手机扫码",
                            subtitle = "打开 iPhone 相机、iPad 或微信轻扫右侧二维码"
                        )

                        HorizontalDivider(color = Color(0x14FFFFFF))

                        // Step 2
                        AppleStepItem(
                            stepNumber = "2",
                            icon = Icons.Outlined.AccountCircle,
                            title = "填写授权凭证",
                            subtitle = "在手机页面填入凭证信息并点击保存"
                        )

                        HorizontalDivider(color = Color(0x14FFFFFF))

                        // Step 3
                        AppleStepItem(
                            stepNumber = "3",
                            icon = Icons.Outlined.Tv,
                            title = "电视即刻同步",
                            subtitle = "授权完成后电视屏幕将瞬间进入您的云盘相册"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Service Status Indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color(0xFF30D158), CircleShape)
                    )
                    Text(
                        text = "电视配对服务运行中 · $pairingUrl",
                        fontSize = 13.sp,
                        color = Color(0xFF8E8E93)
                    )
                }
            }

            // Right Column: Apple TV Floating QR Pedestal
            Column(
                modifier = Modifier
                    .weight(0.95f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val qrBitmap = remember(pairingUrl) {
                    QrCodeGenerator.generate(pairingUrl, size = 420)
                }

                // Apple TV Frosted Glass Pedestal
                Card(
                    modifier = Modifier
                        .wrapContentSize()
                        .clip(RoundedCornerShape(32.dp))
                        .background(Color(0x16FFFFFF))
                        .border(1.dp, Color(0x28FFFFFF), RoundedCornerShape(32.dp)),
                    shape = RoundedCornerShape(32.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0x16FFFFFF))
                ) {
                    Column(
                        modifier = Modifier.padding(26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        // QR Code Canvas with rounded corners
                        if (qrBitmap != null) {
                            Box(
                                modifier = Modifier
                                    .size(240.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color.White)
                                    .padding(14.dp)
                            ) {
                                Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = "Login QR Code",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        // Apple-style Scan instructions
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CameraAlt,
                                    contentDescription = null,
                                    tint = Color(0xFF2997FF),
                                    modifier = Modifier.size(17.dp)
                                )
                                Text(
                                    text = "打开手机【相机】或【微信】扫码",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFF5F5F7)
                                )
                            }
                            Text(
                                text = "支持任意手机浏览器一键登录",
                                fontSize = 12.sp,
                                color = Color(0xFF8E8E93)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppleStepItem(
    stepNumber: String,
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Step icon badge
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(Color(0x222997FF), CircleShape)
                .border(1.dp, Color(0x442997FF), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFF2997FF),
                modifier = Modifier.size(18.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$stepNumber. $title",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFF5F5F7)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = Color(0xFF8E8E93)
            )
        }
    }
}
