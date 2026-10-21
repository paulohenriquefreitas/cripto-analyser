"""Synthetic cursor-prefix diagnosis only. Does not change or execute the EA.

The existing experimental Python query has the same unconditional prefix-count
predicate as RecoverTicks. Tests exercise actual query behavior with fake replies.
The decision helper below is a proposed conservative policy, not production code.
"""
import unittest
from unittest.mock import Mock

from mt5_canonical_price_stream import Cursor, Counters, query

T = 1792174240036


def tick(ms=T):
    return {'time':ms//1000,'time_msc':ms,'last':210095.,'volume':5,
            'volume_real':5.,'bid':210095.,'ask':210100.,'flags':1112}


def fake_api(response):
    api=Mock()
    api.COPY_TICKS_ALL=-1
    api.copy_ticks_from.return_value=response
    api.last_error.return_value=(1,'Success')
    return api


def proposed_prefix_evidence(count,error,ordinal,seen_prefix):
    # Minimal fail-closed semantic distinction; no retry, cursor reset or skip.
    if error or count<0:return 'API_ERROR'
    if ordinal and count==0:return 'INDETERMINADO_EMPTY_HOLD_AND_STOP'
    if ordinal and seen_prefix<ordinal:return 'NONEMPTY_PREFIX_NOT_VERIFIED_HOLD_AND_STOP'
    return 'PREFIX_CHECK_PASSED'


class CursorPrefixDiagnosisTest(unittest.TestCase):
    def read(self,response,cursor=Cursor(T,1)):
        return query(fake_api(response),'WINZ26',cursor,2**63-1,Counters())

    def test_real_failure_shape_empty_reply_with_consumed_ordinal(self):
        api=fake_api([]);cursor=Cursor(T,1);stats=Counters()
        with self.assertRaisesRegex(RuntimeError,'cursor prefix disappeared'):
            query(api,'WINZ26',cursor,2**63-1,stats)
        self.assertEqual(cursor,Cursor(T,1))
        self.assertEqual(stats.rowsFetched,0)
        self.assertEqual(stats.fullBatches,0)
        api.copy_ticks_from.assert_called_once()

    def test_same_empty_reply_does_not_fail_before_any_consumed_row(self):
        rows,cursor,drained=self.read([],Cursor(T,0))
        self.assertEqual(rows,[])
        self.assertEqual(cursor,Cursor(T,0))
        self.assertTrue(drained)

    def test_prefix_only_is_empty_new_ticks_but_not_empty_api_reply(self):
        rows,cursor,drained=self.read([tick()])
        self.assertEqual(rows,[])
        self.assertEqual(cursor,Cursor(T,1))
        self.assertTrue(drained)

    def test_additional_identical_ticks_at_same_millisecond_are_retained(self):
        rows,cursor,_=self.read([tick(),tick(),tick()])
        self.assertEqual(len(rows),2)
        self.assertEqual(rows[0],rows[1])
        self.assertEqual(cursor,Cursor(T,3))

    def test_nonempty_reply_without_anchor_remains_a_continuity_failure(self):
        with self.assertRaisesRegex(RuntimeError,'prefix disappeared'):
            self.read([tick(T+1)])
        self.assertEqual(proposed_prefix_evidence(1,0,1,0),
                         'NONEMPTY_PREFIX_NOT_VERIFIED_HOLD_AND_STOP')

    def test_shorter_same_timestamp_prefix_remains_a_failure(self):
        with self.assertRaisesRegex(RuntimeError,'prefix disappeared'):
            self.read([tick()],Cursor(T,2))
        self.assertEqual(proposed_prefix_evidence(1,0,2,1),
                         'NONEMPTY_PREFIX_NOT_VERIFIED_HOLD_AND_STOP')

    def test_api_error_is_not_reinterpreted_as_missing_prefix(self):
        with self.assertRaisesRegex(RuntimeError,'copy_ticks_from'):
            self.read(None)
        self.assertEqual(proposed_prefix_evidence(0,4403,1,0),'API_ERROR')
        self.assertEqual(proposed_prefix_evidence(-1,0,1,0),'API_ERROR')

    def test_proposed_empty_policy_does_not_certify_no_trades_or_move_cursor(self):
        cursor=Cursor(T,1)
        self.assertEqual(proposed_prefix_evidence(0,0,cursor.ordinal,0),
                         'INDETERMINADO_EMPTY_HOLD_AND_STOP')
        self.assertEqual(cursor,Cursor(T,1))
        # A later prefix-bearing reply is compatible with a temporary empty reply.
        # This demonstrates possibility, not that such a reply occurred in reality.
        rows,updated,_=self.read([tick(),tick(),tick(T+1)],cursor)
        self.assertEqual([r['time_msc'] for r in rows],[T,T+1])
        self.assertEqual(updated,Cursor(T+1,1))

    def test_prefix_size_alone_cannot_detect_same_size_replacement(self):
        changed=tick();changed['volume_real']=99.;changed['volume']=99
        rows,cursor,_=self.read([changed])
        self.assertEqual(rows,[])
        self.assertEqual(cursor,Cursor(T,1))
        # Existing cursor counts positions, not payload identity or exchange IDs.
        self.assertEqual(proposed_prefix_evidence(1,0,1,1),'PREFIX_CHECK_PASSED')


if __name__=='__main__':unittest.main()
