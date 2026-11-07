package com.evanaronson.linguize.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.RowDivider
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.openExternal

/** How to turn on the accessibility button, for apps whose selection menu doesn't show Linguize. */
@Composable
internal fun AccessibilitySetupScreen() {
    val context = LocalContext.current
    ScreenColumn {
        Spacer(Modifier.height(8.dp))
        SectionCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
            Quiet(stringResource(R.string.accessibility_intro))
        }
        SectionHeader(stringResource(R.string.steps))
        SectionCard {
            ListRow(stringResource(R.string.step_open_accessibility), stringResource(R.string.step_open_accessibility_detail))
            RowDivider()
            ListRow(stringResource(R.string.step_restricted), stringResource(R.string.step_restricted_detail))
            RowDivider()
            ListRow(stringResource(R.string.step_tap), stringResource(R.string.step_tap_detail))
            FlowRow(
                Modifier.padding(top = 4.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LinguizeButton(stringResource(R.string.open_accessibility), {
                    context.openExternal(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }, ButtonKind.Tonal)
                LinguizeButton(stringResource(R.string.open_app_info), {
                    context.openExternal(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                    )
                }, ButtonKind.Text)
            }
        }
    }
}
