package com.sadaqah.kiosk.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.common.BitMatrix
import com.sadaqah.kiosk.Strings
import com.sadaqah.kiosk.responsiveDp
import com.sadaqah.kiosk.responsiveSp
import com.sadaqah.kiosk.telemetry.DisclosureView
import com.sadaqah.kiosk.telemetry.QrEncoder

// Fixed, not settings-driven: this screen's signature (see DisclosurePresenter's
// caller in MainActivity) carries no Settings reference to draw a kiosk's brand
// colours from, and a compliance disclosure reading the same on every kiosk
// regardless of its chosen palette is not a loss. Same style as the hardcoded
// semantic colours already used in AnalyticsSettingsScreen (warningColor,
// errorColor) — this just extends that to the whole screen.
private val DisclosureBackground = Color.White
private val DisclosureText = Color(0xFF1A1A1A)
private val DisclosureButton = Color(0xFF1A1A1A)

/**
 * The telemetry disclosure. Renders [view] and nothing else — every "should this
 * show at all" and "is there a terms URL" question is already answered by
 * [DisclosureView], via [com.sadaqah.kiosk.telemetry.DisclosurePresenter]. This
 * composable is unreachable from a JVM test, so it must not decide anything a
 * test could otherwise pin.
 */
@Composable
fun DisclosureScreen(
    view: DisclosureView,
    strings: Strings,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DisclosureBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(responsiveDp(24.dp)),
            verticalArrangement = Arrangement.spacedBy(responsiveDp(18.dp))
        ) {
            Text(
                text = strings.disclosureTitle,
                color = DisclosureText,
                fontSize = responsiveSp(32.0),
                fontWeight = FontWeight.Bold
            )

            Text(
                text = strings.disclosureIntro,
                color = DisclosureText,
                fontSize = responsiveSp(14.0)
            )

            // ── What is sent ───────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(responsiveDp(4.dp))) {
                DisclosureHeading(strings.disclosureSendsHeading)
                DisclosureBullet(strings.disclosureSendsAmount)
                // Renders unconditionally: the copy itself reads "its kiosk code
                // if one is set", which is true whether or not one is — no
                // per-kiosk variant needed here or in the presenter.
                DisclosureBullet(strings.disclosureSendsIdentity)
                DisclosureBullet(strings.disclosureSendsHealth)
            }

            Text(
                text = strings.disclosureIdentified,
                color = DisclosureText,
                fontSize = responsiveSp(13.0),
                fontWeight = FontWeight.Bold
            )

            // ── What is never sent ─────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(responsiveDp(4.dp))) {
                DisclosureHeading(strings.disclosureNeverHeading)
                Text(strings.disclosureNeverBody, color = DisclosureText, fontSize = responsiveSp(13.0))
            }

            // ── Destination ─────────────────────────────────────────────────
            // Text only, no QR: unlike the policy/terms links, this is the
            // ingestion endpoint itself, not a page meant to be read on a phone.
            Column(verticalArrangement = Arrangement.spacedBy(responsiveDp(4.dp))) {
                DisclosureHeading(strings.disclosureDestinationHeading)
                SelectionContainer {
                    Text(view.destinationUrl, color = DisclosureText, fontSize = responsiveSp(13.0))
                }
                Text(
                    text = strings.disclosureOffBody,
                    color = DisclosureText.copy(alpha = 0.7f),
                    fontSize = responsiveSp(12.0)
                )
            }

            // ── Policy / terms ──────────────────────────────────────────────
            // QR because lock task mode blocks launching a browser (MainActivity
            // calls startLockTask) — a tappable link is inert here, so the
            // operator's own phone is the only way to actually open either page.
            DisclosureUrlBlock(label = strings.disclosurePrivacyLabel, url = view.privacyUrl)

            val termsUrl = view.termsUrl
            if (termsUrl != null) {
                DisclosureUrlBlock(label = strings.disclosureTermsLabel, url = termsUrl)
            }

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = DisclosureButton),
                shape = RoundedCornerShape(responsiveDp(10.dp)),
                border = BorderStroke(responsiveDp(2.dp), DisclosureText),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(strings.disclosureDismiss, color = Color.White, fontSize = responsiveSp(14.0))
            }

            Spacer(modifier = Modifier.height(responsiveDp(8.dp)))
        }
    }
}

// ── Subcomponents ────────────────────────────────────────────────────────────

@Composable
private fun DisclosureHeading(text: String) {
    Text(text, color = DisclosureText, fontSize = responsiveSp(18.0), fontWeight = FontWeight.Bold)
}

@Composable
private fun DisclosureBullet(text: String) {
    Text("• $text", color = DisclosureText, fontSize = responsiveSp(13.0))
}

/**
 * A label, the URL as selectable text, and its QR code beside it. The matrix is
 * re-encoded (not cached across recompositions beyond `remember`) because a URL
 * changes only on a fresh [DisclosureView], which is itself a new save — cheap
 * relative to that.
 */
@Composable
private fun DisclosureUrlBlock(label: String, url: String) {
    val matrix = remember(url) { QrEncoder.encode(url) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(responsiveDp(12.dp))
    ) {
        Column(modifier = Modifier.weight(1f)) {
            DisclosureHeading(label)
            SelectionContainer {
                Text(url, color = DisclosureText, fontSize = responsiveSp(12.0))
            }
        }
        // Null is a real, expected outcome (see QrEncoder.encode's KDoc) — these
        // URLs are never validated, so silently drawing nothing beside the text
        // is the correct behaviour here, not a fallback for an error case.
        if (matrix != null) {
            DisclosureQrCode(matrix = matrix, edge = responsiveDp(88.dp))
        }
    }
}

/**
 * Draws [matrix] with one filled rect per dark module, scaled to [edge] — never
 * converted to a Bitmap (see QrEncoder.encode's KDoc on why: at pixel size this
 * would be ~130,000 draw calls per code instead of ~45 per side). The matrix
 * already carries its own quiet zone; drawing it edge-to-edge across [edge]
 * reproduces that margin instead of adding a second one on top of it.
 */
@Composable
private fun DisclosureQrCode(matrix: BitMatrix, edge: Dp) {
    Canvas(modifier = Modifier.size(edge)) {
        val moduleWidth = size.width / matrix.width
        val moduleHeight = size.height / matrix.height
        for (row in 0 until matrix.height) {
            for (col in 0 until matrix.width) {
                if (matrix.get(col, row)) {
                    drawRect(
                        color = DisclosureText,
                        topLeft = Offset(col * moduleWidth, row * moduleHeight),
                        size = Size(moduleWidth, moduleHeight)
                    )
                }
            }
        }
    }
}
