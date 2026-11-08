package com.evanaronson.linguize.ui.gaps

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.lab.GapHint
import com.evanaronson.linguize.core.lab.Gaps
import com.evanaronson.linguize.ui.components.FooterRow
import com.evanaronson.linguize.ui.copyToClipboard

/**
 * The check card's offer to fill the gaps: shown only when the text has bracketed gaps or
 * looks like it mixes in English, so correct text never gets an extra row.
 */
class GapOffer(val hint: GapHint, val onFill: () -> Unit) {
    companion object {
        fun of(text: String?, onFill: () -> Unit): GapOffer? = text?.let(Gaps::hint)?.let { GapOffer(it, onFill) }
    }
}

/** The offer as the card's footer row. */
@Composable
fun GapOfferRow(offer: GapOffer) {
    val subtitle = when (val hint = offer.hint) {
        is GapHint.Marked -> pluralStringResource(R.plurals.lab_gap_filler_offer_marked, hint.count, hint.count)
        GapHint.Mixed -> stringResource(R.string.lab_gap_filler_offer_mixed)
    }
    FooterRow(
        stringResource(R.string.lab_gap_filler_title), subtitle, offer.onFill,
        onClickLabel = stringResource(R.string.lab_gap_filler_open),
    )
}

/**
 * A popup's card: the check, with an offer to fill gaps in [source], or the fill opened
 * from it. [onPutBack] closes the popup, which puts back [GapFillViewModel.replacement];
 * [onCheck] runs the normal check on the filled text.
 */
@Composable
fun PopupGapsOrCheck(
    gaps: GapFillViewModel,
    source: String?,
    language: Language?,
    canReplace: Boolean,
    onPutBack: () -> Unit,
    onCheck: (String) -> Unit,
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier,
    check: @Composable (GapOffer?) -> Unit,
) {
    val context = LocalContext.current
    val state = gaps.state
    if (state != null) {
        GapFillCard(state, popupActions(gaps, context, canReplace, onPutBack, onCheck, onOpenSettings), modifier)
    } else {
        check(GapOffer.of(source) { source?.let { gaps.start(it, language) } })
    }
}

private fun popupActions(
    gaps: GapFillViewModel,
    context: Context,
    canReplace: Boolean,
    onPutBack: () -> Unit,
    onCheck: (String) -> Unit,
    onOpenSettings: (() -> Unit)?,
) = GapActions(
    onChoose = gaps::choose,
    onRetry = gaps::retry,
    onCopy = { context.copyToClipboard(it) },
    onPutBack = if (canReplace) ({ if (gaps.use() != null) onPutBack() }) else null,
    onCheck = { gaps.check()?.let(onCheck) },
    onBack = gaps::close,
    onOpenSettings = onOpenSettings,
)
