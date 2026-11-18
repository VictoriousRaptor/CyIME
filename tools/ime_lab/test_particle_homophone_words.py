import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("particle_homophone_words.py")
HEAD = "---\nname: t\n...\n"


class ParticleHomophoneWordsTest(unittest.TestCase):
    def run_script(self, corpus):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            (d / "chars.dict.yaml").write_text(HEAD + "马\tma\t10\n吗\tma\t10\n码\tma\t10\n", encoding="utf-8")
            (d / "base.dict.yaml").write_text(HEAD + "生效\tsheng xiao\t30000\n生肖\tsheng xiao\t26000\n付款\tfu kuan\t5000\n",
                                              encoding="utf-8")
            (d / "tencent.dict.yaml").write_text(HEAD + "生肖马\t97\n付款码\t300\n生效吗\t50\n", encoding="utf-8")
            (d / "corpus.txt").write_text(corpus, encoding="utf-8")
            subprocess.run([sys.executable, "-I", str(SCRIPT), "--chars", str(d / "chars.dict.yaml"),
                            "--keep", str(d / "base.dict.yaml"), "--check", str(d / "tencent.dict.yaml"),
                            "--corpus", str(d / "corpus.txt"), "--list", str(d / "list.txt"), "--report", str(d / "report.tsv")],
                           check=True, capture_output=True)
            return (d / "list.txt").read_text(encoding="utf-8").split(), (d / "report.tsv").read_text(encoding="utf-8")

    def test_word_written_less_than_its_particle_form_is_listed(self):
        listed, report = self.run_script("生效吗\n这样生效吗？\n生效吗\n我的生肖马\n付款码给我\n付款码\n付款吗\n")
        self.assertEqual(["生肖马"], listed)
        # 付款码 is written more than 付款吗; 生效吗 ends in the particle itself and is never a candidate.
        self.assertIn("付款码\t2\t付款吗\t1\t1", report)
        self.assertFalse(any(line.startswith("生效吗\t") for line in report.splitlines()))

    def test_rare_counts_keep_the_word(self):
        listed, _ = self.run_script("生效吗\n")
        self.assertEqual([], listed)


if __name__ == "__main__":
    unittest.main()
