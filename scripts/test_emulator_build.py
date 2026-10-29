"""Which android-build run the emulator tests take: the same commit's, never another's (Droidtop/tracker#735).

Run: python3 -m unittest discover -s scripts -p 'test_emulator_build.py'
"""
import importlib.util
import os
import unittest

spec = importlib.util.spec_from_file_location("emulator_build", os.path.join(os.path.dirname(os.path.abspath(__file__)), "emulator-build.py"))
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


def run(id, sha, status="completed", conclusion="success"):
    return {"id": id, "head_sha": sha, "status": status, "conclusion": conclusion}


class Pick(unittest.TestCase):
    def test_no_run_of_the_commit_is_none_even_when_others_exist(self):
        self.assertEqual(("none", None), build.pick([run(1, "main-sha")], "branch-sha"))

    def test_a_successful_run_of_the_commit_is_taken(self):
        self.assertEqual(("success", run(2, "a")), build.pick([run(1, "b"), run(2, "a")], "a"))

    def test_a_run_in_progress_is_waited_for(self):
        self.assertEqual("running", build.pick([run(3, "a", "in_progress", None)], "a")[0])

    def test_a_failed_run_is_a_failure_not_another_build(self):
        self.assertEqual("failed", build.pick([run(4, "a", conclusion="failure"), run(1, "b")], "a")[0])

    def test_a_later_success_of_the_commit_beats_an_earlier_failure(self):
        self.assertEqual(("success", run(6, "a")), build.pick([run(5, "a", conclusion="failure"), run(6, "a")], "a"))


if __name__ == "__main__":
    unittest.main()
