package com.sadaqah.kiosk.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.common.BitMatrix
import com.sadaqah.kiosk.R
import com.sadaqah.kiosk.Strings
import com.sadaqah.kiosk.model.Settings
import com.sadaqah.kiosk.responsiveDp
import com.sadaqah.kiosk.responsiveSp
import com.sadaqah.kiosk.telemetry.DisclosureView
import com.sadaqah.kiosk.telemetry.QrEncoder

/**
 * The telemetry disclosure. Renders [view] and nothing else — every "should this
 * show at all" and "is there a terms URL" question is already answered by
 * [DisclosureView], via [com.sadaqah.kiosk.telemetry.DisclosurePresenter]. This
 * composable is unreachable from a JVM test, so it must not decide anything a
 * test could otherwise pin.
 *
 * Coloured from [settings], same as every other screen — a kiosk is branded per
 * deployment, and this is the one screen whose job is telling an operator what
 * leaves their device; rendering it in a palette that doesn't match the kiosk
 * around it would read as belonging to a different app.
 */
@Composable
fun DisclosureScreen(
    view: DisclosureView,
    settings: Settings,
    strings: Strings,
    onDismiss: () -> Unit
) {
    val border = Color(settings.buttonBorderColor)
    val button = Color(settings.buttonColor)

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
            colorFilter = ColorFilter.tint(Color(settings.patternColor), blendMode = BlendMode.Modulate)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(responsiveDp(24.dp)),
            verticalArrangement = Arrangement.spacedBy(responsiveDp(18.dp))
        ) {
            Text(
                text = strings.disclosureTitle,
                color = border,
                fontSize = responsiveSp(32.0),
                fontWeight = FontWeight.Bold
            )

            Text(
                text = strings.disclosureIntro,
                color = border,
                fontSize = responsiveSp(14.0)
            )

            // ── What is sent ───────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(responsiveDp(4.dp))) {
                DisclosureHeading(strings.disclosureSendsHeading, border)
                DisclosureBullet(strings.disclosureSendsAmount, border)
                // Renders unconditionally: the copy itself reads "its kiosk code
                // if one is set", which is true whether or not one is — no
                // per-kiosk variant needed here or in the presenter.
                DisclosureBullet(strings.disclosureSendsIdentity, border)
                DisclosureBullet(strings.disclosureSendsHealth, border)
            }

            Text(
                text = strings.disclosureIdentified,
                color = border,
                fontSize = responsiveSp(13.0),
                fontWeight = FontWeight.Bold
            )

            // ── What is never sent ─────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(responsiveDp(4.dp))) {
                DisclosureHeading(strings.disclosureNeverHeading, border)
                Text(strings.disclosureNeverBody, color = border, fontSize = responsiveSp(13.0))
            }

            // ── Destination ─────────────────────────────────────────────────
            // Text only, no QR: unlike the policy/terms links, this is the
            // ingestion endpoint itself, not a published document — there is
            // nothing there for a person to read, and a scannable code beside
            // it would invite someone to try. Selectable text lets the operator
            // verify what they typed without suggesting there's more to visit.
            Column(verticalArrangement = Arrangement.spacedBy(responsiveDp(4.dp))) {
                DisclosureHeading(strings.disclosureDestinationHeading, border)
                SelectionContainer {
                    Text(view.destinationUrl, color = border, fontSize = responsiveSp(13.0))
                }
                Text(
                    text = strings.disclosureOffBody,
                    color = border.copy(alpha = 0.7f),
                    fontSize = responsiveSp(12.0)
                )
            }

            // ── Policy / terms ──────────────────────────────────────────────
            // QR because lock task mode blocks launching a browser (MainActivity
            // calls startLockTask) — a tappable link is inert here, so the
            // operator's own phone is the only way to actually open either page.
            DisclosureUrlBlock(label = strings.disclosurePrivacyLabel, url = view.privacyUrl, color = border)

            val termsUrl = view.termsUrl
            if (termsUrl != null) {
                DisclosureUrlBlock(label = strings.disclosureTermsLabel, url = termsUrl, color = border)
            }

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = button),
                shape = RoundedCornerShape(responsiveDp(10.dp)),
                border = BorderStroke(responsiveDp(2.dp), border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(strings.disclosureDismiss, color = border, fontSize = responsiveSp(14.0))
            }

            Spacer(modifier = Modifier.height(responsiveDp(8.dp)))
        }
    }
}

