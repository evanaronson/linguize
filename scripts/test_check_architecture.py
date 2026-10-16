"""Regression cases for import parsing and the gate's failure behavior."""

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from check_architecture import PACKAGE_PATH, SOURCE_ROOT, check


class ArchitectureGuardTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.repo = Path(self.folder.name)

    def source(self, relative, source):
        file = self.repo / SOURCE_ROOT / PACKAGE_PATH / relative
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(source, encoding="utf-8")

    def test_forbidden_import_reports_path_line_and_nonzero_exit(self):
        self.source("core/Example.kt", "package com.evanaronson.linguize.core\n"
                    "import com.evanaronson.linguize.ui.card.CardState as State\n")
        result = subprocess.run([sys.executable, str(Path(__file__).with_name("check_architecture.py")),
                                 "--root", str(self.repo)], capture_output=True, text=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("core/Example.kt:2", result.stderr)
        self.assertIn("import com.evanaronson.linguize.ui.card.CardState", result.stderr)
        self.assertIn("forbidden dependency core -> ui", result.stderr)

    def test_android_is_rejected_in_both_pure_layers(self):
        self.source("core/Example.kt", "package com.evanaronson.linguize.core\nimport android.os.Bundle\n")
        self.source("codec/Example.kt", "package com.evanaronson.linguize.codec\nimport androidx.annotation.StringRes\n")
        _, errors = check(self.repo)
        self.assertEqual(2, len(errors))
        self.assertTrue(all("must remain free of Android" in error for error in errors))

    def test_semicolon_separated_android_import_is_checked_on_its_physical_line(self):
        self.source("core/Example.kt", "package com.evanaronson.linguize.core; import android.os.Bundle\n")
        _, errors = check(self.repo)
        self.assertEqual(1, len(errors))
        self.assertIn("core/Example.kt:1", errors[0])
        self.assertIn("import android.os.Bundle", errors[0])
        self.assertIn("must remain free of Android", errors[0])

    def test_semicolon_sequence_checks_declarations_after_an_allowed_import(self):
        self.source("core/Example.kt", "package com.evanaronson.linguize.core\n"
                    "import com.evanaronson.linguize.codec.Tokens; "
                    "import com.evanaronson.linguize.ui.card.CardState\n")
        _, errors = check(self.repo)
        self.assertEqual(1, len(errors))
        self.assertIn("core/Example.kt:2", errors[0])
        self.assertIn("forbidden dependency core -> ui", errors[0])

    def test_allowed_semicolon_sequences_include_package_and_import_on_one_line(self):
        self.source("core/Example.kt", "package com.evanaronson.linguize.core; "
                    "import com.evanaronson.linguize.codec.Tokens;\n"
                    "import com.evanaronson.linguize.codec.Codec\n")
        self.assertEqual((1, []), check(self.repo))

    def test_allowed_edges_and_import_examples_in_comments_and_strings(self):
        self.source("core/Example.kt", '''/* Outer comment /* nested comment */
import android.os.Bundle
*/
package com.evanaronson.linguize.core
import com.evanaronson.linguize.codec.Tokens // allowed lower layer
val example = """
import android.app.Activity
"""
''')
        self.source("data/Example.kt", "package com.evanaronson.linguize.data\n"
                    "import com.evanaronson.linguize.R\nimport java.io.OutputStream\n")
        self.source("ui/popup/Example.kt", "package com.evanaronson.linguize.ui.popup\n"
                    "import com.evanaronson.linguize.ui.settings.SettingsViewModel\n"
                    "import com.evanaronson.linguize.ui.text.titleResource\n")
        self.assertEqual((3, []), check(self.repo))

    def test_package_mismatch_cannot_hide_a_lower_layer_file(self):
        self.source("core/Example.kt", "package com.evanaronson.linguize.ui\n")
        _, errors = check(self.repo)
        self.assertIn("expected com.evanaronson.linguize.core", errors[0])

    def test_missing_source_tree_fails_instead_of_passing_an_empty_scan(self):
        count, errors = check(self.repo)
        self.assertEqual(0, count)
        self.assertIn("no Kotlin sources found", errors[0])


if __name__ == "__main__":
    unittest.main()
