"""changelog_commits.py's cases: python3 -m unittest discover -s build-scripts -p 'test_*.py'"""

import unittest

import changelog_commits as cc


class Convention(unittest.TestCase):
    def test_trailers_are_read_and_checked(self):
        message = "Subject\n\nWhy.\n\nChangelog: Fixed: Updates no longer stop at the first mirror\nOnboarding: Turn on TorBox again"
        self.assertEqual(
            [("Changelog", "Fixed: Updates no longer stop at the first mirror"), ("Onboarding", "Turn on TorBox again")],
            cc.trailers(message),
        )
        cc.check(message)
        cc.check("Subject only")
        # The subject is never a trailer, nor a line before the last paragraph.
        self.assertEqual([], cc.trailers("Changelog: a subject\n\nChangelog: Fixed: early\n\nNeeds a rig check: 1. Open it"))
        cc.check("Changelog: a subject that reads like one\n\nWhy.")
        cc.check("Subject\n\nChangelog: none")
        with self.assertRaises(cc.ConventionError):
            cc.check("Subject\n\nChangelog: Fixed the thing")
        with self.assertRaises(cc.ConventionError):
            cc.check("Subject\n\nOnboarding:")


class Lines(unittest.TestCase):
    def test_a_trailer_is_the_line_and_none_is_internal(self):
        lines, steps, internal = cc.commit_lines("x\n\nChangelog: Added: Open thread on a failed download\nOnboarding: Sign in to F95zone again")
        self.assertEqual([("Added", "Open thread on a failed download")], lines)
        self.assertEqual(["Sign in to F95zone again"], steps)
        self.assertFalse(internal)
        self.assertEqual(([], [], True), cc.commit_lines("Anything\n\nChangelog: none"))

    def test_a_subject_without_a_trailer_reads_as_its_section(self):
        self.assertEqual("Fixed", cc.section_for("Updates: a mirror that fails no longer stops the pick"))
        self.assertEqual("Added", cc.section_for("Add a View thread row"))
        self.assertEqual("Changed", cc.section_for("Wine games: mouse mode starts on for pointer genres"))
        for internal in ("SourceRecordPageTest: the rebase's conflict marker resolved", "CI: pin the runner",
                         "Changelog fragment for Search leaving the left menu", "Docs: contract 1 is gone",
                         "Merge branch 'main'"):
            self.assertIsNone(cc.section_for(internal), internal)

    def test_a_subject_loses_its_tracker_reference(self):
        self.assertEqual("Downloads pick the next mirror", cc.plain("downloads pick the next mirror (Droidtop/tracker#862)."))


class Entry(unittest.TestCase):
    def test_a_fragment_replaces_its_commits_line_and_internal_commits_fold(self):
        fragments = {"862-open-thread": {"Added": ["Open thread on a game's Downloads"]}}
        commits = [
            ("Updates: Open thread on the record and the failed job", ["862-open-thread"]),
            ("Search leaves the left menu for a Power and mode tile", []),
            ("SourceRecordPageTest: a marker resolved", []),
            ("CI: retry the fetch", []),
            ("Fix the crash on start\n\nOnboarding: Sign in to TorBox again", []),
        ]
        sections, onboarding = cc.entry(commits, fragments)
        self.assertEqual(["Open thread on a game's Downloads"], sections["Added"])
        self.assertEqual(["Search leaves the left menu for a Power and mode tile", "Internal changes (2); nothing looks different"], sections["Changed"])
        self.assertEqual(["Fix the crash on start"], sections["Fixed"])
        self.assertEqual(["Sign in to TorBox again"], onboarding)

    def test_a_fragment_from_an_earlier_unpublished_commit_is_still_carried_and_private_names_are_left_out(self):
        sections, _ = cc.entry([("Romgi plugin: faster search", [])], {"700-x": {"Fixed": ["A fix"]}}, private_names=("romgi",))
        self.assertEqual({"Fixed": ["A fix"]}, sections)

    def test_only_internal_commits_still_make_an_entry(self):
        sections, _ = cc.entry([("CI: one", []), ("Docs: two", [])], {})
        self.assertEqual({"Changed": ["Internal changes (2); nothing looks different"]}, sections)


class Cited(unittest.TestCase):
    def test_a_commit_citing_a_fragments_issue_is_told_by_the_fragment(self):
        fragments = {"513-plugins-search": {"Added": ["The Plugins page has a Search"]}}
        commits = [
            ("Plugins: one search over the installed plugins\n\nDroidtop/tracker#513 asked for it.", []),
            ("Changelog fragment for the Plugins page's search", ["513-plugins-search"]),
            ("Other change for #900", []),
        ]
        sections, _ = cc.entry(commits, fragments)
        self.assertEqual({"Added": ["The Plugins page has a Search"], "Changed": ["Other change for #900"]}, sections)


class Range(unittest.TestCase):
    def test_every_build_between_two_versions_is_merged_once(self):
        builds = [
            {"versionCode": 30, "versionName": "dev.30", "sections": {"Fixed": ["B"]}, "onboarding": ["Do Y"]},
            {"versionCode": 20, "versionName": "dev.20", "sections": {"Added": ["A"], "Fixed": ["B"]}, "onboarding": ["Do X"]},
            {"versionCode": 10, "versionName": "dev.10", "sections": {"Added": ["Old"]}},
        ]
        out = cc.merged(builds, after=10, through=30)
        self.assertEqual(["dev.30", "dev.20"], out["builds"])
        self.assertEqual({"Added": ["A"], "Fixed": ["B"]}, out["sections"])
        self.assertEqual(["Do X", "Do Y"], out["onboarding"])


if __name__ == "__main__":
    unittest.main()