// ── Subcomponents ────────────────────────────────────────────────────────────

@Composable
private fun DisclosureHeading(text: String, color: Color) {
    Text(text, color = color, fontSize = responsiveSp(18.0), fontWeight = FontWeight.Bold)
}

@Composable
private fun DisclosureBullet(text: String, color: Color) {
    Text("• $text", color = color, fontSize = responsiveSp(13.0))
}

/**
 * A label, the URL as selectable text, and its QR code beside it. The matrix is
 * re-encoded (not cached across recompositions beyond `remember`) because a URL
 * changes only on a fresh [DisclosureView], which is itself a new save — cheap
 * relative to that.
 */
@Composable
private fun DisclosureUrlBlock(label: String, url: String, color: Color) {
    val matrix = remember(url) { QrEncoder.encode(url) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(responsiveDp(12.dp))
    ) {
        Column(modifier = Modifier.weight(1f)) {
            DisclosureHeading(label, color)
            SelectionContainer {
                Text(url, color = color, fontSize = responsiveSp(12.0))
            }
        }
        // Null is a real, expected outcome (see QrEncoder.encode's KDoc) — these
        // URLs are never validated, so silently drawing nothing beside the text
        // is the correct behaviour here, not a fallback for an error case.
        if (matrix != null) {
            DisclosureQrCode(matrix = matrix, edge = QrModuleDp * matrix.width)
        }
    }
}

// Fixed regardless of kiosk branding — deliberately not derived from `settings`.
// Light modules (including the whole quiet zone) are painted, not left
// transparent: on a dark-branded kiosk, transparent light modules would show
// through as the kiosk's own dark background and patterned image, producing an
// inverted symbol with texture running through the quiet zone. Most phone
// cameras — the iOS one included — do not read an inverted code, and pattern
// detail in the quiet zone can stop a scanner finding the symbol at all. Both
// are camera-only failures no unit test here can catch. Scannability outranks
// matching the kiosk's palette for the one element on this screen that has to
// work against a lens; the panel around the code stays branded, the code itself
// does not.
private val QrLight = Color(0xFFFFFFFF)
private val QrDark = Color(0xFF000000)

// A fixed edge (or one scaled only by responsiveDp's 0.5-1.5 screen factor)
// does not track the symbol's own module count, which ranges roughly 37x37
// to 65x65 across the URL lengths this screen actually renders — the long
// end is a 200-character URL, the length QrEncoderTest itself exercises. A
// code held to a constant edge gets *less* readable as the payload grows,
// which is backwards: it needs to grow with it. 6dp/module is chosen as a
// floor a phone camera can resolve at arm's length under shop lighting; it
// is deliberately not run through responsiveDp, since shrinking module size
// on a small screen is the opposite of what scanning needs.
private val QrModuleDp = 6.dp

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
        // The opaque light card: without it, every light module — the quiet
        // zone included — is transparent and shows whatever is behind this
        // Canvas instead of a clean white margin.
        drawRect(color = QrLight, size = size)
        // Modules are drawn at a whole-pixel size and offset, not a fractional
        // one: size.width / matrix.width is rarely an integer, and a rect
        // whose edge lands mid-pixel gets anti-aliased into a soft gray line
        // between modules instead of a hard black/white edge — exactly the
        // ambiguity a decoder's binarizer struggles with. Flooring to Int
        // trades a few leftover pixels of extra quiet zone at the far edge
        // (already light, since drawRect above already filled the whole
        // canvas white) for edges a camera can actually threshold.
        val moduleSize = kotlin.math.floor(
            minOf(size.width / matrix.width, size.height / matrix.height)
        ).toInt().coerceAtLeast(1)
        for (row in 0 until matrix.height) {
            for (col in 0 until matrix.width) {
                if (matrix.get(col, row)) {
                    drawRect(
                        color = QrDark,
                        topLeft = Offset((col * moduleSize).toFloat(), (row * moduleSize).toFloat()),
                        size = Size(moduleSize.toFloat(), moduleSize.toFloat())
                    )
                }
            }
        }
    }
}
