package com.sadaqah.kiosk.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.rememberAsyncImagePainter
import com.sadaqah.kiosk.model.Settings
import com.sadaqah.kiosk.R
import com.sadaqah.kiosk.rememberStrings
import com.sadaqah.kiosk.responsiveDp
import com.sadaqah.kiosk.responsiveSp

@Composable
fun AffiliateLoginScreen(
    affiliateKey: String,
    onKeyChange: (String) -> Unit,
    onLogin: () -> Unit,
    authenticate: (String) -> Unit,
    connectCardReader: () -> Unit,
    onSettingsClick: () -> Unit,
    settings: Settings
) {
    val context = LocalContext.current
    val strings = rememberStrings()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(settings.backgroundColor))
    ) {
        Image(
            painter = painterResource(id = R.drawable.pattern),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.tint(
                Color(settings.patternColor),
                blendMode = BlendMode.Modulate
            )
        )

        Button(
            onClick = onSettingsClick,
            modifier = Modifier
                .padding(top = responsiveDp(16.dp), start = responsiveDp(8.dp))
                .size(responsiveDp(96.dp)),
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color(settings.buttonBorderColor)
            ),
        ) {
            Image(
                painter = painterResource(id = R.drawable.gear_icon),
                contentDescription = strings.settings,
                modifier = Modifier.fillMaxSize(),
                colorFilter = ColorFilter.tint(
                    Color(settings.buttonBorderColor),
                    blendMode = BlendMode.Modulate
                )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = responsiveDp(150.dp)),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(responsiveDp(80.dp)))
            Text(
                text = strings.welcome,
                color = Color(settings.buttonBorderColor),
                fontSize = responsiveSp(80.0),
                lineHeight = responsiveSp(80.0),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(responsiveDp(200.dp)))
            val fieldHeight = responsiveDp(70.dp).coerceAtLeast(56.dp)
            Box(
                modifier = Modifier
                    .height(fieldHeight)
                    .fillMaxWidth()
                    .border(
                        width = responsiveDp(4.dp),
                        color = Color(settings.buttonBorderColor),
                        shape = RoundedCornerShape(responsiveDp(25.dp))
                    )
                    .padding(responsiveDp(1.dp))
            ) {
                OutlinedTextField(
                    value = affiliateKey,
                    onValueChange = onKeyChange,
                    placeholder = {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = strings.sumupAffiliateKey,
                                color = Color(settings.buttonBorderColor),
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                fontSize = responsiveSp(25.0),
                                lineHeight = responsiveSp(25.0)
                            )
                        }
                    },
                    textStyle = TextStyle(
                        color = Color(settings.buttonBorderColor),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(settings.buttonColor),
                        unfocusedBorderColor = Color(settings.buttonColor),
                        cursorColor = Color(settings.buttonColor),
                        focusedLabelColor = Color(settings.buttonColor),
                        focusedTextColor = Color(settings.buttonBorderColor),
                        unfocusedTextColor = Color(settings.buttonBorderColor),
                    ),
                    shape = RoundedCornerShape(responsiveDp(25.dp)),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(fieldHeight)
                )
            }

            Spacer(modifier = Modifier.height(responsiveDp(24.dp)))
            Button(
                onClick = {
                    Toast.makeText(context, strings.affiliateKeySaved, Toast.LENGTH_SHORT).show()
                    authenticate(affiliateKey)
                    onLogin()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(settings.buttonColor)),
                shape = RoundedCornerShape(responsiveDp(25.dp)),
                border = BorderStroke(responsiveDp(4.dp), Color(settings.buttonBorderColor)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(responsiveDp(70.dp))

            ) {
                Text(
                    strings.logIn,
                    color = Color(settings.buttonBorderColor),
                    fontWeight = FontWeight.Bold,
                    fontSize = responsiveSp(25.0),
                    lineHeight = responsiveSp(25.0),
                )
            }
            Spacer(modifier = Modifier.height(responsiveDp(32.dp)))
            if (!settings.logoUri.isNullOrBlank()) {
                val logoUri = remember(settings.logoUri) {
                    settings.logoUri.toUri()
                }
                Image(
                    painter = rememberAsyncImagePainter(logoUri),
                    contentDescription = strings.logoImage,
                    modifier = Modifier
                        .size(responsiveDp(400.dp))
                        .clip(RoundedCornerShape(responsiveDp(12.dp))),
                    contentScale = ContentScale.Crop
                )
            }
            else {
                Spacer(modifier = Modifier.height(responsiveDp(400.dp)))
            }
            Spacer(modifier = Modifier.height(responsiveDp(32.dp)))
        }
    }
}
