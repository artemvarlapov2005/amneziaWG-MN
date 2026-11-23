import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('publisher', Path(__file__).with_name('publish-update.py'))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)

class PublishingTests(unittest.TestCase):
    def setUp(self):
        self.catalog = json.loads((Path(__file__).resolve().parents[1] / 'updates/channels.json').read_text())
    def entry(self, version, code):
        return dict(tag=version, versionName=version, versionCode=code,
                    apkUrl=f'{publisher.RELEASES}/download/{version}/AmneziaWG-MN-{version}-arm64.apk',sha256='a'*64,notes='')
    def test_bootstrap_catalog(self):
        publisher.validate_catalog(self.catalog)
    def test_beta_then_stable_hotfix_use_global_counter(self):
        code=max(r['versionCode'] for r in self.catalog.values() if isinstance(r,dict))
        c=publisher.replace_entry(self.catalog,'beta',self.entry('9.0.0-beta.1',code+1))
        with self.assertRaises(ValueError): publisher.replace_entry(c,'stable',self.entry('8.0.0',code+1))
        publisher.replace_entry(c,'stable',self.entry('8.0.0',code+2))
    def test_rejects_wrong_channel_and_foreign_url(self):
        with self.assertRaises(ValueError): publisher.validate_entry('stable',self.entry('2.0.0-beta.1',99))
        e=self.entry('2.0.0',99); e['apkUrl']='https://evil.invalid/a.apk'
        with self.assertRaises(ValueError): publisher.validate_entry('stable',e)
    def test_orders_beta_numbers_and_final(self):
        self.assertGreater(publisher.version_key('2.0.0-beta.10'),publisher.version_key('2.0.0-beta.2'))
        self.assertGreater(publisher.version_key('2.0.0'),publisher.version_key('2.0.0-beta.10'))
    def test_rejects_malformed_schema_and_non_integer_code(self):
        c=dict(self.catalog,schemaVersion='1')
        with self.assertRaises(ValueError): publisher.validate_catalog(c)
        e=self.entry('2.0.0',99); e['versionCode']=99.5
        with self.assertRaises(ValueError): publisher.validate_entry('stable',e)

if __name__ == '__main__': unittest.main()
