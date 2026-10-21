import unittest
from mt5_experimental_aggressor import aggressor_side, unknown_flag_mask


class ExperimentalAggressorTest(unittest.TestCase):
    def test_observed_flags(self):
        for flags,side in ((1112,'SELL'),(1080,'BUY'),(1144,'AMBIGUOUS'),(1026,'AMBIGUOUS'),(1028,'AMBIGUOUS')):
            with self.subTest(flags=flags):
                self.assertEqual(aggressor_side(flags),side)
                self.assertEqual(unknown_flag_mask(flags),1024)

    def test_known_flags(self):
        for flags,side in ((32,'BUY'),(64,'SELL'),(40,'BUY'),(56,'BUY'),(88,'SELL'),
                           (80,'SELL'),(96,'AMBIGUOUS'),(120,'AMBIGUOUS'),(0,'AMBIGUOUS'),
                           (2,'AMBIGUOUS'),(4,'AMBIGUOUS'),(24,'AMBIGUOUS')):
            with self.subTest(flags=flags):
                self.assertEqual(aggressor_side(flags),side)
                self.assertEqual(unknown_flag_mask(flags),0)

    def test_unknown_bits_do_not_define_side(self):
        for flags,side,unknown in ((1024,'AMBIGUOUS',1024),(2048|32,'BUY',2048),
                                  (1024|2048|64,'SELL',3072)):
            self.assertEqual(aggressor_side(flags),side)
            self.assertEqual(unknown_flag_mask(flags),unknown)

    def test_invalid_flags(self):
        for flags in (-1,1.5,True,None):
            with self.assertRaises(ValueError): aggressor_side(flags)
            with self.assertRaises(ValueError): unknown_flag_mask(flags)


if __name__=='__main__': unittest.main()
