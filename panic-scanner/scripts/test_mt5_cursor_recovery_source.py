"""Static scope/architecture checks, not a substitute for executing MQL5.

Hashes refer to normalized function text before the recovery implementation.
They guard the explicit requirement to leave DOM and classification untouched.
"""
import hashlib
from pathlib import Path
import re
import unittest


EA = Path(__file__).parent / 'mql5' / 'WinZ26EventJointProbe.mq5'


def function(source, name):
    match = re.search(r'^(?:void|string|bool) '+name+r'\(', source, re.M)
    if not match:
        raise AssertionError(f'Missing function {name}')
    end = source.index('\n}\n', match.start()) + 2
    return source[match.start():end]


class RecoverySourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = EA.read_text(encoding='utf-8')

    def test_dom_classification_and_notification_handlers_unchanged(self):
        expected = {
            'BookRow': '4da3a804f61922857d0ac7efd26b908b004b259f2e989a8f63345e0f76d43058',
            'BestSide': '4b72007ebfafce82713d55095ca9117147a52bfc1b9e634cee4b8461d313583b',
            'TickRow': '1fe799cb62cd66f56d0df3c24f9378e1abf94ab4551f32c91df0645efb4c6fb7',
            'CaptureBook': '8b5ca5ffc761d71b956574cc9c88f199ba2156518dcb1ac598fe4b20177f7a5c',
            'OnBookEvent': 'c0227057cb580be1250a4f26167ee64a3c593e2f995857810927a5403015b590',
            'OnTick': '7943834a34c7e4cadf2c5fbfc54bd16f2ccc84a8de4b906b8db6ae80f8255c64',
        }
        for name, digest in expected.items():
            with self.subTest(function=name):
                self.assertEqual(hashlib.sha256(function(self.source, name).encode()).hexdigest(), digest)

    def test_single_copyticks_call_site_only_reachable_from_timer(self):
        # Static architecture guarantee: no retry loop or call in event handlers.
        self.assertEqual(len(re.findall(r'\bCopyTicks\(', self.source)), 1)
        self.assertEqual(len(re.findall(r'\bRecoverTicks\(', self.source)), 2)
        timer = function(self.source, 'OnTimer')
        self.assertEqual(len(re.findall(r'\bRecoverTicks\(', timer)), 1)
        self.assertNotRegex(timer, r'\b(for|while|do)\b')
        self.assertNotRegex(self.source, r'\bSleep\(')
        self.assertIn('if(waiting_prefix || pending_ticks ||', timer)

    def test_capture_remains_read_only(self):
        self.assertNotRegex(self.source, r'\b(OrderSend|OrderSendAsync|CTrade|Buy|Sell)\s*\(')
        self.assertEqual(len(re.findall(r'\bMarketBookGet\(', self.source)), 1)


if __name__ == '__main__':
    unittest.main()
